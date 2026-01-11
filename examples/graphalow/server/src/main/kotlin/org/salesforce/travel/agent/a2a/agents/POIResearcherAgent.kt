package org.salesforce.travel.agent.a2a.agents

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
import ai.koog.a2a.model.TaskStatusUpdateEvent
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
import org.salesforce.travel.LLM_MODEL
import org.salesforce.travel.agent.a2a.A2ATelemetry
import org.salesforce.travel.agent.LogColors
import org.salesforce.travel.dto.POIResearchRequest
import org.salesforce.travel.dto.POIResearchResult
import org.salesforce.travel.tools.Tools
import org.slf4j.LoggerFactory
import kotlin.reflect.typeOf
import kotlin.uuid.ExperimentalUuidApi

private val logger = LoggerFactory.getLogger("POIResearcherAgent")

const val POI_RESEARCHER_PATH = "/a2a/poi-researcher"
const val POI_RESEARCHER_CARD_PATH = "$POI_RESEARCHER_PATH/agent-card.json"

fun poiResearcherAgentCard(baseUrl: String): AgentCard = AgentCard(
    protocolVersion = "0.3.0",
    name = "POI Researcher Agent",
    description = "Researches detailed information about points of interest including history, culture, and events",
    version = "1.0.0",
    url = "$baseUrl$POI_RESEARCHER_PATH",
    preferredTransport = TransportProtocol.JSONRPC,
    additionalInterfaces = listOf(
        AgentInterface(
            url = "$baseUrl$POI_RESEARCHER_PATH",
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
            id = "poi_research",
            name = "Point of Interest Research",
            description = "Researches detailed information about travel destinations including history, culture, art, and events",
            examples = listOf(
                "Research the Eiffel Tower",
                "Find interesting facts about the Colosseum",
                "What events are happening in Barcelona in July?"
            ),
            tags = listOf("travel", "research", "culture", "history", "events")
        )
    ),
    supportsAuthenticatedExtendedCard = false
)

