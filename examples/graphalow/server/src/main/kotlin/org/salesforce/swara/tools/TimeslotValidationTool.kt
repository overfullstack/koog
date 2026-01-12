package org.jetbrains.demo.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.PickUtils.afterStepContainingHeader
import com.salesforce.revoman.output.ExeType
import org.slf4j.LoggerFactory

object TimeslotValidationUtils {
    const val IGNORE_HTTP_STATUS_UNSUCCESSFUL = "ignoreHTTPStatusUnsuccessful"
    val WAIT_HOOK = com.salesforce.revoman.input.config.HookConfig.Companion.post(
        afterStepContainingHeader("isAsync")
    ) { _, _ -> Thread.sleep(7000) }
}

class TimeslotValidationTool : ToolSet {
    private val logger = LoggerFactory.getLogger(TimeslotValidationTool::class.java)
    val dynamicEnv = mutableMapOf<String, String>()

    @Tool
    @LLMDescription("Validates if a timeslot is available for the given appointment time, service territory, and work type group. Returns available timeslots.")
    fun validateTimeslot(
        @LLMDescription("The appointment time to validate (ISO 8601 format)")
        appointmentTime: String,
        @LLMDescription("The service territory ID (from service territory validation)")
        serviceTerritoryId: String,
        @LLMDescription("The work type group ID (from work type group validation)")
        workTypeGroupId: String
    ): String {
        logger.info("Validating timeslot. AppointmentTime: $appointmentTime, ServiceTerritoryId: $serviceTerritoryId, WorkTypeGroupId: $workTypeGroupId")
        
        try {
            val pmCollectionPaths = "scheduler-e2e/Validate_Timeslot.json"
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

            // Set dynamic environment variables for timeslot validation
            // Note: The Postman collection uses workTypeGroupID and serviceTerritoryID (with capital ID)
            dynamicEnv["workTypeGroupID"] = workTypeGroupId
            dynamicEnv["serviceTerritoryID"] = serviceTerritoryId
            dynamicEnv["appointmentTime"] = appointmentTime
            
            logger.info("Set dynamic environment variables: workTypeGroupID=$workTypeGroupId, serviceTerritoryID=$serviceTerritoryId, appointmentTime=$appointmentTime")

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
                        afterStepContainingHeader(TimeslotValidationUtils.IGNORE_HTTP_STATUS_UNSUCCESSFUL),
                    )
                    .hooks(
                        TimeslotValidationUtils.WAIT_HOOK,
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
                    
                    // If the text contains HTTP headers, find where the JSON starts
                    // Look for the pattern: blank line followed by {
                    val jsonStartPattern = Regex("""\n\s*\n\s*\{""")
                    val jsonStartMatch = jsonStartPattern.find(text)
                    if (jsonStartMatch != null) {
                        val startIndex = jsonStartMatch.range.last
                        // Find the matching closing brace
                        var braceCount = 0
                        var inString = false
                        var escapeNext = false
                        
                        for (i in startIndex until text.length) {
                            val char = text[i]
                            when {
                                escapeNext -> escapeNext = false
                                char == '\\' -> escapeNext = true
                                char == '"' && !escapeNext -> inString = !inString
                                !inString && char == '{' -> braceCount++
                                !inString && char == '}' -> {
                                    braceCount--
                                    if (braceCount == 0) {
                                        return text.substring(startIndex, i + 1)
                                    }
                                }
                            }
                        }
                    }
                    
                    // Fallback: Find the last complete JSON object by counting braces
                    var jsonStart = -1
                    var braceCount = 0
                    var inString = false
                    var escapeNext = false
                    var bestStart = -1
                    var bestEnd = -1
                    
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
                                        bestStart = jsonStart
                                        bestEnd = i + 1
                                        jsonStart = -1 // Reset to find next object
                                    }
                                }
                            }
                        }
                    }
                    
                    // If we found a complete JSON object, return it
                    if (bestStart >= 0 && bestEnd > bestStart) {
                        return text.substring(bestStart, bestEnd)
                    }
                    
                    // PRIORITY: First look specifically for the response structure (allAppointmentTimeSlotResponse)
                    // This ensures we don't accidentally return the request body
                    val responseMarker = "\"allAppointmentTimeSlotResponse\""
                    val responseIdx = text.indexOf(responseMarker)
                    if (responseIdx >= 0) {
                        // Find the start of the JSON object containing this marker
                        var searchStart = responseIdx
                        while (searchStart > 0 && text[searchStart] != '{') {
                            searchStart--
                        }
                        if (searchStart >= 0 && text[searchStart] == '{') {
                            // Now find the matching closing brace
                            var depth = 0
                            var inStr = false
                            var escNext = false
                            for (i in searchStart until text.length) {
                                val c = text[i]
                                when {
                                    escNext -> escNext = false
                                    c == '\\' -> escNext = true
                                    c == '"' && !escNext -> inStr = !inStr
                                    !inStr && c == '{' -> depth++
                                    !inStr && c == '}' -> {
                                        depth--
                                        if (depth == 0) {
                                            val result = text.substring(searchStart, i + 1)
                                            logger.info("Found allAppointmentTimeSlotResponse JSON: ${result.take(200)}...")
                                            return result
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // Last resort: find first '{' and last '}' (simple approach)
                    val firstBrace = text.indexOf('{')
                    val lastBrace = text.lastIndexOf('}')
                    if (firstBrace >= 0 && lastBrace > firstBrace) {
                        val candidate = text.substring(firstBrace, lastBrace + 1)
                        // Basic validation - check it looks like JSON and contains response structure
                        if (candidate.trim().startsWith("{") && candidate.trim().endsWith("}") && 
                            (candidate.contains("\"allAppointmentTimeSlotResponse\"") || candidate.contains("\"slots\""))) {
                            return candidate
                        }
                        // If the candidate contains request fields, it's NOT the response - skip it
                        if (candidate.contains("\"workTypeGroupId\"") && candidate.contains("\"territoryIds\"")) {
                            logger.warn("Skipping JSON that appears to be request body, not response")
                            return null
                        }
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
                
                // Try to extract JSON from whatever we found
                var extractedJson = extractJsonFromResponse(responseBody)
                
                // Check if we accidentally got the request body instead of response
                if (extractedJson != null && 
                    extractedJson.contains("\"workTypeGroupId\"") && 
                    extractedJson.contains("\"territoryIds\"") &&
                    !extractedJson.contains("\"allAppointmentTimeSlotResponse\"")) {
                    logger.warn("Extracted JSON appears to be REQUEST body, not response. Looking for actual response...")
                    // Try to find the response in the full step string
                    val stepString = lastStep.toString()
                    val responseMarkerIdx = stepString.indexOf("\"allAppointmentTimeSlotResponse\"")
                    if (responseMarkerIdx >= 0) {
                        logger.info("Found allAppointmentTimeSlotResponse marker at index $responseMarkerIdx, re-extracting...")
                        extractedJson = extractJsonFromResponse(stepString.substring(maxOf(0, responseMarkerIdx - 100)))
                    }
                }
                
                if (extractedJson != null) {
                    logger.info("Extracted JSON (${extractedJson.length} chars): ${extractedJson.take(300)}...")
                    extractedJson
                } else {
                    logger.warn("Could not extract JSON. Response body was: ${responseBody?.take(500)}")
                    // Last resort: try to find JSON pattern in the raw response
                    val regexExtractedJson = responseBody?.let { body ->
                        // Look for JSON object pattern more aggressively
                        val jsonPattern = Regex("""\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}""", RegexOption.DOT_MATCHES_ALL)
                        val matches = jsonPattern.findAll(body).toList()
                        if (matches.isNotEmpty()) {
                            // Take the largest match (likely the main response)
                            val largestMatch = matches.maxByOrNull { it.value.length }
                            largestMatch?.value?.let {
                                logger.info("Found JSON using regex pattern (${it.length} chars)")
                                it
                            }
                        } else {
                            null
                        }
                    }
                    
                    regexExtractedJson ?: "{\"error\": \"No valid JSON response from validation API\"}"
                }
            } catch (e: Exception) {
                logger.error("Error extracting response body: ${e.message}", e)
                "{\"error\": \"Failed to extract response: ${e.message}\"}"
            }
            
            logger.info("Validation response (first 500 chars): ${response.take(500)}")
            return response
        } catch (e: Exception) {
            logger.error("Error during validation: ${e.message}", e)
            return """{"error": "${e.message ?: "Unknown error occurred during validation"}", "isValid": false}"""
        }
    }
}

