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
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.markdown.markdown
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import org.jetbrains.demo.agent.a2a.model.AppointmentBookingRequest
import org.jetbrains.demo.agent.a2a.model.AppointmentBookingResult
import org.salesforce.LogColors
import org.salesforce.travel.LLM_MODEL
import org.salesforce.A2ATelemetry
import org.slf4j.LoggerFactory
import kotlin.reflect.typeOf
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val logger = LoggerFactory.getLogger("AppointmentBookingAgent")

const val APPOINTMENT_BOOKING_PATH = "/a2a/appointment-booking"
const val APPOINTMENT_BOOKING_CARD_PATH = "$APPOINTMENT_BOOKING_PATH/agent-card.json"

fun appointmentBookingAgentCard(baseUrl: String): AgentCard = AgentCard(
    protocolVersion = "0.3.0",
    name = "Appointment Booking Agent",
    description = "Sends appointment booking confirmation messages with all necessary details",
    version = "1.0.0",
    url = "$baseUrl$APPOINTMENT_BOOKING_PATH",
    preferredTransport = TransportProtocol.JSONRPC,
    additionalInterfaces = listOf(
        AgentInterface(
            url = "$baseUrl$APPOINTMENT_BOOKING_PATH",
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
            id = "appointment_booking",
            name = "Appointment Booking Confirmation",
            description = "Generates and sends appointment booking confirmation messages with all details",
            examples = listOf(
                "Send booking confirmation for blood test appointment",
                "Confirm appointment booking with all details",
                "Generate appointment confirmation message"
            ),
            tags = listOf("appointment", "booking", "confirmation", "medical")
        )
    ),
    supportsAuthenticatedExtendedCard = false
)

