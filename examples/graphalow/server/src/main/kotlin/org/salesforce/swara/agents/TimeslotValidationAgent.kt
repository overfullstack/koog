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
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.demo.agent.a2a.model.TimeslotValidationRequest
import org.jetbrains.demo.agent.a2a.model.TimeslotValidationResult
import org.salesforce.A2ATelemetry
import org.salesforce.LogColors
import org.salesforce.travel.LLM_MODEL
import org.slf4j.LoggerFactory
import kotlin.reflect.typeOf
import kotlin.uuid.ExperimentalUuidApi

private val logger = LoggerFactory.getLogger("TimeslotValidationAgent")

const val TIMESLOT_VALIDATION_PATH = "/a2a/timeslot-validation"
const val TIMESLOT_VALIDATION_CARD_PATH = "$TIMESLOT_VALIDATION_PATH/agent-card.json"

fun timeslotValidationAgentCard(baseUrl: String): AgentCard = AgentCard(
    protocolVersion = "0.3.0",
    name = "Timeslot Validation Agent",
    description = "Validates if a timeslot is available for the given appointment time, service territory, and work type group",
    version = "1.0.0",
    url = "$baseUrl$TIMESLOT_VALIDATION_PATH",
    preferredTransport = TransportProtocol.JSONRPC,
    additionalInterfaces = listOf(
        AgentInterface(
            url = "$baseUrl$TIMESLOT_VALIDATION_PATH",
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
            id = "timeslot_validation",
            name = "Timeslot Validation",
            description = "Validates if a timeslot is available for the given appointment time, service territory, and work type group",
            examples = listOf(
                "Validate timeslot for appointment time '2025-01-15T10:00:00' with service territory ID 'xxx' and work type group ID 'yyy'",
                "Check if timeslot is available",
                "Validate appointment timeslot"
            ),
            tags = listOf("appointment", "validation", "timeslot", "scheduling")
        )
    ),
    supportsAuthenticatedExtendedCard = false
)

