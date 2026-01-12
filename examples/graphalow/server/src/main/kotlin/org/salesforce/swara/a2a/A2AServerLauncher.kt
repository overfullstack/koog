package org.salesforce.swara.a2a

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.demo.agent.a2a.APPOINTMENT_BOOKING_PATH
import org.salesforce.swara.agents.TAVILY_PATH
import org.salesforce.swara.agents.MAPS_PATH
import org.salesforce.swara.agents.WEATHER_PATH
import org.salesforce.tools.Tools
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("A2AServerLauncher")

fun main() = runBlocking {
    logger.info("Starting A2A Mesh Servers...")

    val config = A2AConfig(
        baseUrl = System.getenv("A2A_BASE_URL") ?: "http://localhost",
        tavilyPort = System.getenv("TAVILY_AGENT_PORT")?.toIntOrNull() ?: 9101,
        appointmentBookingPort = System.getenv("APPOINTMENT_BOOKING_PORT")?.toIntOrNull() ?: 9102,
        mapsPort = System.getenv("MAPS_AGENT_PORT")?.toIntOrNull() ?: 9107,
        weatherPort = System.getenv("WEATHER_AGENT_PORT")?.toIntOrNull() ?: 9108
    )

    val promptExecutor = createPromptExecutor()
    val tools = createTools()

    val meshServer = A2AMeshServer(config, promptExecutor, tools)
    meshServer.start()

    logger.info("A2A Mesh servers started successfully!")
    logger.info("Endpoints:")
    logger.info("  Tavily Agent: ${config.baseUrl}:${config.tavilyPort}$TAVILY_PATH")
    logger.info("  Appointment Booking: ${config.baseUrl}:${config.appointmentBookingPort}$APPOINTMENT_BOOKING_PATH")
    logger.info("  Google Maps: ${config.baseUrl}:${config.mapsPort}$MAPS_PATH")
    logger.info("  Weather Forecast: ${config.baseUrl}:${config.weatherPort}$WEATHER_PATH")
    
    // Keep the main thread alive
    while (true) {
        delay(Long.MAX_VALUE)
    }
}

private fun createPromptExecutor(): MultiLLMPromptExecutor {
    val openAIKey = System.getenv("LLM_GATEWAY_KEY") ?: System.getenv("OPENAI_API_KEY")
    val anthropicKey = System.getenv("ANTHROPIC_AUTH_TOKEN") ?: System.getenv("ANTHROPIC_API_KEY")
    val googleKey = System.getenv("GEMINI_API_KEY")
    val gatewayBaseUrl = System.getenv("LLM_GATEWAY_BASE_URL")
    val clients = buildList {
        if (!openAIKey.isNullOrBlank()) {
            val settings = if (gatewayBaseUrl != null) {
                OpenAIClientSettings(baseUrl = gatewayBaseUrl)
            } else {
                OpenAIClientSettings()
            }
            add(LLMProvider.OpenAI to OpenAILLMClient(apiKey = openAIKey, settings = settings))
        }
        if (!anthropicKey.isNullOrBlank()) {
            val bedrockBaseUrl = System.getenv("ANTHROPIC_BEDROCK_BASE_URL")
            val settings = if (bedrockBaseUrl != null) {
                AnthropicClientSettings(baseUrl = bedrockBaseUrl)
            } else {
                AnthropicClientSettings()
            }
            add(LLMProvider.Anthropic to AnthropicLLMClient(apiKey = anthropicKey, settings = settings))
        }
        if (!googleKey.isNullOrBlank()) {
            add(LLMProvider.Google to GoogleLLMClient(googleKey, GoogleClientSettings(baseUrl = gatewayBaseUrl)))
        }
    }
    
    require(clients.isNotEmpty()) { "No LLM API keys configured" }
    return MultiLLMPromptExecutor(clients.toMap())
}

private suspend fun createTools(): Tools {
    val mcpServerUrl = System.getenv("MCP_SERVER_URL") ?: "http://localhost:9011"

    val mcpTools = try {
        logger.info("Connecting to MCP server at $mcpServerUrl...")
        val registry = McpToolRegistryProvider.fromTransport(McpToolRegistryProvider.defaultSseTransport(mcpServerUrl))
        logger.info("✅ Connected to MCP server! Available tools: ${registry.tools.map { it.descriptor.name }}")
        registry
    } catch (e: Exception) {
        logger.warn("⚠️ Could not connect to MCP server at $mcpServerUrl: ${e.message}")
        logger.warn("⚠️ Google Maps, Tavily, and OpenWeather tools will NOT be available!")
        ToolRegistry.EMPTY
    }

    return Tools(mcpTools = mcpTools)
}
