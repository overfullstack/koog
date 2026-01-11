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
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import ai.koog.a2a.model.TextPart
import ai.koog.a2a.model.TaskArtifactUpdateEvent
import ai.koog.a2a.model.Artifact
import ai.koog.a2a.model.Task
import ai.koog.a2a.model.TaskStatus
import ai.koog.a2a.model.TaskState
import ai.koog.agents.core.tools.reflect.tools
import org.jetbrains.demo.agent.a2a.model.AppointmentValidationRequest
import org.jetbrains.demo.agent.a2a.model.AppointmentValidationResult
import org.slf4j.LoggerFactory
import kotlin.reflect.typeOf
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.delay
import org.salesforce.LogColors
import org.salesforce.travel.LLM_MODEL
import org.salesforce.A2ATelemetry
import org.salesforce.swara.tools.AppointmentValidationTool

private val logger = LoggerFactory.getLogger("AppointmentValidationAgent")

const val APPOINTMENT_VALIDATION_PATH = "/a2a/appointment-validation"
const val APPOINTMENT_VALIDATION_CARD_PATH = "$APPOINTMENT_VALIDATION_PATH/agent-card.json"

fun appointmentValidationAgentCard(baseUrl: String): AgentCard = AgentCard(
    protocolVersion = "0.3.0",
    name = "Appointment Validation Agent",
    description = "Validates if a work type group exists in the system",
    version = "1.0.0",
    url = "$baseUrl$APPOINTMENT_VALIDATION_PATH",
    preferredTransport = TransportProtocol.JSONRPC,
    additionalInterfaces = listOf(
        AgentInterface(
            url = "$baseUrl$APPOINTMENT_VALIDATION_PATH",
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
            id = "appointment_validation",
            name = "Appointment Validation",
            description = "Validates if a work type group exists in the system",
            examples = listOf(
                "Validate work type group 'blood test'",
                "Check if work type group 'MRI' exists",
                "Validate work type group name"
            ),
            tags = listOf("appointment", "validation", "work-type")
        )
    ),
    supportsAuthenticatedExtendedCard = false
)

