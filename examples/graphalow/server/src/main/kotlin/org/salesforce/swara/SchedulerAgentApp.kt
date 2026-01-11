package org.salesforce.swara

import ai.koog.ktor.Koog
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.getAs
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.sse.SSE
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.demo.agent.a2a.A2ASchedulerEndpoints
import org.jetbrains.demo.agent.a2a.APPOINTMENT_BOOKING_PATH
import org.jetbrains.demo.agent.a2a.APPOINTMENT_VALIDATION_PATH
import org.jetbrains.demo.agent.a2a.SERVICE_TERRITORY_VALIDATION_PATH
import org.jetbrains.demo.agent.a2a.SchedulerAgentOrchestrator
import org.jetbrains.demo.agent.a2a.TIMESLOT_VALIDATION_PATH
import org.salesforce.swara.a2a.A2AConfig
import org.salesforce.swara.a2a.a2aSchedulerRoutes
import org.salesforce.swara.agents.LOCATION_WEATHER_PATH
import org.salesforce.swara.chat.ChatService
import org.salesforce.swara.chat.chatRoutes
import kotlin.time.Duration.Companion.seconds

@Serializable
data class AppConfig(
    val host: String,
    val port: Int,
    val auth: org.salesforce.travel.AuthConfig,
    val openAIKey: String,
    val anthropicKey: String,
    val langfuseUrl: String,
    val langfusePublicKey: String,
    val langfuseSecretKey: String,
    val a2aEnabled: Boolean = false,
    val a2aBaseUrl: String = "http://localhost",
)

@Serializable
data class AuthConfig(val issuer: String, val secret: String, val clientId: String)

fun main() {
    val config = ApplicationConfig("application.yaml")
        .property("app")
        .getAs<AppConfig>()

    embeddedServer(Netty, host = config.host, port = config.port) {
        app(config)
    }.start(wait = true)
}

fun Application.app(config: AppConfig) {
    install(Koog) {
        llm {
            openAI(apiKey = System.getenv("LLM_GATEWAY_KEY")) {
                baseUrl = System.getenv("LLM_GATEWAY_BASE_URL")
                chatCompletionsPath = "/chat/completions"
            }
            anthropic(apiKey = System.getenv("ANTHROPIC_AUTH_TOKEN")) {
                baseUrl = System.getenv("ANTHROPIC_BEDROCK_BASE_URL")
            }
            google(apiKey = System.getenv("GEMINI_API_KEY")) {
                baseUrl = System.getenv("LLM_GATEWAY_BASE_URL")
            }
        }
    }

    configure()
    // A2A Mesh mode (optional - can run alongside traditional agent)
    if (config.a2aEnabled) {
        a2aMesh(config)
    }
}

private fun Application.a2aMesh(config: AppConfig) {
    val a2aConfig = A2AConfig(
        baseUrl = config.a2aBaseUrl,
        locationWeatherPort = 9101,
        appointmentValidationPort = 9103,
        serviceTerritoryValidationPort = 9104,
        timeslotValidationPort = 9105,
        appointmentBookingPort = 9102
    )

    // Create the orchestrator that connects to the A2A agent servers
    // The A2A agent servers must be started separately (see A2AServerLauncher.kt)
    val schedulerAgentOrchestrator = SchedulerAgentOrchestrator(
        A2ASchedulerEndpoints(
            locationWeatherUrl = "${config.a2aBaseUrl}:${a2aConfig.locationWeatherPort}$LOCATION_WEATHER_PATH",
            appointmentValidationUrl = "${config.a2aBaseUrl}:${a2aConfig.appointmentValidationPort}$APPOINTMENT_VALIDATION_PATH",
            serviceTerritoryValidationUrl = "${config.a2aBaseUrl}:${a2aConfig.serviceTerritoryValidationPort}$SERVICE_TERRITORY_VALIDATION_PATH",
            timeslotValidationUrl = "${config.a2aBaseUrl}:${a2aConfig.timeslotValidationPort}$TIMESLOT_VALIDATION_PATH",
            appointmentBookingUrl = "${config.a2aBaseUrl}:${a2aConfig.appointmentBookingPort}$APPOINTMENT_BOOKING_PATH"
        )
    )
    a2aSchedulerRoutes()
    
    // Chat UI endpoints (Claude-like experience)
    val koogPlugin = pluginOrNull(Koog)
    val chatService = if (koogPlugin != null) {
        @Suppress("invisible_reference", "invisible_member")
        (ChatService(schedulerAgentOrchestrator, koogPlugin.promptExecutor))
    } else {
        throw IllegalStateException("Koog plugin must be installed before a2aMesh")
    }
    chatRoutes(chatService)
    
    log.info("A2A Mesh mode enabled. Use /a2a/plan endpoint for A2A-based travel planning.")
    log.info("Chat UI endpoints available at /chat/* (SSE: /chat/stream, WebSocket: /chat/ws)")
}

private fun Application.configure() {
    install(SSE)
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
    }

    install(ContentNegotiation) {
        json(Json {
            encodeDefaults = true
            isLenient = true
        })
    }
}
