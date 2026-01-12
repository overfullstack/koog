package com.salesforce.graphalow

import ai.koog.agents.core.agent.AIAgent
import kotlinx.coroutines.runBlocking

object AgentHelper {
    fun runAgent(agent: AIAgent<String, String>, initialInput: String): String {
        return runBlocking {
            agent.run(initialInput)
        }
    }
}