class AppointmentValidationAgentExecutor(
    private val promptExecutor: PromptExecutor
) : AgentExecutor {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        logger.info(LogColors.validationBanner("APPOINTMENT_VALIDATION EXECUTION START"))
        logger.info("${LogColors.VALIDATION} TaskId: ${context.taskId}")
        logger.info("${LogColors.VALIDATION} ContextId: ${context.contextId}")
        val startTime = System.currentTimeMillis()
        try {
            val agent = appointmentValidationAgent(promptExecutor, context, eventProcessor)
            agent.run(context.params.message)
            val duration = System.currentTimeMillis() - startTime
            logger.info("${LogColors.VALIDATION} Execution completed in ${duration}ms")
        } catch (e: Exception) {
            logger.error("${LogColors.VALIDATION} ${LogColors.red("Execution failed")}: ${e.message}", e)
            throw e
        }
        logger.info(LogColors.validationBanner("APPOINTMENT_VALIDATION EXECUTION END"))
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun appointmentValidationAgent(
    promptExecutor: PromptExecutor,
    context: RequestContext<MessageSendParams>,
    eventProcessor: SessionEventProcessor
): GraphAIAgent<A2AMessage, Unit> {
    val agentConfig = AIAgentConfig(
        prompt = prompt("appointment-validation") {
            system {
                +"""
                You are an appointment validation specialist.
                Your task is to validate if a work type group name exists in the system.
                
                Use the validation tool to fetch available work type groups and check if the provided name matches any of them.
                Return a clear validation result with isValid flag, a message, and the workTypeGroupId of the matching work type group if found.
                """.trimIndent()
            }
        },
        model = LLM_MODEL,
        maxAgentIterations = 10
    )

    val toolRegistry = ToolRegistry {
        tools(AppointmentValidationTool())
    }

    return GraphAIAgent(
        inputType = typeOf<A2AMessage>(),
        outputType = typeOf<Unit>(),
        promptExecutor = promptExecutor,
        strategy = appointmentValidationStrategy(),
        agentConfig = agentConfig,
        toolRegistry = toolRegistry,
    ) {
        install(A2AAgentServer) {
            this.context = context
            this.eventProcessor = eventProcessor
        }
        if (A2ATelemetry.isEnabled) {
            install(OpenTelemetry, A2ATelemetry.installLangfuse(
                agentName = "appointment-validation",
                sessionId = context.contextId
            ))
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun appointmentValidationStrategy() = strategy<A2AMessage, Unit>("appointment-validation-strategy") {
    val json = Json { ignoreUnknownKeys = true }

    val parseInput by node<A2AMessage, AppointmentValidationRequest> { message ->
        logger.debug("${LogColors.VALIDATION} Parsing input message...")
        val textContent = message.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
        val request = json.decodeFromString<AppointmentValidationRequest>(textContent)
        logger.info("${LogColors.VALIDATION} Parsed request: workTypeGroupName=${request.workTypeGroupName}")
        request
    }

    val validateAppointment by node<AppointmentValidationRequest, AppointmentValidationResult> { request ->
        logger.info("${LogColors.VALIDATION} Calling validation tool directly...")
        logger.info("${LogColors.VALIDATION} WorkTypeGroupName: ${request.workTypeGroupName}")
        
        // Call the validation tool directly
        val validationTool = AppointmentValidationTool()
        val toolResponse = try {
            validationTool.validateWorkTypeGroup(
                workTypeGroupName = request.workTypeGroupName
            )
        } catch (e: Exception) {
            logger.error("${LogColors.VALIDATION} Tool execution failed: ${e.message}", e)
            return@node AppointmentValidationResult(
                isValid = false,
                message = "Validation tool execution failed: ${e.message}"
            )
        }
        
        logger.info("${LogColors.VALIDATION} Tool response (first 500 chars): ${toolResponse.take(500)}")
        
        // Parse the tool response - it returns a JSON string containing a list of work type groups
        // We need to check if the requested workTypeGroupName matches any of the work type groups
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
                logger.debug("${LogColors.VALIDATION} Extracted JSON part: $jsonPart")
                
                val jsonElement = json.parseToJsonElement(jsonPart)
                
                // Data class to hold work type group info
                data class WorkTypeGroupInfo(
                    val id: String?,
                    val name: String?
                )
                
                // Helper function to extract work type groups with ID and name from various response structures
                fun extractWorkTypeGroups(element: JsonElement): List<WorkTypeGroupInfo> {
                    return when (element) {
                        is JsonArray -> {
                            // Could be direct array of work type groups, or nested array
                            element.flatMap { item ->
                                when (item) {
                                    is JsonObject -> {
                                        // Direct work type group object
                                        val id = item["id"]?.jsonPrimitive?.content
                                            ?: item["Id"]?.jsonPrimitive?.content
                                            ?: item["workTypeGroupId"]?.jsonPrimitive?.content
                                            ?: item["workTypeGroupID"]?.jsonPrimitive?.content
                                        
                                        val name = item["name"]?.jsonPrimitive?.content
                                            ?: item["Name"]?.jsonPrimitive?.content
                                            ?: item["label"]?.jsonPrimitive?.content
                                            ?: item["Label"]?.jsonPrimitive?.content
                                            ?: item["title"]?.jsonPrimitive?.content
                                        
                                        if (id != null || name != null) {
                                            listOf(WorkTypeGroupInfo(id, name))
                                        } else emptyList()
                                    }
                                    is JsonArray -> {
                                        // Nested array - recursively extract from it
                                        extractWorkTypeGroups(item)
                                    }
                                    else -> emptyList()
                                }
                            }
                        }
                        is JsonObject -> {
                            // Object containing an array of work type groups
                            val records = element["records"] as? JsonArray
                                ?: element["workTypeGroups"] as? JsonArray
                                ?: element["data"] as? JsonArray
                                ?: element["items"] as? JsonArray
                            
                            records?.let { extractWorkTypeGroups(it) } ?: emptyList()
                        }
                        else -> emptyList()
                    }
                }
                
                // Extract all work type groups from the response
                val availableWorkTypeGroups = extractWorkTypeGroups(jsonElement)
                logger.info("${LogColors.VALIDATION} Found ${availableWorkTypeGroups.size} work type groups in response")
                logger.debug("${LogColors.VALIDATION} Available work type groups: ${availableWorkTypeGroups.map { "${it.name} (${it.id})" }}")
                
                // If no work type groups found, log the actual JSON structure for debugging
                if (availableWorkTypeGroups.isEmpty()) {
                    logger.warn("${LogColors.VALIDATION} No work type groups extracted. JSON structure: ${jsonElement::class.simpleName}")
                    logger.warn("${LogColors.VALIDATION} JSON element keys: ${if (jsonElement is JsonObject) jsonElement.keys.joinToString(", ") else "N/A"}")
                    logger.warn("${LogColors.VALIDATION} Full JSON (first 1000 chars): ${jsonPart.take(1000)}")
                }
                
                // Normalize the requested name for comparison (lowercase, trim)
                val normalizedRequestName = request.workTypeGroupName.lowercase().trim()
                
                // Find matching work type group by name (case-insensitive, fuzzy matching)
                val matchingGroup = availableWorkTypeGroups.firstOrNull { group ->
                    group.name?.let { name ->
                        val normalizedName = name.lowercase().trim()
                        // Exact match or contains match
                        normalizedName == normalizedRequestName || 
                        normalizedName.contains(normalizedRequestName) ||
                        normalizedRequestName.contains(normalizedName)
                    } ?: false
                }
                
                val isValid = matchingGroup != null
                val matchedWorkTypeGroupId = matchingGroup?.id
                
                val message = if (isValid && matchedWorkTypeGroupId != null) {
                    "Work type group '${request.workTypeGroupName}' is valid. Matched work type group ID: $matchedWorkTypeGroupId"
                } else if (isValid) {
                    "Work type group '${request.workTypeGroupName}' matches but no ID found in response"
                } else {
                    if (availableWorkTypeGroups.isEmpty()) {
                        "No work type groups found in the response. Cannot validate work type group '${request.workTypeGroupName}'. " +
                        "Response structure: ${jsonElement::class.simpleName}. " +
                        "Check logs for full response details."
                    } else {
                        val availableNames = availableWorkTypeGroups.mapNotNull { it.name }.joinToString(", ")
                        "Work type group '${request.workTypeGroupName}' not found. Available work type groups: $availableNames"
                    }
                }
                
                AppointmentValidationResult(
                    isValid = isValid,
                    message = message,
                    workTypeGroupId = matchedWorkTypeGroupId
                )
            } else {
                // No JSON found, return error
                logger.error("${LogColors.VALIDATION} No JSON object or array found in tool response")
                AppointmentValidationResult(
                    isValid = false,
                    message = "Validation tool returned invalid response format: no JSON structure found"
                )
            }
        } catch (e: Exception) {
            logger.error("${LogColors.VALIDATION} Failed to parse tool response: ${e.message}", e)
            AppointmentValidationResult(
                isValid = false,
                message = "Failed to parse validation response: ${e.message}"
            )
        }
        
        logger.info("${LogColors.VALIDATION} Validation complete: isValid=${result.isValid}, message=${result.message}, workTypeGroupId=${result.workTypeGroupId}")
        result
    }

    val createTask by node<AppointmentValidationRequest, AppointmentValidationRequest> { input ->
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

    val sendResult by node<AppointmentValidationResult, Unit> { result ->
        logger.info("${LogColors.VALIDATION} Sending result artifact")
        withA2AAgentServer {
            val artifactUpdate = TaskArtifactUpdateEvent(
                taskId = context.taskId,
                contextId = context.contextId,
                artifact = Artifact(
                    artifactId = "appointment-validation",
                    parts = listOf(TextPart(json.encodeToString(AppointmentValidationResult.serializer(), result)))
                )
            )
            eventProcessor.sendTaskEvent(artifactUpdate)
            delay(100) // Added a small delay to ensure event is emitted
        }
    }

    nodeStart then parseInput then createTask then validateAppointment then sendResult then nodeFinish
}
