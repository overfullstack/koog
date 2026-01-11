package org.jetbrains.demo.agent.a2a

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
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.demo.agent.a2a.model.ServiceTerritoryValidationRequest
import org.jetbrains.demo.agent.a2a.model.ServiceTerritoryValidationResult
import org.salesforce.A2ATelemetry
import org.salesforce.LogColors
import org.salesforce.travel.LLM_MODEL
import org.slf4j.LoggerFactory
import kotlin.reflect.typeOf
import kotlin.uuid.ExperimentalUuidApi

private val logger = LoggerFactory.getLogger("ServiceTerritoryValidationAgent")

const val SERVICE_TERRITORY_VALIDATION_PATH = "/a2a/service-territory-validation"
const val SERVICE_TERRITORY_VALIDATION_CARD_PATH = "$SERVICE_TERRITORY_VALIDATION_PATH/agent-card.json"

fun serviceTerritoryValidationAgentCard(baseUrl: String): AgentCard = AgentCard(
    protocolVersion = "0.3.0",
    name = "Service Territory Validation Agent",
    description = "Validates if a service territory/location exists in the system",
    version = "1.0.0",
    url = "$baseUrl$SERVICE_TERRITORY_VALIDATION_PATH",
    preferredTransport = TransportProtocol.JSONRPC,
    additionalInterfaces = listOf(
        AgentInterface(
            url = "$baseUrl$SERVICE_TERRITORY_VALIDATION_PATH",
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
            id = "service_territory_validation",
            name = "Service Territory Validation",
            description = "Validates if a service territory/location exists in the system",
            examples = listOf(
                "Validate service territory 'San Francisco'",
                "Check if service territory 'New York' exists",
                "Validate service territory/location name"
            ),
            tags = listOf("appointment", "validation", "service-territory", "location")
        )
    ),
    supportsAuthenticatedExtendedCard = false
)

