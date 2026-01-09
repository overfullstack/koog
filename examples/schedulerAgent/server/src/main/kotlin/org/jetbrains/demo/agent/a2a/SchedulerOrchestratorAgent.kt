package org.jetbrains.demo.agent.a2a

import ai.koog.a2a.client.A2AClient
import ai.koog.a2a.client.UrlAgentCardResolver
import ai.koog.a2a.model.*
import ai.koog.a2a.transport.Request
import ai.koog.a2a.transport.client.jsonrpc.http.HttpJSONRPCClientTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.demo.agent.a2a.model.*
import org.slf4j.LoggerFactory
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val logger = LoggerFactory.getLogger("SchedulerOrchestratorAgent")

/**
 * Progress events emitted during appointment booking to keep user in the loop.
 */
@kotlinx.serialization.Serializable
sealed class AppointmentProgress {
    /** Booking has started */
    @kotlinx.serialization.Serializable
    data class Started(val appointmentType: String, val location: String) : AppointmentProgress()
    
    /** Checking location and weather */
    @kotlinx.serialization.Serializable
    data object CheckingLocationWeather : AppointmentProgress()
    
    /** Location and weather check complete */
    @kotlinx.serialization.Serializable
    data class LocationWeatherComplete(
        val weatherInfo: LocationWeatherResult,
        val durationMs: Long
    ) : AppointmentProgress()
    
    /** Booking appointment */
    @kotlinx.serialization.Serializable
    data object BookingAppointment : AppointmentProgress()
    
    /** Booking complete */
    @kotlinx.serialization.Serializable
    data class BookingComplete(
        val result: AppointmentResult,
        val totalDurationMs: Long
    ) : AppointmentProgress()
    
    /** Error occurred */
    @kotlinx.serialization.Serializable
    data class Error(val message: String) : AppointmentProgress()
}

data class A2ASchedulerEndpoints(
    val locationWeatherUrl: String,
    val appointmentBookingUrl: String
)

