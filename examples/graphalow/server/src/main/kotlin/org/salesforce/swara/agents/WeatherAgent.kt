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
import org.jetbrains.demo.agent.a2a.model.WeatherForecastRequest
import org.jetbrains.demo.agent.a2a.model.WeatherForecastResult
import org.salesforce.A2ATelemetry
import org.salesforce.LogColors
import org.salesforce.travel.LLM_MODEL
import org.slf4j.LoggerFactory
import kotlin.reflect.typeOf
import kotlin.uuid.ExperimentalUuidApi

private val logger = LoggerFactory.getLogger("WeatherAgent")

const val WEATHER_PATH = "/a2a/weather"
const val WEATHER_CARD_PATH = "$WEATHER_PATH/agent-card.json"

fun weatherAgentCard(baseUrl: String): AgentCard = AgentCard(
    protocolVersion = "0.3.0",
    name = "Weather Forecast Agent",
    description = "Weather forecast agent using OpenWeather for accurate weather predictions",
    version = "1.0.0",
    url = "$baseUrl$WEATHER_PATH",
    preferredTransport = TransportProtocol.JSONRPC,
    additionalInterfaces = listOf(
        AgentInterface(
            url = "$baseUrl$WEATHER_PATH",
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
            id = "weather_forecast",
            name = "Weather Forecast",
            description = "Provides weather forecasts for specific locations and times using OpenWeather",
            examples = listOf(
                "What's the weather forecast for San Francisco tomorrow at 2 PM?",
                "Get weather conditions for New York on March 15 at 10 AM",
                "Weather forecast for my appointment location"
            ),
            tags = listOf("weather", "forecast", "temperature", "conditions")
        )
    ),
    supportsAuthenticatedExtendedCard = false
)

