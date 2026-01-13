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
import org.jetbrains.demo.agent.a2a.model.LocationReviewRequest
import org.jetbrains.demo.agent.a2a.model.LocationReviewResult
import org.salesforce.A2ATelemetry
import org.salesforce.LogColors
import org.salesforce.travel.LLM_MODEL
import org.slf4j.LoggerFactory
import kotlin.reflect.typeOf
import kotlin.uuid.ExperimentalUuidApi

private val logger = LoggerFactory.getLogger("TavilyAgent")

const val TAVILY_PATH = "/a2a/tavily"
const val TAVILY_CARD_PATH = "$TAVILY_PATH/agent-card.json"

fun tavilyAgentCard(baseUrl: String): AgentCard = AgentCard(
    protocolVersion = "0.3.0",
    name = "Tavily Agent",
    description = "Web search agent using Tavily to find reviews and information about appointment locations",
    version = "1.0.0",
    url = "$baseUrl$TAVILY_PATH",
    preferredTransport = TransportProtocol.JSONRPC,
    additionalInterfaces = listOf(
        AgentInterface(
            url = "$baseUrl$TAVILY_PATH",
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
            id = "location_reviews",
            name = "Location Reviews",
            description = "Searches for reviews, ratings, and helpful information about appointment locations",
            examples = listOf(
                "Find reviews for UCSF Medical Center",
                "What do people say about Kaiser Permanente San Francisco?",
                "Get patient reviews for SF General Hospital emergency room"
            ),
            tags = listOf("reviews", "ratings", "location", "hospital", "clinic", "patient experience")
        )
    ),
    supportsAuthenticatedExtendedCard = false
)

