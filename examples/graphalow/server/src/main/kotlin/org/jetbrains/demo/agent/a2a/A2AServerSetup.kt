package org.jetbrains.demo.agent.a2a

import ai.koog.a2a.server.A2AServer
import ai.koog.a2a.transport.server.jsonrpc.http.HttpJSONRPCServerTransport
import ai.koog.prompt.executor.model.PromptExecutor
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.demo.agent.LogColors
import org.jetbrains.demo.agent.a2a.agents.A2AAgentEndpoints
import org.jetbrains.demo.agent.a2a.agents.PLAN_COMPOSER_CARD_PATH
import org.jetbrains.demo.agent.a2a.agents.PLAN_COMPOSER_PATH
import org.jetbrains.demo.agent.a2a.agents.POIResearcherAgentExecutor
import org.jetbrains.demo.agent.a2a.agents.POI_RESEARCHER_CARD_PATH
import org.jetbrains.demo.agent.a2a.agents.POI_RESEARCHER_PATH
import org.jetbrains.demo.agent.a2a.agents.PlanComposerAgentExecutor
import org.jetbrains.demo.agent.a2a.agents.ROUTE_PLANNER_CARD_PATH
import org.jetbrains.demo.agent.a2a.agents.ROUTE_PLANNER_PATH
import org.jetbrains.demo.agent.a2a.agents.RoutePlannerAgentExecutor
import org.jetbrains.demo.agent.a2a.agents.TravelAgentsOrchestrator
import org.jetbrains.demo.agent.a2a.agents.planComposerAgentCard
import org.jetbrains.demo.agent.a2a.agents.poiResearcherAgentCard
import org.jetbrains.demo.agent.a2a.agents.routePlannerAgentCard
import org.jetbrains.demo.tools.Tools
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("A2AServerSetup")

data class A2AConfig(
    val baseUrl: String,
    val routePlannerPort: Int = 9101,
    val poiResearcherPort: Int = 9102,
    val planComposerPort: Int = 9103
)

