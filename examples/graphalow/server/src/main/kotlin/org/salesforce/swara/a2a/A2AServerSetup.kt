package org.salesforce.swara.a2a

import ai.koog.a2a.server.A2AServer
import ai.koog.a2a.transport.server.jsonrpc.http.HttpJSONRPCServerTransport
import ai.koog.prompt.executor.model.PromptExecutor
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.demo.agent.a2a.A2ASchedulerEndpoints
import org.jetbrains.demo.agent.a2a.APPOINTMENT_BOOKING_CARD_PATH
import org.jetbrains.demo.agent.a2a.APPOINTMENT_BOOKING_PATH
import org.jetbrains.demo.agent.a2a.APPOINTMENT_VALIDATION_CARD_PATH
import org.jetbrains.demo.agent.a2a.APPOINTMENT_VALIDATION_PATH
import org.jetbrains.demo.agent.a2a.AppointmentBookingAgentExecutor
import org.jetbrains.demo.agent.a2a.AppointmentValidationAgentExecutor
import org.jetbrains.demo.agent.a2a.SERVICE_TERRITORY_VALIDATION_CARD_PATH
import org.jetbrains.demo.agent.a2a.SERVICE_TERRITORY_VALIDATION_PATH
import org.jetbrains.demo.agent.a2a.ServiceTerritoryValidationAgentExecutor
import org.jetbrains.demo.agent.a2a.TIMESLOT_VALIDATION_CARD_PATH
import org.jetbrains.demo.agent.a2a.TIMESLOT_VALIDATION_PATH
import org.jetbrains.demo.agent.a2a.TimeslotValidationAgentExecutor
import org.jetbrains.demo.agent.a2a.appointmentBookingAgentCard
import org.jetbrains.demo.agent.a2a.appointmentValidationAgentCard
import org.jetbrains.demo.agent.a2a.serviceTerritoryValidationAgentCard
import org.jetbrains.demo.agent.a2a.timeslotValidationAgentCard
import org.salesforce.LogColors
import org.salesforce.swara.agents.TAVILY_CARD_PATH
import org.salesforce.swara.agents.TAVILY_PATH
import org.salesforce.swara.agents.TavilyAgentExecutor
import org.salesforce.swara.agents.tavilyAgentCard
import org.salesforce.swara.agents.MAPS_CARD_PATH
import org.salesforce.swara.agents.MAPS_PATH
import org.salesforce.swara.agents.MapsAgentExecutor
import org.salesforce.swara.agents.mapsAgentCard
import org.salesforce.swara.agents.WEATHER_CARD_PATH
import org.salesforce.swara.agents.WEATHER_PATH
import org.salesforce.swara.agents.WeatherAgentExecutor
import org.salesforce.swara.agents.weatherAgentCard
import org.salesforce.tools.Tools
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("A2AServerSetup")