class ServiceTerritoryValidationAgentExecutor(
    private val promptExecutor: PromptExecutor
) : AgentExecutor {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        logger.info(LogColors.serviceTerritoryBanner("SERVICE_TERRITORY_VALIDATION EXECUTION START"))
        logger.info("${LogColors.SERVICE_TERRITORY} TaskId: ${context.taskId}")
        logger.info("${LogColors.SERVICE_TERRITORY} ContextId: ${context.contextId}")
        val startTime = System.currentTimeMillis()
        try {
            val agent = serviceTerritoryValidationAgent(promptExecutor, context, eventProcessor)
            agent.run(context.params.message)
            val duration = System.currentTimeMillis() - startTime
            logger.info("${LogColors.SERVICE_TERRITORY} Execution completed in ${duration}ms")
        } catch (e: Exception) {
            logger.error("${LogColors.SERVICE_TERRITORY} ${LogColors.red("Execution failed")}: ${e.message}", e)
            throw e
        }
        logger.info(LogColors.serviceTerritoryBanner("SERVICE_TERRITORY_VALIDATION EXECUTION END"))
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun serviceTerritoryValidationAgent(
    promptExecutor: PromptExecutor,
    context: RequestContext<MessageSendParams>,
    eventProcessor: SessionEventProcessor
): GraphAIAgent<A2AMessage, Unit> {
    val agentConfig = AIAgentConfig(
        prompt = prompt("service-territory-validation") {
            system {
                +"""
                You are a service territory validation specialist.
                Your task is to validate if a service territory/location name exists in the system.
                
                Use the validation tool to fetch available service territories and check if the provided location matches any of them.
                Return a clear validation result with isValid flag, a message, and the serviceTerritoryId of the matching service territory if found.
                """.trimIndent()
            }
        },
        model = LLM_MODEL,
        maxAgentIterations = 10
    )

    val toolRegistry = ToolRegistry {
        tools(org.jetbrains.demo.agent.tools.ServiceTerritoryValidationTool())
    }

    return GraphAIAgent(
        inputType = typeOf<A2AMessage>(),
        outputType = typeOf<Unit>(),
        promptExecutor = promptExecutor,
        strategy = serviceTerritoryValidationStrategy(),
        agentConfig = agentConfig,
        toolRegistry = toolRegistry,
    ) {
        install(A2AAgentServer) {
            this.context = context
            this.eventProcessor = eventProcessor
        }
        if (A2ATelemetry.isEnabled) {
            install(OpenTelemetry, A2ATelemetry.installLangfuse(
                agentName = "service-territory-validation",
                sessionId = context.contextId
            ))
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun serviceTerritoryValidationStrategy() = strategy<A2AMessage, Unit>("service-territory-validation-strategy") {
    val json = Json { ignoreUnknownKeys = true }

    val parseInput by node<A2AMessage, ServiceTerritoryValidationRequest> { message ->
        logger.debug("${LogColors.SERVICE_TERRITORY} Parsing input message...")
        val textContent = message.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
        val request = json.decodeFromString<ServiceTerritoryValidationRequest>(textContent)
        logger.info("${LogColors.SERVICE_TERRITORY} Parsed request: location=${request.location}")
        request
    }

    val validateServiceTerritory by node<ServiceTerritoryValidationRequest, ServiceTerritoryValidationResult> { request ->
        logger.info("${LogColors.SERVICE_TERRITORY} Calling validation tool directly...")
        logger.info("${LogColors.SERVICE_TERRITORY} Location: ${request.location}")
        
        // Call the validation tool directly
        val validationTool = org.jetbrains.demo.agent.tools.ServiceTerritoryValidationTool()
        val toolResponse = try {
            validationTool.validateServiceTerritory(
                location = request.location
            )
        } catch (e: Exception) {
            logger.error("${LogColors.SERVICE_TERRITORY} Tool execution failed: ${e.message}", e)
            return@node ServiceTerritoryValidationResult(
                isValid = false,
                message = "Validation tool execution failed: ${e.message}"
            )
        }
        
        logger.info("${LogColors.SERVICE_TERRITORY} Tool response (first 500 chars): ${toolResponse.take(500)}")
        
        // Parse the tool response - it returns a JSON string containing a list of service territories
        // We need to check if the requested location matches any of the service territories
        val result = try {
            // First, try to find a JSON object or array in the response
            val jsonStart = toolResponse.indexOf('{').let { 
                val arrayStart = toolResponse.indexOf('[')
                when {
                    it >= 0 && arrayStart >= 0 -> minOf(it, arrayStart)
                    it >= 0 -> it
                    arrayStart >= 0 -> arrayStart
                    else -> -1
                }
            }
            val jsonEnd = maxOf(
                toolResponse.lastIndexOf('}') + 1,
                toolResponse.lastIndexOf(']') + 1
            )
            
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonPart = toolResponse.substring(jsonStart, jsonEnd)
                logger.debug("${LogColors.SERVICE_TERRITORY} Extracted JSON part: $jsonPart")
                
                val jsonElement = json.parseToJsonElement(jsonPart)
                
                // Data class to hold service territory info
                data class ServiceTerritoryInfo(
                    val id: String?,
                    val name: String?,
                    val address: String?
                )
                
                // Helper function to extract service territories with ID and name from various response structures
                fun extractServiceTerritories(element: JsonElement): List<ServiceTerritoryInfo> {
                    return when (element) {
                        is JsonArray -> {
                            // Could be direct array of service territories, or nested array
                            element.flatMap { item ->
                                when (item) {
                                    is JsonObject -> {
                                        // Direct service territory object
                                        val id = item["id"]?.jsonPrimitive?.content
                                            ?: item["Id"]?.jsonPrimitive?.content
                                            ?: item["serviceTerritoryId"]?.jsonPrimitive?.content
                                            ?: item["ServiceTerritoryId"]?.jsonPrimitive?.content
                                        
                                        val name = item["name"]?.jsonPrimitive?.content
                                            ?: item["Name"]?.jsonPrimitive?.content
                                            ?: item["label"]?.jsonPrimitive?.content
                                            ?: item["Label"]?.jsonPrimitive?.content
                                            ?: item["title"]?.jsonPrimitive?.content
                                        
                                        val address = item["Address"]?.jsonObject?.let { addr ->
                                            // Address can be an object, try to extract city or full address
                                            addr["city"]?.jsonPrimitive?.content
                                                ?: addr["City"]?.jsonPrimitive?.content
                                                ?: addr["street"]?.jsonPrimitive?.content
                                                ?: addr["Street"]?.jsonPrimitive?.content
                                        } ?: item["address"]?.jsonPrimitive?.content
                                            ?: item["Address"]?.jsonPrimitive?.content
                                        
                                        if (id != null || name != null) {
                                            listOf(ServiceTerritoryInfo(id, name, address))
                                        } else emptyList()
                                    }
                                    is JsonArray -> {
                                        // Nested array - recursively extract from it
                                        extractServiceTerritories(item)
                                    }
                                    else -> emptyList()
                                }
                            }
                        }
                        is JsonObject -> {
                            // Object containing an array of service territories
                            val records = element["records"] as? JsonArray
                                ?: element["serviceTerritories"] as? JsonArray
                                ?: element["data"] as? JsonArray
                                ?: element["items"] as? JsonArray
                            
                            records?.let { extractServiceTerritories(it) } ?: emptyList()
                        }
                        else -> emptyList()
                    }
                }
                
                // Extract all service territories from the response
                val availableServiceTerritories = extractServiceTerritories(jsonElement)
                logger.info("${LogColors.SERVICE_TERRITORY} Found ${availableServiceTerritories.size} service territories in response")
                logger.debug("${LogColors.SERVICE_TERRITORY} Available service territories: ${availableServiceTerritories.map { "${it.name} (${it.id})" }}")
                
                // If no service territories found, log the actual JSON structure for debugging
                if (availableServiceTerritories.isEmpty()) {
                    logger.warn("${LogColors.SERVICE_TERRITORY} No service territories extracted. JSON structure: ${jsonElement::class.simpleName}")
                    logger.warn("${LogColors.SERVICE_TERRITORY} JSON element keys: ${if (jsonElement is JsonObject) jsonElement.keys.joinToString(", ") else "N/A"}")
                    logger.warn("${LogColors.SERVICE_TERRITORY} Full JSON (first 1000 chars): ${jsonPart.take(1000)}")
                }
                
                // Normalize the requested location for comparison (lowercase, trim)
                val normalizedRequestLocation = request.location.lowercase().trim()
                
                // Find matching service territory by name or address (case-insensitive, fuzzy matching)
                val matchingTerritory = availableServiceTerritories.firstOrNull { territory ->
                    territory.name?.let { name ->
                        val normalizedName = name.lowercase().trim()
                        // Exact match or contains match
                        normalizedName == normalizedRequestLocation || 
                        normalizedName.contains(normalizedRequestLocation) ||
                        normalizedRequestLocation.contains(normalizedName)
                    } ?: false || territory.address?.let { addr ->
                        val normalizedAddr = addr.lowercase().trim()
                        normalizedAddr.contains(normalizedRequestLocation) ||
                        normalizedRequestLocation.contains(normalizedAddr)
                    } ?: false
                }
                
                val isValid = matchingTerritory != null
                val matchedServiceTerritoryId = matchingTerritory?.id
                
                val message = if (isValid && matchedServiceTerritoryId != null) {
                    "Service territory '${request.location}' is valid. Matched service territory ID: $matchedServiceTerritoryId"
                } else if (isValid) {
                    "Service territory '${request.location}' matches but no ID found in response"
                } else {
                    if (availableServiceTerritories.isEmpty()) {
                        "No service territories found in the response. Cannot validate service territory '${request.location}'. " +
                        "Response structure: ${jsonElement::class.simpleName}. " +
                        "Check logs for full response details."
                    } else {
                        val availableNames = availableServiceTerritories.mapNotNull { it.name }.joinToString(", ")
                        "Service territory '${request.location}' not found. Available service territories: $availableNames"
                    }
                }
                
                ServiceTerritoryValidationResult(
                    isValid = isValid,
                    message = message,
                    serviceTerritoryId = matchedServiceTerritoryId
                )
            } else {
                // No JSON found, return error
                logger.error("${LogColors.SERVICE_TERRITORY} No JSON object or array found in tool response")
                ServiceTerritoryValidationResult(
                    isValid = false,
                    message = "Validation tool returned invalid response format: no JSON structure found"
                )
            }
        } catch (e: Exception) {
            logger.error("${LogColors.SERVICE_TERRITORY} Failed to parse tool response: ${e.message}", e)
            ServiceTerritoryValidationResult(
                isValid = false,
                message = "Failed to parse validation response: ${e.message}"
            )
        }
        
        logger.info("${LogColors.SERVICE_TERRITORY} Validation complete: isValid=${result.isValid}, message=${result.message}, serviceTerritoryId=${result.serviceTerritoryId}")
        result
    }

    val createTask by node<ServiceTerritoryValidationRequest, ServiceTerritoryValidationRequest> { input ->
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

    val sendResult by node<ServiceTerritoryValidationResult, Unit> { result ->
        logger.info("${LogColors.SERVICE_TERRITORY} Sending result artifact")
        withA2AAgentServer {
            val artifactUpdate = TaskArtifactUpdateEvent(
                taskId = context.taskId,
                contextId = context.contextId,
                artifact = Artifact(
                    artifactId = "service-territory-validation",
                    parts = listOf(TextPart(json.encodeToString(ServiceTerritoryValidationResult.serializer(), result)))
                )
            )
            eventProcessor.sendTaskEvent(artifactUpdate)
            delay(100) // Added a small delay to ensure event is emitted
        }
    }

    nodeStart then parseInput then createTask then validateServiceTerritory then sendResult then nodeFinish
}

