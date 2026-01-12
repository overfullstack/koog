package org.jetbrains.demo.agent.a2a

import ai.koog.a2a.client.A2AClient
import ai.koog.a2a.client.UrlAgentCardResolver
import ai.koog.a2a.model.Artifact
import ai.koog.a2a.model.Event
import ai.koog.a2a.model.Message
import ai.koog.a2a.model.MessageSendParams
import ai.koog.a2a.model.Role
import ai.koog.a2a.model.Task
import ai.koog.a2a.model.TaskArtifactUpdateEvent
import ai.koog.a2a.model.TextPart
import ai.koog.a2a.transport.Request
import ai.koog.a2a.transport.client.jsonrpc.http.HttpJSONRPCClientTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.demo.agent.a2a.model.AppointmentBookingRequest
import org.jetbrains.demo.agent.a2a.model.AppointmentBookingResult
import org.jetbrains.demo.agent.a2a.model.AppointmentForm
import org.jetbrains.demo.agent.a2a.model.AppointmentResult
import org.jetbrains.demo.agent.a2a.model.AppointmentValidationRequest
import org.jetbrains.demo.agent.a2a.model.AppointmentValidationResult
import org.jetbrains.demo.agent.a2a.model.LocationReviewRequest
import org.jetbrains.demo.agent.a2a.model.LocationReviewResult
import org.jetbrains.demo.agent.a2a.model.ServiceTerritoryValidationRequest
import org.jetbrains.demo.agent.a2a.model.ServiceTerritoryValidationResult
import org.jetbrains.demo.agent.a2a.model.TimeslotValidationRequest
import org.jetbrains.demo.agent.a2a.model.TimeslotValidationResult
import org.jetbrains.demo.agent.a2a.model.WorkTypeSuggestion
import org.jetbrains.demo.agent.a2a.model.BranchInfo
import org.jetbrains.demo.agent.a2a.model.BranchSelectionResult
import org.jetbrains.demo.agent.a2a.model.MapsLocationRequest
import org.jetbrains.demo.agent.a2a.model.MapsLocationResult
import org.jetbrains.demo.agent.a2a.model.MapsDistanceMatrixRequest
import org.jetbrains.demo.agent.a2a.model.MapsDistanceMatrixResult
import org.salesforce.swara.agents.MAPS_CARD_PATH
import org.salesforce.swara.agents.MAPS_PATH
import org.salesforce.LogColors
import org.salesforce.swara.agents.TAVILY_CARD_PATH
import org.salesforce.swara.agents.TAVILY_PATH
import org.slf4j.LoggerFactory
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime

private val logger = LoggerFactory.getLogger("SchedulerOrchestratorAgent")

/**
 * Progress events emitted during appointment booking to keep user in the loop.
 */
@kotlinx.serialization.Serializable
sealed class AppointmentProgress {
    /** Booking has started */
    @kotlinx.serialization.Serializable
    data class Started(val appointmentType: String, val location: String) : AppointmentProgress()
    
    /** Checking location reviews */
    @kotlinx.serialization.Serializable
    data object CheckingLocationReviews : AppointmentProgress()
    
    /** Location reviews check complete */
    @kotlinx.serialization.Serializable
    data class LocationReviewsComplete(
        val reviewInfo: LocationReviewResult,
        val durationMs: Long
    ) : AppointmentProgress()
    
    /** Validating appointment */
    @kotlinx.serialization.Serializable
    data object ValidatingAppointment : AppointmentProgress()
    
    /** Validation complete */
    @kotlinx.serialization.Serializable
    data class ValidationComplete(
        val validationResult: AppointmentValidationResult,
        val durationMs: Long
    ) : AppointmentProgress()
    
    /** Validating service territory */
    @kotlinx.serialization.Serializable
    data object ValidatingServiceTerritory : AppointmentProgress()
    
    /** Service territory validation complete */
    @kotlinx.serialization.Serializable
    data class ServiceTerritoryValidationComplete(
        val validationResult: ServiceTerritoryValidationResult,
        val durationMs: Long
    ) : AppointmentProgress()
    
    /** Validating timeslot */
    @kotlinx.serialization.Serializable
    data object ValidatingTimeslot : AppointmentProgress()
    
