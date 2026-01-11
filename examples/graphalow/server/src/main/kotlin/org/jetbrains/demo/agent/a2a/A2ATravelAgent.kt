package org.jetbrains.demo.agent.a2a

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.demo.*
import org.jetbrains.demo.agent.dto.ProposedTravelPlan
import org.jetbrains.demo.agent.dto.TravelPlanResult
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("A2ATravelAgent")

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class A2ATravelPlanResponse(
    val success: Boolean,
    val plan: TravelPlanResult? = null,
    val error: String? = null
)

fun Application.a2aTravelAgentRoutes(orchestrator: TravelOrchestratorAgent) {
    routing {
        route("/a2a") {
            post("/plan") {
                try {
                    val journeyForm = call.receive<JourneyForm>()
                    logger.info("Received travel plan request via A2A mesh: ${journeyForm.fromCity} to ${journeyForm.toCity}")

                    val travelPlan = orchestrator.planTravel(journeyForm)

                    call.respond(
                        HttpStatusCode.OK,
                        A2ATravelPlanResponse(success = true, plan = travelPlan)
                    )
                } catch (e: Exception) {
                    logger.error("Error processing A2A travel plan request", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        A2ATravelPlanResponse(success = false, error = e.message)
                    )
                }
            }

            sse("/plan/stream") {
                try {
                    val journeyFormJson = call.request.queryParameters["journeyForm"]
                        ?: throw IllegalArgumentException("Missing journeyForm parameter")

                    val journeyForm = json.decodeFromString<JourneyForm>(journeyFormJson)
                    logger.info("Received streaming travel plan request via A2A mesh: ${journeyForm.fromCity} to ${journeyForm.toCity}")

                    send(ServerSentEvent(data = json.encodeToString(AgentEvent.AgentStarted.serializer(), 
                        AgentEvent.AgentStarted(agentId = "a2a-orchestrator", runId = "a2a-run"))))

                    send(ServerSentEvent(data = json.encodeToString(AgentEvent.Message.serializer(),
                        AgentEvent.Message(listOf("Starting A2A mesh orchestration...")))))

                    send(ServerSentEvent(data = json.encodeToString(AgentEvent.Message.serializer(),
                        AgentEvent.Message(listOf("Calling Route Planner Agent...")))))

                    val travelPlan = orchestrator.planTravel(journeyForm)

                    val proposedPlan = ProposedTravelPlan(
                        title = travelPlan.title,
                        plan = travelPlan.plan,
                        days = travelPlan.days,
                        imageLinks = travelPlan.imageLinks,
                        pageLinks = travelPlan.pageLinks,
                        countriesVisited = travelPlan.countriesVisited
                    )

                    send(ServerSentEvent(data = json.encodeToString(AgentEvent.AgentFinished.serializer(),
                        AgentEvent.AgentFinished(
                            agentId = "a2a-orchestrator",
                            runId = "a2a-run",
                            plan = proposedPlan
                        ))))

                } catch (e: Exception) {
                    logger.error("Error in A2A streaming travel plan", e)
                    send(ServerSentEvent(data = json.encodeToString(AgentEvent.AgentError.serializer(),
                        AgentEvent.AgentError(
                            agentId = "a2a-orchestrator",
                            runId = "a2a-run",
                            result = e.message
                        ))))
                }
            }

            get("/health") {
                call.respond(HttpStatusCode.OK, mapOf("status" to "healthy", "mode" to "a2a-mesh"))
            }
        }
    }
}
