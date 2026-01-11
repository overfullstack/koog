package org.salesforce.travel.agent.a2a

import ai.koog.agents.features.opentelemetry.attribute.CustomAttribute
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetryConfig
import ai.koog.agents.features.opentelemetry.integration.langfuse.addLangfuseExporter
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("A2ATelemetry")

/**
 * Configuration for A2A telemetry using Langfuse.
 * Reads credentials from environment variables:
 * - LANGFUSE_HOST (defaults to https://cloud.langfuse.com)
 * - LANGFUSE_PUBLIC_KEY
 * - LANGFUSE_SECRET_KEY
 */
object A2ATelemetry {
    
    private val langfuseUrl: String? = System.getenv("LANGFUSE_HOST")
    private val langfusePublicKey: String? = System.getenv("LANGFUSE_PUBLIC_KEY")
    private val langfuseSecretKey: String? = System.getenv("LANGFUSE_SECRET_KEY")
    
    val isEnabled: Boolean = !langfusePublicKey.isNullOrBlank() && !langfuseSecretKey.isNullOrBlank()
    
    init {
        if (isEnabled) {
            logger.info("${_root_ide_package_.org.salesforce.travel.agent.LogColors.SERVER} Langfuse telemetry enabled")
            logger.info("${_root_ide_package_.org.salesforce.travel.agent.LogColors.SERVER}   Host: ${langfuseUrl ?: "https://cloud.langfuse.com"}")
        } else {
            logger.warn("${_root_ide_package_.org.salesforce.travel.agent.LogColors.SERVER} Langfuse telemetry disabled - missing LANGFUSE_PUBLIC_KEY or LANGFUSE_SECRET_KEY")
        }
    }
    
    /**
     * Configures OpenTelemetry with Langfuse exporter for the given agent.
     * 
     * @param agentName The name of the agent (used for trace attributes)
     * @param sessionId Optional session ID for correlating traces
     * @param userId Optional user ID for filtering in Langfuse
     */
    fun OpenTelemetryConfig.configureLangfuse(
        agentName: String,
        sessionId: String? = null,
        userId: String? = null
    ) {
        if (!isEnabled) {
            logger.debug("${_root_ide_package_.org.salesforce.travel.agent.LogColors.SERVER} Skipping Langfuse configuration - not enabled")
            return
        }
        
        setVerbose(true)
        
        val traceAttributes = buildList {
            add(CustomAttribute("langfuse.agent.name", agentName))
            sessionId?.let { add(CustomAttribute("langfuse.session.id", it)) }
            userId?.let { add(CustomAttribute("langfuse.user.id", it)) }
            add(CustomAttribute("langfuse.tags", "a2a,koog,$agentName"))
        }
        
        addLangfuseExporter(
            langfuseUrl = langfuseUrl,
            langfusePublicKey = langfusePublicKey,
            langfuseSecretKey = langfuseSecretKey,
            traceAttributes = traceAttributes
        )
        
        logger.debug("${_root_ide_package_.org.salesforce.travel.agent.LogColors.SERVER} Langfuse configured for agent: $agentName")
    }
    
    /**
     * Installs OpenTelemetry feature with Langfuse for an agent.
     * Use this in agent configuration blocks.
     */
    fun installLangfuse(
        agentName: String,
        sessionId: String? = null,
        userId: String? = null
    ): OpenTelemetryConfig.() -> Unit = {
        configureLangfuse(agentName, sessionId, userId)
    }
}
