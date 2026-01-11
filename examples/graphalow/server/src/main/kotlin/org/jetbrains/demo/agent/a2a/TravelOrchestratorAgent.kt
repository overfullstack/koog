package org.jetbrains.demo.agent.a2a

import ai.koog.a2a.client.A2AClient
import ai.koog.a2a.client.UrlAgentCardResolver
import ai.koog.a2a.model.*
import ai.koog.a2a.transport.Request
import ai.koog.a2a.transport.client.jsonrpc.http.HttpJSONRPCClientTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.demo.JourneyForm
import org.jetbrains.demo.agent.a2a.model.PointOfInterest
import org.jetbrains.demo.agent.a2a.model.ItineraryIdeasResult
import org.jetbrains.demo.agent.a2a.model.POIResearchRequest
import org.jetbrains.demo.agent.a2a.model.POIResearchResult
import org.jetbrains.demo.agent.a2a.model.TravelPlanRequest
import org.jetbrains.demo.agent.a2a.model.TravelPlanResult
import org.slf4j.LoggerFactory
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val logger = LoggerFactory.getLogger("TravelOrchestratorAgent")

/**
 * Progress events emitted during travel planning to keep user in the loop.
 */
@Serializable
sealed class PlanningProgress {
    /** Planning has started */
    @Serializable
    data class Started(val fromCity: String, val toCity: String) : PlanningProgress()
    
    /** Route planner is finding points of interest */
    @Serializable
    data object RoutePlannerStarted : PlanningProgress()
    
    /** Route planner found POIs - share them with user for feedback */
    @Serializable
    data class RoutePlannerComplete(
        val pointsOfInterest: List<String>,
        val durationMs: Long
    ) : PlanningProgress()
    
    /** Researching a specific POI */
    @Serializable
    data class ResearchingPOI(val poiName: String, val index: Int, val total: Int) : PlanningProgress()
    
    /** POI research complete for one item */
    @Serializable
    data class POIResearchComplete(
        val poiName: String, 
        val highlights: List<String>
    ) : PlanningProgress()
    
    /** All POI research complete */
    @Serializable
    data class AllResearchComplete(val totalPOIs: Int, val durationMs: Long) : PlanningProgress()
    
    /** Composing the final plan */
    @Serializable
    data object ComposingPlan : PlanningProgress()
    
    /** Final plan is ready */
    @Serializable
    data class PlanComplete(val plan: TravelPlanResult, val totalDurationMs: Long) : PlanningProgress()
    
    /** Error occurred */
    @Serializable
    data class Error(val message: String) : PlanningProgress()
}

/**
 * Interactive planning events that pause for user decisions.
 * Emulates a real travel agent asking questions and waiting for responses.
 */
@Serializable
sealed class InteractivePlanningEvent {
    /** Checkpoint ID for correlating user responses */
    abstract val checkpointId: String
    
    /** Ask user to select which POIs they want to include */
    @Serializable
    data class SelectPOIs(
        override val checkpointId: String,
        val availablePOIs: List<POIOption>,
        val maxSelections: Int = 5
    ) : InteractivePlanningEvent()
    
    /** Ask user about trip pacing preference */
    @Serializable
    data class ChoosePace(
        override val checkpointId: String,
        val options: List<PaceOption> = listOf(
            PaceOption("relaxed", "Relaxed", "2-3 activities per day, plenty of free time"),
            PaceOption("moderate", "Moderate", "4-5 activities per day, balanced schedule"),
            PaceOption("packed", "Packed", "6+ activities per day, see everything!")
        )
    ) : InteractivePlanningEvent()
    
    /** Share a discovery and ask if user wants to include it */
    @Serializable
    data class ConfirmDiscovery(
        override val checkpointId: String,
        val poiName: String,
        val discovery: String,
        val recommendation: String
    ) : InteractivePlanningEvent()
    
    /** Ask user to choose between options (e.g., restaurant styles) */
    @Serializable
    data class ChoosePreference(
        override val checkpointId: String,
        val question: String,
        val options: List<PreferenceOption>
    ) : InteractivePlanningEvent()
    