class TimeslotValidationAgentExecutor(
    private val promptExecutor: PromptExecutor
) : AgentExecutor {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        logger.info(LogColors.timeslotBanner("TIMESLOT_VALIDATION EXECUTION START"))
        logger.info("${LogColors.TIMESLOT} TaskId: ${context.taskId}")
        logger.info("${LogColors.TIMESLOT} ContextId: ${context.contextId}")
        val startTime = System.currentTimeMillis()
        try {
            val agent = timeslotValidationAgent(promptExecutor, context, eventProcessor)
            agent.run(context.params.message)
            val duration = System.currentTimeMillis() - startTime
            logger.info("${LogColors.TIMESLOT} Execution completed in ${duration}ms")
        } catch (e: Exception) {
            logger.error("${LogColors.TIMESLOT} ${LogColors.red("Execution failed")}: ${e.message}", e)
            throw e
        }
        logger.info(LogColors.timeslotBanner("TIMESLOT_VALIDATION EXECUTION END"))
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun timeslotValidationAgent(
    promptExecutor: PromptExecutor,
    context: RequestContext<MessageSendParams>,
    eventProcessor: SessionEventProcessor
): GraphAIAgent<A2AMessage, Unit> {
    val agentConfig = AIAgentConfig(
        prompt = prompt("timeslot-validation") {
            system {
                +"""
                You are a timeslot validation specialist.
                Your task is to validate if a timeslot is available for the given appointment time, service territory, and work type group.
                
                Use the validation tool to fetch available timeslots and check if the provided appointment time matches any available timeslot.
                Return a clear validation result with isValid flag, a message, and the timeslot details (timeslotId, startTime, endTime) if found.
                """.trimIndent()
            }
        },
        model = LLM_MODEL,
        maxAgentIterations = 10
    )

    val toolRegistry = ToolRegistry {
        tools(org.jetbrains.demo.agent.tools.TimeslotValidationTool())
    }

    return GraphAIAgent(
        inputType = typeOf<A2AMessage>(),
        outputType = typeOf<Unit>(),
        promptExecutor = promptExecutor,
        strategy = timeslotValidationStrategy(),
        agentConfig = agentConfig,
        toolRegistry = toolRegistry,
    ) {
        install(A2AAgentServer) {
            this.context = context
            this.eventProcessor = eventProcessor
        }
        if (A2ATelemetry.isEnabled) {
            install(OpenTelemetry, A2ATelemetry.installLangfuse(
                agentName = "timeslot-validation",
                sessionId = context.contextId
            ))
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun timeslotValidationStrategy() = strategy<A2AMessage, Unit>("timeslot-validation-strategy") {
    val json = Json { ignoreUnknownKeys = true }

    val parseInput by node<A2AMessage, TimeslotValidationRequest> { message ->
        logger.debug("${LogColors.TIMESLOT} Parsing input message...")
        val textContent = message.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
        val request = json.decodeFromString<TimeslotValidationRequest>(textContent)
        logger.info("${LogColors.TIMESLOT} Parsed request: appointmentTime=${request.appointmentTime}, serviceTerritoryId=${request.serviceTerritoryId}, workTypeGroupId=${request.workTypeGroupId}")
        request
    }

    val validateTimeslot by node<TimeslotValidationRequest, TimeslotValidationResult> { request ->
        logger.info("${LogColors.TIMESLOT} Calling validation tool directly...")
        logger.info("${LogColors.TIMESLOT} AppointmentTime: ${request.appointmentTime}, ServiceTerritoryId: ${request.serviceTerritoryId}, WorkTypeGroupId: ${request.workTypeGroupId}")
        
        // Call the validation tool directly
        val validationTool = org.jetbrains.demo.agent.tools.TimeslotValidationTool()
        val toolResponse = try {
            // Convert LocalDateTime to ISO 8601 string
            val appointmentTimeStr = request.appointmentTime.toString()
            validationTool.validateTimeslot(
                appointmentTime = appointmentTimeStr,
                serviceTerritoryId = request.serviceTerritoryId,
                workTypeGroupId = request.workTypeGroupId
            )
        } catch (e: Exception) {
            logger.error("${LogColors.TIMESLOT} Tool execution failed: ${e.message}", e)
            return@node TimeslotValidationResult(
                isValid = false,
                message = "Validation tool execution failed: ${e.message}"
            )
        }
        
        logger.info("${LogColors.TIMESLOT} Tool response (first 500 chars): ${toolResponse.take(500)}")
        
        // Parse the tool response - it returns a JSON string containing timeslot information
        // We need to check if the requested appointment time matches any available timeslot
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
                logger.debug("${LogColors.TIMESLOT} Extracted JSON part: $jsonPart")
                
                val jsonElement = json.parseToJsonElement(jsonPart)
                
                // Data class to hold timeslot info
                data class TimeslotInfo(
                    val id: String?,
                    val startTime: String?,
                    val endTime: String?,
                    val serviceResourceId: String?
                )
                
                // Helper function to extract timeslots from various response structures
                fun extractTimeslots(element: JsonElement): List<TimeslotInfo> {
                    return when (element) {
                        is JsonArray -> {
                            // Could be direct array of timeslots, or nested array
                            element.flatMap { item ->
                                when (item) {
                                    is JsonObject -> {
                                        // Direct timeslot object
                                        val id = item["id"]?.jsonPrimitive?.content
                                            ?: item["Id"]?.jsonPrimitive?.content
                                            ?: item["timeslotId"]?.jsonPrimitive?.content
                                            ?: item["TimeslotId"]?.jsonPrimitive?.content
                                        
                                        val startTime = item["startTime"]?.jsonPrimitive?.content
                                            ?: item["StartTime"]?.jsonPrimitive?.content
                                            ?: item["start"]?.jsonPrimitive?.content
                                            ?: item["Start"]?.jsonPrimitive?.content
                                        
                                        val endTime = item["endTime"]?.jsonPrimitive?.content
                                            ?: item["EndTime"]?.jsonPrimitive?.content
                                            ?: item["end"]?.jsonPrimitive?.content
                                            ?: item["End"]?.jsonPrimitive?.content
                                        
                                        val serviceResourceId = item["serviceResourceId"]?.jsonPrimitive?.content
                                            ?: item["ServiceResourceId"]?.jsonPrimitive?.content
                                            ?: item["serviceResource"]?.jsonPrimitive?.content
                                        
                                        if (startTime != null || endTime != null) {
                                            listOf(TimeslotInfo(id, startTime, endTime, serviceResourceId))
                                        } else emptyList()
                                    }
                                    is JsonArray -> {
                                        // Nested array - recursively extract from it
                                        extractTimeslots(item)
                                    }
                                    else -> emptyList()
                                }
                            }
                        }
                        is JsonObject -> {
                            // Object containing an array of timeslots
                            val records = element["records"] as? JsonArray
                                ?: element["timeslots"] as? JsonArray
                                ?: element["data"] as? JsonArray
                                ?: element["items"] as? JsonArray
                                ?: element["slots"] as? JsonArray
                            
                            records?.let { extractTimeslots(it) } ?: emptyList()
                        }
                        else -> emptyList()
                    }
                }
                
                // Extract all timeslots from the response
                val availableTimeslots = extractTimeslots(jsonElement)
                logger.info("${LogColors.TIMESLOT} Found ${availableTimeslots.size} timeslots in response")
                logger.debug("${LogColors.TIMESLOT} Available timeslots: ${availableTimeslots.map { "${it.startTime} - ${it.endTime} (${it.id})" }}")
                
                // If no timeslots found, log the actual JSON structure for debugging
                if (availableTimeslots.isEmpty()) {
                    logger.warn("${LogColors.TIMESLOT} No timeslots extracted. JSON structure: ${jsonElement::class.simpleName}")
                    logger.warn("${LogColors.TIMESLOT} JSON element keys: ${if (jsonElement is JsonObject) jsonElement.keys.joinToString(", ") else "N/A"}")
                    logger.warn("${LogColors.TIMESLOT} Full JSON (first 1000 chars): ${jsonPart.take(1000)}")
                }
                
                // Normalize the requested appointment time for comparison
                val requestedTimeStr = request.appointmentTime.toString()
                
                // Find matching timeslot by checking if the requested time falls within any timeslot's start and end time
                val matchingTimeslot = availableTimeslots.firstOrNull { timeslot ->
                    try {
                        timeslot.startTime?.let { startStr ->
                            val startTime = LocalDateTime.parse(startStr.substringBefore('+').substringBefore('Z'))
                            val requestedTime = request.appointmentTime
                            // Check if requested time is within the timeslot range (with some tolerance)
                            // For now, we'll do a simple comparison - you might want to add more sophisticated matching
                            startTime <= requestedTime && timeslot.endTime?.let { endStr ->
                                val endTime = LocalDateTime.parse(endStr.substringBefore('+').substringBefore('Z'))
                                requestedTime <= endTime
                            } ?: false
                        } ?: false
                    } catch (e: Exception) {
                        logger.warn("${LogColors.TIMESLOT} Error parsing timeslot time: ${e.message}")
                        false
                    }
                }
                
                val isValid = matchingTimeslot != null
                val matchedTimeslotId = matchingTimeslot?.id
                val matchedStartTime = matchingTimeslot?.startTime?.let { 
                    try {
                        LocalDateTime.parse(it.substringBefore('+').substringBefore('Z'))
                    } catch (e: Exception) {
                        null
                    }
                }
                val matchedEndTime = matchingTimeslot?.endTime?.let {
                    try {
                        LocalDateTime.parse(it.substringBefore('+').substringBefore('Z'))
                    } catch (e: Exception) {
                        null
                    }
                }
                
                val message = if (isValid && matchedTimeslotId != null) {
                    "Timeslot is valid for appointment time '${request.appointmentTime}'. Matched timeslot ID: $matchedTimeslotId"
                } else if (isValid) {
                    "Timeslot matches but no ID found in response"
                } else {
                    if (availableTimeslots.isEmpty()) {
                        "No timeslots found in the response. Cannot validate timeslot for appointment time '${request.appointmentTime}'. " +
                        "Response structure: ${jsonElement::class.simpleName}. " +
                        "Check logs for full response details."
                    } else {
                        val availableSlots = availableTimeslots.mapNotNull { 
                            "${it.startTime} - ${it.endTime}" 
                        }.joinToString(", ")
                        "No matching timeslot found for appointment time '${request.appointmentTime}'. Available timeslots: $availableSlots"
                    }
                }
                
                TimeslotValidationResult(
                    isValid = isValid,
                    message = message,
                    timeslotId = matchedTimeslotId,
                    startTime = matchedStartTime,
                    endTime = matchedEndTime
                )
            } else {
                // No JSON found, return error
                logger.error("${LogColors.TIMESLOT} No JSON object or array found in tool response")
                TimeslotValidationResult(
                    isValid = false,
                    message = "Validation tool returned invalid response format: no JSON structure found"
                )
            }
        } catch (e: Exception) {
            logger.error("${LogColors.TIMESLOT} Failed to parse tool response: ${e.message}", e)
            TimeslotValidationResult(
                isValid = false,
                message = "Failed to parse validation response: ${e.message}"
            )
        }
        
        logger.info("${LogColors.TIMESLOT} Validation complete: isValid=${result.isValid}, message=${result.message}, timeslotId=${result.timeslotId}")
        result
    }

    val createTask by node<TimeslotValidationRequest, TimeslotValidationRequest> { input ->
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

    val sendResult by node<TimeslotValidationResult, Unit> { result ->
        logger.info("${LogColors.TIMESLOT} Sending result artifact")
        withA2AAgentServer {
            val artifactUpdate = TaskArtifactUpdateEvent(
                taskId = context.taskId,
                contextId = context.contextId,
                artifact = Artifact(
                    artifactId = "timeslot-validation",
                    parts = listOf(TextPart(json.encodeToString(TimeslotValidationResult.serializer(), result)))
                )
            )
            eventProcessor.sendTaskEvent(artifactUpdate)
            delay(100) // Added a small delay to ensure event is emitted
        }
    }

    nodeStart then parseInput then createTask then validateTimeslot then sendResult then nodeFinish
}

