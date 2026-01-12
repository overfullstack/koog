package org.salesforce.swara.agents

import ai.koog.a2a.model.AgentCapabilities
import ai.koog.a2a.model.AgentCard
import ai.koog.a2a.model.AgentInterface
import ai.koog.a2a.model.AgentSkill
import ai.koog.a2a.model.Artifact
import ai.koog.a2a.model.MessageSendParams
import ai.koog.a2a.model.Task
import ai.koog.a2a.model.TaskArtifactUpdateEvent
import ai.koog.a2a.model.TaskState
import ai.koog.a2a.model.TaskStatus
import ai.koog.a2a.model.TextPart
import ai.koog.a2a.model.TransportProtocol
import ai.koog.a2a.server.agent.AgentExecutor
import ai.koog.a2a.server.session.RequestContext
import ai.koog.a2a.server.session.SessionEventProcessor
import ai.koog.agents.a2a.core.A2AMessage
import ai.koog.agents.a2a.server.feature.A2AAgentServer
import ai.koog.agents.a2a.server.feature.withA2AAgentServer
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.demo.agent.a2a.model.MapsLocationRequest
import org.jetbrains.demo.agent.a2a.model.MapsLocationResult
import org.salesforce.A2ATelemetry
import org.salesforce.LogColors
import org.salesforce.travel.LLM_MODEL
import org.slf4j.LoggerFactory
import kotlin.reflect.typeOf
import kotlin.uuid.ExperimentalUuidApi

private val logger = LoggerFactory.getLogger("MapsAgent")

const val MAPS_PATH = "/a2a/maps"
const val MAPS_CARD_PATH = "$MAPS_PATH/agent-card.json"

fun mapsAgentCard(baseUrl: String): AgentCard = AgentCard(
    protocolVersion = "0.3.0",
    name = "Google Maps Agent",
    description = "Location verification and geocoding agent using Google Maps",
    version = "1.0.0",
    url = "$baseUrl$MAPS_PATH",
    preferredTransport = TransportProtocol.JSONRPC,
    additionalInterfaces = listOf(
        AgentInterface(
            url = "$baseUrl$MAPS_PATH",
            transport = TransportProtocol.JSONRPC,
        )
    ),
    capabilities = AgentCapabilities(
        streaming = true,
        pushNotifications = false,
        stateTransitionHistory = false,
    ),
    defaultInputModes = listOf("text"),
    defaultOutputModes = listOf("text"),
    skills = listOf(
        AgentSkill(
            id = "location_lookup",
            name = "Location Lookup",
            description = "Verifies addresses, geocodes locations, and provides place details using Google Maps",
            examples = listOf(
                "Verify address: 123 Main St, New York",
                "Get coordinates for UCSF Medical Center",
                "Find details about SF General Hospital"
            ),
            tags = listOf("maps", "location", "geocoding", "address", "places")
        )
    ),
    supportsAuthenticatedExtendedCard = false
)