    /** Informational update (no response needed) */
    @Serializable
    data class StatusUpdate(
        override val checkpointId: String = "",
        val message: String,
        val stage: PlanningStage
    ) : InteractivePlanningEvent()
    
    /** Planning complete */
    @Serializable
    data class Complete(
        override val checkpointId: String = "",
        val plan: TravelPlanResult,
        val summary: String
    ) : InteractivePlanningEvent()
    
    /** Error during planning */
    @Serializable
    data class Failed(
        override val checkpointId: String = "",
        val error: String
    ) : InteractivePlanningEvent()
}

@Serializable
data class POIOption(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val estimatedTime: String = "2-3 hours"
)

@Serializable
data class PaceOption(
    val id: String,
    val name: String,
    val description: String
)

@Serializable
data class PreferenceOption(
    val id: String,
    val label: String,
    val description: String
)

@Serializable
enum class PlanningStage {
    STARTING,
    FINDING_PLACES,
    AWAITING_POI_SELECTION,
    RESEARCHING,
    AWAITING_PREFERENCES,
    COMPOSING,
    COMPLETE
}

/**
 * User's response to an interactive checkpoint.
 */
@Serializable
data class UserPlanningResponse(
    val checkpointId: String,
    val selectedPOIs: List<String>? = null,
    val selectedPace: String? = null,
    val confirmed: Boolean? = null,
    val selectedOption: String? = null,
    val freeformInput: String? = null
)

/**
 * State for an interactive planning session that can be paused/resumed.
 */
@Serializable
data class InteractivePlanningState(
    val journeyForm: JourneyForm,
    val stage: PlanningStage = PlanningStage.STARTING,
    val discoveredPOIs: List<PointOfInterest> = emptyList(),
    val selectedPOIIds: List<String> = emptyList(),
    val researchedPOIs: List<POIResearchResult> = emptyList(),
    val selectedPace: String = "moderate",
    val preferences: Map<String, String> = emptyMap(),
    val pendingCheckpointId: String? = null
)

data class A2AAgentEndpoints(
    val routePlannerUrl: String,
    val poiResearcherUrl: String,
    val planComposerUrl: String
)

