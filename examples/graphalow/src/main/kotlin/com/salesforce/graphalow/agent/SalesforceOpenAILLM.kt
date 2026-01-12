package com.salesforce.graphalow.agent

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import kotlin.time.Duration.Companion.seconds

object SalesforceOpenAILLM {

    fun getLlmExecutor() = SingleLLMPromptExecutor(OpenAILLMClient(
        apiKey = "sk-OQfF1D1YXAvCUzcghg4G4g",
        settings = OpenAIClientSettings(
            baseUrl = "https://express-llm-gateway.sfproxy.devx-preprod.aws-esvc1-useast2.aws.sfdc.cl",
            chatCompletionsPath = "/chat/completions"
        )
    ))

    fun getModel() = OpenAIModels.Chat.GPT5_2
}
