package org.jetbrains.demo.agent.a2a

import ai.koog.a2a.model.*
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
import org.jetbrains.demo.agent.a2a.A2ATelemetry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.markdown.markdown
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ai.koog.a2a.model.TextPart
import ai.koog.a2a.model.TaskArtifactUpdateEvent
import ai.koog.a2a.model.Artifact
import ai.koog.a2a.model.Task
import ai.koog.a2a.model.TaskStatus
import ai.koog.a2a.model.TaskState
import org.jetbrains.demo.LLM_MODEL
import org.jetbrains.demo.agent.a2a.model.TimeslotValidationRequest
import org.jetbrains.demo.agent.a2a.model.TimeslotValidationResult
import org.slf4j.LoggerFactory
import kotlin.reflect.typeOf
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.delay

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
                
                IMPORTANT: ALL TIMES ARE IN UTC (Coordinated Universal Time). Both the requested appointment time and all available timeslots are in UTC.
                
                You will receive:
                1. The requested appointment time in UTC (may be in ISO format like "2026-01-12T02:30:00" or "2026-01-12T02:30:00.000Z")
                2. A list of available timeslots with their start and end times in ISO format, all in UTC
                
                Your job is to:
                - Parse and understand the requested time (all times are already in UTC, no timezone conversion needed)
                - Match it against the available timeslots (direct comparison since both are in UTC)
                - Find the best matching timeslot (exact start time match preferred, or closest match)
                - Return a clear validation result indicating if a match was found
                
                When matching:
                - Prioritize exact start time matches (same date, hour, minute - all in UTC)
                - If no exact match, find the closest available slot within a reasonable time window
                - Since all times are in UTC, you can directly compare them without timezone conversion
                - Match on the same date, hour, and minute if possible
                - If you find a matching slot, set isValid=true and return the slot details
                
                Return a clear validation result with isValid flag, a message explaining the match, and the timeslot details (timeslotId, startTime in ISO format, endTime in ISO format, and serviceResourceId) if found.
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
        logger.info("${LogColors.TIMESLOT} Calling validation tool to get available timeslots...")
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
        
        // Parse the tool response to extract available timeslots
        logger.info("${LogColors.TIMESLOT} ${LogColors.LLM} Using LLM to match requested time against available timeslots...")
        val availableTimeslots = try {
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
                            element.flatMap { item ->
                                when (item) {
                                    is JsonObject -> {
                                        val id = item["id"]?.jsonPrimitive?.content
                                            ?: item["Id"]?.jsonPrimitive?.content
                                            ?: item["timeslotId"]?.jsonPrimitive?.content
                                        
                                        val startTime = item["startTime"]?.jsonPrimitive?.content
                                            ?: item["StartTime"]?.jsonPrimitive?.content
                                        
                                        val endTime = item["endTime"]?.jsonPrimitive?.content
                                            ?: item["EndTime"]?.jsonPrimitive?.content
                                        
                                        // Extract serviceResourceId from resources array (first element)
                                        val serviceResourceId = item["resources"]?.let { resourcesElement ->
                                            when (resourcesElement) {
                                                is JsonArray -> {
                                                    resourcesElement.firstOrNull()?.jsonPrimitive?.content
                                                }
                                                else -> null
                                            }
                                        } ?: item["serviceResourceId"]?.jsonPrimitive?.content
                                            ?: item["ServiceResourceId"]?.jsonPrimitive?.content
                                            ?: item["resourceId"]?.jsonPrimitive?.content
                                            ?: item["ResourceId"]?.jsonPrimitive?.content
                                        
                                        if (startTime != null || endTime != null) {
                                            listOf(TimeslotInfo(id, startTime, endTime, serviceResourceId))
                                        } else emptyList()
                                    }
                                    is JsonArray -> extractTimeslots(item)
                                    else -> emptyList()
                                }
                            }
                        }
                        is JsonObject -> {
                            // Check for nested structure: allAppointmentTimeSlotResponse.slots
                            val allAppointmentResponse = element["allAppointmentTimeSlotResponse"] as? JsonObject
                            if (allAppointmentResponse != null) {
                                val slots = allAppointmentResponse["slots"] as? JsonArray
                                if (slots != null) {
                                    logger.debug("${LogColors.TIMESLOT} Found slots in allAppointmentTimeSlotResponse")
                                    return extractTimeslots(slots)
                                }
                            }
                            
                            // Fallback: check for slots at root level or other common structures
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
                
                extractTimeslots(jsonElement)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            logger.error("${LogColors.TIMESLOT} Failed to extract timeslots: ${e.message}", e)
            emptyList()
        }
        
        logger.info("${LogColors.TIMESLOT} Found ${availableTimeslots.size} timeslots in response")
        
        if (availableTimeslots.isEmpty()) {
            return@node TimeslotValidationResult(
                isValid = false,
                message = "No timeslots found in the response. Cannot validate timeslot for appointment time '${request.appointmentTime}'."
            )
        }
        
        // Use LLM to match the requested time against available timeslots
        val result = llm.writeSession {
            appendPrompt {
                user {
                    markdown {
                        header(1, "Timeslot Matching")
                        bulleted {
                            item("**Requested Appointment Time**: ${request.appointmentTime}")
                            item("**Available Timeslots**: ${availableTimeslots.size} slots found")
                        }
                        header(2, "Available Timeslots")
                        val timeslotsJson = json.encodeToString(JsonArray.serializer(), JsonArray(
                            availableTimeslots.map { slot ->
                                JsonObject(mapOf(
                                    "id" to JsonPrimitive(slot.id ?: ""),
                                    "startTime" to JsonPrimitive(slot.startTime ?: ""),
                                    "endTime" to JsonPrimitive(slot.endTime ?: ""),
                                    "serviceResourceId" to JsonPrimitive(slot.serviceResourceId ?: "")
                                ))
                            }
                        ))
                        codeblock(timeslotsJson, "json")
                        header(2, "Task")
                        bulleted {
                            item("**IMPORTANT: ALL TIMES ARE IN UTC** - Both the requested time and all available timeslots are in UTC. No timezone conversion is needed.")
                            item("Match the requested appointment time '${request.appointmentTime}' (UTC) against the available timeslots (all in UTC)")
                            item("Since all times are in UTC, do a direct comparison - look for exact matches on date, hour, and minute")
                            item("If an exact match is found (same date, hour, minute), set isValid=true and return that timeslot")
                            item("If no exact match, find the closest available slot and consider if it's within a reasonable time window")
                            item("Return the matched timeslot details including timeslotId, startTime (in ISO format with UTC), endTime (in ISO format with UTC), and serviceResourceId")
                            item("Note: serviceResourceId should be the first element from the 'resources' array in the matched timeslot (e.g., if resources is ['0Hnfi8000008SlVCAU'], use '0Hnfi8000008SlVCAU')")
                        }
                    }
                }
            }
            requestLLMStructured<TimeslotValidationResult>().getOrThrow().data
        }
        
        logger.info("${LogColors.TIMESLOT} LLM matching complete: isValid=${result.isValid}, message=${result.message}, timeslotId=${result.timeslotId}")
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