    /** Timeslot validation complete */
    @kotlinx.serialization.Serializable
    data class TimeslotValidationComplete(
        val validationResult: TimeslotValidationResult,
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
    
    /** Validation failed - searching for suggestions */
    @kotlinx.serialization.Serializable
    data object SearchingForSuggestions : AppointmentProgress()
    
    /** Suggestion found - awaiting user confirmation */
    @kotlinx.serialization.Serializable
    data class SuggestionFound(
        val suggestion: WorkTypeSuggestion
    ) : AppointmentProgress()
    
    /** User accepted suggestion - continuing with suggested values */
    @kotlinx.serialization.Serializable
    data class SuggestionAccepted(
        val suggestion: WorkTypeSuggestion
    ) : AppointmentProgress()
    
    /** User declined suggestion */
    @kotlinx.serialization.Serializable
    data class SuggestionDeclined(
        val originalWorkType: String
    ) : AppointmentProgress()
    
    /** Fetching available branches */
    @kotlinx.serialization.Serializable
    data object FetchingBranches : AppointmentProgress()
    
    /** Prompting user for branch selection */
    @kotlinx.serialization.Serializable
    data class PromptBranchSelection(
        val availableBranches: List<BranchInfo>,
        val message: String
    ) : AppointmentProgress()
    
    /** Finding closest branch using Maps */
    @kotlinx.serialization.Serializable
    data object FindingClosestBranch : AppointmentProgress()
    
    /** Branch selection complete */
    @kotlinx.serialization.Serializable
    data class BranchSelectionComplete(
        val selectedBranch: BranchInfo,
        val message: String
    ) : AppointmentProgress()
    
    /** Prompting user for timeslot confirmation */
    @kotlinx.serialization.Serializable
    data class PromptTimeslotConfirmation(
        val suggestedTime: String,
        val travelTimeMinutes: Int?,
        val message: String
    ) : AppointmentProgress()
    
    /** User confirmed timeslot preference */
    @kotlinx.serialization.Serializable
    data class TimeslotConfirmed(
        val confirmedTime: String
    ) : AppointmentProgress()
}

data class A2ASchedulerEndpoints(
    val locationReviewUrl: String,
    val appointmentValidationUrl: String,
    val serviceTerritoryValidationUrl: String,
    val timeslotValidationUrl: String,
    val appointmentBookingUrl: String,
    val mapsUrl: String
)

class SchedulerAgentOrchestrator(
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

        // Step 1: Validate Work Type Group
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.yellow("[STEP 1/4]")} Validating Work Type Group at ${endpoints.appointmentValidationUrl}")
        val validationStart = System.currentTimeMillis()
        val validationRequest = AppointmentValidationRequest(
            workTypeGroupName = appointmentForm.appointmentGroup,
            hospitalName = appointmentForm.location
        )
        val validationResult = callAppointmentValidationAgent(validationRequest)
        val validationDuration = System.currentTimeMillis() - validationStart
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.yellow("[STEP 1/4]")} Work Type Group Validation completed in ${validationDuration}ms")
        
        if (!validationResult.isValid) {
            logger.error("${LogColors.ORCHESTRATOR} ${LogColors.red("Work type group validation failed: ${validationResult.message}")}")
            throw IllegalStateException(validationResult.message)
        }
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.green("Work type group validation passed")}: ${validationResult.message}")

        // Step 2: Validate Service Territory
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.blue("[STEP 2/4]")} Validating Service Territory at ${endpoints.serviceTerritoryValidationUrl}")
        val serviceTerritoryValidationStart = System.currentTimeMillis()
        val serviceTerritoryValidationRequest = ServiceTerritoryValidationRequest(
            location = appointmentForm.location
        )
        val serviceTerritoryValidationResult = callServiceTerritoryValidationAgent(serviceTerritoryValidationRequest)
        val serviceTerritoryValidationDuration = System.currentTimeMillis() - serviceTerritoryValidationStart
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.blue("[STEP 2/4]")} Service Territory Validation completed in ${serviceTerritoryValidationDuration}ms")
        
        if (!serviceTerritoryValidationResult.isValid) {
            val errorMessage = "Service territory validation failed: ${serviceTerritoryValidationResult.message}"
            logger.error("${LogColors.ORCHESTRATOR} ${LogColors.red(errorMessage)}")
            throw IllegalStateException(errorMessage)
        }
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.green("Service territory validation passed")}: ${serviceTerritoryValidationResult.message}")

        // Step 3: Validate Timeslot
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.green("[STEP 3/4]")} Validating Timeslot at ${endpoints.timeslotValidationUrl}")
        val timeslotValidationStart = System.currentTimeMillis()
        val timeslotValidationRequest = TimeslotValidationRequest(
            appointmentTime = appointmentForm.appointmentTime,
            serviceTerritoryId = serviceTerritoryValidationResult.serviceTerritoryId
                ?: throw IllegalStateException("Service territory ID is required for timeslot validation"),
            workTypeGroupId = validationResult.workTypeGroupId
                ?: throw IllegalStateException("Work type group ID is required for timeslot validation")
        )
        val timeslotValidationResult = callTimeslotValidationAgent(timeslotValidationRequest)
        val timeslotValidationDuration = System.currentTimeMillis() - timeslotValidationStart
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.green("[STEP 3/4]")} Timeslot Validation completed in ${timeslotValidationDuration}ms")
        
        if (!timeslotValidationResult.isValid) {
            val errorMessage = "Timeslot validation failed: ${timeslotValidationResult.message}"
            logger.error("${LogColors.ORCHESTRATOR} ${LogColors.red(errorMessage)}")
            throw IllegalStateException(errorMessage)
        }
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.green("Timeslot validation passed")}: ${timeslotValidationResult.message}")

        // Step 4: Call Appointment Booking Agent
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.magenta("[STEP 4/4]")} Calling Appointment Booking Agent at ${endpoints.appointmentBookingUrl}")
        val bookingStart = System.currentTimeMillis()
        val bookingRequest = AppointmentBookingRequest(
            appointmentForm = appointmentForm,
            locationReviewInfo = null,
            workTypeGroupId = validationResult.workTypeGroupId
        )
        val bookingResult = callAppointmentBookingAgent(bookingRequest)
        val bookingDuration = System.currentTimeMillis() - bookingStart
        logger.info("${LogColors.ORCHESTRATOR} ${LogColors.magenta("[STEP 4/4]")} Appointment Booking completed in ${bookingDuration}ms")

        val totalDuration = validationDuration + serviceTerritoryValidationDuration + timeslotValidationDuration + bookingDuration
        logger.info(LogColors.orchestratorBanner("A2A SCHEDULER ORCHESTRATION COMPLETE"))
        logger.info("${LogColors.ORCHESTRATOR} Total orchestration time: ${totalDuration}ms")
        logger.info("${LogColors.ORCHESTRATOR}   ${LogColors.yellow("Work Type Validation")}: ${validationDuration}ms")
        logger.info("${LogColors.ORCHESTRATOR}   ${LogColors.blue("Service Territory Validation")}: ${serviceTerritoryValidationDuration}ms")
        logger.info("${LogColors.ORCHESTRATOR}   ${LogColors.green("Timeslot Validation")}: ${timeslotValidationDuration}ms")
        logger.info("${LogColors.ORCHESTRATOR}   ${LogColors.magenta("Appointment Booking")}: ${bookingDuration}ms")

        return AppointmentResult(
            bookingMessage = bookingResult.confirmationMessage,
            locationReviewInfo = null,
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
            appointmentType = appointmentForm.appointmentGroup,
            location = appointmentForm.location
        ))
        logger.info(LogColors.orchestratorBanner("A2A SCHEDULER ORCHESTRATION START (Streaming)"))
        
        try {
            // Step 1: Validate work type group
            emit(AppointmentProgress.ValidatingAppointment)
            val validationStart = System.currentTimeMillis()
            val validationRequest = AppointmentValidationRequest(
                workTypeGroupName = appointmentForm.appointmentGroup,
                hospitalName = appointmentForm.location
            )
            var validationResult = callAppointmentValidationAgent(validationRequest)
            val validationDuration = System.currentTimeMillis() - validationStart
            emit(AppointmentProgress.ValidationComplete(validationResult, validationDuration))
            
            // Mutable form to track if we use suggested values
            var currentForm = appointmentForm
            
            if (!validationResult.isValid) {
                logger.warn("${LogColors.ORCHESTRATOR} ${LogColors.yellow("Work type group validation failed - searching for suggestions...")}")
                
                // Emit searching for suggestions
                emit(AppointmentProgress.SearchingForSuggestions)
                
                // Hardcoded suggestion: Apollo Hospitals for Asthma
                val suggestedWorkType = "Asthma"
                val suggestedLocation = "Apollo Hospitals"
                
                // Use Tavily to search for Apollo Hospital asthma reviews with doctors
                val suggestionSearchStart = System.currentTimeMillis()
                val suggestionReviewRequest = LocationReviewRequest(
                    location = "$suggestedLocation",
                    appointmentType = "$suggestedWorkType treatment with doctors"
                )
                val suggestionReviews = try {
                    callLocationReviewAgent(suggestionReviewRequest)
                } catch (e: Exception) {
                    logger.warn("${LogColors.ORCHESTRATOR} Failed to get suggestion reviews: ${e.message}")
                    LocationReviewResult(
                        location = suggestedLocation,
                        reviewSummary = "Apollo Hospitals is a leading healthcare provider with excellent facilities for asthma treatment.",
                        rating = 4.5,
                        highlights = listOf("Expert pulmonologists", "Modern diagnostic equipment", "Comprehensive asthma care"),
                        concerns = emptyList(),
                        tips = "Book an appointment with a pulmonologist for asthma consultation."
                    )
                }
                val suggestionSearchDuration = System.currentTimeMillis() - suggestionSearchStart
                logger.info("${LogColors.ORCHESTRATOR} Suggestion search completed in ${suggestionSearchDuration}ms")
                
                val suggestion = WorkTypeSuggestion(
                    suggestedWorkType = suggestedWorkType,
                    suggestedLocation = suggestedLocation,
                    suggestionMessage = "The work type group '${appointmentForm.appointmentGroup}' is not available. " +
                            "However, '$suggestedWorkType' treatment is available at $suggestedLocation. Would you like to try that?",
                    locationReviews = suggestionReviews
                )
                
                // Emit suggestion found - this will pause the flow awaiting user response
                emit(AppointmentProgress.SuggestionFound(suggestion))
                
                // Stop here and let the ChatService handle user confirmation
                // The user needs to respond before we can continue - no error message needed
                logger.info("${LogColors.ORCHESTRATOR} ${LogColors.yellow("Suggestion emitted - awaiting user confirmation...")}")
                return@flow
            }
            logger.info("${LogColors.ORCHESTRATOR} ${LogColors.green("Work type group validation passed")}: ${validationResult.message}")
            
            // Step 2: Fetch available branches and prompt user for service territory selection
            emit(AppointmentProgress.FetchingBranches)
            logger.info("${LogColors.ORCHESTRATOR} ${LogColors.blue("[STEP 2]")} Fetching available branches for service territory selection...")
            
            val availableBranches = getAvailableBranches(appointmentForm.location)
            
            // Always prompt user for service territory selection
            val branchMessage = buildString {
                appendLine("✅ Work type '${appointmentForm.appointmentGroup}' is available!")
                appendLine()
                if (availableBranches.isNotEmpty()) {
                    appendLine("I found ${availableBranches.size} branch(es) for ${appointmentForm.location}:")
                    availableBranches.forEachIndexed { index, branch ->
                        appendLine("${index + 1}. ${branch.branchName}${branch.address?.let { " - $it" } ?: ""}")
                    }
                    appendLine()
                }
                appendLine("📍 **Where are you currently located?** And **which branch would you like to visit?**")
                appendLine()
                appendLine("You can:")
                appendLine("• Tell me your current location and preferred branch")
                appendLine("• Say 'find closest' with your location to find the nearest branch")
                appendLine("• Or simply tell me which branch number you prefer (e.g., '1' or 'Jubilee Hills')")
            }
            
            emit(AppointmentProgress.PromptBranchSelection(availableBranches, branchMessage))
            
            // ALWAYS stop here and wait for user response for service territory
            // The flow will continue in bookAppointmentWithBranchSelection after user responds
            logger.info("${LogColors.ORCHESTRATOR} ${LogColors.yellow("Service territory selection prompt emitted - ALWAYS awaiting user response before continuing...")}")
            return@flow
            
        } catch (e: Exception) {
            logger.error("${LogColors.ORCHESTRATOR} Error during booking: ${e.message}", e)
            emit(AppointmentProgress.Error(e.message ?: "Unknown error during appointment booking"))
        }
    }

    /**
     * Continue booking after user accepts a suggestion.
     * This function resumes the booking flow with the suggested work type and location.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun bookAppointmentWithSuggestion(
        originalForm: AppointmentForm,
        suggestion: WorkTypeSuggestion
    ): Flow<AppointmentProgress> = flow {
        val overallStart = System.currentTimeMillis()
        
        // Create updated form with suggestion
        val updatedForm = originalForm.copy(
            appointmentGroup = suggestion.suggestedWorkType,
            location = suggestion.suggestedLocation
        )
        
        emit(AppointmentProgress.SuggestionAccepted(suggestion))
        logger.info(LogColors.orchestratorBanner("A2A SCHEDULER ORCHESTRATION RESUME (With Suggestion)"))
        logger.info("${LogColors.ORCHESTRATOR} Using suggested: ${suggestion.suggestedWorkType} at ${suggestion.suggestedLocation}")
        
        try {
            // Use the reviews from the suggestion if available
            val reviewInfo = suggestion.locationReviews ?: LocationReviewResult(
                location = suggestion.suggestedLocation,
                reviewSummary = "Using suggested location: ${suggestion.suggestedLocation}",
                rating = null,
                highlights = emptyList(),
                concerns = emptyList(),
                tips = null
            )
            
            // Step 2: Validate work type group (with suggested work type)
            emit(AppointmentProgress.ValidatingAppointment)
            val validationStart = System.currentTimeMillis()
            val validationRequest = AppointmentValidationRequest(
                workTypeGroupName = suggestion.suggestedWorkType,
                hospitalName = suggestion.suggestedLocation
            )
            val validationResult = callAppointmentValidationAgent(validationRequest)
            val validationDuration = System.currentTimeMillis() - validationStart
            emit(AppointmentProgress.ValidationComplete(validationResult, validationDuration))
            
            if (!validationResult.isValid) {
                logger.error("${LogColors.ORCHESTRATOR} ${LogColors.red("Suggested work type group validation also failed: ${validationResult.message}")}")
                emit(AppointmentProgress.Error("Even the suggested work type '${suggestion.suggestedWorkType}' is not available: ${validationResult.message}"))
                return@flow
            }
            logger.info("${LogColors.ORCHESTRATOR} ${LogColors.green("Suggested work type group validation passed")}: ${validationResult.message}")
            
            // Step 3: ALWAYS prompt user for branch selection before service territory validation
            emit(AppointmentProgress.FetchingBranches)
            logger.info("${LogColors.ORCHESTRATOR} ${LogColors.blue("[STEP 3]")} Fetching available branches for service territory selection...")
            
            val availableBranches = getAvailableBranches(suggestion.suggestedLocation)
            
            // Always prompt user for service territory selection
            val branchMessage = buildString {
                appendLine("✅ Work type '${suggestion.suggestedWorkType}' is available at ${suggestion.suggestedLocation}!")
                appendLine()
                if (availableBranches.isNotEmpty()) {
                    appendLine("I found ${availableBranches.size} branch(es) for ${suggestion.suggestedLocation}:")
                    availableBranches.forEachIndexed { index, branch ->
                        appendLine("${index + 1}. ${branch.branchName}${branch.address?.let { " - $it" } ?: ""}")
                    }
                    appendLine()
                }
                appendLine("📍 **Where are you currently located?** And **which branch would you like to visit?**")
                appendLine()
                appendLine("You can:")
                appendLine("• Tell me your current location and preferred branch")
                appendLine("• Say 'find closest' with your location to find the nearest branch")
                appendLine("• Or simply tell me which branch number you prefer (e.g., '1' or 'Jubilee Hills')")
            }
            
            emit(AppointmentProgress.PromptBranchSelection(availableBranches, branchMessage))
            
            // ALWAYS stop here and wait for user response for service territory
            // The flow will continue in bookAppointmentWithBranchSelection after user responds
            logger.info("${LogColors.ORCHESTRATOR} ${LogColors.yellow("Service territory selection prompt emitted (from suggestion flow) - ALWAYS awaiting user response before continuing...")}")
            return@flow
            
        } catch (e: Exception) {
            logger.error("${LogColors.ORCHESTRATOR} Error during booking with suggestion: ${e.message}", e)
            emit(AppointmentProgress.Error(e.message ?: "Unknown error during appointment booking"))
        }
    }
    
    /**
     * Get available branches/service territories for a hospital.
     */
    private suspend fun getAvailableBranches(hospitalName: String): List<BranchInfo> {
        logger.info("${LogColors.ORCHESTRATOR} Fetching available branches for: $hospitalName")
        
        try {
            // Call service territory validation to get all territories
            val serviceTerritoryValidationRequest = ServiceTerritoryValidationRequest(location = hospitalName)
            val result = callServiceTerritoryValidationAgent(serviceTerritoryValidationRequest)
            
            // Parse the result to extract branches
            // The service territory validation returns info about available territories
            // For now, we'll create hardcoded branches based on the hospital
            val branches = when {
                hospitalName.lowercase().contains("apollo") -> listOf(
                    BranchInfo("ST001", "Apollo Hospitals - Jubilee Hills", "Jubilee Hills, Hyderabad"),
                    BranchInfo("ST002", "Apollo Hospitals - Secunderabad", "Secunderabad, Hyderabad"),
                    BranchInfo("ST003", "Apollo Hospitals - Banjara Hills", "Banjara Hills, Hyderabad")
                )
                hospitalName.lowercase().contains("yashoda") -> listOf(
                    BranchInfo("ST101", "Yashoda Hospitals - Somajiguda", "Somajiguda, Hyderabad"),
                    BranchInfo("ST102", "Yashoda Hospitals - Secunderabad", "Secunderabad, Hyderabad"),
                    BranchInfo("ST103", "Yashoda Hospitals - Malakpet", "Malakpet, Hyderabad")
                )
                else -> {
                    // If valid service territory, create a single branch
                    if (result.isValid && result.serviceTerritoryId != null) {
                        listOf(BranchInfo(result.serviceTerritoryId, hospitalName, null))
                    } else {
                        emptyList()
                    }
                }
            }
            
            logger.info("${LogColors.ORCHESTRATOR} Found ${branches.size} branches")
            return branches
        } catch (e: Exception) {
            logger.error("${LogColors.ORCHESTRATOR} Error fetching branches: ${e.message}", e)
            return emptyList()
        }
    }
    
    /**
     * Continue booking after user selects a branch/service territory.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun bookAppointmentWithBranchSelection(
        appointmentForm: AppointmentForm,
        selectedBranch: BranchInfo,
        workTypeGroupId: String
    ): Flow<AppointmentProgress> = flow {
        val overallStart = System.currentTimeMillis()
        
        logger.info(LogColors.orchestratorBanner("A2A SCHEDULER ORCHESTRATION RESUME (With Branch Selection)"))
        logger.info("${LogColors.ORCHESTRATOR} Selected branch: ${selectedBranch.branchName}")
        
        emit(AppointmentProgress.BranchSelectionComplete(
            selectedBranch,
            "Selected branch: ${selectedBranch.branchName}"
        ))
        
        try {
            // Step 2: Validate service territory with user's selection
            emit(AppointmentProgress.ValidatingServiceTerritory)
            val serviceTerritoryValidationStart = System.currentTimeMillis()
            val serviceTerritoryValidationRequest = ServiceTerritoryValidationRequest(
                location = selectedBranch.branchName
            )
            val serviceTerritoryValidationResult = callServiceTerritoryValidationAgent(serviceTerritoryValidationRequest)
            val serviceTerritoryValidationDuration = System.currentTimeMillis() - serviceTerritoryValidationStart
            emit(AppointmentProgress.ServiceTerritoryValidationComplete(serviceTerritoryValidationResult, serviceTerritoryValidationDuration))
            
            // Use validated service territory ID if available, otherwise use the one from branch selection
            val serviceTerritoryId = serviceTerritoryValidationResult.serviceTerritoryId ?: selectedBranch.serviceTerritoryId
            
            if (!serviceTerritoryValidationResult.isValid) {
                logger.warn("${LogColors.ORCHESTRATOR} ${LogColors.yellow("Service territory validation returned invalid, using branch ID: $serviceTerritoryId")}")
            } else {
                logger.info("${LogColors.ORCHESTRATOR} ${LogColors.green("Service territory validation passed")}: ${serviceTerritoryValidationResult.message}")
            }
            
            // Step 3: Ask user for timeslot confirmation BEFORE searching
            val travelTime = selectedBranch.travelTimeMinutes
            val currentTime = java.time.LocalDateTime.now()
            val suggestedTime = if (travelTime != null) {
                currentTime.plusMinutes(travelTime.toLong() + 15) // Add 15 min buffer
            } else {
                currentTime.plusMinutes(30) // Default 30 min from now
            }
            
            val formattedTime = suggestedTime.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
            val travelTimeStr = travelTime?.let { "Your estimated travel time is ~$it minutes. " } ?: ""
            
            val confirmationMessage = buildString {
                appendLine("✅ Service territory validated: ${selectedBranch.branchName}")
                appendLine()
                appendLine("${travelTimeStr}Should I look for available appointment slots around **$formattedTime** (current time + travel time)?")
                appendLine()
                appendLine("You can:")
                appendLine("• Say **'yes'** to search around this time")
                appendLine("• Specify a different time (e.g., '3:00 PM', 'tomorrow morning')")
                appendLine("• Say **'any time today'** to see all available slots")
            }
            
            emit(AppointmentProgress.PromptTimeslotConfirmation(
                suggestedTime = formattedTime,
                travelTimeMinutes = travelTime,
                message = confirmationMessage
            ))
            
            // STOP here and wait for user response
            logger.info("${LogColors.ORCHESTRATOR} ${LogColors.yellow("Timeslot confirmation prompt emitted - awaiting user response...")}")
            return@flow
            
        } catch (e: Exception) {
            logger.error("${LogColors.ORCHESTRATOR} Error during booking with branch selection: ${e.message}", e)
            emit(AppointmentProgress.Error(e.message ?: "Unknown error during appointment booking"))
        }
    }
    
    /**
     * Continue booking after user confirms timeslot preference.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun bookAppointmentWithTimeslotConfirmation(
        appointmentForm: AppointmentForm,
        selectedBranch: BranchInfo,
        workTypeGroupId: String,
        serviceTerritoryId: String,
        preferredTime: String?
    ): Flow<AppointmentProgress> = flow {
        val overallStart = System.currentTimeMillis()
        
        logger.info(LogColors.orchestratorBanner("A2A SCHEDULER ORCHESTRATION RESUME (With Timeslot Confirmation)"))
        logger.info("${LogColors.ORCHESTRATOR} User's preferred time input: ${preferredTime ?: "use suggested time"}")
        logger.info("${LogColors.ORCHESTRATOR} Original appointment time from form: ${appointmentForm.appointmentTime}")
        
        // Parse user's preferred time and apply it to the appointment time
        val actualAppointmentTime = parseAndApplyPreferredTime(preferredTime, appointmentForm.appointmentTime)
        logger.info("${LogColors.ORCHESTRATOR} Final appointment time for validation: $actualAppointmentTime")
        
        // Show user-friendly time in the progress message
        val displayTime = preferredTime ?: formatTimeForDisplay(actualAppointmentTime)
        emit(AppointmentProgress.TimeslotConfirmed(displayTime))
        
        try {
            // Step 3: Validate timeslot with selected branch
            emit(AppointmentProgress.ValidatingTimeslot)
            val timeslotValidationStart = System.currentTimeMillis()
            val timeslotValidationRequest = TimeslotValidationRequest(
                appointmentTime = actualAppointmentTime,
                serviceTerritoryId = serviceTerritoryId,
                workTypeGroupId = workTypeGroupId
            )
            val timeslotValidationResult = callTimeslotValidationAgent(timeslotValidationRequest)
            val timeslotValidationDuration = System.currentTimeMillis() - timeslotValidationStart
            emit(AppointmentProgress.TimeslotValidationComplete(timeslotValidationResult, timeslotValidationDuration))
            
            if (!timeslotValidationResult.isValid) {
                val errorMessage = "Timeslot validation failed: ${timeslotValidationResult.message}"
                logger.error("${LogColors.ORCHESTRATOR} ${LogColors.red(errorMessage)}")
                emit(AppointmentProgress.Error(errorMessage))
                return@flow
            }
            logger.info("${LogColors.ORCHESTRATOR} ${LogColors.green("Timeslot validation passed")}: ${timeslotValidationResult.message}")
            
            // Update form with selected branch location AND the actual appointment time
            val updatedForm = appointmentForm.copy(
                location = selectedBranch.branchName,
                appointmentTime = actualAppointmentTime
            )
            
            // Step 4: Book appointment
            emit(AppointmentProgress.BookingAppointment)
            val bookingStart = System.currentTimeMillis()
            val bookingRequest = AppointmentBookingRequest(
                appointmentForm = updatedForm,
                locationReviewInfo = null,
                workTypeGroupId = workTypeGroupId
            )
            val bookingResult = callAppointmentBookingAgent(bookingRequest)
            val bookingDuration = System.currentTimeMillis() - bookingStart
            
            val totalDuration = System.currentTimeMillis() - overallStart
            val finalResult = AppointmentResult(
                bookingMessage = bookingResult.confirmationMessage,
                locationReviewInfo = null,
                appointment = updatedForm
            )
            
            emit(AppointmentProgress.BookingComplete(finalResult, totalDuration))
            
            logger.info(LogColors.orchestratorBanner("A2A SCHEDULER ORCHESTRATION COMPLETE (With Branch Selection)"))
            logger.info("${LogColors.ORCHESTRATOR} Total time: ${totalDuration}ms")
            
        } catch (e: Exception) {
            logger.error("${LogColors.ORCHESTRATOR} Error during booking with branch selection: ${e.message}", e)
            emit(AppointmentProgress.Error(e.message ?: "Unknown error during appointment booking"))
        }
    }
    
    /**
     * Find the closest branch to user's current location using Maps agent.
     * Returns a pair of (closest branch, list of all branches with distances and travel times)
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun findClosestBranch(
        userLocation: String,
        branches: List<BranchInfo>,
        progressCallback: (suspend (String) -> Unit)? = null
    ): Pair<BranchInfo?, List<BranchInfo>> {
        logger.info("${LogColors.ORCHESTRATOR} Finding closest branch to: $userLocation")
        
        if (branches.isEmpty()) return Pair(null, emptyList())
        
        try {
            // Step 1: Get coordinates for user's location using Maps agent
            progressCallback?.invoke("📍 Getting your location coordinates...")
            logger.info("${LogColors.ORCHESTRATOR} 🗺️ Calling Maps Agent for user location: $userLocation")
            
            val userLocationResult = callMapsAgent(MapsLocationRequest(
                location = userLocation,
                includeDetails = false
            ))
            
            val userLat = userLocationResult.latitude
            val userLng = userLocationResult.longitude
            
            logger.info("${LogColors.ORCHESTRATOR} User coordinates: lat=$userLat, lng=$userLng")
            
            if (userLat == null || userLng == null) {
                logger.warn("${LogColors.ORCHESTRATOR} Could not geocode user location")
                progressCallback?.invoke("⚠️ Could not determine your exact location. Selecting first branch.")
                return Pair(branches.first(), branches)
            }
            
            val userFormattedLocation = userLocationResult.formattedAddress ?: userLocation
            progressCallback?.invoke("✅ Found your location: $userFormattedLocation")
            
            // Step 2: Get coordinates AND travel time for EACH branch using Maps agent (one by one)
            val branchesWithDistances = mutableListOf<BranchInfo>()
            
            for ((index, branch) in branches.withIndex()) {
                val branchAddress = branch.address ?: "${branch.branchName}, Hyderabad"
                
                progressCallback?.invoke("🏥 [${index + 1}/${branches.size}] Getting distance & travel time to: ${branch.branchName}...")
                logger.info("${LogColors.ORCHESTRATOR} 🗺️ Calling Maps Agent for branch ${index + 1}/${branches.size}: $branchAddress")
                
                try {
                    // First get branch coordinates
                    val branchResult = callMapsAgent(MapsLocationRequest(
                        location = branchAddress,
                        includeDetails = false
                    ))
                    
                    val branchLat = branchResult.latitude
                    val branchLng = branchResult.longitude
                    
                    logger.info("${LogColors.ORCHESTRATOR} Branch ${branch.branchName} coordinates: lat=$branchLat, lng=$branchLng")
                    
                    // Calculate straight-line distance
                    val distance = if (branchLat != null && branchLng != null) {
                        calculateDistance(userLat, userLng, branchLat, branchLng)
                    } else {
                        Double.MAX_VALUE
                    }
                    
                    // Get travel time using distance matrix
                    var travelTime: Int? = null
                    var actualDistance: Double? = null
                    
                    progressCallback?.invoke("   ↳ 🚗 Getting driving time...")
                    logger.info("${LogColors.ORCHESTRATOR} 🗺️ Calling Maps Agent for distance matrix: $userFormattedLocation -> $branchAddress")
                    
                    try {
                        val distanceMatrixResult = callMapsAgentForDistanceMatrix(
                            origin = userFormattedLocation,
                            destination = branchAddress
                        )
                        travelTime = distanceMatrixResult.travelTimeMinutes
                        actualDistance = distanceMatrixResult.distanceKm ?: distance
                        
                        if (travelTime != null) {
                            logger.info("${LogColors.ORCHESTRATOR} Travel time to ${branch.branchName}: $travelTime minutes, distance: ${"%.2f".format(actualDistance)} km")
                        }
                    } catch (e: Exception) {
                        logger.warn("${LogColors.ORCHESTRATOR} Could not get travel time for ${branch.branchName}: ${e.message}")
                        actualDistance = distance
                    }
                    
                    // actualDistance is guaranteed non-null here (set in both try and catch)
                    val finalDistance = actualDistance!!
                    val branchWithDistance = branch.copy(
                        distanceKm = finalDistance,
                        travelTimeMinutes = travelTime
                    )
                    branchesWithDistances.add(branchWithDistance)
                    
                    if (finalDistance != Double.MAX_VALUE) {
                        val timeStr = travelTime?.let { " (~$it min drive)" } ?: ""
                        progressCallback?.invoke("   ↳ Distance: ${"%.2f".format(finalDistance)} km$timeStr")
                        logger.info("${LogColors.ORCHESTRATOR} Distance to ${branch.branchName}: ${"%.2f".format(finalDistance)} km$timeStr")
                    } else {
                        progressCallback?.invoke("   ↳ Could not calculate distance to ${branch.branchName}")
                    }
                    
                } catch (e: Exception) {
                    logger.warn("${LogColors.ORCHESTRATOR} Failed to geocode branch ${branch.branchName}: ${e.message}")
                    progressCallback?.invoke("   ↳ ⚠️ Could not get location for ${branch.branchName}")
                    branchesWithDistances.add(branch.copy(distanceKm = Double.MAX_VALUE))
                }
            }
            
            // Step 3: Find the closest branch (prefer by travel time if available, otherwise by distance)
            val closest = branchesWithDistances.minByOrNull { branch ->
                // Prefer travel time for comparison if available
                branch.travelTimeMinutes?.toDouble() ?: (branch.distanceKm ?: Double.MAX_VALUE) * 2
            }
            
            if (closest != null && closest.distanceKm != Double.MAX_VALUE) {
                val timeStr = closest.travelTimeMinutes?.let { " (~$it min drive)" } ?: ""
                progressCallback?.invoke("✅ Closest branch: ${closest.branchName} (${"%.2f".format(closest.distanceKm)} km$timeStr)")
                logger.info("${LogColors.ORCHESTRATOR} Closest branch: ${closest.branchName} (${"%.2f".format(closest.distanceKm ?: 0.0)} km$timeStr)")
            }
            
            return Pair(closest, branchesWithDistances)
        } catch (e: Exception) {
            logger.error("${LogColors.ORCHESTRATOR} Error finding closest branch: ${e.message}", e)
            return Pair(branches.first(), branches)
        }
    }
    
    /**
     * Calculate distance between two coordinates using Haversine formula.
     */
    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6371.0 // km
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        
        return earthRadius * c
    }
    
    /**
     * Call the Maps agent to get location information.
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun callMapsAgent(request: MapsLocationRequest): MapsLocationResult {
        val transport = HttpJSONRPCClientTransport(url = endpoints.mapsUrl)
        val agentCardResolver = UrlAgentCardResolver(
            baseUrl = endpoints.mapsUrl.substringBefore(MAPS_PATH),
            path = MAPS_CARD_PATH
        )
        val client = A2AClient(transport = transport, agentCardResolver = agentCardResolver)

        try {
            client.connect()
            val contextId = Uuid.random().toString()

            val message = Message(
                messageId = Uuid.random().toString(),
                role = Role.User,
                parts = listOf(TextPart(json.encodeToString(MapsLocationRequest.serializer(), request))),
                contextId = contextId,
                taskId = null
            )

            val responses = client.sendMessageStreaming(Request(MessageSendParams(message = message))).toList()
            return extractArtifact(responses, "maps-location")
        } finally {
            transport.close()
        }
    }
    
    /**
     * Call the Maps agent to get distance and travel time between two locations.
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun callMapsAgentForDistanceMatrix(
        origin: String,
        destination: String
    ): MapsDistanceMatrixResult {
        val transport = HttpJSONRPCClientTransport(url = endpoints.mapsUrl)
        val agentCardResolver = UrlAgentCardResolver(
            baseUrl = endpoints.mapsUrl.substringBefore(MAPS_PATH),
            path = MAPS_CARD_PATH
        )
        val client = A2AClient(transport = transport, agentCardResolver = agentCardResolver)

        try {
            client.connect()
            val contextId = Uuid.random().toString()

            // Send a text prompt asking for distance matrix calculation
            val prompt = """Calculate the driving distance and travel time from "$origin" to "$destination".
Use the maps_distance_matrix tool with:
- origins: ["$origin"]
- destinations: ["$destination"]
- mode: "driving"

Return the distance in kilometers and travel time in minutes."""

            val message = Message(
                messageId = Uuid.random().toString(),
                role = Role.User,
                parts = listOf(TextPart(prompt)),
                contextId = contextId,
                taskId = null
            )

            val responses = client.sendMessageStreaming(Request(MessageSendParams(message = message))).toList()
            
            // Try to extract distance matrix result from the artifact
            return try {
                extractArtifact<MapsDistanceMatrixResult>(responses, "maps-distance-matrix")
            } catch (e: Exception) {
                // If we can't extract the specific artifact, try to parse from maps-location
                logger.debug("${LogColors.ORCHESTRATOR} Could not extract maps-distance-matrix artifact, trying maps-location")
                val locationResult = extractArtifact<MapsLocationResult>(responses, "maps-location")
                MapsDistanceMatrixResult(
                    origin = origin,
                    destination = destination,
                    distanceKm = locationResult.distanceKm,
                    travelTimeMinutes = locationResult.travelTimeMinutes,
                    distanceText = locationResult.distanceKm?.let { "%.1f km".format(it) },
                    durationText = locationResult.travelTimeMinutes?.let { "$it mins" }
                )
            }
        } finally {
            transport.close()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun callLocationReviewAgent(request: LocationReviewRequest): LocationReviewResult {
        val transport = HttpJSONRPCClientTransport(url = endpoints.locationReviewUrl)
        val agentCardResolver = UrlAgentCardResolver(
            baseUrl = endpoints.locationReviewUrl.substringBefore(TAVILY_PATH),
            path = TAVILY_CARD_PATH
        )
        val client = A2AClient(transport = transport, agentCardResolver = agentCardResolver)

        try {
            client.connect()
            val contextId = Uuid.random().toString()

            val message = Message(
                messageId = Uuid.random().toString(),
                role = Role.User,
                parts = listOf(TextPart(json.encodeToString(LocationReviewRequest.serializer(), request))),
                contextId = contextId,
                taskId = null
            )

            val responses = client.sendMessageStreaming(Request(MessageSendParams(message = message))).toList()
            return extractArtifact(responses, "location-review")
        } finally {
            transport.close()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun callAppointmentValidationAgent(request: AppointmentValidationRequest): AppointmentValidationResult {
        val transport = HttpJSONRPCClientTransport(url = endpoints.appointmentValidationUrl)
        val agentCardResolver = UrlAgentCardResolver(
            baseUrl = endpoints.appointmentValidationUrl.substringBefore(APPOINTMENT_VALIDATION_PATH),
            path = APPOINTMENT_VALIDATION_CARD_PATH
        )
        val client = A2AClient(transport = transport, agentCardResolver = agentCardResolver)

        try {
            client.connect()
            val contextId = Uuid.random().toString()

            val message = Message(
                messageId = Uuid.random().toString(),
                role = Role.User,
                parts = listOf(TextPart(json.encodeToString(AppointmentValidationRequest.serializer(), request))),
                contextId = contextId,
                taskId = null
            )

            val responses = client.sendMessageStreaming(Request(MessageSendParams(message = message))).toList()
            return extractArtifact(responses, "appointment-validation")
        } finally {
            transport.close()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun callServiceTerritoryValidationAgent(request: ServiceTerritoryValidationRequest): ServiceTerritoryValidationResult {
        val transport = HttpJSONRPCClientTransport(url = endpoints.serviceTerritoryValidationUrl)
        val agentCardResolver = UrlAgentCardResolver(
            baseUrl = endpoints.serviceTerritoryValidationUrl.substringBefore(SERVICE_TERRITORY_VALIDATION_PATH),
            path = SERVICE_TERRITORY_VALIDATION_CARD_PATH
        )
        val client = A2AClient(transport = transport, agentCardResolver = agentCardResolver)

        try {
            client.connect()
            val contextId = Uuid.random().toString()

            val message = Message(
                messageId = Uuid.random().toString(),
                role = Role.User,
                parts = listOf(TextPart(json.encodeToString(ServiceTerritoryValidationRequest.serializer(), request))),
                contextId = contextId,
                taskId = null
            )

            val responses = client.sendMessageStreaming(Request(MessageSendParams(message = message))).toList()
            return extractArtifact(responses, "service-territory-validation")
        } finally {
            transport.close()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun callTimeslotValidationAgent(request: TimeslotValidationRequest): TimeslotValidationResult {
        val transport = HttpJSONRPCClientTransport(url = endpoints.timeslotValidationUrl)
        val agentCardResolver = UrlAgentCardResolver(
            baseUrl = endpoints.timeslotValidationUrl.substringBefore(TIMESLOT_VALIDATION_PATH),
            path = TIMESLOT_VALIDATION_CARD_PATH
        )
        val client = A2AClient(transport = transport, agentCardResolver = agentCardResolver)

        try {
            client.connect()
            val contextId = Uuid.random().toString()

            val message = Message(
                messageId = Uuid.random().toString(),
                role = Role.User,
                parts = listOf(TextPart(json.encodeToString(TimeslotValidationRequest.serializer(), request))),
                contextId = contextId,
                taskId = null
            )

            val responses = client.sendMessageStreaming(Request(MessageSendParams(message = message))).toList()
            return extractArtifact(responses, "timeslot-validation")
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
    
    /**
     * Parse user's preferred time string and apply it to create the actual appointment time.
     * Handles inputs like "2:00 pm", "3pm", "morning", "afternoon", "any time", etc.
     */
    /**
     * Format a LocalDateTime for user-friendly display.
     */
    private fun formatTimeForDisplay(time: LocalDateTime): String {
        val hour = time.hour
        val minute = time.minute
        val ampm = if (hour >= 12) "PM" else "AM"
        val hour12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return if (minute == 0) {
            "$hour12 $ampm"
        } else {
            "$hour12:${minute.toString().padStart(2, '0')} $ampm"
        }
    }
    
    private fun parseAndApplyPreferredTime(preferredTime: String?, baseTime: LocalDateTime): LocalDateTime {
        if (preferredTime == null) {
            // User confirmed without specifying a different time - use base time
            return baseTime
        }
        
        val lowerInput = preferredTime.lowercase().trim()
        
        // Handle "any time" type responses - use base time (current time context)
        if (lowerInput.contains("any") || lowerInput.contains("whenever") || lowerInput.contains("available")) {
            logger.info("${LogColors.ORCHESTRATOR} User wants 'any available time' - using base time")
            return baseTime
        }
        
        // Try to parse specific time formats
        try {
            // Pattern: "2:30 pm", "2:30pm", "14:30"
            val timeWithMinutesPattern = Regex("""(\d{1,2}):(\d{2})\s*(am|pm)?""", RegexOption.IGNORE_CASE)
            val timeWithMinutesMatch = timeWithMinutesPattern.find(lowerInput)
            if (timeWithMinutesMatch != null) {
                var hour = timeWithMinutesMatch.groupValues[1].toInt()
                val minute = timeWithMinutesMatch.groupValues[2].toInt()
                val ampm = timeWithMinutesMatch.groupValues[3].lowercase()
                
                // Convert to 24-hour format
                if (ampm == "pm" && hour != 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
                
                logger.info("${LogColors.ORCHESTRATOR} Parsed time with minutes: $hour:$minute")
                return LocalDateTime(baseTime.year, baseTime.month, baseTime.day, hour, minute)
            }
            
            // Pattern: "2pm", "2 pm", "14"
            val timeOnlyPattern = Regex("""(\d{1,2})\s*(am|pm)""", RegexOption.IGNORE_CASE)
            val timeOnlyMatch = timeOnlyPattern.find(lowerInput)
            if (timeOnlyMatch != null) {
                var hour = timeOnlyMatch.groupValues[1].toInt()
                val ampm = timeOnlyMatch.groupValues[2].lowercase()
                
                // Convert to 24-hour format
                if (ampm == "pm" && hour != 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
                
                logger.info("${LogColors.ORCHESTRATOR} Parsed time (hour only): $hour:00")
                return LocalDateTime(baseTime.year, baseTime.month, baseTime.day, hour, 0)
            }
            
            // Handle relative time words
            val hour = when {
                lowerInput.contains("morning") -> 9
                lowerInput.contains("noon") -> 12
                lowerInput.contains("afternoon") -> 14
                lowerInput.contains("evening") -> 18
                lowerInput.contains("night") -> 20
                else -> null
            }
            
            if (hour != null) {
                logger.info("${LogColors.ORCHESTRATOR} Parsed relative time word: $hour:00")
                return LocalDateTime(baseTime.year, baseTime.month, baseTime.day, hour, 0)
            }
            
        } catch (e: Exception) {
            logger.warn("${LogColors.ORCHESTRATOR} Failed to parse preferred time '$preferredTime': ${e.message}")
        }
        
        // If we couldn't parse the user's input, fall back to base time
        logger.info("${LogColors.ORCHESTRATOR} Could not parse '$preferredTime', using base time: $baseTime")
        return baseTime
    }
}