class AppointmentBookingAgentExecutor(
    private val promptExecutor: PromptExecutor
) : AgentExecutor {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        logger.info(LogColors.appointmentBookingBanner("APPOINTMENT_BOOKING EXECUTION START"))
        logger.info("${LogColors.APPOINTMENT_BOOKING} TaskId: ${context.taskId}")
        logger.info("${LogColors.APPOINTMENT_BOOKING} ContextId: ${context.contextId}")
        val startTime = System.currentTimeMillis()
        try {
            val agent = appointmentBookingAgent(promptExecutor, context, eventProcessor)
            agent.run(context.params.message)
            val duration = System.currentTimeMillis() - startTime
            logger.info("${LogColors.APPOINTMENT_BOOKING} Execution completed in ${duration}ms")
        } catch (e: Exception) {
            logger.error("${LogColors.APPOINTMENT_BOOKING} ${LogColors.red("Execution failed")}: ${e.message}", e)
            throw e
        }
        logger.info(LogColors.appointmentBookingBanner("APPOINTMENT_BOOKING EXECUTION END"))
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun appointmentBookingAgent(
    promptExecutor: PromptExecutor,
    context: RequestContext<MessageSendParams>,
    eventProcessor: SessionEventProcessor
): GraphAIAgent<A2AMessage, Unit> {
    val agentConfig = AIAgentConfig(
        prompt = prompt("appointment-booking") {
            system {
                +"""
                You are an appointment booking confirmation specialist.
                Your task is to generate a clear, professional confirmation message that includes:
                
                1. A friendly greeting
                2. Confirmation that the appointment has been booked
                3. All appointment details:
                   - Appointment type (e.g., Blood Test, Diagnostic Scan)
                   - Location/address
                   - Date and time
                   - Patient name (if provided)
                   - Any special notes or requirements
                4. Location review information (if provided) - ratings, tips, helpful info
                5. Reminders or preparation instructions if needed
                6. Contact information or next steps
                
                Format the message in a clear, easy-to-read way. Use markdown for formatting.
                Be professional but friendly and helpful.
                """.trimIndent()
            }
        },
        model = LLM_MODEL,
        maxAgentIterations = 100
    )

    val toolRegistry = ToolRegistry {
        // No tools needed - just generates confirmation message
    }

    return GraphAIAgent(
        inputType = typeOf<A2AMessage>(),
        outputType = typeOf<Unit>(),
        promptExecutor = promptExecutor,
        strategy = appointmentBookingStrategy(),
        agentConfig = agentConfig,
        toolRegistry = toolRegistry,
    ) {
        install(A2AAgentServer) {
            this.context = context
            this.eventProcessor = eventProcessor
        }
        if (A2ATelemetry.isEnabled) {
            install(OpenTelemetry, A2ATelemetry.installLangfuse(
                agentName = "appointment-booking",
                sessionId = context.contextId
            ))
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun appointmentBookingStrategy() = strategy<A2AMessage, Unit>("appointment-booking-strategy") {
    val json = Json { ignoreUnknownKeys = true }

    val parseInput by node<A2AMessage, AppointmentBookingRequest> { message ->
        logger.debug("${LogColors.APPOINTMENT_BOOKING} Parsing input message...")
        val textContent = message.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
        val request = json.decodeFromString<AppointmentBookingRequest>(textContent)
        logger.info("${LogColors.APPOINTMENT_BOOKING} Parsed request: ${request.appointmentForm.appointmentGroup} at ${request.appointmentForm.location}")
        request
    }

    val generateConfirmation by node<AppointmentBookingRequest, AppointmentBookingResult> { request ->
        logger.info("${LogColors.APPOINTMENT_BOOKING} ${LogColors.LLM} Generating confirmation message...")
        val appointment = request.appointmentForm
        val locationReviewInfo = request.locationReviewInfo
        val workTypeGroupId = request.workTypeGroupId
        
        // Use LLM to generate a professional confirmation message
        val result = llm.writeSession {
            appendPrompt {
                user {
                    markdown {
                        header(1, "Generate Appointment Confirmation")
                        bulleted {
                            item("Appointment Type: ${appointment.appointmentGroup}")
                            workTypeGroupId?.let { item("Work Type Group ID: $it") }
                            item("Location: ${appointment.location}")
                            item("Date & Time: ${appointment.appointmentTime}")
                            appointment.patientName?.let { item("Patient: $it") }
                            appointment.notes?.let { item("Notes: $it") }
                        }
                        if (locationReviewInfo != null) {
                            header(2, "Location Information")
                            bulleted {
                                item("Review Summary: ${locationReviewInfo.reviewSummary}")
                                locationReviewInfo.rating?.let { item("Rating: ${it}/5") }
                                locationReviewInfo.tips?.let { item("Tips: $it") }
                            }
                        }
                        header(2, "Task")
                        bulleted {
                            item("Generate a professional, friendly confirmation message")
                            item("Include all appointment details")
                            item("Include the Work Type Group ID if provided")
                            item("Include location review information if available")
                            item("Add appointment ID: APT-${Uuid.random().toString().take(8).uppercase()}")
                            item("Include reminders about arriving early and cancellation policy")
                        }
                    }
                }
            }
            requestLLMStructured<AppointmentBookingResult>().getOrThrow().data
        }
        
        logger.info("${LogColors.APPOINTMENT_BOOKING} Confirmation generated")
        result
    }

    val createTask by node<AppointmentBookingRequest, AppointmentBookingRequest> { input ->
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

    val sendResult by node<AppointmentBookingResult, Unit> { result ->
        logger.info("${LogColors.APPOINTMENT_BOOKING} Sending result artifact")
        withA2AAgentServer {
            val artifactUpdate = TaskArtifactUpdateEvent(
                taskId = context.taskId,
                contextId = context.contextId,
                artifact = Artifact(
                    artifactId = "appointment-booking",
                    parts = listOf(TextPart(json.encodeToString(AppointmentBookingResult.serializer(), result)))
                )
            )
            eventProcessor.sendTaskEvent(artifactUpdate)
        }
    }

    nodeStart then parseInput then createTask then generateConfirmation then sendResult then nodeFinish
}

