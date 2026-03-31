package org.jetbrains.demo

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
import org.jetbrains.demo.agent.a2a.A2AAgentEndpoints
import org.jetbrains.demo.agent.a2a.A2AConfig
import org.jetbrains.demo.agent.a2a.ChatService
import org.jetbrains.demo.agent.a2a.TravelOrchestratorAgent
import org.jetbrains.demo.agent.a2a.a2aTravelAgentRoutes
import org.jetbrains.demo.agent.a2a.chatRoutes
import org.jetbrains.demo.agent.chat.agent
import kotlin.String
import kotlin.time.Duration.Companion.seconds

@Serializable
data class AppConfig(
    val host: String,
    val port: Int,
    val auth: AuthConfig,
    val openAIKey: String,
    val anthropicKey: String,
    val langfuseUrl: String,
    val langfusePublicKey: String,
    val langfuseSecretKey: String,
    val weatherApiUrl: String,
    val tavilyApiKey: String,
    val a2aEnabled: Boolean = false,
    val a2aBaseUrl: String = "http://localhost",
    val database: DatabaseConfig? = null,
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
            val llmGatewayKey = System.getenv("LLM_GATEWAY_KEY") ?: config.openAIKey.takeIf { it.isNotBlank() }
            if (llmGatewayKey != null) {
                openAI(apiKey = llmGatewayKey) {
                    System.getenv("LLM_GATEWAY_BASE_URL")?.let { baseUrl = it }
                    chatCompletionsPath = "/chat/completions"
                }
            }
            val anthropicKey = System.getenv("ANTHROPIC_AUTH_TOKEN") ?: System.getenv("ANTHROPIC_API_KEY") ?: config.anthropicKey.takeIf { it.isNotBlank() }
            if (anthropicKey != null) {
                anthropic(apiKey = anthropicKey) {
                    System.getenv("ANTHROPIC_BEDROCK_BASE_URL")?.let { baseUrl = it }
                }
            }
            val googleKey = System.getenv("GEMINI_API_KEY")
            if (googleKey != null) {
                google(apiKey = googleKey) {
                    System.getenv("LLM_GATEWAY_BASE_URL")?.let { baseUrl = it }
                }
            }
        }
    }

    configure()
    agent(config)
    
    // A2A Mesh mode (optional - can run alongside traditional agent)
    if (config.a2aEnabled) {
        a2aMesh(config)
    }
}

private fun Application.a2aMesh(config: AppConfig) {
    val a2aConfig = A2AConfig(
        baseUrl = config.a2aBaseUrl,
        routePlannerPort = 9101,
        poiResearcherPort = 9102,
        planComposerPort = 9103
    )

    // Create the orchestrator that connects to the A2A agent servers
    // The A2A agent servers must be started separately (see A2AServerLauncher.kt)
    val orchestrator = TravelOrchestratorAgent(
        A2AAgentEndpoints(
            routePlannerUrl = "${config.a2aBaseUrl}:${a2aConfig.routePlannerPort}/a2a/route-planner",
            poiResearcherUrl = "${config.a2aBaseUrl}:${a2aConfig.poiResearcherPort}/a2a/poi-researcher",
            planComposerUrl = "${config.a2aBaseUrl}:${a2aConfig.planComposerPort}/a2a/plan-composer"
        )
    )
    a2aTravelAgentRoutes(orchestrator)
    
    // Chat UI endpoints (Claude-like experience)
    val koogPlugin = pluginOrNull(ai.koog.ktor.Koog)
    val chatService = if (koogPlugin != null) {
        @Suppress("invisible_reference", "invisible_member")
        ChatService(orchestrator, koogPlugin.promptExecutor)
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
