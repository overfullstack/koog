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
import ai.koog.a2a.model.TextPart
import ai.koog.a2a.model.TaskArtifactUpdateEvent
import ai.koog.a2a.model.Artifact
import ai.koog.a2a.model.Task
import ai.koog.a2a.model.TaskStatus
import ai.koog.a2a.model.TaskState
import ai.koog.agents.a2a.server.feature.withA2AAgentServer
import org.jetbrains.demo.LLM_MODEL
import org.jetbrains.demo.agent.a2a.model.LocationWeatherRequest
import org.jetbrains.demo.agent.a2a.model.LocationWeatherResult
import org.jetbrains.demo.agent.tools.Tools
import org.slf4j.LoggerFactory
import kotlin.reflect.typeOf
import kotlin.uuid.ExperimentalUuidApi

private val logger = LoggerFactory.getLogger("LocationWeatherAgent")

const val LOCATION_WEATHER_PATH = "/a2a/location-weather"
const val LOCATION_WEATHER_CARD_PATH = "$LOCATION_WEATHER_PATH/agent-card.json"

fun locationWeatherAgentCard(baseUrl: String): AgentCard = AgentCard(
    protocolVersion = "0.3.0",
    name = "Location Weather Agent",
    description = "Checks location information and weather forecast for appointment times",
    version = "1.0.0",
    url = "$baseUrl$LOCATION_WEATHER_PATH",
    preferredTransport = TransportProtocol.JSONRPC,
    additionalInterfaces = listOf(
        AgentInterface(
            url = "$baseUrl$LOCATION_WEATHER_PATH",
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
            id = "location_weather",
            name = "Location and Weather Check",
            description = "Checks location details and provides weather forecast for a specific time",
            examples = listOf(
                "Check weather for appointment at 123 Main St, New York on March 15 at 2 PM",
                "Get location and weather info for hospital appointment",
                "What's the weather forecast for my appointment location?"
            ),
            tags = listOf("weather", "location", "appointment", "forecast")
        )
    ),
    supportsAuthenticatedExtendedCard = false
)

class LocationWeatherAgentExecutor(
    private val promptExecutor: PromptExecutor,
    private val tools: Tools
) : AgentExecutor {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        logger.info(LogColors.locationWeatherBanner("LOCATION_WEATHER EXECUTION START"))
        logger.info("${LogColors.LOCATION_WEATHER} TaskId: ${context.taskId}")
        logger.info("${LogColors.LOCATION_WEATHER} ContextId: ${context.contextId}")
        val startTime = System.currentTimeMillis()
        try {
            val agent = locationWeatherAgent(promptExecutor, tools, context, eventProcessor)
            agent.run(context.params.message)
            val duration = System.currentTimeMillis() - startTime
            logger.info("${LogColors.LOCATION_WEATHER} Execution completed in ${duration}ms")
        } catch (e: Exception) {
            logger.error("${LogColors.LOCATION_WEATHER} ${LogColors.red("Execution failed")}: ${e.message}", e)
            throw e
        }
        logger.info(LogColors.locationWeatherBanner("LOCATION_WEATHER EXECUTION END"))
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun locationWeatherAgent(
    promptExecutor: PromptExecutor,
    tools: Tools,
    context: RequestContext<MessageSendParams>,
    eventProcessor: SessionEventProcessor
): GraphAIAgent<A2AMessage, Unit> {
    val agentConfig = AIAgentConfig(
        prompt = prompt("location-weather") {
            system {
                +"""
                You are a location and weather information specialist for medical appointments.
                Your task is to:
                1. Verify and provide information about the appointment location
                2. Get weather forecast for the specific appointment date and time
                3. Provide helpful advice based on weather conditions (e.g., dress appropriately, travel considerations)
                
                Use the weather tool to get accurate forecasts for the appointment time.
                Use search tools if you need to verify location details or get coordinates.
                
                Provide clear, concise information that helps patients prepare for their appointment.
                """.trimIndent()
            }
        },
        model = LLM_MODEL,
        maxAgentIterations = 10
    )

    val toolRegistry = ToolRegistry {
        tools(tools.weatherTool)
        tools(tools.googleMaps.tools)
        tools(tools.searchTool)
    }

    return GraphAIAgent(
        inputType = typeOf<A2AMessage>(),
        outputType = typeOf<Unit>(),
        promptExecutor = promptExecutor,
        strategy = locationWeatherStrategy(),
        agentConfig = agentConfig,
        toolRegistry = toolRegistry,
    ) {
        install(A2AAgentServer) {
            this.context = context
            this.eventProcessor = eventProcessor
        }
        if (A2ATelemetry.isEnabled) {
            install(OpenTelemetry, A2ATelemetry.installLangfuse(
                agentName = "location-weather",
                sessionId = context.contextId
            ))
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun locationWeatherStrategy() = strategy<A2AMessage, Unit>("location-weather-strategy") {
    val json = Json { ignoreUnknownKeys = true }

    val parseInput by node<A2AMessage, LocationWeatherRequest> { message ->
        logger.debug("${LogColors.LOCATION_WEATHER} Parsing input message...")
        val textContent = message.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
        val request = json.decodeFromString<LocationWeatherRequest>(textContent)
        logger.info("${LogColors.LOCATION_WEATHER} Parsed request: ${request.location} at ${request.appointmentTime}")
        request
    }

    val checkLocationAndWeather by node<LocationWeatherRequest, LocationWeatherResult> { request ->
        logger.info("${LogColors.LOCATION_WEATHER} ${LogColors.LLM} Checking location and weather...")
        logger.debug("${LogColors.LOCATION_WEATHER} Location: ${request.location}, Time: ${request.appointmentTime}")
        
        // Use LLM with tools to get location and weather info
        val result = llm.writeSession {
            appendPrompt {
                user {
                    markdown {
                        header(1, "Location and Weather Check")
                        bulleted {
                            item("Location: ${request.location}")
                            item("Appointment Time: ${request.appointmentTime}")
                        }
                        header(2, "Task")
                        bulleted {
                            item("Get weather forecast for the appointment location and time")
                            item("Provide helpful advice based on weather conditions")
                            item("Use weather tools to get accurate forecast")
                        }
                    }
                }
            }
            requestLLMStructured<LocationWeatherResult>().getOrThrow().data
        }
        
        logger.info("${LogColors.LOCATION_WEATHER} Weather check complete: ${result.weather}")
        result
    }

    val createTask by node<LocationWeatherRequest, LocationWeatherRequest> { input ->
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

    val sendResult by node<LocationWeatherResult, Unit> { result ->
        logger.info("${LogColors.LOCATION_WEATHER} Sending result artifact")
        withA2AAgentServer {
            val artifactUpdate = TaskArtifactUpdateEvent(
                taskId = context.taskId,
                contextId = context.contextId,
                artifact = Artifact(
                    artifactId = "location-weather",
                    parts = listOf(TextPart(json.encodeToString(LocationWeatherResult.serializer(), result)))
                )
            )
            eventProcessor.sendTaskEvent(artifactUpdate)
        }
    }

    nodeStart then parseInput then createTask then checkLocationAndWeather then sendResult then nodeFinish
}