class TavilyAgentExecutor(
    private val promptExecutor: PromptExecutor,
    private val tools: ToolRegistry
) : AgentExecutor {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        logger.info(LogColors.tavilyBanner("TAVILY AGENT EXECUTION START"))
        logger.info("${LogColors.TAVILY} TaskId: ${context.taskId}")
        logger.info("${LogColors.TAVILY} ContextId: ${context.contextId}")
        val startTime = System.currentTimeMillis()
        try {
            val agent = tavilyAgent(promptExecutor, tools, context, eventProcessor)
            agent.run(context.params.message)
            val duration = System.currentTimeMillis() - startTime
            logger.info("${LogColors.TAVILY} Execution completed in ${duration}ms")
        } catch (e: Exception) {
            logger.error("${LogColors.TAVILY} ${LogColors.red("Execution failed")}: ${e.message}", e)
            throw e
        }
        logger.info(LogColors.tavilyBanner("TAVILY AGENT EXECUTION END"))
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun tavilyAgent(
    promptExecutor: PromptExecutor,
    tools: ToolRegistry,
    context: RequestContext<MessageSendParams>,
    eventProcessor: SessionEventProcessor
): GraphAIAgent<A2AMessage, Unit> {
    val agentConfig = AIAgentConfig(
        prompt = prompt("location-review") {
            system {
                +"""You are a location review specialist that helps patients learn about appointment locations.
                
Your task is to:
1. Use tavily tools to search for reviews and information about the specified location
2. Find patient reviews, ratings, and experiences at the location
3. Look for helpful information like parking, accessibility, wait times, staff quality
4. Provide a clear summary with highlights and any concerns

Available MCP tools:
- tavily: For web search to find reviews and information

Search for:
- Patient reviews and ratings
- Google reviews, Yelp reviews, Healthgrades reviews
- Parking information
- Accessibility features
- Wait time experiences
- Staff and service quality

IMPORTANT: Your final summary must be 250 words or less. Be concise and focus on the most important information.
Provide clear, helpful summaries that help patients prepare for their visit."""
            }
        },
        model = LLM_MODEL,
        maxAgentIterations = 10
    )

    // Log available MCP tools for verification
    logger.info("${LogColors.TAVILY} Available MCP tools: ${tools.tools.map { it.descriptor.name }}")

    return GraphAIAgent(
        inputType = typeOf<A2AMessage>(),
        outputType = typeOf<Unit>(),
        promptExecutor = promptExecutor,
        strategy = tavilyStrategy(),
        agentConfig = agentConfig,
        toolRegistry = tools,
    ) {
        install(A2AAgentServer) {
            this.context = context
            this.eventProcessor = eventProcessor
        }
        if (A2ATelemetry.isEnabled) {
            install(OpenTelemetry, A2ATelemetry.installLangfuse(
                agentName = "location-review",
                sessionId = context.contextId
            ))
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun tavilyStrategy() = strategy<A2AMessage, Unit>("location-review-strategy") {
    val json = Json { ignoreUnknownKeys = true }

    val parseInput by node<A2AMessage, LocationReviewRequest> { message ->
        logger.debug("${LogColors.TAVILY} Parsing input message...")
        val textContent = message.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
        val request = json.decodeFromString<LocationReviewRequest>(textContent)
        logger.info("${LogColors.TAVILY} Parsed request: ${request.location}")
        request
    }

    val performSearch by node<LocationReviewRequest, LocationReviewResult> { request ->
        logger.info("${LogColors.TAVILY} ${LogColors.LLM} Searching for location reviews...")
        logger.debug("${LogColors.TAVILY} Location: ${request.location}")
        
        // Detect if this is a parking-only request
        val isParkingRequest = request.appointmentType?.contains("PARKING ONLY", ignoreCase = true) == true ||
                               request.location.contains("parking", ignoreCase = true)
        
        // Add user prompt based on request type
        llm.writeSession {
            appendPrompt {
                user {
                    if (isParkingRequest) {
                        +"""Search for PARKING information only at: ${request.location}

Use tavily to search for parking info at this hospital/location.

Respond with a SHORT summary (2-3 sentences MAX):
🅿️ [Is parking available? Free or paid? Any tips?]

Example: "Paid parking available at ₹50/hour. Valet service offered. Arrive 15 mins early for parking."

BE VERY BRIEF - only parking info, nothing else!"""
                    } else {
                        +"""Search for reviews and information about: ${request.location}
${if (request.appointmentType != null) "Focus: ${request.appointmentType}" else ""}

Use tavily to search for:
1. Patient reviews and ratings for this location
2. Google reviews, Yelp reviews, or Healthgrades reviews
3. Information about parking, accessibility, and facilities
4. Wait time experiences and staff quality

After searching, provide a CONCISE summary (250 words maximum) including:
- Overall rating if found
- Key positive highlights from reviews
- Any concerns or warnings mentioned in reviews  
- Helpful tips for visiting this location

IMPORTANT: Keep your final summary to 250 words or less. Be concise and prioritize the most useful information.

Note: If any tool fails, continue with available information."""
                    }
                }
            }
        }
        
        // Execute tool loop (limited to 3 iterations max, then generate summary)
        var iterations = 0
        val maxIterations = 3
        var finalResponse: String = ""
        
        while (iterations < maxIterations) {
            iterations++
            logger.info("${LogColors.TAVILY} Tool loop iteration $iterations")
            
            val response = llm.writeSession { requestLLM() }
            logger.info("${LogColors.TAVILY} LLM response type: ${response::class.simpleName}")
            
            when (response) {
                is Message.Tool.Call -> {
                    logger.info("${LogColors.TAVILY} 🔧 Executing tool: ${response.tool}")
                    try {
                        val toolResult = environment.executeTool(response)
                        logger.info("${LogColors.TAVILY} ✅ Tool result: ${toolResult.content.take(100)}...")
                        
                        llm.writeSession {
                            appendPrompt {
                                tool {
                                    result(toolResult.id, toolResult.tool, toolResult.content)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("${LogColors.TAVILY} ❌ Tool ${response.tool} failed: ${e.message}")
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
                    logger.info("${LogColors.TAVILY} Got final response: ${finalResponse.take(100)}...")
                    break
                }
                else -> {
                    logger.warn("${LogColors.TAVILY} Unexpected response type: ${response::class.simpleName}")
                    finalResponse = response.content
                    break
                }
            }
        }
        
        // If no final response yet (all iterations were tool calls), force a summary
        if (finalResponse.isBlank()) {
            logger.info("${LogColors.TAVILY} Tool iterations complete, requesting final summary...")
            llm.writeSession {
                appendPrompt {
                    user {
                        +"""Based on the search results above, please provide a CONCISE summary (250 words max) of the reviews and information found. Include:
- Overall rating if found
- Key positive highlights
- Any concerns or warnings
- Helpful tips for visiting

If no useful information was found, say so briefly."""
                    }
                }
            }
            val summaryResponse = llm.writeSession { requestLLM() }
            finalResponse = if (summaryResponse is Message.Assistant) {
                summaryResponse.content
            } else {
                "Unable to generate summary for this location."
            }
            logger.info("${LogColors.TAVILY} Generated summary: ${finalResponse.take(100)}...")
        }
        
        // Limit summary length - shorter for parking requests
        val maxLength = if (isParkingRequest) 300 else 1000
        
        LocationReviewResult(
            location = request.location,
            reviewSummary = finalResponse.take(maxLength),
            rating = null,
            highlights = emptyList(),
            concerns = emptyList(),
            tips = null
        )
    }

    val createTask by node<LocationReviewRequest, LocationReviewRequest> { input ->
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

    val sendResult by node<LocationReviewResult, Unit> { result ->
        logger.info("${LogColors.TAVILY} Sending result artifact")
        withA2AAgentServer {
            val artifactUpdate = TaskArtifactUpdateEvent(
                taskId = context.taskId,
                contextId = context.contextId,
                artifact = Artifact(
                    artifactId = "location-review",
                    parts = listOf(TextPart(json.encodeToString(LocationReviewResult.serializer(), result)))
                )
            )
            eventProcessor.sendTaskEvent(artifactUpdate)
        }
    }

    nodeStart then parseInput then createTask then performSearch then sendResult then nodeFinish
}