class MapsAgentExecutor(
    private val promptExecutor: PromptExecutor,
    private val tools: ToolRegistry
) : AgentExecutor {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        logger.info(LogColors.mapsBanner("MAPS EXECUTION START"))
        logger.info("${LogColors.MAPS} TaskId: ${context.taskId}")
        logger.info("${LogColors.MAPS} ContextId: ${context.contextId}")
        val startTime = System.currentTimeMillis()
        try {
            val agent = mapsAgent(promptExecutor, tools, context, eventProcessor)
            agent.run(context.params.message)
            val duration = System.currentTimeMillis() - startTime
            logger.info("${LogColors.MAPS} Execution completed in ${duration}ms")
        } catch (e: Exception) {
            logger.error("${LogColors.MAPS} ${LogColors.red("Execution failed")}: ${e.message}", e)
            throw e
        }
        logger.info(LogColors.mapsBanner("MAPS EXECUTION END"))
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun mapsAgent(
    promptExecutor: PromptExecutor,
    tools: ToolRegistry,
    context: RequestContext<MessageSendParams>,
    eventProcessor: SessionEventProcessor
): GraphAIAgent<A2AMessage, Unit> {
    val agentConfig = AIAgentConfig(
        prompt = prompt("maps-location") {
            system {
                +"""You are a location specialist using Google Maps.
                
Your task is to:
1. Use google-maps tools to verify and geocode addresses
2. Get place details including coordinates, formatted address, and additional info
3. Provide accurate location information for appointments

Available MCP tools:
- google-maps: For geocoding, place search, and place details

Use these tools to gather accurate location information.
Provide clear location details including coordinates and formatted addresses."""
            }
        },
        model = LLM_MODEL,
        maxAgentIterations = 10
    )

    // Log available MCP tools for verification
    logger.info("${LogColors.MAPS} Available MCP tools: ${tools.tools.map { it.descriptor.name }}")

    return GraphAIAgent(
        inputType = typeOf<A2AMessage>(),
        outputType = typeOf<Unit>(),
        promptExecutor = promptExecutor,
        strategy = mapsStrategy(),
        agentConfig = agentConfig,
        toolRegistry = tools,
    ) {
        install(A2AAgentServer) {
            this.context = context
            this.eventProcessor = eventProcessor
        }
        if (A2ATelemetry.isEnabled) {
            install(OpenTelemetry, A2ATelemetry.installLangfuse(
                agentName = "maps",
                sessionId = context.contextId
            ))
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun mapsStrategy() = strategy<A2AMessage, Unit>("maps-strategy") {
    val json = Json { ignoreUnknownKeys = true }

    val parseInput by node<A2AMessage, MapsLocationRequest> { message ->
        logger.debug("${LogColors.MAPS} Parsing input message...")
        val textContent = message.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
        val request = json.decodeFromString<MapsLocationRequest>(textContent)
        logger.info("${LogColors.MAPS} Parsed request: ${request.location}")
        request
    }

    val lookupLocation by node<MapsLocationRequest, MapsLocationResult> { request ->
        logger.info("${LogColors.MAPS} ${LogColors.LLM} Looking up location...")
        logger.debug("${LogColors.MAPS} Location: ${request.location}")
        
        // Add user prompt
        llm.writeSession {
            appendPrompt {
                user {
                    +"""Look up location: ${request.location}
${if (request.includeDetails) "Include place details (phone, hours, rating, etc.)" else "Basic geocoding only"}

Use the maps_geocode tool to get coordinates for this location.
Call the tool with the address: "${request.location}"

After getting the result, provide a summary with:
- The formatted address
- The latitude and longitude coordinates

IMPORTANT: You must call the maps_geocode tool first before providing any response."""
                }
            }
        }
        
        // Execute tool loop
        var iterations = 0
        val maxIterations = 8
        var finalResponse: String = ""
        var extractedLat: Double? = null
        var extractedLng: Double? = null
        var extractedAddress: String? = null
        var extractedDistanceKm: Double? = null
        var extractedTravelTimeMinutes: Int? = null
        
        while (iterations < maxIterations) {
            iterations++
            logger.info("${LogColors.MAPS} Tool loop iteration $iterations")
            
            val response = llm.writeSession { requestLLM() }
            logger.info("${LogColors.MAPS} LLM response type: ${response::class.simpleName}")
            
            when (response) {
                is Message.Tool.Call -> {
                    logger.info("${LogColors.MAPS} 🔧 Executing tool: ${response.tool}")
                    try {
                        val toolResult = environment.executeTool(response)
                        logger.info("${LogColors.MAPS} ✅ Tool result: ${toolResult.content.take(200)}...")
                        
                        // Try to extract coordinates from geocode results
                        if (response.tool == "maps_geocode") {
                            try {
                                val content = toolResult.content
                                logger.debug("${LogColors.MAPS} Raw geocode content length: ${content.length}")
                                
                                // Try JSON parsing first (most reliable)
                                try {
                                    val outerJson = json.parseToJsonElement(content)
                                    
                                    // Navigate: content[0].text -> parse as JSON -> results[0].geometry.location
                                    val contentArray = outerJson.jsonObject["content"]?.jsonArray
                                    val textContent = contentArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                                    
                                    if (textContent != null) {
                                        logger.debug("${LogColors.MAPS} Parsing inner JSON from text field...")
                                        val innerJson = json.parseToJsonElement(textContent)
                                        val results = innerJson.jsonObject["results"]?.jsonArray
                                        
                                        if (results != null && results.isNotEmpty()) {
                                            val firstResult = results[0].jsonObject
                                            
                                            // Extract coordinates from geometry.location
                                            val geometry = firstResult["geometry"]?.jsonObject
                                            val location = geometry?.get("location")?.jsonObject
                                            
                                            extractedLat = location?.get("lat")?.jsonPrimitive?.doubleOrNull
                                            extractedLng = location?.get("lng")?.jsonPrimitive?.doubleOrNull
                                            
                                            if (extractedLat != null && extractedLng != null) {
                                                logger.info("${LogColors.MAPS} ✅ JSON parsed coordinates: lat=$extractedLat, lng=$extractedLng")
                                            }
                                            
                                            // Extract formatted address
                                            extractedAddress = firstResult["formatted_address"]?.jsonPrimitive?.contentOrNull
                                            if (extractedAddress != null) {
                                                logger.info("${LogColors.MAPS} ✅ JSON parsed address: $extractedAddress")
                                            }
                                        }
                                    }
                                } catch (jsonEx: Exception) {
                                    logger.debug("${LogColors.MAPS} JSON parsing failed, falling back to regex: ${jsonEx.message}")
                                }
                                
                                // Fallback to regex if JSON parsing didn't work
                                if (extractedLat == null || extractedLng == null) {
                                    logger.debug("${LogColors.MAPS} Using regex fallback...")
                                    
                                    // Pattern for "lat": 17.xxx anywhere in the content
                                    val latPattern = Regex(""""lat"\s*:\s*(-?\d+\.?\d*)""")
                                    val lngPattern = Regex(""""lng"\s*:\s*(-?\d+\.?\d*)""")
                                    val addressPattern = Regex(""""formatted_address"\s*:\s*"([^"]+)"""")
                                    
                                    latPattern.find(content)?.let { match ->
                                        extractedLat = match.groupValues[1].toDoubleOrNull()
                                        logger.info("${LogColors.MAPS} ✅ Regex extracted lat: $extractedLat")
                                    }
                                    
                                    lngPattern.find(content)?.let { match ->
                                        extractedLng = match.groupValues[1].toDoubleOrNull()
                                        logger.info("${LogColors.MAPS} ✅ Regex extracted lng: $extractedLng")
                                    }
                                    
                                    if (extractedAddress == null) {
                                        addressPattern.find(content)?.let { match ->
                                            extractedAddress = match.groupValues[1]
                                            logger.info("${LogColors.MAPS} ✅ Regex extracted address: $extractedAddress")
                                        }
                                    }
                                }
                                
                                if (extractedLat == null || extractedLng == null) {
                                    logger.warn("${LogColors.MAPS} ⚠️ Could not extract coordinates from geocode response")
                                    // Log a portion that might contain geometry
                                    val geometryIndex = content.indexOf("geometry")
                                    if (geometryIndex >= 0) {
                                        logger.warn("${LogColors.MAPS} Geometry section: ${content.substring(geometryIndex, minOf(geometryIndex + 300, content.length))}")
                                    } else {
                                        logger.warn("${LogColors.MAPS} No 'geometry' found in response. Sample: ${content.take(500)}")
                                    }
                                }
                            } catch (e: Exception) {
                                logger.warn("${LogColors.MAPS} Failed to parse coordinates: ${e.message}", e)
                            }
                        }
                        
                        // Try to extract distance and travel time from distance_matrix results
                        if (response.tool == "maps_distance_matrix") {
                            try {
                                val content = toolResult.content
                                logger.debug("${LogColors.MAPS} Parsing distance matrix response...")
                                
                                // Try JSON parsing first
                                try {
                                    val outerJson = json.parseToJsonElement(content)
                                    val contentArray = outerJson.jsonObject["content"]?.jsonArray
                                    val textContent = contentArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                                    
                                    if (textContent != null) {
                                        val innerJson = json.parseToJsonElement(textContent)
                                        val rows = innerJson.jsonObject["rows"]?.jsonArray
                                        
                                        if (rows != null && rows.isNotEmpty()) {
                                            val elements = rows[0].jsonObject["elements"]?.jsonArray
                                            if (elements != null && elements.isNotEmpty()) {
                                                val element = elements[0].jsonObject
                                                
                                                // Extract distance (in meters, convert to km)
                                                val distanceObj = element["distance"]?.jsonObject
                                                val distanceMeters = distanceObj?.get("value")?.jsonPrimitive?.doubleOrNull
                                                if (distanceMeters != null) {
                                                    extractedDistanceKm = distanceMeters / 1000.0
                                                    logger.info("${LogColors.MAPS} ✅ Extracted distance: ${"%.2f".format(extractedDistanceKm)} km")
                                                }
                                                
                                                // Extract duration (in seconds, convert to minutes)
                                                val durationObj = element["duration"]?.jsonObject
                                                val durationSeconds = durationObj?.get("value")?.jsonPrimitive?.doubleOrNull
                                                if (durationSeconds != null) {
                                                    extractedTravelTimeMinutes = (durationSeconds / 60.0).toInt()
                                                    logger.info("${LogColors.MAPS} ✅ Extracted travel time: $extractedTravelTimeMinutes minutes")
                                                }
                                            }
                                        }
                                    }
                                } catch (jsonEx: Exception) {
                                    logger.debug("${LogColors.MAPS} JSON parsing failed for distance matrix: ${jsonEx.message}")
                                }
                                
                                // Fallback to regex
                                if (extractedDistanceKm == null) {
                                    // Pattern: "value": 12345 after "distance"
                                    val distanceIndex = content.indexOf("distance")
                                    if (distanceIndex >= 0) {
                                        val valuePattern = Regex(""""value"\s*:\s*(\d+\.?\d*)""")
                                        val distanceSection = content.substring(distanceIndex, minOf(distanceIndex + 100, content.length))
                                        valuePattern.find(distanceSection)?.let { match ->
                                            val meters = match.groupValues[1].toDoubleOrNull()
                                            if (meters != null) {
                                                extractedDistanceKm = meters / 1000.0
                                                logger.info("${LogColors.MAPS} ✅ Regex extracted distance: ${"%.2f".format(extractedDistanceKm)} km")
                                            }
                                        }
                                    }
                                }
                                
                                if (extractedTravelTimeMinutes == null) {
                                    // Pattern: "value": 1234 after "duration"
                                    val durationIndex = content.indexOf("duration")
                                    if (durationIndex >= 0) {
                                        val valuePattern = Regex(""""value"\s*:\s*(\d+\.?\d*)""")
                                        val durationSection = content.substring(durationIndex, minOf(durationIndex + 100, content.length))
                                        valuePattern.find(durationSection)?.let { match ->
                                            val seconds = match.groupValues[1].toDoubleOrNull()
                                            if (seconds != null) {
                                                extractedTravelTimeMinutes = (seconds / 60.0).toInt()
                                                logger.info("${LogColors.MAPS} ✅ Regex extracted travel time: $extractedTravelTimeMinutes minutes")
                                            }
                                        }
                                    }
                                }
                                
                            } catch (e: Exception) {
                                logger.warn("${LogColors.MAPS} Failed to parse distance matrix: ${e.message}", e)
                            }
                        }
                        
                        llm.writeSession {
                            appendPrompt {
                                tool {
                                    result(toolResult.id, toolResult.tool, toolResult.content)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("${LogColors.MAPS} ❌ Tool ${response.tool} failed: ${e.message}")
                        llm.writeSession {
                            appendPrompt {
                                tool {
                                    result(response.id, response.tool, "Error: Tool '${response.tool}' failed - ${e.message}. Please continue with other available tools.")
                                }
                            }
                        }
                    }
                }
                is Message.Assistant -> {
                    finalResponse = response.content
                    logger.info("${LogColors.MAPS} Got final response: ${finalResponse.take(100)}...")
                    break
                }
                else -> {
                    logger.warn("${LogColors.MAPS} Unexpected response type: ${response::class.simpleName}")
                    finalResponse = response.content
                    break
                }
            }
        }
        
        if (finalResponse.isBlank()) {
            finalResponse = "Unable to look up location after $maxIterations attempts."
        }
        
        logger.info("${LogColors.MAPS} Final result: lat=$extractedLat, lng=$extractedLng, address=$extractedAddress, distance=${extractedDistanceKm?.let { "%.2f km".format(it) }}, travelTime=${extractedTravelTimeMinutes?.let { "$it min" }}")
        
        MapsLocationResult(
            location = request.location,
            formattedAddress = extractedAddress,
            latitude = extractedLat,
            longitude = extractedLng,
            placeDetails = finalResponse.take(500),
            directions = null,
            travelTimeMinutes = extractedTravelTimeMinutes,
            distanceKm = extractedDistanceKm
        )
    }

    val createTask by node<MapsLocationRequest, MapsLocationRequest> { input ->
        withA2AAgentServer {
            val userInput = context.params.message
            val task = Task(
                id = context.taskId,
                contextId = context.contextId,
                status = TaskStatus(
                    state = TaskState.Working,
                    message = userInput,
                    timestamp = Clock.System.now(),
                ),
            )
            eventProcessor.sendTaskEvent(task)
        }
        input
    }

    val sendResult by node<MapsLocationResult, Unit> { result ->
        logger.info("${LogColors.MAPS} Sending result artifact")
        withA2AAgentServer {
            val artifactUpdate = TaskArtifactUpdateEvent(
                taskId = context.taskId,
                contextId = context.contextId,
                artifact = Artifact(
                    artifactId = "maps-location",
                    parts = listOf(TextPart(json.encodeToString(MapsLocationResult.serializer(), result)))
                )
            )
            eventProcessor.sendTaskEvent(artifactUpdate)
        }
    }

    nodeStart then parseInput then createTask then lookupLocation then sendResult then nodeFinish
}

