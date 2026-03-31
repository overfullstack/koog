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
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.markdown.markdown
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import org.jetbrains.demo.LLM_MODEL
import org.jetbrains.demo.agent.a2a.model.ServiceTerritoryRequest
import org.jetbrains.demo.agent.a2a.model.ServiceTerritoryResult
import org.jetbrains.demo.agent.tools.TerritoryTools
import org.slf4j.LoggerFactory
import kotlin.reflect.typeOf
import kotlin.uuid.ExperimentalUuidApi

private val logger = LoggerFactory.getLogger("ServiceTerritoryAgent")

const val SERVICE_TERRITORY_PATH = "/a2a/service-territory"
const val SERVICE_TERRITORY_CARD_PATH = "$SERVICE_TERRITORY_PATH/agent-card.json"

fun serviceTerritoryAgentCard(baseUrl: String): AgentCard = AgentCard(
    protocolVersion = "0.3.0",
    name = "Service Territory Agent",
    description = "Converts location addresses to geographic coordinates (latitude/longitude) for service territory mapping",
    version = "1.0.0",
    url = "$baseUrl$SERVICE_TERRITORY_PATH",
    preferredTransport = TransportProtocol.JSONRPC,
    additionalInterfaces = listOf(
        AgentInterface(
            url = "$baseUrl$SERVICE_TERRITORY_PATH",
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
            id = "service_territory",
            name = "Service Territory Geocoding",
            description = "Converts location addresses to latitude/longitude coordinates and identifies service territories",
            examples = listOf(
                "Geocode address: 123 Main St, New York, NY",
                "Find coordinates for Times Square, Manhattan",
                "Get latitude and longitude for San Francisco General Hospital"
            ),
            tags = listOf("geocoding", "location", "coordinates", "territory")
        )
    ),
    supportsAuthenticatedExtendedCard = false
)

class ServiceTerritoryAgentExecutor(
    private val promptExecutor: PromptExecutor,
    private val territoryTools: TerritoryTools
) : AgentExecutor {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        logger.info(LogColors.serviceTerritoryBanner("SERVICE_TERRITORY EXECUTION START"))
        logger.info("${LogColors.SERVICE_TERRITORY} TaskId: ${context.taskId}")
        logger.info("${LogColors.SERVICE_TERRITORY} ContextId: ${context.contextId}")
        val startTime = System.currentTimeMillis()
        try {
            val agent = serviceTerritoryAgent(promptExecutor, territoryTools, context, eventProcessor)
            agent.run(context.params.message)
            val duration = System.currentTimeMillis() - startTime
            logger.info("${LogColors.SERVICE_TERRITORY} Execution completed in ${duration}ms")
        } catch (e: Exception) {
            logger.error("${LogColors.SERVICE_TERRITORY} ${LogColors.red("Execution failed")}: ${e.message}", e)
            throw e
        }
        logger.info(LogColors.serviceTerritoryBanner("SERVICE_TERRITORY EXECUTION END"))
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun serviceTerritoryAgent(
    promptExecutor: PromptExecutor,
    territoryTools: TerritoryTools,
    context: RequestContext<MessageSendParams>,
    eventProcessor: SessionEventProcessor
): GraphAIAgent<A2AMessage, Unit> {
    val agentConfig = AIAgentConfig(
        prompt = prompt("service-territory") {
            system {
                +"""
                You are a service territory specialist that converts location addresses to geographic coordinates.
                Your task is to:
                1. Take a location address or place name as input
                2. Use the geocoding tools to convert it to latitude and longitude coordinates
                3. Optionally identify the nearest service territory
                4. Return accurate geographic coordinates
                
                Use the geocodeLocation tool to get coordinates for the given location.
                Use the findNearestTerritory tool if you need to identify the service territory.
                
                Provide accurate latitude and longitude coordinates for the given location.
                """.trimIndent()
            }
        },
        model = LLM_MODEL,
        maxAgentIterations = 10
    )

    val toolRegistry = ToolRegistry {
        tools(territoryTools)
    }

    return GraphAIAgent(
        inputType = typeOf<A2AMessage>(),
        outputType = typeOf<Unit>(),
        promptExecutor = promptExecutor,
        strategy = serviceTerritoryStrategy(territoryTools, context, eventProcessor),
        agentConfig = agentConfig,
        toolRegistry = toolRegistry,
    ) {
        install(A2AAgentServer) {
            this.context = context
            this.eventProcessor = eventProcessor
        }
        if (A2ATelemetry.isEnabled) {
            install(OpenTelemetry, A2ATelemetry.installLangfuse(
                agentName = "service-territory",
                sessionId = context.contextId
            ))
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun serviceTerritoryStrategy(
    territoryTools: TerritoryTools,
    context: RequestContext<MessageSendParams>,
    eventProcessor: SessionEventProcessor
) = strategy<A2AMessage, Unit>("service-territory-strategy") {
    val json = Json { ignoreUnknownKeys = true }

    val parseInput by node<A2AMessage, ServiceTerritoryRequest> { message ->
        logger.debug("${LogColors.SERVICE_TERRITORY} Parsing input message...")
        val textContent = message.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
        val request = json.decodeFromString<ServiceTerritoryRequest>(textContent)
        logger.info("${LogColors.SERVICE_TERRITORY} Parsed request: ${request.location}")
        request
    }

    val lookupTerritory by node<ServiceTerritoryRequest, ServiceTerritoryResult> { request ->
        logger.info("${LogColors.SERVICE_TERRITORY} Looking up territory coordinates...")
        logger.debug("${LogColors.SERVICE_TERRITORY} Location: ${request.location}")
        
        // Directly call the geocoding tool
        val geocoded = territoryTools.geocodeLocation(request.location)
        
        val result = ServiceTerritoryResult(
            location = request.location,
            latitude = geocoded.latitude,
            longitude = geocoded.longitude
        )
        
        logger.info("${LogColors.SERVICE_TERRITORY} Territory lookup complete: lat=${result.latitude}, lon=${result.longitude}")
        result
    }

    val createTask by node<ServiceTerritoryRequest, ServiceTerritoryRequest> { input ->
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

    val sendResult by node<ServiceTerritoryResult, Unit> { result ->
        logger.info("${LogColors.SERVICE_TERRITORY} Sending result artifact")
        withA2AAgentServer {
            val artifactUpdate = TaskArtifactUpdateEvent(
                taskId = context.taskId,
                contextId = context.contextId,
                artifact = Artifact(
                    artifactId = "service-territory",
                    parts = listOf(TextPart(json.encodeToString(ServiceTerritoryResult.serializer(), result)))
                )
            )
            eventProcessor.sendTaskEvent(artifactUpdate)
        }
    }

    nodeStart then parseInput then createTask then lookupTerritory then sendResult then nodeFinish
}