class POIResearcherAgentExecutor(
    private val promptExecutor: PromptExecutor,
    private val tools: Tools
) : AgentExecutor {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        logger.info(LogColors.poiResearcherBanner("POI_RESEARCHER EXECUTION START"))
        logger.info("${LogColors.POI_RESEARCHER} TaskId: ${context.taskId}")
        logger.info("${LogColors.POI_RESEARCHER} ContextId: ${context.contextId}")
        val startTime = System.currentTimeMillis()
        try {
            val agent = poiResearcherAgent(promptExecutor, tools, context, eventProcessor)
            agent.run(context.params.message)
            val duration = System.currentTimeMillis() - startTime
            logger.info("${LogColors.POI_RESEARCHER} Execution completed in ${duration}ms")
        } catch (e: Exception) {
            logger.error("${LogColors.POI_RESEARCHER} ${LogColors.red("Execution failed")}: ${e.message}", e)
            throw e
        }
        logger.info(LogColors.poiResearcherBanner("POI_RESEARCHER EXECUTION END"))
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun poiResearcherAgent(
    promptExecutor: PromptExecutor,
    tools: Tools,
    context: RequestContext<MessageSendParams>,
    eventProcessor: SessionEventProcessor
): GraphAIAgent<A2AMessage, Unit> {
    val agentConfig = AIAgentConfig(
        prompt = prompt("poi-researcher") {
            system {
                +"""
                You are a travel research expert specializing in cultural and historical information.
                Your task is to research points of interest and provide rich, detailed information.
                
                Focus on:
                - Interesting stories about art, culture, and famous people
                - Historical significance and context
                - Events happening during the travel dates
                - Practical visitor information
                - High-quality images that showcase the location
                
                Use Tavily MCP tools to find accurate, up-to-date information.
                """.trimIndent()
            }
        },
        model = LLM_MODEL,
        maxAgentIterations = 15
    )

    val toolRegistry = ToolRegistry {
        tools(tools.mcpTools.tools)
    }

    return GraphAIAgent(
        inputType = typeOf<A2AMessage>(),
        outputType = typeOf<Unit>(),
        promptExecutor = promptExecutor,
        strategy = poiResearcherStrategy(),
        agentConfig = agentConfig,
        toolRegistry = toolRegistry,
    ) {
        install(A2AAgentServer) {
            this.context = context
            this.eventProcessor = eventProcessor
        }
        if (A2ATelemetry.isEnabled) {
            install(OpenTelemetry, A2ATelemetry.installLangfuse(
                agentName = "poi-researcher",
                sessionId = context.contextId
            ))
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun poiResearcherStrategy() = strategy<A2AMessage, Unit>("poi-researcher-strategy") {
    val json = Json { ignoreUnknownKeys = true }

    val parseInput by node<A2AMessage, POIResearchRequest> { message ->
        logger.debug("${LogColors.POI_RESEARCHER} Parsing input message...")
        val textContent = message.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
        val request = json.decodeFromString<POIResearchRequest>(textContent)
        logger.info("${LogColors.POI_RESEARCHER} Parsed request for POI: ${request.pointOfInterest.name}")
        request
    }

    val createTask by node<POIResearchRequest, POIResearchRequest> { input ->
        logger.debug("${LogColors.POI_RESEARCHER} Creating task for POI: ${input.pointOfInterest.name}")
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

    val setupContextAndResearch by node<POIResearchRequest, POIResearchResult> { request ->
        logger.info("${LogColors.POI_RESEARCHER} ${LogColors.LLM} Researching POI: ${request.pointOfInterest.name}")
        logger.debug("${LogColors.POI_RESEARCHER} ${LogColors.LLM} Location: ${request.pointOfInterest.location}")
        val llmStart = System.currentTimeMillis()
        val result = llm.writeSession {
            appendPrompt {
                user {
                    markdown {
                        +"Research the following point of interest."
                        +"Consider interesting stories about art and culture and famous people."
                        +"Details from the traveler: ${request.travelers}."
                        +"Dates to consider: departure from ${request.startDate} to ${request.endDate}."
                        +"If any particularly important events are happening here during this time, mention them and list specific dates."
                        header(1, "Point of interest to research")
                        bulleted {
                            item("Name: ${request.pointOfInterest.name}")
                            item("Location: ${request.pointOfInterest.location}")
                            item("From ${request.pointOfInterest.fromDate} to ${request.pointOfInterest.toDate}")
                            item("Description: ${request.pointOfInterest.description}")
                        }
                    }
                }
            }
            requestLLMStructured<POIResearchResult>().getOrThrow().data
        }
        val llmDuration = System.currentTimeMillis() - llmStart
        logger.info("${LogColors.POI_RESEARCHER} ${LogColors.LLM} Research for ${request.pointOfInterest.name} completed in ${llmDuration}ms")
        logger.debug("${LogColors.POI_RESEARCHER} ${LogColors.LLM} Found ${result.links.size} links, ${result.imageLinks.size} images")
        result
    }

    val sendResult by node<POIResearchResult, Unit> { researchResult ->
        logger.info("${LogColors.POI_RESEARCHER} Sending result artifact for POI: ${researchResult.pointOfInterest.name}")
        withA2AAgentServer {
            val artifactUpdate = TaskArtifactUpdateEvent(
                taskId = context.taskId,
                contextId = context.contextId,
                artifact = Artifact(
                    artifactId = "poi-research",
                    parts = listOf(
                        TextPart(json.encodeToString(POIResearchResult.serializer(), researchResult))
                    )
                ),
            )
            eventProcessor.sendTaskEvent(artifactUpdate)
            logger.debug("${LogColors.POI_RESEARCHER} Artifact sent")

            val taskStatusUpdate = TaskStatusUpdateEvent(
                taskId = context.taskId,
                contextId = context.contextId,
                status = TaskStatus(
                    state = TaskState.Completed,
                    timestamp = Clock.System.now(),
                ),
                final = true,
            )
            eventProcessor.sendTaskEvent(taskStatusUpdate)
            logger.info("${LogColors.POI_RESEARCHER} Task completed for POI: ${researchResult.pointOfInterest.name}")
        }
    }

    nodeStart then parseInput then createTask then setupContextAndResearch then sendResult then nodeFinish
}
