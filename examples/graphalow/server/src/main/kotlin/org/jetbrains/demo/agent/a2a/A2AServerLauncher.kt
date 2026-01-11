package org.jetbrains.demo.agent.a2a

import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.demo.tools.Tools
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import org.jetbrains.demo.agent.a2a.agents.PLAN_COMPOSER_PATH
import org.jetbrains.demo.agent.a2a.agents.POI_RESEARCHER_PATH
import org.jetbrains.demo.agent.a2a.agents.ROUTE_PLANNER_PATH
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("A2AServerLauncher")

fun main() = runBlocking {
    logger.info("Starting A2A Mesh Servers...")

    val config = A2AConfig(
        baseUrl = System.getenv("A2A_BASE_URL") ?: "http://localhost",
        routePlannerPort = System.getenv("ROUTE_PLANNER_PORT")?.toIntOrNull() ?: 9101,
        poiResearcherPort = System.getenv("POI_RESEARCHER_PORT")?.toIntOrNull() ?: 9102,
        planComposerPort = System.getenv("PLAN_COMPOSER_PORT")?.toIntOrNull() ?: 9103
    )

    val promptExecutor = createPromptExecutor()
    val tools = createTools()

    val meshServer = A2AMeshServer(config, promptExecutor, tools)
    meshServer.start()

    logger.info("A2A Mesh servers started successfully!")
    logger.info("Endpoints:")
    logger.info("  Route Planner:  ${config.baseUrl}:${config.routePlannerPort}${ROUTE_PLANNER_PATH}")
    logger.info("  POI Researcher: ${config.baseUrl}:${config.poiResearcherPort}${POI_RESEARCHER_PATH}")
    logger.info("  Plan Composer:  ${config.baseUrl}:${config.planComposerPort}${PLAN_COMPOSER_PATH}")

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
        McpToolRegistryProvider.fromTransport(McpToolRegistryProvider.defaultSseTransport(mcpServerUrl))
    } catch (e: Exception) {
        logger.warn("Could not connect to MCP server at $mcpServerUrl: ${e.message}")
        ToolRegistry.EMPTY
    }

    return Tools(mcpTools = mcpTools)
}