class TravelOrchestratorAgent(
    private val endpoints: A2AAgentEndpoints
) {
    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = true
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun planTravel(journeyForm: JourneyForm): TravelPlanResult = coroutineScope {
        logger.info(LogColors.orchestratorBanner("A2A ORCHESTRATION START"))
        logger.info("${LogColors.ORCHESTRATOR} Journey: ${journeyForm.fromCity} -> ${journeyForm.toCity}")
        logger.info("${LogColors.ORCHESTRATOR} Dates: ${journeyForm.startDate} to ${journeyForm.endDate}")
        logger.info("${LogColors.ORCHESTRATOR} Travelers: ${journeyForm.travelers.joinToString { it.name }}")
        logger.info("${LogColors.ORCHESTRATOR} Transport: ${journeyForm.transport}")

        // Step 1: Call Route Planner Agent to get itinerary ideas
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.green("[STEP 1/3]")} Calling Route Planner Agent at ${endpoints.routePlannerUrl}")
        val routePlannerStart = System.currentTimeMillis()
        val itineraryIdeas = callRoutePlannerAgent(journeyForm)
        val routePlannerDuration = System.currentTimeMillis() - routePlannerStart
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.green("[STEP 1/3]")} Route Planner completed in ${routePlannerDuration}ms")
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.green("[STEP 1/3]")} Returned ${itineraryIdeas.pointsOfInterest.size} POIs:")
        itineraryIdeas.pointsOfInterest.forEachIndexed { idx, poi ->
            logger.info("${LogColors.ORCHESTRATOR}   POI ${idx + 1}: ${poi.name} @ ${poi.location}")
        }

        // Step 2: Call POI Researcher Agent in parallel for each point of interest
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.yellow("[STEP 2/3]")} Calling POI Researcher Agent for ${itineraryIdeas.pointsOfInterest.size} POIs in parallel")
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.yellow("[STEP 2/3]")} POI Researcher endpoint: ${endpoints.poiResearcherUrl}")
        val poiResearcherStart = System.currentTimeMillis()
        val researchResults = itineraryIdeas.pointsOfInterest.map { poi ->
            async {
                logger.debug("${LogColors.ORCHESTRATOR} ${LogColors.yellow("[STEP 2/3]")} Starting research for: ${poi.name}")
                val result = callPOIResearcherAgent(
                    POIResearchRequest(
                        pointOfInterest = poi,
                        travelers = journeyForm.travelers.joinToString { it.name },
                        startDate = journeyForm.startDate.toString(),
                        endDate = journeyForm.endDate.toString()
                    )
                )
                logger.debug("${LogColors.ORCHESTRATOR} ${LogColors.yellow("[STEP 2/3]")} Completed research for: ${poi.name}")
                result
            }
        }.awaitAll()
        val poiResearcherDuration = System.currentTimeMillis() - poiResearcherStart
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.yellow("[STEP 2/3]")} POI Researcher completed in ${poiResearcherDuration}ms (parallel)")
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.yellow("[STEP 2/3]")} Received ${researchResults.size} research results")

        // Step 3: Call Plan Composer Agent to create the final travel plan
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.blue("[STEP 3/3]")} Calling Plan Composer Agent at ${endpoints.planComposerUrl}")
        val planComposerStart = System.currentTimeMillis()
        val travelPlanRequest = TravelPlanRequest(
            journeyDetails = buildJourneyDetails(journeyForm),
            researchedPoints = researchResults
        )
        val travelPlan = callPlanComposerAgent(travelPlanRequest)
        val planComposerDuration = System.currentTimeMillis() - planComposerStart
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.blue("[STEP 3/3]")} Plan Composer completed in ${planComposerDuration}ms")
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.blue("[STEP 3/3]")} Plan title: ${travelPlan.title}")
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.blue("[STEP 3/3]")} Plan has ${travelPlan.days.size} days")

        val totalDuration = routePlannerDuration + poiResearcherDuration + planComposerDuration
        logger.info(LogColors.orchestratorBanner("A2A ORCHESTRATION COMPLETE"))
        logger.info("${LogColors.ORCHESTRATOR} Total orchestration time: ${totalDuration}ms")
        logger.info("${LogColors.ORCHESTRATOR}   ${LogColors.green("Route Planner")}: ${routePlannerDuration}ms")
        logger.info("${LogColors.ORCHESTRATOR}   ${LogColors.yellow("POI Researcher")}: ${poiResearcherDuration}ms (parallel)")
        logger.info("${LogColors.ORCHESTRATOR}   ${LogColors.blue("Plan Composer")}: ${planComposerDuration}ms")

        travelPlan
    }
    
    /**
     * Stream-based travel planning that emits progress updates to keep user informed.
     * This allows the UI to show real-time progress instead of waiting for the final result.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun planTravelWithProgress(journeyForm: JourneyForm): Flow<PlanningProgress> = flow {
        val overallStart = System.currentTimeMillis()
        
        emit(PlanningProgress.Started(journeyForm.fromCity, journeyForm.toCity))
        logger.info(LogColors.orchestratorBanner("A2A ORCHESTRATION START (Streaming)"))
        
        try {
            // Step 1: Route Planner
            emit(PlanningProgress.RoutePlannerStarted)
            val routePlannerStart = System.currentTimeMillis()
            val itineraryIdeas = callRoutePlannerAgent(journeyForm)
            val routePlannerDuration = System.currentTimeMillis() - routePlannerStart
            
            val poiNames = itineraryIdeas.pointsOfInterest.map { it.name }
            emit(PlanningProgress.RoutePlannerComplete(poiNames, routePlannerDuration))
            logger.info("${LogColors.ORCHESTRATOR} Found ${poiNames.size} POIs: ${poiNames.joinToString()}")
            
            // Step 2: POI Research - emit progress for each POI
            val poiResearcherStart = System.currentTimeMillis()
            val totalPOIs = itineraryIdeas.pointsOfInterest.size
            
            val researchResults = mutableListOf<POIResearchResult>()
            itineraryIdeas.pointsOfInterest.forEachIndexed { index, poi ->
                emit(PlanningProgress.ResearchingPOI(poi.name, index + 1, totalPOIs))
                
                val result = callPOIResearcherAgent(
                    POIResearchRequest(
                        pointOfInterest = poi,
                        travelers = journeyForm.travelers.joinToString { it.name },
                        startDate = journeyForm.startDate.toString(),
                        endDate = journeyForm.endDate.toString()
                    )
                )
                researchResults.add(result)
                
                // Extract a brief highlight from the research text
                val highlights = result.research.split(". ")
                    .take(2)
                    .map { it.trim() }
                    .filter { it.length in 10..100 }
                emit(PlanningProgress.POIResearchComplete(poi.name, highlights))
            }
            
            val poiResearcherDuration = System.currentTimeMillis() - poiResearcherStart
            emit(PlanningProgress.AllResearchComplete(totalPOIs, poiResearcherDuration))
            
            // Step 3: Compose final plan
            emit(PlanningProgress.ComposingPlan)
            val planComposerStart = System.currentTimeMillis()
            val travelPlanRequest = TravelPlanRequest(
                journeyDetails = buildJourneyDetails(journeyForm),
                researchedPoints = researchResults
            )
            val travelPlan = callPlanComposerAgent(travelPlanRequest)
            val planComposerDuration = System.currentTimeMillis() - planComposerStart
            logger.info("${LogColors.ORCHESTRATOR} ${LogColors.blue("[STEP 3/3]")} Plan Composer completed in ${planComposerDuration}ms")
            
            val totalDuration = System.currentTimeMillis() - overallStart
            emit(PlanningProgress.PlanComplete(travelPlan, totalDuration))
            
            logger.info(LogColors.orchestratorBanner("A2A ORCHESTRATION COMPLETE (Streaming)"))
            logger.info("${LogColors.ORCHESTRATOR} Total time: ${totalDuration}ms (Route: ${routePlannerDuration}ms, POI: ${poiResearcherDuration}ms, Compose: ${planComposerDuration}ms)")
            
        } catch (e: Exception) {
            logger.error("${LogColors.ORCHESTRATOR} Error during planning: ${e.message}", e)
            emit(PlanningProgress.Error(e.message ?: "Unknown error during planning"))
        }
    }
    
    /**
     * Interactive planning that pauses at checkpoints for user decisions.
     * This emulates a real travel agent conversation where the user guides choices.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun startInteractivePlanning(journeyForm: JourneyForm): Flow<InteractivePlanningEvent> = flow {
        val sessionId = Uuid.random().toString()
        logger.info(LogColors.orchestratorBanner("INTERACTIVE PLANNING START"))
        
        emit(InteractivePlanningEvent.StatusUpdate(
            message = "Let me find some amazing places for your trip to ${journeyForm.toCity}...",
            stage = PlanningStage.FINDING_PLACES
        ))
        
        try {
            // Step 1: Find POIs
            val itineraryIdeas = callRoutePlannerAgent(journeyForm)
            val poiOptions = itineraryIdeas.pointsOfInterest.mapIndexed { idx, poi ->
                POIOption(
                    id = "poi_$idx",
                    name = poi.name,
                    description = poi.description,
                    category = "attraction",
                    estimatedTime = "2-3 hours"
                )
            }
            
            // Checkpoint 1: Let user select POIs
            emit(InteractivePlanningEvent.SelectPOIs(
                checkpointId = "${sessionId}_select_pois",
                availablePOIs = poiOptions,
                maxSelections = minOf(5, poiOptions.size)
            ))
            
        } catch (e: Exception) {
            logger.error("${LogColors.ORCHESTRATOR} Interactive planning error: ${e.message}", e)
            emit(InteractivePlanningEvent.Failed(error = e.message ?: "Planning failed"))
        }
    }
    
    /**
     * Continue interactive planning after user responds to a checkpoint.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun continueInteractivePlanning(
        state: InteractivePlanningState,
        response: UserPlanningResponse
    ): Flow<InteractivePlanningEvent> = flow {
        logger.info("${LogColors.ORCHESTRATOR} Continuing planning from stage: ${state.stage}")
        
        try {
            when (state.stage) {
                PlanningStage.AWAITING_POI_SELECTION -> {
                    val selectedPOIs = response.selectedPOIs ?: emptyList()
                    val poisToResearch = state.discoveredPOIs.filterIndexed { idx, _ -> 
                        "poi_$idx" in selectedPOIs 
                    }
                    
                    emit(InteractivePlanningEvent.StatusUpdate(
                        message = "Great choices! I'll research ${poisToResearch.size} places for you...",
                        stage = PlanningStage.RESEARCHING
                    ))
                    
                    // Research selected POIs
                    val researchResults = mutableListOf<POIResearchResult>()
                    poisToResearch.forEachIndexed { idx, poi ->
                        emit(InteractivePlanningEvent.StatusUpdate(
                            message = "🔍 Researching **${poi.name}** (${idx + 1}/${poisToResearch.size})...",
                            stage = PlanningStage.RESEARCHING
                        ))
                        
                        val result = callPOIResearcherAgent(
                            POIResearchRequest(
                                pointOfInterest = poi,
                                travelers = state.journeyForm.travelers.joinToString { it.name },
                                startDate = state.journeyForm.startDate.toString(),
                                endDate = state.journeyForm.endDate.toString()
                            )
                        )
                        researchResults.add(result)
                        
                        // Share interesting finding
                        val highlight = result.research.split(". ").firstOrNull { it.length in 20..150 }
                        if (highlight != null) {
                            emit(InteractivePlanningEvent.ConfirmDiscovery(
                                checkpointId = "${response.checkpointId}_discovery_$idx",
                                poiName = poi.name,
                                discovery = highlight,
                                recommendation = "This looks like a great fit for your trip!"
                            ))
                        }
                    }
                    
                    // Checkpoint 2: Ask about pace preference
                    emit(InteractivePlanningEvent.ChoosePace(
                        checkpointId = "${response.checkpointId}_pace"
                    ))
                }
                
                PlanningStage.AWAITING_PREFERENCES -> {
                    val pace = response.selectedPace ?: "moderate"
                    
                    emit(InteractivePlanningEvent.StatusUpdate(
                        message = "Perfect! Creating a **$pace** itinerary just for you...",
                        stage = PlanningStage.COMPOSING
                    ))
                    
                    // Add pace context to journey details
                    val paceDescription = when (pace) {
                        "relaxed" -> "Keep the schedule relaxed with 2-3 activities per day and plenty of downtime."
                        "packed" -> "Pack in as many activities as possible - they want to see everything!"
                        else -> "Balance activities with some free time for spontaneous exploration."
                    }
                    
                    val enhancedDetails = buildJourneyDetails(state.journeyForm) + "\n\nPace preference: $paceDescription"
                    
                    val travelPlanRequest = TravelPlanRequest(
                        journeyDetails = enhancedDetails,
                        researchedPoints = state.researchedPOIs
                    )
                    val travelPlan = callPlanComposerAgent(travelPlanRequest)
                    
                    val summary = buildString {
                        appendLine("I've created your personalized $pace itinerary!")
                        appendLine("• ${travelPlan.days.size} days of adventure")
                        appendLine("• ${state.researchedPOIs.size} hand-picked locations")
                        appendLine("• Tailored to your preferences")
                    }
                    
                    emit(InteractivePlanningEvent.Complete(
                        plan = travelPlan,
                        summary = summary
                    ))
                }
                
                else -> {
                    emit(InteractivePlanningEvent.Failed(error = "Unexpected planning stage: ${state.stage}"))
                }
            }
        } catch (e: Exception) {
            logger.error("${LogColors.ORCHESTRATOR} Error continuing planning: ${e.message}", e)
            emit(InteractivePlanningEvent.Failed(error = e.message ?: "Planning failed"))
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun callRoutePlannerAgent(journeyForm: JourneyForm): ItineraryIdeasResult {
        val transport = HttpJSONRPCClientTransport(url = endpoints.routePlannerUrl)
        val agentCardResolver = UrlAgentCardResolver(
            baseUrl = endpoints.routePlannerUrl.substringBefore(ROUTE_PLANNER_PATH),
            path = ROUTE_PLANNER_CARD_PATH
        )
        val client = A2AClient(transport = transport, agentCardResolver = agentCardResolver)

        try {
            client.connect()
            val contextId = Uuid.random().toString()

            val message = Message(
                messageId = Uuid.random().toString(),
                role = Role.User,
                parts = listOf(TextPart(json.encodeToString(JourneyForm.serializer(), journeyForm))),
                contextId = contextId,
                taskId = null
            )

            val responses = client.sendMessageStreaming(Request(MessageSendParams(message = message))).toList()
            return extractArtifact(responses, "itinerary-ideas")
        } finally {
            transport.close()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun callPOIResearcherAgent(request: POIResearchRequest): POIResearchResult {
        val transport = HttpJSONRPCClientTransport(url = endpoints.poiResearcherUrl)
        val agentCardResolver = UrlAgentCardResolver(
            baseUrl = endpoints.poiResearcherUrl.substringBefore(POI_RESEARCHER_PATH),
            path = POI_RESEARCHER_CARD_PATH
        )
        val client = A2AClient(transport = transport, agentCardResolver = agentCardResolver)

        try {
            client.connect()
            val contextId = Uuid.random().toString()

            val message = Message(
                messageId = Uuid.random().toString(),
                role = Role.User,
                parts = listOf(TextPart(json.encodeToString(POIResearchRequest.serializer(), request))),
                contextId = contextId,
                taskId = null
            )

            val responses = client.sendMessageStreaming(Request(MessageSendParams(message = message))).toList()
            return extractArtifact(responses, "poi-research")
        } finally {
            transport.close()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun callPlanComposerAgent(request: TravelPlanRequest): TravelPlanResult {
        val transport = HttpJSONRPCClientTransport(url = endpoints.planComposerUrl)
        val agentCardResolver = UrlAgentCardResolver(
            baseUrl = endpoints.planComposerUrl.substringBefore(PLAN_COMPOSER_PATH),
            path = PLAN_COMPOSER_CARD_PATH
        )
        val client = A2AClient(transport = transport, agentCardResolver = agentCardResolver)

        try {
            client.connect()
            val contextId = Uuid.random().toString()

            val message = Message(
                messageId = Uuid.random().toString(),
                role = Role.User,
                parts = listOf(TextPart(json.encodeToString(TravelPlanRequest.serializer(), request))),
                contextId = contextId,
                taskId = null
            )

            val responses = client.sendMessageStreaming(Request(MessageSendParams(message = message))).toList()
            return extractArtifact(responses, "travel-plan")
        } finally {
            transport.close()
        }
    }

    private inline fun <reified T> extractArtifact(
        responses: List<ai.koog.a2a.transport.Response<Event>>,
        artifactId: String
    ): T {
        val artifacts = mutableMapOf<String, Artifact>()

        responses.forEach { response ->
            when (val event = response.data) {
                is Task -> event.artifacts?.forEach { artifacts[it.artifactId] = it }
                is TaskArtifactUpdateEvent -> {
                    if (event.append == true) {
                        val existing = artifacts[event.artifact.artifactId]
                        if (existing != null) {
                            artifacts[event.artifact.artifactId] = existing.copy(
                                parts = existing.parts + event.artifact.parts
                            )
                        } else {
                            artifacts[event.artifact.artifactId] = event.artifact
                        }
                    } else {
                        artifacts[event.artifact.artifactId] = event.artifact
                    }
                }
                else -> {}
            }
        }

        val artifact = artifacts[artifactId]
            ?: throw IllegalStateException("Artifact '$artifactId' not found in response")

        val textContent = artifact.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
        return json.decodeFromString<T>(textContent)
    }

    private fun buildJourneyDetails(journeyForm: JourneyForm): String = """
        Travelers: ${journeyForm.travelers.joinToString { "${it.name}${it.about?.let { a -> " ($a)" } ?: ""}" }}
        From: ${journeyForm.fromCity}
        To: ${journeyForm.toCity}
        Transport: ${journeyForm.transport}
        Departure: ${journeyForm.startDate}
        Return: ${journeyForm.endDate}
        ${journeyForm.details?.let { "Additional details: $it" } ?: ""}
    """.trimIndent()
}
