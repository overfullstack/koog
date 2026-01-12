package org.jetbrains.demo.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.PickUtils.afterStepContainingHeader
import com.salesforce.revoman.output.ExeType
import org.slf4j.LoggerFactory

object AppointmentBookingUtils {
    const val IGNORE_HTTP_STATUS_UNSUCCESSFUL = "ignoreHTTPStatusUnsuccessful"
    val WAIT_HOOK = com.salesforce.revoman.input.config.HookConfig.Companion.post(
        afterStepContainingHeader("isAsync")
    ) { _, _ -> Thread.sleep(7000) }
}

class AppointmentBookingTool : ToolSet {
    private val logger = LoggerFactory.getLogger(AppointmentBookingTool::class.java)
    val dynamicEnv = mutableMapOf<String, String>()

    @Tool
    @LLMDescription("Books an appointment with the provided timeslot details, service territory, work type, and service resource.")
    fun bookAppointment(
        @LLMDescription("The start time of the appointment in ISO 8601 format (UTC)")
        startTime: String,
        @LLMDescription("The end time of the appointment in ISO 8601 format (UTC)")
        endTime: String,
        @LLMDescription("The service territory ID")
        serviceTerritoryId: String,
        @LLMDescription("The work type group ID")
        workTypeGroupId: String,
        @LLMDescription("The service resource ID")
        serviceResourceId: String
    ): String {
        logger.info("Booking appointment. StartTime: $startTime, EndTime: $endTime, ServiceTerritoryId: $serviceTerritoryId, WorkTypeGroupId: $workTypeGroupId, ServiceResourceId: $serviceResourceId")
        
        try {
            val pmCollectionPaths = "scheduler-e2e/Book_Appointment.json"
            val pmEnvironmentPaths = listOf("scheduler-e2e/Scheduler_Test_Env.json")
            
            // Verify files exist
            val collectionFile = java.io.File("src/main/resources/$pmCollectionPaths")
            val envFile = java.io.File("src/main/resources/${pmEnvironmentPaths.first()}")
            
            if (!collectionFile.exists() && !java.io.File(pmCollectionPaths).exists()) {
                logger.error("Postman collection not found at: $pmCollectionPaths or src/main/resources/$pmCollectionPaths")
                return """{"error": "Postman collection file not found", "success": false}"""
            }
            
            if (!envFile.exists() && !java.io.File(pmEnvironmentPaths.first()).exists()) {
                logger.error("Postman environment file not found at: ${pmEnvironmentPaths.first()} or src/main/resources/${pmEnvironmentPaths.first()}")
                return """{"error": "Postman environment file not found", "success": false}"""
            }
            
            logger.info("Postman collection path: $pmCollectionPaths")
            logger.info("Postman environment path: ${pmEnvironmentPaths.first()}")

            // Set dynamic environment variables for appointment booking
            // Note: The Postman collection uses specific variable names
            dynamicEnv["schedStartTime"] = startTime
            dynamicEnv["schedEndTime"] = endTime
            dynamicEnv["serviceTerritoryId"] = serviceTerritoryId
            dynamicEnv["workTypeId"] = workTypeGroupId
            dynamicEnv["serviceResourceId"] = serviceResourceId
            
            logger.info("Set dynamic environment variables: schedStartTime=$startTime, schedEndTime=$endTime, serviceTerritoryId=$serviceTerritoryId, workTypeId=$workTypeGroupId, serviceResourceId=$serviceResourceId")

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
                        afterStepContainingHeader(AppointmentBookingUtils.IGNORE_HTTP_STATUS_UNSUCCESSFUL),
                    )
                    .hooks(
                        AppointmentBookingUtils.WAIT_HOOK,
                    )
                    .nodeModulesPath(jsPath)
                    .off()
            )

            val lastStep = rundown.stepReports.last()
            logger.info("Step reports count: ${rundown.stepReports.size}")
            
