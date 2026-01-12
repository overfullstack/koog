package com.salesforce.graphalow.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools

class SchedulerAgent {

  val toolRegistry = ToolRegistry { tools(SchedulerAgentTools.CustomTools()) }

  fun simpleAgent(): AIAgent<String, String> {
    val agent =
      AIAgent(
        promptExecutor = SalesforceOpenAILLM.getLlmExecutor(),
        llmModel = SalesforceOpenAILLM.getModel(),
        toolRegistry = toolRegistry,
        systemPrompt = "You help the user in booking service appointments.",
        strategy = SchedulerAgentStrategy.schedulerAIAgentStrategy,
        maxIterations = 200,
      )

    return agent
  }
}

suspend fun main() {

  val agent = SchedulerAgent()
  val ans = agent.simpleAgent().run("")

  println(ans)

  println(SchedulerAgentTools.dynamicEnv)

  //val mermaidDiagram: String = SchedulerAgentStrategy.schedulerAIAgentStrategy.asMermaidDiagram()

  //println(mermaidDiagram)

}