data class A2AConfig(
    val baseUrl: String,
    val tavilyPort: Int = 9101,
    val appointmentValidationPort: Int = 9103,
    val serviceTerritoryValidationPort: Int = 9104,
    val timeslotValidationPort: Int = 9105,
    val appointmentBookingPort: Int = 9102,
    // Additional agent ports
    val mapsPort: Int = 9107,
    val weatherPort: Int = 9108
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
        logger.info("${LogColors.SERVER}   ${LogColors.yellow("Tavily Agent")}: ${config.tavilyPort}")
        logger.info("${LogColors.SERVER}   ${LogColors.yellow("Appointment Validation")}: ${config.appointmentValidationPort}")
        logger.info("${LogColors.SERVER}   ${LogColors.blue("Service Territory Validation")}: ${config.serviceTerritoryValidationPort}")
        logger.info("${LogColors.SERVER}   ${LogColors.green("Timeslot Validation")}: ${config.timeslotValidationPort}")
        logger.info("${LogColors.SERVER}   ${LogColors.magenta("Appointment Booking")}: ${config.appointmentBookingPort}")
        logger.info("${LogColors.SERVER}   ${LogColors.green("Google Maps")}: ${config.mapsPort}")
        logger.info("${LogColors.SERVER}   ${LogColors.blue("Weather Forecast")}: ${config.weatherPort}")

        scope.launch { startTavilyServer() }
        scope.launch { startAppointmentValidationServer() }
        scope.launch { startServiceTerritoryValidationServer() }
        scope.launch { startTimeslotValidationServer() }
        scope.launch { startAppointmentBookingServer() }
        scope.launch { startMapsServer() }
        scope.launch { startWeatherServer() }

        logger.info(LogColors.serverBanner("A2A SCHEDULER SERVER STARTED"))
        logger.info("${LogColors.SERVER} Endpoints available:")
        logger.info("${LogColors.SERVER}   ${LogColors.yellow("Tavily Agent")}: ${config.baseUrl}:${config.tavilyPort}$TAVILY_PATH")
        logger.info("${LogColors.SERVER}   ${LogColors.yellow("Appointment Validation")}: ${config.baseUrl}:${config.appointmentValidationPort}${APPOINTMENT_VALIDATION_PATH}")
        logger.info("${LogColors.SERVER}   ${LogColors.blue("Service Territory Validation")}: ${config.baseUrl}:${config.serviceTerritoryValidationPort}$SERVICE_TERRITORY_VALIDATION_PATH")
        logger.info("${LogColors.SERVER}   ${LogColors.green("Timeslot Validation")}: ${config.baseUrl}:${config.timeslotValidationPort}$TIMESLOT_VALIDATION_PATH")
        logger.info("${LogColors.SERVER}   ${LogColors.magenta("Appointment Booking")}: ${config.baseUrl}:${config.appointmentBookingPort}${APPOINTMENT_BOOKING_PATH}")
        logger.info("${LogColors.SERVER}   ${LogColors.green("Google Maps")}: ${config.baseUrl}:${config.mapsPort}$MAPS_PATH")
        logger.info("${LogColors.SERVER}   ${LogColors.blue("Weather Forecast")}: ${config.baseUrl}:${config.weatherPort}$WEATHER_PATH")
        
        logger.info("${LogColors.SERVER} Agent cards available at:")
        logger.info("${LogColors.SERVER}   ${LogColors.yellow("Tavily Agent")}: ${config.baseUrl}:${config.tavilyPort}$TAVILY_CARD_PATH")
        logger.info("${LogColors.SERVER}   ${LogColors.yellow("Appointment Validation")}: ${config.baseUrl}:${config.appointmentValidationPort}${APPOINTMENT_VALIDATION_CARD_PATH}")
        logger.info("${LogColors.SERVER}   ${LogColors.blue("Service Territory Validation")}: ${config.baseUrl}:${config.serviceTerritoryValidationPort}$SERVICE_TERRITORY_VALIDATION_CARD_PATH")
        logger.info("${LogColors.SERVER}   ${LogColors.green("Timeslot Validation")}: ${config.baseUrl}:${config.timeslotValidationPort}$TIMESLOT_VALIDATION_CARD_PATH")
        logger.info("${LogColors.SERVER}   ${LogColors.magenta("Appointment Booking")}: ${config.baseUrl}:${config.appointmentBookingPort}${APPOINTMENT_BOOKING_CARD_PATH}")
        logger.info("${LogColors.SERVER}   ${LogColors.green("Google Maps")}: ${config.baseUrl}:${config.mapsPort}$MAPS_CARD_PATH")
        logger.info("${LogColors.SERVER}   ${LogColors.blue("Weather Forecast")}: ${config.baseUrl}:${config.weatherPort}$WEATHER_CARD_PATH")
    }

    private suspend fun startTavilyServer() {
        logger.info("${LogColors.TAVILY} Server initializing...")
        val agentCard = tavilyAgentCard("${config.baseUrl}:${config.tavilyPort}")
        val agentExecutor = TavilyAgentExecutor(promptExecutor, tools.mcpTools)
        val a2aServer = A2AServer(
            agentExecutor = agentExecutor,
            agentCard = agentCard,
        )

        val serverTransport = HttpJSONRPCServerTransport(a2aServer)
        logger.info("${LogColors.TAVILY} Server starting on port ${config.tavilyPort}")

        serverTransport.start(
            engineFactory = CIO,
            port = config.tavilyPort,
            path = TAVILY_PATH,
            wait = false,
            agentCard = agentCard,
            agentCardPath = TAVILY_CARD_PATH
        )
        logger.info("${LogColors.TAVILY} Server started successfully")
    }

    private suspend fun startAppointmentValidationServer() {
        logger.info("${LogColors.VALIDATION} Server initializing...")
        val agentCard = appointmentValidationAgentCard("${config.baseUrl}:${config.appointmentValidationPort}")
        val agentExecutor = AppointmentValidationAgentExecutor(promptExecutor)
        val a2aServer = A2AServer(
            agentExecutor = agentExecutor,
            agentCard = agentCard,
        )

        val serverTransport = HttpJSONRPCServerTransport(a2aServer)
        logger.info("${LogColors.VALIDATION} Server starting on port ${config.appointmentValidationPort}")

        serverTransport.start(
            engineFactory = CIO,
            port = config.appointmentValidationPort,
            path = APPOINTMENT_VALIDATION_PATH,
            wait = false,
            agentCard = agentCard,
            agentCardPath = APPOINTMENT_VALIDATION_CARD_PATH
        )
        logger.info("${LogColors.VALIDATION} Server started successfully")
    }

    private suspend fun startServiceTerritoryValidationServer() {
        logger.info("${LogColors.SERVICE_TERRITORY} Server initializing...")
        val agentCard = serviceTerritoryValidationAgentCard("${config.baseUrl}:${config.serviceTerritoryValidationPort}")
        val agentExecutor = ServiceTerritoryValidationAgentExecutor(promptExecutor)
        val a2aServer = A2AServer(
            agentExecutor = agentExecutor,
            agentCard = agentCard,
        )

        val serverTransport = HttpJSONRPCServerTransport(a2aServer)
        logger.info("${LogColors.SERVICE_TERRITORY} Server starting on port ${config.serviceTerritoryValidationPort}")

        serverTransport.start(
            engineFactory = CIO,
            port = config.serviceTerritoryValidationPort,
            path = SERVICE_TERRITORY_VALIDATION_PATH,
            wait = false,
            agentCard = agentCard,
            agentCardPath = SERVICE_TERRITORY_VALIDATION_CARD_PATH
        )
        logger.info("${LogColors.SERVICE_TERRITORY} Server started successfully")
    }

    private suspend fun startTimeslotValidationServer() {
        logger.info("${LogColors.TIMESLOT} Server initializing...")
        val agentCard = timeslotValidationAgentCard("${config.baseUrl}:${config.timeslotValidationPort}")
        val agentExecutor = TimeslotValidationAgentExecutor(promptExecutor)
        val a2aServer = A2AServer(
            agentExecutor = agentExecutor,
            agentCard = agentCard,
        )

        val serverTransport = HttpJSONRPCServerTransport(a2aServer)
        logger.info("${LogColors.TIMESLOT} Server starting on port ${config.timeslotValidationPort}")

        serverTransport.start(
            engineFactory = CIO,
            port = config.timeslotValidationPort,
            path = TIMESLOT_VALIDATION_PATH,
            wait = false,
            agentCard = agentCard,
            agentCardPath = TIMESLOT_VALIDATION_CARD_PATH
        )
        logger.info("${LogColors.TIMESLOT} Server started successfully")
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

    private suspend fun startMapsServer() {
        logger.info("${LogColors.MAPS} Server initializing...")
        val agentCard = mapsAgentCard("${config.baseUrl}:${config.mapsPort}")
        val agentExecutor = MapsAgentExecutor(promptExecutor, tools.mcpTools)
        val a2aServer = A2AServer(
            agentExecutor = agentExecutor,
            agentCard = agentCard,
        )

        val serverTransport = HttpJSONRPCServerTransport(a2aServer)
        logger.info("${LogColors.MAPS} Server starting on port ${config.mapsPort}")

        serverTransport.start(
            engineFactory = CIO,
            port = config.mapsPort,
            path = MAPS_PATH,
            wait = false,
            agentCard = agentCard,
            agentCardPath = MAPS_CARD_PATH
        )
        logger.info("${LogColors.MAPS} Server started successfully")
    }

    private suspend fun startWeatherServer() {
        logger.info("${LogColors.WEATHER} Server initializing...")
        val agentCard = weatherAgentCard("${config.baseUrl}:${config.weatherPort}")
        val agentExecutor = WeatherAgentExecutor(promptExecutor, tools.mcpTools)
        val a2aServer = A2AServer(
            agentExecutor = agentExecutor,
            agentCard = agentCard,
        )

        val serverTransport = HttpJSONRPCServerTransport(a2aServer)
        logger.info("${LogColors.WEATHER} Server starting on port ${config.weatherPort}")

        serverTransport.start(
            engineFactory = CIO,
            port = config.weatherPort,
            path = WEATHER_PATH,
            wait = false,
            agentCard = agentCard,
            agentCardPath = WEATHER_CARD_PATH
        )
        logger.info("${LogColors.WEATHER} Server started successfully")
    }

    fun getEndpoints(): A2ASchedulerEndpoints = A2ASchedulerEndpoints(
        locationReviewUrl = "${config.baseUrl}:${config.tavilyPort}$TAVILY_PATH",
        appointmentValidationUrl = "${config.baseUrl}:${config.appointmentValidationPort}${APPOINTMENT_VALIDATION_PATH}",
        serviceTerritoryValidationUrl = "${config.baseUrl}:${config.serviceTerritoryValidationPort}$SERVICE_TERRITORY_VALIDATION_PATH",
        timeslotValidationUrl = "${config.baseUrl}:${config.timeslotValidationPort}$TIMESLOT_VALIDATION_PATH",
        appointmentBookingUrl = "${config.baseUrl}:${config.appointmentBookingPort}${APPOINTMENT_BOOKING_PATH}",
        mapsUrl = "${config.baseUrl}:${config.mapsPort}$MAPS_PATH"
    )
    
    /**
     * Get endpoints for the additional agents (Maps, Weather)
     */
    fun getAdditionalAgentEndpoints(): AdditionalAgentEndpoints = AdditionalAgentEndpoints(
        mapsUrl = "${config.baseUrl}:${config.mapsPort}$MAPS_PATH",
        weatherUrl = "${config.baseUrl}:${config.weatherPort}$WEATHER_PATH"
    )
}

/**
 * Endpoints for the additional agents.
 */
data class AdditionalAgentEndpoints(
    val mapsUrl: String,
    val weatherUrl: String
)

fun Application.a2aSchedulerRoutes() {
    routing {
        // Health check endpoint for the A2A scheduler
        get("/a2a/health") {
            call.respondText("""{"status":"healthy"}""", io.ktor.http.ContentType.Application.Json)
        }
    }
}