            // Extract the actual HTTP response body from the step report
            val response = try {
                // Helper function to extract JSON from a string that may contain HTTP headers
                fun extractJsonFromResponse(responseText: String?): String? {
                    if (responseText.isNullOrBlank()) return null
                    
                    var text = responseText.trim()
                    
                    // Remove triple quotes if present at the start/end
                    text = text.removePrefix("\"\"\"").removeSuffix("\"\"\"")
                    
                    // Find all JSON objects in the text
                    val jsonObjects = mutableListOf<Pair<Int, Int>>()
                    var jsonStart = -1
                    var braceCount = 0
                    var inString = false
                    var escapeNext = false
                    
                    for (i in text.indices) {
                        val char = text[i]
                        when {
                            escapeNext -> escapeNext = false
                            char == '\\' -> escapeNext = true
                            char == '"' && !escapeNext -> inString = !inString
                            !inString && char == '{' -> {
                                if (jsonStart == -1) {
                                    jsonStart = i
                                    braceCount = 1
                                } else {
                                    braceCount++
                                }
                            }
                            !inString && char == '}' -> {
                                if (jsonStart != -1) {
                                    braceCount--
                                    if (braceCount == 0) {
                                        // Found a complete JSON object
                                        jsonObjects.add(jsonStart to (i + 1))
                                        jsonStart = -1
                                    }
                                }
                            }
                        }
                    }
                    
                    // Return the largest JSON object (usually the response)
                    val largestJson = jsonObjects.maxByOrNull { it.second - it.first }
                    if (largestJson != null) {
                        val candidate = text.substring(largestJson.first, largestJson.second)
                        logger.debug("Found JSON response: ${candidate.take(200)}")
                        return candidate
                    }
                    
                    // Fallback: find first '{' and last '}'
                    val firstBrace = text.indexOf('{')
                    val lastBrace = text.lastIndexOf('}')
                    if (firstBrace >= 0 && lastBrace > firstBrace) {
                        val candidate = text.substring(firstBrace, lastBrace + 1)
                        logger.debug("Found JSON using simple brace matching: ${candidate.take(200)}")
                        return candidate
                    }
                    
                    return null
                }
                
                // Try multiple ways to get the response body
                var responseBody: String? = null
                
                // Method 1: Try to get from responseInfo
                responseBody = lastStep.responseInfo?.fold(
                    { error ->
                        logger.debug("Response info is Left (error): $error")
                        null
                    },
                    { success ->
                        // Try different ways to get the body
                        val body = success?.httpMsg?.body
                        when {
                            body is String -> body
                            body != null -> body.toString()
                            else -> null
                        }
                    }
                )
                
                // Method 2: Try to get from step report's response field if available
                if (responseBody.isNullOrBlank()) {
                    try {
                        // Access the response through reflection or direct field access
                        val responseField = lastStep.javaClass.declaredFields.find { 
                            it.name.contains("response", ignoreCase = true) || 
                            it.name.contains("body", ignoreCase = true) ||
                            it.name.contains("http", ignoreCase = true)
                        }
                        responseField?.let {
                            it.isAccessible = true
                            val value = it.get(lastStep)
                            if (value is String) {
                                responseBody = value
                            } else if (value != null) {
                                responseBody = value.toString()
                            }
                        }
                    } catch (e: Exception) {
                        logger.debug("Could not access response field via reflection: ${e.message}")
                    }
                }
                
                // Method 3: Try to extract from the full step string representation
                if (responseBody.isNullOrBlank()) {
                    val stepString = lastStep.toString()
                    logger.debug("Step string length: ${stepString.length}")
                    logger.debug("Step string preview: ${stepString.take(1000)}")
                    
                    // Look for JSON in the step string - it might be embedded
                    val jsonFromStep = extractJsonFromResponse(stepString)
                    if (jsonFromStep != null) {
                        responseBody = jsonFromStep
                        logger.info("Extracted JSON from step string representation")
                    }
                }
                
                logger.debug("Raw response (first 500 chars): ${responseBody?.take(500)}")
                
                val extractedJson = extractJsonFromResponse(responseBody)
                if (extractedJson != null) {
                    logger.info("Extracted JSON response (first 500 chars): ${extractedJson.take(500)}")
                    extractedJson
                } else {
                    logger.warn("Could not extract JSON from response. Returning raw response.")
                    responseBody ?: """{"error": "No response body", "success": false}"""
                }
            } catch (e: Exception) {
                logger.error("Error extracting response: ${e.message}", e)
                """{"error": "Failed to extract response: ${e.message}", "success": false}"""
            }
            
            logger.info("Booking response (first 500 chars): ${response.take(500)}")
            return response
        } catch (e: Exception) {
            logger.error("Appointment booking failed: ${e.message}", e)
            return """{"error": "Appointment booking failed: ${e.message}", "success": false}"""
        }
    }
}

