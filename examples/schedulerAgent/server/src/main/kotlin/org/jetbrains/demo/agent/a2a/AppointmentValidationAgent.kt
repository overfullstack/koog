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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ai.koog.a2a.model.TextPart
import ai.koog.a2a.model.TaskArtifactUpdateEvent
import ai.koog.a2a.model.Artifact
import ai.koog.a2a.model.Task
import ai.koog.a2a.model.TaskStatus
import ai.koog.a2a.model.TaskState
import org.jetbrains.demo.LLM_MODEL
import org.jetbrains.demo.agent.a2a.model.AppointmentValidationRequest
import org.jetbrains.demo.agent.a2a.model.AppointmentValidationResult
import org.jetbrains.demo.agent.tools.Tools
import org.slf4j.LoggerFactory
import kotlin.reflect.typeOf
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.delay

private val logger = LoggerFactory.getLogger("AppointmentValidationAgent")

const val APPOINTMENT_VALIDATION_PATH = "/a2a/appointment-validation"
const val APPOINTMENT_VALIDATION_CARD_PATH = "$APPOINTMENT_VALIDATION_PATH/agent-card.json"

fun appointmentValidationAgentCard(baseUrl: String): AgentCard = AgentCard(
    protocolVersion = "0.3.0",
    name = "Appointment Validation Agent",
    description = "Validates work type group and appointment type combinations before booking",
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
            description = "Validates if a work type group and appointment type combination is valid",
            examples = listOf(
                "Validate work type group 123 with appointment type Blood Test",
                "Check if work type group and appointment type are compatible",
                "Validate appointment configuration"
            ),
            tags = listOf("appointment", "validation", "work-type", "appointment-type")
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
                Your task is to validate if a work type group and appointment type combination is valid.
                
                Use the validation tool to check if the combination is valid before proceeding with booking.
                Return a clear validation result with isValid flag and a message.
                """.trimIndent()
            }
        },
        model = LLM_MODEL,
        maxAgentIterations = 10
    )

    val toolRegistry = ToolRegistry {
        tools(org.jetbrains.demo.agent.tools.AppointmentValidationTool())
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
        logger.info("${LogColors.VALIDATION} Parsed request: workTypeGroupId=${request.workTypeGroupId}, appointmentType=${request.appointmentType}")
        request
    }

    val validateAppointment by node<AppointmentValidationRequest, AppointmentValidationResult> { request ->
        logger.info("${LogColors.VALIDATION} Calling validation tool directly...")
        logger.info("${LogColors.VALIDATION} WorkTypeGroupId: ${request.workTypeGroupId}, AppointmentType: ${request.appointmentType}")
        
        // Call the validation tool directly
        val validationTool = org.jetbrains.demo.agent.tools.AppointmentValidationTool()
        val toolResponse = try {
            validationTool.validateWorkTypeAndAppointmentType(
                workTypeGroupId = request.workTypeGroupId,
                appointmentType = request.appointmentType
            )
        } catch (e: Exception) {
            logger.error("${LogColors.VALIDATION} Tool execution failed: ${e.message}", e)
            return@node AppointmentValidationResult(
                isValid = false,
                message = "Validation tool execution failed: ${e.message}"
            )
        }
        
        logger.info("${LogColors.VALIDATION} Tool response (first 500 chars): ${toolResponse.take(500)}")
        
        // Parse the tool response - it returns a JSON string
        // The response might be the step report toString(), so we need to extract the actual JSON
        val result = try {
            // First, try to find a JSON object in the response
            val jsonStart = toolResponse.indexOf('{')
            val jsonEnd = toolResponse.lastIndexOf('}') + 1
            
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonPart = toolResponse.substring(jsonStart, jsonEnd)
                logger.debug("${LogColors.VALIDATION} Extracted JSON part: $jsonPart")
                
                // Try to parse as AppointmentValidationResult
                try {
                    json.decodeFromString<AppointmentValidationResult>(jsonPart)
                } catch (e: Exception) {
                    // If it's not the right format, try to extract isValid and message/error
                    logger.warn("${LogColors.VALIDATION} Could not parse as AppointmentValidationResult, trying to extract fields: ${e.message}")
                    
                    val jsonElement = json.parseToJsonElement(jsonPart)
                    val jsonObj = jsonElement as? JsonObject
                        ?: throw IllegalArgumentException("Expected JSON object but got: ${jsonElement::class.simpleName}")
                    val isValid = jsonObj["isValid"]?.jsonPrimitive?.booleanOrNull ?: false
                    val message = jsonObj["message"]?.jsonPrimitive?.content
                        ?: jsonObj["error"]?.jsonPrimitive?.content
                        ?: "Validation completed"
                    
                    AppointmentValidationResult(isValid = isValid, message = message)
                }
            } else {
                // No JSON object found, return error
                logger.error("${LogColors.VALIDATION} No JSON object found in tool response")
                AppointmentValidationResult(
                    isValid = false,
                    message = "Validation tool returned invalid response format"
                )
            }
        } catch (e: Exception) {
            logger.error("${LogColors.VALIDATION} Failed to parse tool response: ${e.message}", e)
            AppointmentValidationResult(
                isValid = false,
                message = "Failed to parse validation response: ${e.message}"
            )
        }
        
        logger.info("${LogColors.VALIDATION} Validation complete: isValid=${result.isValid}, message=${result.message}")
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