class WeatherAgentExecutor(
    private val promptExecutor: PromptExecutor,
    private val tools: ToolRegistry
) : AgentExecutor {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        logger.info(LogColors.weatherBanner("WEATHER EXECUTION START"))
        logger.info("${LogColors.WEATHER} TaskId: ${context.taskId}")
        logger.info("${LogColors.WEATHER} ContextId: ${context.contextId}")
        val startTime = System.currentTimeMillis()
        try {
            val agent = weatherAgent(promptExecutor, tools, context, eventProcessor)
            agent.run(context.params.message)
            val duration = System.currentTimeMillis() - startTime
            logger.info("${LogColors.WEATHER} Execution completed in ${duration}ms")
        } catch (e: Exception) {
            logger.error("${LogColors.WEATHER} ${LogColors.red("Execution failed")}: ${e.message}", e)
            throw e
        }
        logger.info(LogColors.weatherBanner("WEATHER EXECUTION END"))
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun weatherAgent(
    promptExecutor: PromptExecutor,
    tools: ToolRegistry,
    context: RequestContext<MessageSendParams>,
    eventProcessor: SessionEventProcessor
): GraphAIAgent<A2AMessage, Unit> {
    val agentConfig = AIAgentConfig(
        prompt = prompt("weather-forecast") {
            system {
                +"""You are a weather specialist using OpenWeather.
                
Your task is to:
1. Use openweather tools to get accurate weather forecasts
2. Provide temperature, conditions, humidity, and wind information
3. Give helpful advice based on weather conditions

Available MCP tools:
- openweather: For weather forecasts and current conditions

Use these tools to gather accurate weather information.
Provide clear weather details and helpful preparation advice."""
            }
        },
        model = LLM_MODEL,
        maxAgentIterations = 10
    )

    // Log available MCP tools for verification
    logger.info("${LogColors.WEATHER} Available MCP tools: ${tools.tools.map { it.descriptor.name }}")

    return GraphAIAgent(
        inputType = typeOf<A2AMessage>(),
        outputType = typeOf<Unit>(),
        promptExecutor = promptExecutor,
        strategy = weatherStrategy(),
        agentConfig = agentConfig,
        toolRegistry = tools,
    ) {
        install(A2AAgentServer) {
            this.context = context
            this.eventProcessor = eventProcessor
        }
        if (A2ATelemetry.isEnabled) {
            install(OpenTelemetry, A2ATelemetry.installLangfuse(
                agentName = "weather",
                sessionId = context.contextId
            ))
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun weatherStrategy() = strategy<A2AMessage, Unit>("weather-strategy") {
    val json = Json { ignoreUnknownKeys = true }

    val parseInput by node<A2AMessage, WeatherForecastRequest> { message ->
        logger.debug("${LogColors.WEATHER} Parsing input message...")
        val textContent = message.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
        val request = json.decodeFromString<WeatherForecastRequest>(textContent)
        logger.info("${LogColors.WEATHER} Parsed request: ${request.location} at ${request.dateTime}")
        request
    }

    val getWeatherForecast by node<WeatherForecastRequest, WeatherForecastResult> { request ->
        logger.info("${LogColors.WEATHER} ${LogColors.LLM} Getting weather forecast...")
        logger.debug("${LogColors.WEATHER} Location: ${request.location}, Time: ${request.dateTime}")
        
        // Add user prompt
        llm.writeSession {
            appendPrompt {
                user {
                    +"""Get weather forecast for: ${request.location} at ${request.dateTime}

Use openweather tools to get the weather forecast.
Provide detailed weather information including:
- Temperature (current and feels like)
- Weather conditions (sunny, cloudy, rainy, etc.)
- Humidity percentage
- Wind speed
- Any relevant weather advice

Note: If any tool fails, continue with available information."""
                }
            }
        }
        
        // Execute tool loop
        var iterations = 0
        val maxIterations = 8
        var finalResponse: String = ""
        
        while (iterations < maxIterations) {
            iterations++
            logger.info("${LogColors.WEATHER} Tool loop iteration $iterations")
            
            val response = llm.writeSession { requestLLM() }
            logger.info("${LogColors.WEATHER} LLM response type: ${response::class.simpleName}")
            
            when (response) {
                is Message.Tool.Call -> {
                    logger.info("${LogColors.WEATHER} 🔧 Executing tool: ${response.tool}")
                    try {
                        val toolResult = environment.executeTool(response)
                        logger.info("${LogColors.WEATHER} ✅ Tool result: ${toolResult.content.take(100)}...")
                        
                        llm.writeSession {
                            appendPrompt {
                                tool {
                                    result(toolResult.id, toolResult.tool, toolResult.content)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("${LogColors.WEATHER} ❌ Tool ${response.tool} failed: ${e.message}")
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
                    logger.info("${LogColors.WEATHER} Got final response: ${finalResponse.take(100)}...")
                    break
                }
                else -> {
                    logger.warn("${LogColors.WEATHER} Unexpected response type: ${response::class.simpleName}")
                    finalResponse = response.content
                    break
                }
            }
        }
        
        if (finalResponse.isBlank()) {
            finalResponse = "Unable to get weather forecast after $maxIterations attempts."
        }
        
        WeatherForecastResult(
            location = request.location,
            dateTime = request.dateTime,
            temperature = null,
            feelsLike = null,
            conditions = null,
            humidity = null,
            windSpeed = null,
            summary = finalResponse.take(500)
        )
    }

    val createTask by node<WeatherForecastRequest, WeatherForecastRequest> { input ->
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

    val sendResult by node<WeatherForecastResult, Unit> { result ->
        logger.info("${LogColors.WEATHER} Sending result artifact")
        withA2AAgentServer {
            val artifactUpdate = TaskArtifactUpdateEvent(
                taskId = context.taskId,
                contextId = context.contextId,
                artifact = Artifact(
                    artifactId = "weather-forecast",
                    parts = listOf(TextPart(json.encodeToString(WeatherForecastResult.serializer(), result)))
                )
            )
            eventProcessor.sendTaskEvent(artifactUpdate)
        }
    }

    nodeStart then parseInput then createTask then getWeatherForecast then sendResult then nodeFinish
}

