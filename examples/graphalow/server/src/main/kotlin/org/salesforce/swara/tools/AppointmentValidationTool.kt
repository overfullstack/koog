package org.salesforce.swara.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.PickUtils.afterStepContainingHeader
import com.salesforce.revoman.output.ExeType
import org.slf4j.LoggerFactory
import java.io.File

object AppointmentValidationUtils {
    const val IGNORE_HTTP_STATUS_UNSUCCESSFUL = "ignoreHTTPStatusUnsuccessful"
    val WAIT_HOOK = com.salesforce.revoman.input.config.HookConfig.Companion.post(
        afterStepContainingHeader("isAsync")
    ) { _, _ -> Thread.sleep(7000) }
}

class AppointmentValidationTool : ToolSet {
    private val logger = LoggerFactory.getLogger(AppointmentValidationTool::class.java)
    val dynamicEnv = mutableMapOf<String, String>()
    
    // Track which hospital environment is being used
    var currentHospital: String = "yashoda"

    @Tool
    @LLMDescription("Returns a list of available work type groups. Used to validate if a work type group name matches any existing ones.")
    fun validateWorkTypeGroup(
        @LLMDescription("The work type group name to validate (e.g., 'blood test')")
        workTypeGroupName: String,
        @LLMDescription("The hospital/location name (e.g., 'Yashoda', 'Apollo'). Defaults to Yashoda if not specified.")
        hospitalName: String = "yashoda"
    ): String {
        logger.info("Fetching work type groups for validation. Requested name: $workTypeGroupName, Hospital: $hospitalName")
        
        // Store the current hospital for later use
        currentHospital = hospitalName.lowercase()
        
        try {
            val pmCollectionPaths = "scheduler-e2e/Validate_WorkType_AppointmentType.json"
            
            // Select environment file based on hospital name
            val envFileName = when {
                hospitalName.lowercase().contains("apollo") -> "scheduler-e2e/Apollo_Env.json"
                hospitalName.lowercase().contains("yashoda") -> "scheduler-e2e/Yashoda_Env.json"
                else -> {
                    logger.warn("Unknown hospital '$hospitalName', defaulting to Yashoda_Env.json")
                    "scheduler-e2e/Yashoda_Env.json"
                }
            }
            logger.info("Using environment file: $envFileName for hospital: $hospitalName")
            
            val pmEnvironmentPaths = listOf(envFileName)
            
            // Verify files exist
            val collectionFile = File("src/main/resources/$pmCollectionPaths")
            val envFile = File("src/main/resources/${pmEnvironmentPaths.first()}")
            
            if (!collectionFile.exists() && !File(pmCollectionPaths).exists()) {
                logger.error("Postman collection not found at: $pmCollectionPaths or src/main/resources/$pmCollectionPaths")
                return """{"error": "Postman collection file not found", "isValid": false}"""
            }
            
            if (!envFile.exists() && !File(pmEnvironmentPaths.first()).exists()) {
                logger.error("Postman environment file not found at: ${pmEnvironmentPaths.first()} or src/main/resources/${pmEnvironmentPaths.first()}")
                return """{"error": "Postman environment file not found", "isValid": false}"""
            }
            
            logger.info("Postman collection path: $pmCollectionPaths")
            logger.info("Postman environment path: ${pmEnvironmentPaths.first()}")

            // Note: We don't need to set workTypeGroupID in dynamicEnv since we're just fetching all work type groups
            // The validation will be done by matching the name against the returned list

            // Get the js directory path - prefer directories that have node_modules
            fun hasNodeModules(path: String): Boolean {
                val nodeModules = File(path, "node_modules")
                return nodeModules.exists() && nodeModules.isDirectory
            }
            
            val jsPath = when {
                // First try build directory (copied during build with node_modules)
                File("build/js").exists() && hasNodeModules("build/js") ->
                    File("build/js").absolutePath
                // Try js at root (has node_modules)
                File("js").exists() && hasNodeModules("js") ->
                    File("js").absolutePath
                // Try server/build/js (if running from project root)
                File("server/build/js").exists() && hasNodeModules("server/build/js") ->
                    File("server/build/js").absolutePath
                // Try server/js (source location, should have node_modules after npmInstall)
                File("server/js").exists() && hasNodeModules("server/js") ->
                    File("server/js").absolutePath
                else -> {
                    // Last resort: try to find it from user.dir, prioritizing those with node_modules
                    val possiblePaths = listOf(
                        File(System.getProperty("user.dir"), "build/js"),
                        File(System.getProperty("user.dir"), "js"),
                        File(System.getProperty("user.dir"), "server/build/js"),
                        File(System.getProperty("user.dir"), "server/js")
                    )
                    // First try to find one with node_modules
                    possiblePaths.firstOrNull { it.exists() && hasNodeModules(it.absolutePath) }?.absolutePath
                        ?: possiblePaths.firstOrNull { it.exists() }?.absolutePath
                        ?: "js"
                }
            }
            val nodeModulesExists = hasNodeModules(jsPath)
            logger.info("Using js path: $jsPath (exists: ${File(jsPath).exists()}, has node_modules: $nodeModulesExists)")
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
                        afterStepContainingHeader(AppointmentValidationUtils.IGNORE_HTTP_STATUS_UNSUCCESSFUL),
                    )
                    .hooks(
                        AppointmentValidationUtils.WAIT_HOOK,
                    )
                    .nodeModulesPath(jsPath)
                    .off()
            )

            val lastStep = rundown.stepReports.last()
            logger.info("Step reports count: ${rundown.stepReports.size}")
            
            // Extract the actual HTTP response body from the step report
            val response = try {
                val responseBody = lastStep.responseInfo?.get()?.httpMsg?.body?.toString()
                if (responseBody.isNullOrBlank()) {
                    logger.warn("Response body is null or blank, trying toString() on step report")
                    // Fallback to toString() if body extraction fails
                    lastStep.toString() ?: "{\"error\": \"No response from validation API\"}"
                } else {
                    logger.info("Extracted response body: ${responseBody.take(500)}")
                    responseBody
                }
            } catch (e: Exception) {
                logger.error("Error extracting response body: ${e.message}", e)
                // Fallback to toString() if extraction fails
                lastStep.toString() ?: "{\"error\": \"No response from validation API\"}"
            }
            
            logger.info("Validation response (first 500 chars): ${response.take(500)}")
            return response
        } catch (e: Exception) {
            logger.error("Error during validation: ${e.message}", e)
            return """{"error": "${e.message ?: "Unknown error occurred during validation"}", "isValid": false}"""
        }
    }
}

