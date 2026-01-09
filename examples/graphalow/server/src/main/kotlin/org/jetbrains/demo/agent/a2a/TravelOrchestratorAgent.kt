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
