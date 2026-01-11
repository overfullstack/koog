package org.jetbrains.demo.agent.tools

import ai.koog.agents.core.agent.entity.ToolSelectionStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tool
import ai.koog.agents.mcp.McpToolRegistryProvider
import org.jetbrains.demo.agent.koog.descriptors

data class Tools(
    val mcpTools: ToolRegistry,
) {
    fun registry() = ToolRegistry {
        tools(mcpTools.tools)
        tool(::addDate)
    }

    fun selectionStrategy() = ToolSelectionStrategy.Tools(registry().descriptors())
}

suspend fun tools(): Tools {
    val mcpTools = McpToolRegistryProvider.fromSseTransport("http://localhost:9011")
    return Tools(mcpTools = mcpTools)
}

private suspend fun McpToolRegistryProvider.fromSseTransport(url: String): ToolRegistry =
    fromTransport(McpToolRegistryProvider.defaultSseTransport(url))