class A2AMeshServer(
    private val config: A2AConfig,
    private val promptExecutor: PromptExecutor,
    private val tools: Tools
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        logger.info(LogColors.serverBanner("A2A MESH SERVER STARTING"))
        logger.info("${LogColors.SERVER} Base URL: ${config.baseUrl}")
        logger.info("${LogColors.SERVER} Configured ports:")
        logger.info("${LogColors.SERVER}   ${LogColors.green("Route Planner")}: ${config.routePlannerPort}")
        logger.info("${LogColors.SERVER}   ${LogColors.yellow("POI Researcher")}: ${config.poiResearcherPort}")
        logger.info("${LogColors.SERVER}   ${LogColors.blue("Plan Composer")}: ${config.planComposerPort}")

        scope.launch { startRoutePlannerServer() }
        scope.launch { startPOIResearcherServer() }
        scope.launch { startPlanComposerServer() }

        logger.info(LogColors.serverBanner("A2A MESH SERVER STARTED"))
        logger.info("${LogColors.SERVER} Endpoints available:")
        logger.info("${LogColors.SERVER}   ${LogColors.green("Route Planner")}: ${config.baseUrl}:${config.routePlannerPort}${ROUTE_PLANNER_PATH}")
        logger.info("${LogColors.SERVER}   ${LogColors.yellow("POI Researcher")}: ${config.baseUrl}:${config.poiResearcherPort}${POI_RESEARCHER_PATH}")
        logger.info("${LogColors.SERVER}   ${LogColors.blue("Plan Composer")}: ${config.baseUrl}:${config.planComposerPort}${PLAN_COMPOSER_PATH}")
        logger.info("${LogColors.SERVER} Agent cards available at:")
        logger.info("${LogColors.SERVER}   ${LogColors.green("Route Planner")}: ${config.baseUrl}:${config.routePlannerPort}${ROUTE_PLANNER_CARD_PATH}")
        logger.info("${LogColors.SERVER}   ${LogColors.yellow("POI Researcher")}: ${config.baseUrl}:${config.poiResearcherPort}${POI_RESEARCHER_CARD_PATH}")
        logger.info("${LogColors.SERVER}   ${LogColors.blue("Plan Composer")}: ${config.baseUrl}:${config.planComposerPort}${PLAN_COMPOSER_CARD_PATH}")
    }

    private suspend fun startRoutePlannerServer() {
        logger.info("${LogColors.ROUTE_PLANNER} Server initializing...")
        val agentCard = routePlannerAgentCard("${config.baseUrl}:${config.routePlannerPort}")
        val agentExecutor = RoutePlannerAgentExecutor(promptExecutor, tools)
        val a2aServer = A2AServer(
            agentExecutor = agentExecutor,
            agentCard = agentCard,
        )

        val serverTransport = HttpJSONRPCServerTransport(a2aServer)
        logger.info("${LogColors.ROUTE_PLANNER} Server starting on port ${config.routePlannerPort}")

        serverTransport.start(
            engineFactory = CIO,
            port = config.routePlannerPort,
            path = ROUTE_PLANNER_PATH,
            wait = false,
            agentCard = agentCard,
            agentCardPath = ROUTE_PLANNER_CARD_PATH
        )
        logger.info("${LogColors.ROUTE_PLANNER} Server started successfully")
    }

    private suspend fun startPOIResearcherServer() {
        logger.info("${LogColors.POI_RESEARCHER} Server initializing...")
        val agentCard = poiResearcherAgentCard("${config.baseUrl}:${config.poiResearcherPort}")
        val agentExecutor = POIResearcherAgentExecutor(promptExecutor, tools)
        val a2aServer = A2AServer(
            agentExecutor = agentExecutor,
            agentCard = agentCard,
        )

        val serverTransport = HttpJSONRPCServerTransport(a2aServer)
        logger.info("${LogColors.POI_RESEARCHER} Server starting on port ${config.poiResearcherPort}")

        serverTransport.start(
            engineFactory = CIO,
            port = config.poiResearcherPort,
            path = POI_RESEARCHER_PATH,
            wait = false,
            agentCard = agentCard,
            agentCardPath = POI_RESEARCHER_CARD_PATH
        )
        logger.info("${LogColors.POI_RESEARCHER} Server started successfully")
    }

    private suspend fun startPlanComposerServer() {
        logger.info("${LogColors.PLAN_COMPOSER} Server initializing...")
        val agentCard = planComposerAgentCard("${config.baseUrl}:${config.planComposerPort}")
        val agentExecutor = PlanComposerAgentExecutor(promptExecutor, tools)
        val a2aServer = A2AServer(
            agentExecutor = agentExecutor,
            agentCard = agentCard,
        )

        val serverTransport = HttpJSONRPCServerTransport(a2aServer)
        logger.info("${LogColors.PLAN_COMPOSER} Server starting on port ${config.planComposerPort}")

        serverTransport.start(
            engineFactory = CIO,
            port = config.planComposerPort,
            path = PLAN_COMPOSER_PATH,
            wait = false,
            agentCard = agentCard,
            agentCardPath = PLAN_COMPOSER_CARD_PATH
        )
        logger.info("${LogColors.PLAN_COMPOSER} Server started successfully")
    }

    fun getEndpoints(): A2AAgentEndpoints = A2AAgentEndpoints(
        routePlannerUrl = "${config.baseUrl}:${config.routePlannerPort}${ROUTE_PLANNER_PATH}",
        poiResearcherUrl = "${config.baseUrl}:${config.poiResearcherPort}${POI_RESEARCHER_PATH}",
        planComposerUrl = "${config.baseUrl}:${config.planComposerPort}${PLAN_COMPOSER_PATH}"
    )
}

fun Application.a2aMeshRoutes(orchestrator: TravelAgentsOrchestrator) {
    routing {
        // Health check endpoint for the A2A mesh
        get("/a2a/health") {
            call.respondText("""{"status":"healthy"}""", io.ktor.http.ContentType.Application.Json)
        }
    }
}
