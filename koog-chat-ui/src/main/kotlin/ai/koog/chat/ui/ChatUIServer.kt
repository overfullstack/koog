package ai.koog.chat.ui

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

/**
 * Chat UI Server - serves a beautiful WhatsApp-like chat interface
 * that connects to the backend via WebSockets.
 * 
 * Run with: ./gradlew :koog-chat-ui:runChatUI
 * Then open: http://localhost:3001
 */
fun main() {
    val port = System.getenv("CHAT_UI_PORT")?.toIntOrNull() ?: 3001
    val backendUrl = System.getenv("BACKEND_WS_URL") ?: "ws://localhost:8080/chat/ws"
    
    println("""
        
        ╔═══════════════════════════════════════════════════════════╗
        ║                    🗨️  Koog Chat UI                        ║
        ╠═══════════════════════════════════════════════════════════╣
        ║  UI Server:     http://localhost:$port                     ║
        ║  Backend WS:    $backendUrl
        ╚═══════════════════════════════════════════════════════════╝
        
    """.trimIndent())
    
    embeddedServer(Netty, port = port) {
        configureChatUI(backendUrl)
    }.start(wait = true)
}

fun Application.configureChatUI(backendWsUrl: String) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
    
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
    }
    
    routing {
        // API endpoint to get backend WebSocket URL
        get("/api/config") {
            call.respond(mapOf(
                "websocketUrl" to backendWsUrl,
                "version" to "1.0.0"
            ))
        }
        
        // Health check
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
        
        // Serve static files from resources/static
        staticResources("/", "static") {
            default("index.html")
        }
    }
}

