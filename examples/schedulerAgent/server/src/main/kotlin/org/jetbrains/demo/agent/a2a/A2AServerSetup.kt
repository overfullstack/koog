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
import org.jetbrains.demo.agent.tools.Tools
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("A2AServerSetup")

data class A2AConfig(
    val baseUrl: String,
    val locationWeatherPort: Int = 9101,
    val appointmentBookingPort: Int = 9102
)

class A2AMeshServer(
    private val config: A2AConfig,
    private val promptExecutor: PromptExecutor,
    private val tools: Tools
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        logger.info(LogColors.serverBanner("A2A SCHEDULER SERVER STARTING"))
        logger.info("${LogColors.SERVER} Base URL: ${config.baseUrl}")
        logger.info("${LogColors.SERVER} Configured ports:")
        logger.info("${LogColors.SERVER}   ${LogColors.cyan("Location Weather")}: ${config.locationWeatherPort}")
        logger.info("${LogColors.SERVER}   ${LogColors.magenta("Appointment Booking")}: ${config.appointmentBookingPort}")

        scope.launch { startLocationWeatherServer() }
        scope.launch { startAppointmentBookingServer() }

        logger.info(LogColors.serverBanner("A2A SCHEDULER SERVER STARTED"))
        logger.info("${LogColors.SERVER} Endpoints available:")
        logger.info("${LogColors.SERVER}   ${LogColors.cyan("Location Weather")}: ${config.baseUrl}:${config.locationWeatherPort}$LOCATION_WEATHER_PATH")
        logger.info("${LogColors.SERVER}   ${LogColors.magenta("Appointment Booking")}: ${config.baseUrl}:${config.appointmentBookingPort}$APPOINTMENT_BOOKING_PATH")
        logger.info("${LogColors.SERVER} Agent cards available at:")
        logger.info("${LogColors.SERVER}   ${LogColors.cyan("Location Weather")}: ${config.baseUrl}:${config.locationWeatherPort}$LOCATION_WEATHER_CARD_PATH")
        logger.info("${LogColors.SERVER}   ${LogColors.magenta("Appointment Booking")}: ${config.baseUrl}:${config.appointmentBookingPort}$APPOINTMENT_BOOKING_CARD_PATH")
    }

    private suspend fun startLocationWeatherServer() {
        logger.info("${LogColors.LOCATION_WEATHER} Server initializing...")
        val agentCard = locationWeatherAgentCard("${config.baseUrl}:${config.locationWeatherPort}")
        val agentExecutor = LocationWeatherAgentExecutor(promptExecutor, tools)
        val a2aServer = A2AServer(
            agentExecutor = agentExecutor,
            agentCard = agentCard,
        )

        val serverTransport = HttpJSONRPCServerTransport(a2aServer)
        logger.info("${LogColors.LOCATION_WEATHER} Server starting on port ${config.locationWeatherPort}")

        serverTransport.start(
            engineFactory = CIO,
            port = config.locationWeatherPort,
            path = LOCATION_WEATHER_PATH,
            wait = false,
            agentCard = agentCard,
            agentCardPath = LOCATION_WEATHER_CARD_PATH
        )
        logger.info("${LogColors.LOCATION_WEATHER} Server started successfully")
    }

    private suspend fun startAppointmentBookingServer() {
        logger.info("${LogColors.APPOINTMENT_BOOKING} Server initializing...")
        val agentCard = appointmentBookingAgentCard("${config.baseUrl}:${config.appointmentBookingPort}")
        val agentExecutor = AppointmentBookingAgentExecutor(promptExecutor)
        val a2aServer = A2AServer(
            agentExecutor = agentExecutor,
            agentCard = agentCard,
        )

        val serverTransport = HttpJSONRPCServerTransport(a2aServer)
        logger.info("${LogColors.APPOINTMENT_BOOKING} Server starting on port ${config.appointmentBookingPort}")

        serverTransport.start(
            engineFactory = CIO,
            port = config.appointmentBookingPort,
            path = APPOINTMENT_BOOKING_PATH,
            wait = false,
            agentCard = agentCard,
            agentCardPath = APPOINTMENT_BOOKING_CARD_PATH
        )
        logger.info("${LogColors.APPOINTMENT_BOOKING} Server started successfully")
    }

    fun getEndpoints(): A2ASchedulerEndpoints = A2ASchedulerEndpoints(
        locationWeatherUrl = "${config.baseUrl}:${config.locationWeatherPort}$LOCATION_WEATHER_PATH",
        appointmentBookingUrl = "${config.baseUrl}:${config.appointmentBookingPort}$APPOINTMENT_BOOKING_PATH"
    )
}

fun Application.a2aSchedulerRoutes(orchestrator: SchedulerOrchestratorAgent) {
    routing {
        // Health check endpoint for the A2A scheduler
        get("/a2a/health") {
            call.respondText("""{"status":"healthy"}""", io.ktor.http.ContentType.Application.Json)
        }
    }
}