class SchedulerOrchestratorAgent(
    private val endpoints: A2ASchedulerEndpoints
) {
    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = true
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun bookAppointment(appointmentForm: AppointmentForm): AppointmentResult {
        logger.info(LogColors.orchestratorBanner("A2A SCHEDULER ORCHESTRATION START"))
        logger.info("${LogColors.ORCHESTRATOR} Appointment: ${appointmentForm.appointmentGroup} at ${appointmentForm.location}")
        logger.info("${LogColors.ORCHESTRATOR} Time: ${appointmentForm.appointmentTime}")

        // Step 1: Call Location Weather Agent
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.cyan("[STEP 1/2]")} Calling Location Weather Agent at ${endpoints.locationWeatherUrl}")
        val locationWeatherStart = System.currentTimeMillis()
        val weatherRequest = LocationWeatherRequest(
            location = appointmentForm.location,
            appointmentTime = appointmentForm.appointmentTime
        )
        val weatherInfo = callLocationWeatherAgent(weatherRequest)
        val locationWeatherDuration = System.currentTimeMillis() - locationWeatherStart
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.cyan("[STEP 1/2]")} Location Weather completed in ${locationWeatherDuration}ms")

        // Step 2: Call Appointment Booking Agent
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.magenta("[STEP 2/2]")} Calling Appointment Booking Agent at ${endpoints.appointmentBookingUrl}")
        val bookingStart = System.currentTimeMillis()
        val bookingRequest = AppointmentBookingRequest(
            appointmentForm = appointmentForm,
            weatherInfo = weatherInfo
        )
        val bookingResult = callAppointmentBookingAgent(bookingRequest)
        val bookingDuration = System.currentTimeMillis() - bookingStart
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.magenta("[STEP 2/2]")} Appointment Booking completed in ${bookingDuration}ms")

        val totalDuration = locationWeatherDuration + bookingDuration
        logger.info(LogColors.orchestratorBanner("A2A SCHEDULER ORCHESTRATION COMPLETE"))
        logger.info("${LogColors.ORCHESTRATOR} Total orchestration time: ${totalDuration}ms")
        logger.info("${LogColors.ORCHESTRATOR}   ${LogColors.cyan("Location Weather")}: ${locationWeatherDuration}ms")
        logger.info("${LogColors.ORCHESTRATOR}   ${LogColors.magenta("Appointment Booking")}: ${bookingDuration}ms")

        return AppointmentResult(
            bookingMessage = bookingResult.confirmationMessage,
            weatherInfo = weatherInfo,
            appointment = appointmentForm
        )
    }
    
    /**
     * Stream-based appointment booking that emits progress updates.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun bookAppointmentWithProgress(appointmentForm: AppointmentForm): Flow<AppointmentProgress> = flow {
        val overallStart = System.currentTimeMillis()
        
        emit(AppointmentProgress.Started(
            appointmentType = appointmentForm.appointmentGroup.name.replace("_", " "),
            location = appointmentForm.location
        ))
        logger.info(LogColors.orchestratorBanner("A2A SCHEDULER ORCHESTRATION START (Streaming)"))
        
        try {
            // Step 1: Check location and weather
            emit(AppointmentProgress.CheckingLocationWeather)
            val locationWeatherStart = System.currentTimeMillis()
            val weatherRequest = LocationWeatherRequest(
                location = appointmentForm.location,
                appointmentTime = appointmentForm.appointmentTime
            )
            val weatherInfo = callLocationWeatherAgent(weatherRequest)
            val locationWeatherDuration = System.currentTimeMillis() - locationWeatherStart
            
            emit(AppointmentProgress.LocationWeatherComplete(weatherInfo, locationWeatherDuration))
            logger.info("${LogColors.ORCHESTRATOR} Weather check complete: ${weatherInfo.weather}")
            
            // Step 2: Book appointment
            emit(AppointmentProgress.BookingAppointment)
            val bookingStart = System.currentTimeMillis()
            val bookingRequest = AppointmentBookingRequest(
                appointmentForm = appointmentForm,
                weatherInfo = weatherInfo
            )
            val bookingResult = callAppointmentBookingAgent(bookingRequest)
            val bookingDuration = System.currentTimeMillis() - bookingStart
            
            val totalDuration = System.currentTimeMillis() - overallStart
            val finalResult = AppointmentResult(
                bookingMessage = bookingResult.confirmationMessage,
                weatherInfo = weatherInfo,
                appointment = appointmentForm
            )
            
            emit(AppointmentProgress.BookingComplete(finalResult, totalDuration))
            
            logger.info(LogColors.orchestratorBanner("A2A SCHEDULER ORCHESTRATION COMPLETE (Streaming)"))
            logger.info("${LogColors.ORCHESTRATOR} Total time: ${totalDuration}ms (Weather: ${locationWeatherDuration}ms, Booking: ${bookingDuration}ms)")
            
        } catch (e: Exception) {
            logger.error("${LogColors.ORCHESTRATOR} Error during booking: ${e.message}", e)
            emit(AppointmentProgress.Error(e.message ?: "Unknown error during appointment booking"))
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun callLocationWeatherAgent(request: LocationWeatherRequest): LocationWeatherResult {
        val transport = HttpJSONRPCClientTransport(url = endpoints.locationWeatherUrl)
        val agentCardResolver = UrlAgentCardResolver(
            baseUrl = endpoints.locationWeatherUrl.substringBefore(LOCATION_WEATHER_PATH),
            path = LOCATION_WEATHER_CARD_PATH
        )
        val client = A2AClient(transport = transport, agentCardResolver = agentCardResolver)

        try {
            client.connect()
            val contextId = Uuid.random().toString()

            val message = Message(
                messageId = Uuid.random().toString(),
                role = Role.User,
                parts = listOf(TextPart(json.encodeToString(LocationWeatherRequest.serializer(), request))),
                contextId = contextId,
                taskId = null
            )

            val responses = client.sendMessageStreaming(Request(MessageSendParams(message = message))).toList()
            return extractArtifact(responses, "location-weather")
        } finally {
            transport.close()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun callAppointmentBookingAgent(request: AppointmentBookingRequest): AppointmentBookingResult {
        val transport = HttpJSONRPCClientTransport(url = endpoints.appointmentBookingUrl)
        val agentCardResolver = UrlAgentCardResolver(
            baseUrl = endpoints.appointmentBookingUrl.substringBefore(APPOINTMENT_BOOKING_PATH),
            path = APPOINTMENT_BOOKING_CARD_PATH
        )
        val client = A2AClient(transport = transport, agentCardResolver = agentCardResolver)

        try {
            client.connect()
            val contextId = Uuid.random().toString()

            val message = Message(
                messageId = Uuid.random().toString(),
                role = Role.User,
                parts = listOf(TextPart(json.encodeToString(AppointmentBookingRequest.serializer(), request))),
                contextId = contextId,
                taskId = null
            )

            val responses = client.sendMessageStreaming(Request(MessageSendParams(message = message))).toList()
            return extractArtifact(responses, "appointment-booking")
        } finally {
            transport.close()
        }
    }

    private inline fun <reified T> extractArtifact(
        responses: List<ai.koog.a2a.transport.Response<Event>>,
        artifactId: String
    ): T {
        val artifacts = mutableMapOf<String, Artifact>()

        responses.forEach { response ->
            when (val event = response.data) {
                is Task -> event.artifacts?.forEach { artifacts[it.artifactId] = it }
                is TaskArtifactUpdateEvent -> {
                    if (event.append == true) {
                        val existing = artifacts[event.artifact.artifactId]
                        if (existing != null) {
                            artifacts[event.artifact.artifactId] = existing.copy(
                                parts = existing.parts + event.artifact.parts
                            )
                        } else {
                            artifacts[event.artifact.artifactId] = event.artifact
                        }
                    } else {
                        artifacts[event.artifact.artifactId] = event.artifact
                    }
                }
                else -> {}
            }
        }

        val artifact = artifacts[artifactId]
            ?: throw IllegalStateException("Artifact '$artifactId' not found in response")

        val textContent = artifact.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
        val jsonText = extractJsonFromText(textContent)
        return json.decodeFromString<T>(jsonText)
    }
    
    /**
     * Extracts a valid JSON object from text that may contain extra text before/after or multiple JSON objects.
     * Tries to find the last complete JSON object in the text.
     */
    private fun extractJsonFromText(text: String): String {
        // First, try to strip markdown code blocks if present
        val cleaned = text.trim().let { content ->
            when {
                content.startsWith("```json") -> content.removePrefix("```json").removeSuffix("```").trim()
                content.startsWith("```") -> content.removePrefix("```").removeSuffix("```").trim()
                content.startsWith("`") -> content.removePrefix("`").removeSuffix("`").trim()
                content.startsWith("~~~json") -> content.removePrefix("~~~json").removeSuffix("~~~").trim()
                content.startsWith("~~~") -> content.removePrefix("~~~").removeSuffix("~~~").trim()
                else -> content
            }
        }
        
        // Try to parse the cleaned text directly first
        try {
            json.parseToJsonElement(cleaned)
            return cleaned
        } catch (_: Exception) {
            // If direct parsing fails, try to extract JSON from the text
        }
        
        // Find all potential JSON objects by looking for balanced braces
        // We'll try to find the last complete JSON object
        val candidates = mutableListOf<Pair<Int, Int>>()
        var braceCount = 0
        var startIndex = -1
        
        for (i in cleaned.indices) {
            when (cleaned[i]) {
                '{' -> {
                    if (braceCount == 0) {
                        startIndex = i
                    }
                    braceCount++
                }
                '}' -> {
                    braceCount--
                    if (braceCount == 0 && startIndex != -1) {
                        candidates.add(startIndex to i)
                        startIndex = -1
                    }
                }
            }
        }
        
        // Try candidates from last to first (most recent JSON object)
        for ((start, end) in candidates.reversed()) {
            val candidate = cleaned.substring(start, end + 1)
            try {
                json.parseToJsonElement(candidate)
                return candidate
            } catch (_: Exception) {
                // Try next candidate
            }
        }
        
        // If we can't find a valid JSON object, try a simpler regex approach
        // Look for JSON objects that start with { and end with }
        val jsonPattern = Regex("""\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}""")
        val matches = jsonPattern.findAll(cleaned).toList()
        
        for (match in matches.reversed()) {
            try {
                val candidate = match.value
                json.parseToJsonElement(candidate)
                return candidate
            } catch (_: Exception) {
                // Try next match
            }
        }
        
        // If all else fails, return the cleaned text and let the decoder handle the error
        // This will provide a better error message
        return cleaned
    }
}

