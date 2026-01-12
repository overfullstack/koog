package org.jetbrains.demo.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.PickUtils.afterStepContainingHeader
import com.salesforce.revoman.output.ExeType
import org.slf4j.LoggerFactory

object ServiceTerritoryValidationUtils {
    const val IGNORE_HTTP_STATUS_UNSUCCESSFUL = "ignoreHTTPStatusUnsuccessful"
    val WAIT_HOOK = com.salesforce.revoman.input.config.HookConfig.Companion.post(
        afterStepContainingHeader("isAsync")
    ) { _, _ -> Thread.sleep(7000) }
}

class ServiceTerritoryValidationTool : ToolSet {
    private val logger = LoggerFactory.getLogger(ServiceTerritoryValidationTool::class.java)
    val dynamicEnv = mutableMapOf<String, String>()

    @Tool
    @LLMDescription("Returns a list of available service territories. Used to validate if a service territory/location name matches any existing ones.")
    fun validateServiceTerritory(
        @LLMDescription("The service territory/location name to validate (e.g., 'San Francisco')")
        location: String
    ): String {
        logger.info("Fetching service territories for validation. Requested location: $location")
        
        try {
            val pmCollectionPaths = "scheduler-e2e/Validate_ServiceTerritory.json"
            val pmEnvironmentPaths = listOf("scheduler-e2e/Apollo_Env.json")
            
            // Verify files exist
            val collectionFile = java.io.File("src/main/resources/$pmCollectionPaths")
            val envFile = java.io.File("src/main/resources/${pmEnvironmentPaths.first()}")
            
            if (!collectionFile.exists() && !java.io.File(pmCollectionPaths).exists()) {
                logger.error("Postman collection not found at: $pmCollectionPaths or src/main/resources/$pmCollectionPaths")
                return """{"error": "Postman collection file not found", "isValid": false}"""
            }
            
            if (!envFile.exists() && !java.io.File(pmEnvironmentPaths.first()).exists()) {
                logger.error("Postman environment file not found at: ${pmEnvironmentPaths.first()} or src/main/resources/${pmEnvironmentPaths.first()}")
                return """{"error": "Postman environment file not found", "isValid": false}"""
            }
            
            logger.info("Postman collection path: $pmCollectionPaths")
            logger.info("Postman environment path: ${pmEnvironmentPaths.first()}")

            // Note: We don't need to set serviceTerritoryId in dynamicEnv since we're just fetching all service territories
            // The validation will be done by matching the name against the returned list

            // Get the js directory path - prefer directories that have node_modules
            fun hasNodeModules(path: String): Boolean {
                val nodeModules = java.io.File(path, "node_modules")
                return nodeModules.exists() && nodeModules.isDirectory
            }
            
            val jsPath = when {
                // First try build directory (copied during build with node_modules)
                java.io.File("build/js").exists() && hasNodeModules("build/js") -> 
                    java.io.File("build/js").absolutePath
                // Try js at root (has node_modules)
                java.io.File("js").exists() && hasNodeModules("js") -> 
                    java.io.File("js").absolutePath
                // Try server/build/js (if running from project root)
                java.io.File("server/build/js").exists() && hasNodeModules("server/build/js") -> 
                    java.io.File("server/build/js").absolutePath
                // Try server/js (source location, should have node_modules after npmInstall)
                java.io.File("server/js").exists() && hasNodeModules("server/js") -> 
                    java.io.File("server/js").absolutePath
                else -> {
                    // Last resort: try to find it from user.dir, prioritizing those with node_modules
                    val possiblePaths = listOf(
                        java.io.File(System.getProperty("user.dir"), "build/js"),
                        java.io.File(System.getProperty("user.dir"), "js"),
                        java.io.File(System.getProperty("user.dir"), "server/build/js"),
                        java.io.File(System.getProperty("user.dir"), "server/js")
                    )
                    // First try to find one with node_modules
                    possiblePaths.firstOrNull { it.exists() && hasNodeModules(it.absolutePath) }?.absolutePath
                        ?: possiblePaths.firstOrNull { it.exists() }?.absolutePath
                        ?: "js"
                }
            }
            val nodeModulesExists = hasNodeModules(jsPath)
            logger.info("Using js path: $jsPath (exists: ${java.io.File(jsPath).exists()}, has node_modules: $nodeModulesExists)")
            if (!nodeModulesExists) {
                logger.warn("WARNING: node_modules not found in $jsPath. Lodash and other npm packages may not be available.")
            }
            
            val rundown = ReVoman.revUp(
                Kick.configure()
                    .templatePaths(pmCollectionPaths)
                    .dynamicEnvironment(dynamicEnv)
                    .environmentPaths(pmEnvironmentPaths)
                    .haltOnFailureOfTypeExcept(
                        ExeType.HTTP_STATUS,
                        afterStepContainingHeader(ServiceTerritoryValidationUtils.IGNORE_HTTP_STATUS_UNSUCCESSFUL),
                    )
                    .hooks(
                        ServiceTerritoryValidationUtils.WAIT_HOOK,
                    )
                    .nodeModulesPath(jsPath)
                    .off()
            )

            val lastStep = rundown.stepReports.last()
            logger.info("Step reports count: ${rundown.stepReports.size}")
            
            // Extract the actual HTTP response body from the step report
            val response = try {
                // responseInfo is an Either type, need to handle it properly
                val responseBody = lastStep.responseInfo?.fold(
                    { error ->
                        logger.warn("Response info is Left (error): $error")
                        null
                    },
                    { success ->
                        success?.httpMsg?.body?.toString()
                    }
                )
                
                if (responseBody.isNullOrBlank()) {
                    logger.warn("Response body is null or blank, trying to extract from step report")
                    // Try to get the response from the step report in a different way
                    // Look for the actual response in the step report
                    val stepString = lastStep.toString()
                    // Try to find JSON in the step string
                    val jsonStart = stepString.indexOf('{')
                    val jsonEnd = stepString.lastIndexOf('}') + 1
                    if (jsonStart >= 0 && jsonEnd > jsonStart) {
                        val extractedJson = stepString.substring(jsonStart, jsonEnd)
                        logger.info("Extracted JSON from step report: ${extractedJson.take(500)}")
                        extractedJson
                    } else {
                        logger.warn("No JSON found in step report, returning error")
                        "{\"error\": \"No response from validation API\"}"
                    }
                } else {
                    logger.info("Extracted response body: ${responseBody.take(500)}")
                    responseBody
                }
            } catch (e: Exception) {
                logger.error("Error extracting response body: ${e.message}", e)
                // Try to extract JSON from the step report as last resort
                try {
                    val stepString = lastStep.toString()
                    val jsonStart = stepString.indexOf('{')
                    val jsonEnd = stepString.lastIndexOf('}') + 1
                    if (jsonStart >= 0 && jsonEnd > jsonStart) {
                        stepString.substring(jsonStart, jsonEnd)
                    } else {
                        "{\"error\": \"No response from validation API: ${e.message}\"}"
                    }
                } catch (e2: Exception) {
                    "{\"error\": \"Failed to extract response: ${e.message}\"}"
                }
            }
            
            logger.info("Validation response (first 500 chars): ${response.take(500)}")
            return response
        } catch (e: Exception) {
            logger.error("Error during validation: ${e.message}", e)
            return """{"error": "${e.message ?: "Unknown error occurred during validation"}", "isValid": false}"""
        }
    }
}

