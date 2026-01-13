package org.salesforce.swara.chat

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.executeStructured
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.demo.agent.a2a.AppointmentProgress
import org.jetbrains.demo.agent.a2a.SchedulerAgentOrchestrator
import org.jetbrains.demo.agent.a2a.model.*
import org.jetbrains.demo.agent.a2a.model.BranchInfo
import org.salesforce.LogColors
import org.salesforce.travel.LLM_MODEL
import org.salesforce.travel.agent.a2a.ConversationState
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val logger = LoggerFactory.getLogger("ChatService")

/**
 * User intent classification for semantic understanding of user messages.
 * Used by LLM to classify what the user wants to do.
 */
@Serializable
enum class UserIntent {
    /** User wants to book an appointment or confirms they're ready to book */
    START_BOOKING,
    /** User is providing appointment details (type, location, time) */
    PROVIDE_APPOINTMENT_DETAILS,
    /** User confirms the collected details are correct */
    CONFIRM_DETAILS,
    /** User wants to modify or correct previously provided details */
    MODIFY_DETAILS,
    /** User is greeting or making small talk */
    GREETING,
    /** User is asking a question about appointments or the service */
    ASK_QUESTION,
    /** User accepts a suggestion (responds yes/ok/sure to a suggestion) */
    ACCEPT_SUGGESTION,
    /** User declines a suggestion (responds no/cancel to a suggestion) */
    DECLINE_SUGGESTION,
    /** Intent is unclear or doesn't fit other categories */
    OTHER
}

@Serializable
data class IntentClassification(
    val intent: UserIntent,
    val confidence: String,
    val extractedDetails: ExtractedAppointmentDetails? = null
)

@Serializable
data class ExtractedAppointmentDetails(
    val appointmentType: String? = null,
    val location: String? = null,
    val time: String? = null,
    val date: String? = null,
    val patientName: String? = null,
    val notes: String? = null
)

@Serializable
enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}

@Serializable
enum class MessageType {
    TEXT, THINKING, TOOL_USE, TOOL_RESULT, PLAN_RESULT, APPOINTMENT_RESULT, ERROR
}

@Serializable
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val type: MessageType = MessageType.TEXT,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String>? = null
)

/**
 * Tracks partially collected appointment information during negotiation.
 * Fields are nullable - once all required fields are filled, we can create an AppointmentForm.
 */
@Serializable
data class PartialAppointmentInfo(
    val appointmentGroup: String? = null,
    val location: String? = null,
    val timeOfDay: String? = null,
    val date: String? = null,
    val patientName: String? = null,
    val notes: String? = null
) {
    fun missingFields(): List<String> = buildList {
        if (appointmentGroup == null) add("appointment type")
        if (location == null) add("location")
        if (timeOfDay == null && date == null) add("date and time")
    }
    
    fun isComplete(): Boolean = appointmentGroup != null && location != null && (timeOfDay != null || date != null)
    
    fun summary(): String = buildString {
        appointmentGroup?.let { append("**Type:** $it") }
        location?.let { append("\n**Location:** $it") }
        date?.let { append("\n**Date:** $it") }
        timeOfDay?.let { append("\n**Time:** $it") }
        patientName?.let { append("\n**Patient:** $it") }
        notes?.let { append("\n**Notes:** $it") }
    }
}

@Serializable
data class ChatSession(
    val sessionId: String,
    val userId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val appointmentForm: AppointmentForm? = null,
    val partialAppointment: PartialAppointmentInfo? = null,
    val appointmentResult: AppointmentResult? = null,
    val conversationState: ConversationState = ConversationState.GREETING,
    val pendingSuggestion: WorkTypeSuggestion? = null,
    val pendingBranchSelection: List<BranchInfo>? = null,
    val workTypeGroupId: String? = null,
    val serviceTerritoryId: String? = null,
    val selectedBranch: BranchInfo? = null,
    val suggestedTimeslot: String? = null,
    val pendingTimeslotId: String? = null, // For booking confirmation
    val pendingSelectedTimeslot: String? = null, // Display string for booking confirmation
    val pendingTimeslotInfo: TimeslotInfo? = null, // Full timeslot info for booking
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class ChatRequest(
    val sessionId: String? = null,
    val userId: String? = null,
    val message: String,
    val appointmentForm: AppointmentForm? = null
)

@Serializable
data class ChatStreamEvent(
    val sessionId: String,
    val type: String,
    val content: String? = null,
    val message: ChatMessage? = null,
    val done: Boolean = false,
    val appointmentResult: AppointmentResult? = null,
    val awaitingResponse: Boolean = false
)

class ChatService(
    private val orchestrator: SchedulerAgentOrchestrator,
    private val promptExecutor: PromptExecutor,
    private val model: LLModel = LLM_MODEL
) {
    private val sessions = ConcurrentHashMap<String, ChatSession>()
    
    /**
     * Use LLM to semantically classify user intent and extract appointment details.
     */
    private suspend fun classifyIntent(
        message: String,
        conversationState: ConversationState,
        hasAppointmentForm: Boolean,
        hasAppointmentResult: Boolean
    ): IntentClassification {
        val contextInfo = buildString {
            appendLine("Current conversation state: $conversationState")
            appendLine("Has appointment details collected: $hasAppointmentForm")
            appendLine("Has existing appointment booking: $hasAppointmentResult")
        }
        
        val classificationPrompt = prompt("intent-classifier") {
            system {
                +"""
                You are an intent classifier for a hospital appointment booking assistant.
                Analyze the user's message and classify their intent.
                
                Context:
                $contextInfo
                
                Available intents:
                - START_BOOKING: User wants to book an appointment or confirms ready to book (e.g., "book appointment", "yes, go ahead", "schedule", "start")
                - PROVIDE_APPOINTMENT_DETAILS: User is giving appointment info like type, location, time (e.g., "blood test", "at Main Hospital", "tomorrow at 2 PM")
                - CONFIRM_DETAILS: User confirms collected details are correct (e.g., "yes that's right", "looks good", "correct", "perfect")
                - MODIFY_DETAILS: User wants to change something (e.g., "actually change the time", "not blood test, I meant MRI")
                - GREETING: Simple greeting or small talk (e.g., "hello", "hi there", "how are you")
                - ASK_QUESTION: User is asking a question (e.g., "what can you do?", "how does this work?")
                - OTHER: Doesn't fit other categories
                
                Also extract any appointment details mentioned in the message:
                - appointmentType: type of appointment
                - location: address or hospital name
                - time: time of day (morning, afternoon, evening, or specific time)
                - date: specific date or relative date (tomorrow, next week, etc.)
                - patientName: name of the patient
                
                Respond with JSON only.
                """.trimIndent()
            }
            user { +message }
        }
        
        return try {
            val result = promptExecutor.executeStructured<IntentClassification>(
                prompt = classificationPrompt,
                model = model
            )
            result.fold(
                onSuccess = { it.data },
                onFailure = { 
                    logger.warn("Intent classification failed: ${it.message}, falling back to heuristics")
                    fallbackIntentClassification(message, conversationState)
                }
            )
        } catch (e: Exception) {
            logger.warn("Intent classification error: ${e.message}, falling back to heuristics")
            fallbackIntentClassification(message, conversationState)
        }
    }
    
    /**
     * Fallback to keyword-based classification if LLM fails.
     */
    private fun fallbackIntentClassification(message: String, state: ConversationState): IntentClassification {
        val msgLower = message.lowercase()
        val intent = when {
            state == ConversationState.CONFIRMING_DETAILS && 
                msgLower.containsAny("yes", "correct", "right", "book it", "go ahead", "looks good", "perfect") -> UserIntent.CONFIRM_DETAILS
            msgLower.containsAny("book", "schedule", "appointment", "start", "yes", "go") -> UserIntent.START_BOOKING
            msgLower.containsAny("blood test", "diagnostic scan", "mri", "ct scan", "x-ray", "ultrasound", "physical exam", "consultation", 
                "location", "at", "hospital", "time", "morning", "afternoon", "evening") -> UserIntent.PROVIDE_APPOINTMENT_DETAILS
            msgLower.containsAny("change", "modify", "different", "instead", "not", "actually") -> UserIntent.MODIFY_DETAILS
            msgLower.containsAny("hello", "hi", "hey", "good morning", "good afternoon") -> UserIntent.GREETING
            msgLower.containsAny("what", "how", "can you", "help") -> UserIntent.ASK_QUESTION
            else -> UserIntent.OTHER
        }
        return IntentClassification(intent = intent, confidence = "low_fallback")
    }

    @OptIn(ExperimentalUuidApi::class)
    fun createSession(userId: String? = null): ChatSession {
        val sessionId = Uuid.random().toString()
        val session = ChatSession(sessionId = sessionId, userId = userId)
        sessions[sessionId] = session
        logger.info("${LogColors.SESSION} Created new session: $sessionId${userId?.let { " for user: $it" } ?: ""}")
        return session
    }
    
    private fun updateConversationState(sessionId: String, state: ConversationState) {
        sessions.computeIfPresent(sessionId) { _, session ->
            session.copy(conversationState = state, updatedAt = System.currentTimeMillis())
        }
    }

    fun getSession(sessionId: String): ChatSession? {
        val session = sessions[sessionId]
        logger.debug("${LogColors.SESSION} Get session $sessionId: ${if (session != null) "found" else "not found"}")
        return session
    }

    fun getMessages(sessionId: String): List<ChatMessage> {
        val messages = sessions[sessionId]?.messages ?: emptyList()
        logger.debug("${LogColors.SESSION} Get messages for $sessionId: ${messages.size} messages")
        return messages
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun addMessage(sessionId: String, role: MessageRole, content: String, type: MessageType = MessageType.TEXT): ChatMessage {
        val message = ChatMessage(
            id = Uuid.random().toString(),
            role = role,
            content = content,
            type = type
        )
        sessions.computeIfPresent(sessionId) { _, session ->
            session.copy(
                messages = session.messages + message,
                updatedAt = System.currentTimeMillis()
            )
        }
        return message
    }

    fun updateAppointmentForm(sessionId: String, appointmentForm: AppointmentForm) {
        sessions.computeIfPresent(sessionId) { _, session ->
            session.copy(appointmentForm = appointmentForm, updatedAt = System.currentTimeMillis())
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun chat(request: ChatRequest): Flow<ChatStreamEvent> = flow {
        logger.info(LogColors.chatBanner("CHAT REQUEST"))
        logger.info("${LogColors.CHAT} Session: ${request.sessionId ?: "new"}, User: ${request.userId ?: "anonymous"}")
        logger.info("${LogColors.USER_INPUT} Message: ${LogColors.userInput(request.message)}")
        if (request.appointmentForm != null) {
            logger.info("${LogColors.USER_INPUT} Appointment Form: ${request.appointmentForm.appointmentGroup} at ${request.appointmentForm.location}")
        }
        
        // Get or create session, linking to user if provided
        val session = request.sessionId?.let { sessions[it] } 
            ?: createSession(request.userId).also { sessions[it.sessionId] = it }
        val sessionId = session.sessionId
        val userId = request.userId ?: session.userId
        
        
        // Add user message to history
        val userMessage = addMessage(sessionId, MessageRole.USER, request.message)
        emit(ChatStreamEvent(sessionId = sessionId, type = "user_message", message = userMessage))

        // Update appointment form if provided
        if (request.appointmentForm != null) {
            logger.info("${LogColors.CHAT} Updating appointment form for session $sessionId")
            updateAppointmentForm(sessionId, request.appointmentForm)
        }

        val appointmentForm = request.appointmentForm ?: session.appointmentForm
        val currentState = session.conversationState
        
        logger.debug("${LogColors.CHAT} Current state: $currentState, Appointment form: ${appointmentForm != null}")

        // Use LLM to classify user intent semantically
        val classification = classifyIntent(
            message = request.message,
            conversationState = currentState,
            hasAppointmentForm = appointmentForm != null,
            hasAppointmentResult = session.appointmentResult != null
        )
        logger.info("${LogColors.CHAT} Intent: ${classification.intent} (confidence: ${classification.confidence})")

        // Route based on classified intent
        when {
            // Handle suggestion response - user accepts or declines the suggestion
            currentState == ConversationState.AWAITING_SUGGESTION_RESPONSE && 
            session.pendingSuggestion != null -> {
                val suggestion = session.pendingSuggestion
                val originalForm = session.appointmentForm
                
                // Detect if user accepts the suggestion
                val accepts = detectSuggestionAcceptance(request.message)
                
                // Check if user is providing new details instead
                val providingNewDetails = detectNewAppointmentDetails(request.message)
                
                when {
                    accepts && originalForm != null -> {
                        logger.info("${LogColors.CHAT} User accepted suggestion: ${suggestion.suggestedWorkType} at ${suggestion.suggestedLocation}")
                        
                        // Clear the pending suggestion and resume booking with suggestion
                        sessions.computeIfPresent(sessionId) { _, s ->
                            s.copy(
                                pendingSuggestion = null,
                                conversationState = ConversationState.PLANNING,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                        
                        // Resume booking flow with suggestion
                        handleBookingWithSuggestion(sessionId, userId, originalForm, suggestion)
                            .collect { emit(it) }
                    }
                    
                    providingNewDetails -> {
                        logger.info("${LogColors.CHAT} User providing new appointment details instead of accepting suggestion")
                        
                        // User wants to provide different details - go to negotiation
                        sessions.computeIfPresent(sessionId) { _, s ->
                            s.copy(
                                pendingSuggestion = null,
                                appointmentForm = null,
                                conversationState = ConversationState.COLLECTING_JOURNEY_DETAILS,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                        
                        // Extract details from user message and continue negotiation
                        val partial = PartialAppointmentInfo()
                        handleNegotiation(sessionId, userId, request.message, partial)
                            .collect { emit(it) }
                    }
                    
                    else -> {
                        logger.info("${LogColors.CHAT} User declined suggestion - offering options")
                        
                        // Clear the pending suggestion and go back to collecting details
                        sessions.computeIfPresent(sessionId) { _, s ->
                            s.copy(
                                pendingSuggestion = null,
                                appointmentForm = null,
                                conversationState = ConversationState.COLLECTING_JOURNEY_DETAILS,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                        
                        val response = buildString {
                            appendLine("No problem! I can help you with a different appointment.")
                            appendLine()
                            appendLine("You can either:")
                            appendLine("• Tell me what appointment type and location you'd prefer")
                            appendLine("• Or say **\"${suggestion.suggestedWorkType} at ${suggestion.suggestedLocation}\"** if you change your mind")
                            appendLine()
                            appendLine("What would you like to book?")
                        }
                        val assistantMessage = addMessage(sessionId, MessageRole.ASSISTANT, response)
                        emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = assistantMessage))
                        emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
                    }
                }
            }
            
            // Handle branch selection response
            currentState == ConversationState.AWAITING_BRANCH_SELECTION &&
            session.pendingBranchSelection != null -> {
                val branches = session.pendingBranchSelection
                val form = session.appointmentForm
                val workTypeGroupId = session.workTypeGroupId
                
                if (form == null || workTypeGroupId == null) {
                    logger.error("${LogColors.CHAT} Missing form or workTypeGroupId for branch selection")
                    val errorMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        "Session data missing. Please start over.",
                        MessageType.ERROR
                    )
                    emit(ChatStreamEvent(sessionId = sessionId, type = "error", message = errorMessage))
                    emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
                    return@flow
                }
                
                // Detect if user wants closest branch
                val wantsClosest = detectClosestBranchRequest(request.message)
                
                // Detect which branch user selected
                val selectedBranch = if (wantsClosest) {
                    logger.info("${LogColors.CHAT} User wants closest branch - finding closest...")
                    
                    // Extract user's location from message
                    val userLocation = extractUserLocation(request.message)
                    
                    if (userLocation == null) {
                        // Ask user for their location
                        logger.info("${LogColors.CHAT} Could not extract user location, asking for it...")
                        val askLocationMessage = addMessage(
                            sessionId,
                            MessageRole.ASSISTANT,
                            "I'd be happy to find the closest branch for you! 📍\n\n" +
                            "**Please tell me your current location** so I can calculate distances.\n\n" +
                            "For example:\n" +
                            "• \"I am at Jubilee Hills, Hyderabad\"\n" +
                            "• \"My location is Banjara Hills\"\n" +
                            "• \"I'm near Gachibowli\"",
                            MessageType.TEXT
                        )
                        emit(ChatStreamEvent(
                            sessionId = sessionId,
                            type = "branch_selection",
                            message = askLocationMessage,
                            awaitingResponse = true
                        ))
                        emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true, awaitingResponse = true))
                        return@flow
                    }
                    
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "📍 Finding the closest branch to '$userLocation' using Maps..."
                    ))
                    
                    // Find closest branch using Maps agent (with progress updates)
                    val (closestBranch, branchesWithDistances) = orchestrator.findClosestBranch(
                        userLocation, 
                        branches
                    ) { progressMessage ->
                        // Emit progress updates to the client
                        emit(ChatStreamEvent(
                            sessionId = sessionId,
                            type = "progress",
                            content = progressMessage
                        ))
                    }
                    
                    // Show summary of all distances and travel times
                    if (branchesWithDistances.isNotEmpty()) {
                        val distanceSummary = buildString {
                            appendLine("📊 **Distance & Travel Time Summary:**")
                            branchesWithDistances
                                .filter { it.distanceKm != null && it.distanceKm != Double.MAX_VALUE }
                                .sortedBy { it.travelTimeMinutes ?: it.distanceKm?.times(2)?.toInt() ?: Int.MAX_VALUE }
                                .forEach { branch ->
                                    val timeStr = branch.travelTimeMinutes?.let { " (~$it min)" } ?: ""
                                    appendLine("• ${branch.branchName}: ${"%.2f".format(branch.distanceKm)} km$timeStr")
                                }
                        }
                        emit(ChatStreamEvent(
                            sessionId = sessionId,
                            type = "progress",
                            content = distanceSummary
                        ))
                        
                        // Store travel time of closest branch for timeslot suggestions
                        closestBranch?.travelTimeMinutes?.let { travelTime ->
                            sessions.computeIfPresent(sessionId) { _, s ->
                                s.copy(
                                    // Store travel time for later use in timeslot suggestions
                                    updatedAt = System.currentTimeMillis()
                                )
                            }
                            logger.info("${LogColors.CHAT} Closest branch travel time: $travelTime minutes (can be used for timeslot suggestions)")
                        }
                    }
                    
                    closestBranch ?: branches.first()
                } else {
                    // Try to match branch by name from user's message
                    matchBranchFromMessage(request.message, branches) ?: branches.first()
                }
                
                logger.info("${LogColors.CHAT} User selected branch: ${selectedBranch.branchName}")
                
                // Clear pending branch selection and continue booking
                sessions.computeIfPresent(sessionId) { _, s ->
                    s.copy(
                        pendingBranchSelection = null,
                        conversationState = ConversationState.PLANNING,
                        updatedAt = System.currentTimeMillis()
                    )
                }
                
                val confirmMessage = addMessage(
                    sessionId,
                    MessageRole.ASSISTANT,
                    "✅ Great! You've selected **${selectedBranch.branchName}**${selectedBranch.distanceKm?.let { " (%.1f km away)".format(it) } ?: ""}. Continuing with the booking...",
                    MessageType.THINKING
                )
                emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = confirmMessage))
                
                // Continue booking with selected branch
                handleBookingWithBranch(sessionId, userId, form, selectedBranch, workTypeGroupId)
                    .collect { emit(it) }
            }
            
            // Handle timeslot confirmation response
            currentState == ConversationState.AWAITING_TIMESLOT_CONFIRMATION -> {
                val form = session.appointmentForm
                val workTypeGroupId = session.workTypeGroupId
                val serviceTerritoryId = session.serviceTerritoryId
                val selectedBranch = session.selectedBranch
                val suggestedTimeslot = session.suggestedTimeslot // Get the suggested time from the prompt
                
                if (form == null || workTypeGroupId == null || serviceTerritoryId == null || selectedBranch == null) {
                    logger.error("${LogColors.CHAT} Missing data for timeslot confirmation")
                    val errorMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        "Session data missing. Please start over.",
                        MessageType.ERROR
                    )
                    emit(ChatStreamEvent(sessionId = sessionId, type = "error", message = errorMessage))
                    emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
                    return@flow
                }
                
                // Parse user's preferred time from their message
                // If user just says "yes", use the suggested time from the prompt
                val userTime = parsePreferredTime(request.message)
                val preferredTime = userTime ?: suggestedTimeslot
                
                logger.info("${LogColors.CHAT} Timeslot confirmation - User input: '${request.message}', Parsed: $userTime, Suggested: $suggestedTimeslot, Using: $preferredTime")
                
                // Clear state and continue
                sessions.computeIfPresent(sessionId) { _, s ->
                    s.copy(
                        suggestedTimeslot = null,
                        conversationState = ConversationState.PLANNING,
                        updatedAt = System.currentTimeMillis()
                    )
                }
                
                val displayTime = preferredTime ?: "the suggested time"
                val confirmMessage = addMessage(
                    sessionId,
                    MessageRole.ASSISTANT,
                    "✅ Got it! Searching for available slots around $displayTime...",
                    MessageType.THINKING
                )
                emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = confirmMessage))
                
                // Continue booking with timeslot confirmation
                handleBookingWithTimeslotConfirmation(sessionId, userId, form, selectedBranch, workTypeGroupId, serviceTerritoryId, preferredTime)
                    .collect { emit(it) }
            }
            
            // Handle final booking confirmation response
            currentState == ConversationState.AWAITING_BOOKING_CONFIRMATION -> {
                val form = session.appointmentForm
                val workTypeGroupId = session.workTypeGroupId
                val selectedBranch = session.selectedBranch
                val pendingTimeslotId = session.pendingTimeslotId
                val pendingSelectedTimeslot = session.pendingSelectedTimeslot
                
                if (form == null || workTypeGroupId == null || selectedBranch == null) {
                    logger.error("${LogColors.CHAT} Missing data for booking confirmation")
                    val errorMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        "Session data missing. Please start over.",
                        MessageType.ERROR
                    )
                    emit(ChatStreamEvent(sessionId = sessionId, type = "error", message = errorMessage))
                    emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
                    return@flow
                }
                
                val lowerMessage = request.message.lowercase().trim()
                
                // Check if user confirmed
                val isConfirmed = listOf("yes", "yeah", "yep", "ok", "okay", "sure", "confirm", "book it", "go ahead", "proceed")
                    .any { lowerMessage == it || lowerMessage.startsWith("$it ") || lowerMessage.startsWith("$it,") || lowerMessage.startsWith("$it.") }
                
                if (isConfirmed) {
                    logger.info("${LogColors.CHAT} User confirmed booking for timeslot: $pendingSelectedTimeslot")
                    
                    // Clear pending state and continue
                    sessions.computeIfPresent(sessionId) { _, s ->
                        s.copy(
                            pendingTimeslotId = null,
                            pendingSelectedTimeslot = null,
                            pendingTimeslotInfo = null,
                            conversationState = ConversationState.PLANNING,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    
                    val confirmMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        "✅ Great! Finalizing your booking...",
                        MessageType.THINKING
                    )
                    emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = confirmMessage))
                    
                    // Get full booking details from session
                    val serviceTerritoryId = session.serviceTerritoryId
                    val pendingTimeslotInfo = session.pendingTimeslotInfo
                    
                    logger.info("${LogColors.CHAT} ========== SESSION DATA BEFORE FINAL BOOKING ==========")
                    logger.info("${LogColors.CHAT} session.workTypeGroupId: $workTypeGroupId")
                    logger.info("${LogColors.CHAT} session.serviceTerritoryId: $serviceTerritoryId")
                    logger.info("${LogColors.CHAT} session.pendingTimeslotId: $pendingTimeslotId")
                    logger.info("${LogColors.CHAT} session.pendingSelectedTimeslot: $pendingSelectedTimeslot")
                    logger.info("${LogColors.CHAT} session.pendingTimeslotInfo: $pendingTimeslotInfo")
                    logger.info("${LogColors.CHAT} session.selectedBranch: ${selectedBranch.branchName}")
                    logger.info("${LogColors.CHAT} ========================================================")
                    
                    // Continue with actual booking
                    handleFinalBooking(sessionId, userId, form, selectedBranch, workTypeGroupId, pendingTimeslotId, 
                        pendingSelectedTimeslot ?: "selected timeslot", serviceTerritoryId, pendingTimeslotInfo)
                        .collect { emit(it) }
                } else {
                    // User declined or wants to change
                    logger.info("${LogColors.CHAT} User declined or wants to change: ${request.message}")
                    
                    // Reset to timeslot selection
                    sessions.computeIfPresent(sessionId) { _, s ->
                        s.copy(
                            pendingTimeslotId = null,
                            pendingSelectedTimeslot = null,
                            pendingTimeslotInfo = null,
                            conversationState = ConversationState.AWAITING_TIMESLOT_CONFIRMATION,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    
                    val changeMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        "No problem! Please tell me what time you'd prefer for your appointment.",
                        MessageType.TEXT
                    )
                    emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = changeMessage, awaitingResponse = true))
                    emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true, awaitingResponse = true))
                }
            }
            
            // New conversation - greet
            currentState == ConversationState.GREETING && appointmentForm == null && 
            classification.intent == UserIntent.GREETING -> {
                val greeting = buildGreeting()
                val assistantMessage = addMessage(sessionId, MessageRole.ASSISTANT, greeting)
                updateConversationState(sessionId, ConversationState.COLLECTING_JOURNEY_DETAILS)
                emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = assistantMessage))
                emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
            }
            
            // User confirms they want to book (with full AppointmentForm)
            classification.intent == UserIntent.START_BOOKING && appointmentForm != null -> {
                handleBookingFlow(sessionId, userId, appointmentForm)
                    .collect { emit(it) }
            }
            
            // User confirms partial appointment details - convert to AppointmentForm and book
            classification.intent == UserIntent.CONFIRM_DETAILS && 
            currentState == ConversationState.CONFIRMING_DETAILS -> {
                val partial = session.partialAppointment
                if (partial != null && partial.isComplete()) {
                    val form = buildAppointmentFormFromPartial(partial)
                    updateAppointmentForm(sessionId, form)
                    handleBookingFlow(sessionId, userId, form)
                        .collect { emit(it) }
                } else {
                    handleNegotiation(sessionId, userId, request.message, partial)
                        .collect { emit(it) }
                }
            }
            
            // User wants to start booking but we need to collect details first
            classification.intent == UserIntent.START_BOOKING && appointmentForm == null -> {
                val partial = session.partialAppointment
                if (partial != null && partial.isComplete()) {
                    val form = buildAppointmentFormFromPartial(partial)
                    updateAppointmentForm(sessionId, form)
                    handleBookingFlow(sessionId, userId, form)
                        .collect { emit(it) }
                } else {
                    handleNegotiation(sessionId, userId, request.message, partial)
                        .collect { emit(it) }
                }
            }
            
            // User is providing appointment details or modifying - continue negotiation
            classification.intent in listOf(UserIntent.PROVIDE_APPOINTMENT_DETAILS, UserIntent.MODIFY_DETAILS) ||
            currentState == ConversationState.COLLECTING_JOURNEY_DETAILS ||
            currentState == ConversationState.CONFIRMING_DETAILS -> {
                // Apply extracted details from LLM classification
                val enrichedPartial = applyExtractedDetails(session.partialAppointment, classification.extractedDetails)
                handleNegotiation(sessionId, userId, request.message, enrichedPartial)
                    .collect { emit(it) }
            }
            
            // Default - prompt user to tell us about their appointment
            else -> {
                val response = buildString {
                    appendLine("I'm here to help you book hospital appointments! 🏥")
                    appendLine()
                    appendLine("To get started, I need a few details:")
                    appendLine("• **Appointment type** (e.g., blood test, diagnostic scan, MRI, CT scan)")
                    appendLine("• **Location** (hospital name or address)")
                    appendLine("• **Date and time** (e.g., tomorrow at 2 PM, next Monday morning)")
                    appendLine()
                    appendLine("For example, you could say:")
                    appendLine("• *\"I need a blood test at Main Hospital tomorrow at 2 PM\"*")
                    appendLine("• *\"Book a diagnostic scan for next week\"*")
                }
                val assistantMessage = addMessage(sessionId, MessageRole.ASSISTANT, response)
                emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = assistantMessage))
                emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
            }
        }
    }
    
    /**
     * Apply extracted appointment details from LLM classification to partial appointment info.
     */
    private fun applyExtractedDetails(
        current: PartialAppointmentInfo?,
        extracted: ExtractedAppointmentDetails?
    ): PartialAppointmentInfo {
        if (extracted == null) return current ?: PartialAppointmentInfo()
        val base = current ?: PartialAppointmentInfo()
        
        // Use extracted appointment type directly as string
        val appointmentGroup = extracted.appointmentType ?: base.appointmentGroup
        
        return base.copy(
            appointmentGroup = appointmentGroup,
            location = extracted.location ?: base.location,
            timeOfDay = extracted.time ?: base.timeOfDay,
            date = extracted.date ?: base.date,
            patientName = extracted.patientName ?: base.patientName,
            notes = extracted.notes ?: base.notes
        )
    }
    
    private fun String.containsAny(vararg keywords: String): Boolean = 
        keywords.any { this.contains(it, ignoreCase = true) }
    
    /**
     * Detect if user's message indicates acceptance of a suggestion.
     */
    private fun detectSuggestionAcceptance(message: String): Boolean {
        val lowerMessage = message.lowercase().trim()
        val acceptKeywords = listOf("yes", "yeah", "yep", "sure", "ok", "okay", "proceed", "go ahead", "book it", "let's do it", "confirm", "accept")
        val declineKeywords = listOf("no", "nope", "cancel", "decline", "don't", "different", "other", "change")
        
        // Check for decline keywords first
        if (declineKeywords.any { lowerMessage.contains(it) }) {
            return false
        }
        
        // Check for accept keywords
        return acceptKeywords.any { lowerMessage.contains(it) }
    }
    
    /**
     * Detect if user wants the closest branch.
     */
    private fun detectClosestBranchRequest(message: String): Boolean {
        val lowerMessage = message.lowercase().trim()
        val closestKeywords = listOf("closest", "nearest", "close", "near", "nearby", "find closest", "get closest")
        return closestKeywords.any { lowerMessage.contains(it) }
    }
    
    /**
     * Extract user's current location from their message.
     * Returns null if no valid location can be extracted.
     */
    private fun extractUserLocation(message: String): String? {
        // Try to extract location after common phrases
        val patterns = listOf(
            "i am at (.+)",
            "i'm at (.+)",
            "currently at (.+)",
            "i am in (.+)",
            "i'm in (.+)",
            "from (.+)",
            "near (.+)",
            "located at (.+)",
            "location is (.+)",
            "my location is (.+)",
            "at (.+)"
        )
        
        val lowerMessage = message.lowercase()
        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val match = regex.find(lowerMessage)
            if (match != null) {
                val location = cleanupLocation(match.groupValues[1])
                // Only return if we got a meaningful location (at least 3 chars)
                if (location.length >= 3 && !isGenericPhrase(location)) {
                    return location
                }
            }
        }
        
        // Try to find location after keywords
        val locationKeywords = listOf("in ", "at ", "from ", "near ")
        for (keyword in locationKeywords) {
            val idx = lowerMessage.lastIndexOf(keyword)
            if (idx >= 0) {
                val remaining = cleanupLocation(message.substring(idx + keyword.length))
                if (remaining.length >= 3 && !isGenericPhrase(remaining)) {
                    return remaining
                }
            }
        }
        
        // Return null if we couldn't extract a valid location
        return null
    }
    
    /**
     * Clean up extracted location by removing trailing junk.
     */
    private fun cleanupLocation(raw: String): String {
        var location = raw.trim()
        
        // Remove common trailing phrases
        val trailingPhrases = listOf(
            "find closest", "get closest", "find the closest", "get the closest",
            "find nearest", "get nearest", "find the nearest", "get the nearest",
            "closest branch", "nearest branch", "closest one", "nearest one",
            "please", "thanks", "thank you", "can you", "could you"
        )
        for (phrase in trailingPhrases) {
            location = location.replace(Regex("\\s*\\.?\\s*$phrase.*", RegexOption.IGNORE_CASE), "")
        }
        
        // Cut off at sentence boundaries if there's trailing text
        val sentenceEnd = location.indexOfAny(charArrayOf('.', '!', '?'))
        if (sentenceEnd > 0) {
            location = location.substring(0, sentenceEnd)
        }
        
        // Remove trailing punctuation and common words
        location = location
            .replace(Regex("[.,!?;:]+$"), "")
            .replace(Regex("\\s+(and|or|the|a|an|to|for|is|are)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
        
        return location
    }
    
    /**
     * Check if the extracted text is just a generic phrase (not a real location).
     */
    private fun isGenericPhrase(text: String): Boolean {
        val genericPhrases = listOf(
            "can you find", "could you find", "please find", "find the", "get the",
            "the closest", "the nearest", "closest", "nearest", "branch", "hospital",
            "one", "it", "that", "this", "me"
        )
        return genericPhrases.any { text.lowercase().trim() == it.lowercase() }
    }
    
    /**
     * Parse user's preferred time from their message.
     * Returns null if user says "yes" or just confirms without a time, otherwise extracts time.
     */
    private fun parsePreferredTime(message: String): String? {
        val lowerMessage = message.lowercase().trim()
        
        // First, try to extract specific time mentions - do this BEFORE checking for confirmations
        // so that "yes, 2pm" extracts "2pm" instead of being treated as just "yes"
        val timePatterns = listOf(
            Regex("""(\d{1,2}:\d{2}\s*(?:am|pm))""", RegexOption.IGNORE_CASE),
            Regex("""(\d{1,2}\s*(?:am|pm))""", RegexOption.IGNORE_CASE),
            Regex("""(morning|afternoon|evening|noon)""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in timePatterns) {
            val match = pattern.find(lowerMessage)
            if (match != null) {
                logger.info("${LogColors.CHAT} Extracted time from user message: '${match.value}'")
                return match.value
            }
        }
        
        // Check for "any time" type responses
        if (lowerMessage.contains("any time") || lowerMessage.contains("anytime") || lowerMessage.contains("whenever")) {
            return "any available time"
        }
        
        // If message contains time-related words, return the whole message as context
        val timeKeywords = listOf("am", "pm", "o'clock", "oclock", "hour", "around")
        if (timeKeywords.any { lowerMessage.contains(it) }) {
            logger.info("${LogColors.CHAT} Found time keyword in message, returning full message: '$message'")
            return message.trim()
        }
        
        // If user just says yes/ok/sure (without any time), use the suggested time (return null)
        val confirmationPhrases = listOf("yes", "yeah", "yep", "ok", "okay", "sure", "sounds good", "that works", "perfect", "great", "go ahead", "proceed")
        if (confirmationPhrases.any { lowerMessage == it || lowerMessage.startsWith("$it.") || lowerMessage.startsWith("$it!") }) {
            logger.info("${LogColors.CHAT} User confirmed without specific time, using suggested time")
            return null
        }
        
        // Check for date patterns like "tomorrow", "today"
        val datePatterns = listOf(
            Regex("""(tomorrow|today|next week)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in datePatterns) {
            val match = pattern.find(lowerMessage)
            if (match != null) {
                logger.info("${LogColors.CHAT} Found date reference: '${match.value}'")
                return match.value
            }
        }
        
        // Default: use suggested time
        logger.info("${LogColors.CHAT} No time extracted from message, using suggested time")
        return null
    }
    
    /**
     * Match a branch name from user's message.
     */
    private fun matchBranchFromMessage(message: String, branches: List<BranchInfo>): BranchInfo? {
        val lowerMessage = message.lowercase()
        
        // Try to find a branch that matches the user's message
        for (branch in branches) {
            val branchName = branch.branchName.lowercase()
            val branchAddress = branch.address?.lowercase() ?: ""
            
            // Check if branch name or address is mentioned
            if (lowerMessage.contains(branchName) || 
                branchAddress.isNotEmpty() && lowerMessage.contains(branchAddress)) {
                return branch
            }
            
            // Check for partial matches (e.g., "Jubilee Hills" matches "Apollo Hospitals - Jubilee Hills")
            val parts = branchName.split("-", " ").filter { it.length > 3 }
            for (part in parts) {
                if (lowerMessage.contains(part.trim())) {
                    return branch
                }
            }
        }
        
        // Try to match by number (e.g., "1" or "first" or "option 1")
        val numberMatch = Regex("\\b(\\d+)\\b").find(lowerMessage)
        if (numberMatch != null) {
            val index = numberMatch.groupValues[1].toIntOrNull()
            if (index != null && index >= 1 && index <= branches.size) {
                return branches[index - 1]
            }
        }
        
        return null
    }
    
    /**
     * Handle booking flow with a selected branch.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun handleBookingWithBranch(
        sessionId: String,
        userId: String?,
        appointmentForm: AppointmentForm,
        selectedBranch: BranchInfo,
        workTypeGroupId: String
    ): Flow<ChatStreamEvent> = flow {
        logger.info("${LogColors.CHAT} Continuing booking with branch: ${selectedBranch.branchName}")
        
        var finalResult: AppointmentResult? = null
        
        // Stream progress updates from orchestrator with branch selection
        orchestrator.bookAppointmentWithBranchSelection(appointmentForm, selectedBranch, workTypeGroupId).collect { progress ->
            when (progress) {
                is AppointmentProgress.BranchSelectionComplete -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "✅ ${progress.message}"
                    ))
                }
                
                is AppointmentProgress.FetchingParkingInfo -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "🅿️ Checking parking availability..."
                    ))
                }
                
                is AppointmentProgress.ParkingInfoComplete -> {
                    // Show parking info as a separate detailed message
                    val parkingContent = buildString {
                        appendLine("🅿️ **Parking Information for ${progress.parkingInfo.location}**")
                        appendLine()
                        appendLine(progress.parkingInfo.summary)
                        progress.parkingInfo.parkingFees?.let { 
                            appendLine()
                            appendLine("💰 **Fees:** $it")
                        }
                        progress.parkingInfo.parkingTips?.let { 
                            appendLine()
                            appendLine("💡 **Tips:** $it")
                        }
                    }
                    
                    val parkingMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        parkingContent,
                        MessageType.TEXT
                    )
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "parking_info",
                        message = parkingMessage
                    ))
                }
                
                is AppointmentProgress.ValidatingServiceTerritory -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "📍 Validating service territory..."
                    ))
                }
                
                is AppointmentProgress.ServiceTerritoryValidationComplete -> {
                    // Store the service territory ID for later use
                    val stId = progress.validationResult.serviceTerritoryId ?: selectedBranch.serviceTerritoryId
                    sessions.computeIfPresent(sessionId) { _, s ->
                        s.copy(
                            serviceTerritoryId = stId,
                            selectedBranch = selectedBranch,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "✅ Service territory validation complete: ${progress.validationResult.message}"
                    ))
                }
                
                is AppointmentProgress.PromptTimeslotConfirmation -> {
                    // Store suggested time and update state
                    sessions.computeIfPresent(sessionId) { _, s ->
                        s.copy(
                            suggestedTimeslot = progress.suggestedTime,
                            conversationState = ConversationState.AWAITING_TIMESLOT_CONFIRMATION,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    
                    val timeslotMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        progress.message,
                        MessageType.TEXT
                    )
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "timeslot_confirmation",
                        message = timeslotMessage,
                        awaitingResponse = true
                    ))
                    
                    // Emit done event since we're waiting for user response
                    emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true, awaitingResponse = true))
                }
                
                is AppointmentProgress.TimeslotConfirmed -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "✅ Looking for slots around ${progress.confirmedTime}..."
                    ))
                }
                
                is AppointmentProgress.ValidatingTimeslot -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "⏰ Validating timeslot availability..."
                    ))
                }
                
                is AppointmentProgress.TimeslotValidationComplete -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "✅ Timeslot validation complete: ${progress.validationResult.message}"
                    ))
                }
                
                is AppointmentProgress.FetchingWeather -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "🌤️ Fetching weather forecast for your appointment day..."
                    ))
                }
                
                is AppointmentProgress.WeatherForecastComplete -> {
                    // Show weather as a separate detailed message
                    val weatherContent = buildString {
                        appendLine("🌤️ **Weather Forecast for ${progress.forecast.location} on ${progress.forecast.dateTime.date}**")
                        appendLine()
                        progress.forecast.summary?.let { appendLine(it) }
                    }
                    
                    val weatherMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        weatherContent,
                        MessageType.TEXT
                    )
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "weather_forecast",
                        message = weatherMessage
                    ))
                }
                
                is AppointmentProgress.PromptBookingConfirmation -> {
                    // Store booking details and update state
                    logger.info("${LogColors.CHAT} Received PromptBookingConfirmation: timeslotId=${progress.timeslotId}, timeslotInfo=${progress.timeslotInfo}, serviceTerritoryId=${progress.serviceTerritoryId}")
                    sessions.computeIfPresent(sessionId) { _, s ->
                        s.copy(
                            pendingTimeslotId = progress.timeslotId,
                            pendingSelectedTimeslot = progress.selectedTimeslot,
                            pendingTimeslotInfo = progress.timeslotInfo,
                            serviceTerritoryId = progress.serviceTerritoryId ?: s.serviceTerritoryId,
                            conversationState = ConversationState.AWAITING_BOOKING_CONFIRMATION,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    
                    val confirmMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        progress.message,
                        MessageType.TEXT
                    )
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "booking_confirmation",
                        message = confirmMessage,
                        awaitingResponse = true
                    ))
                    
                    // Emit done event since we're waiting for user response
                    emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true, awaitingResponse = true))
                }
                
                is AppointmentProgress.BookingConfirmed -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "✅ Booking confirmed! Processing your appointment..."
                    ))
                }
                
                is AppointmentProgress.BookingAppointment -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "📝 Booking your appointment..."
                    ))
                }
                
                is AppointmentProgress.BookingComplete -> {
                    finalResult = progress.result
                    logger.info("${LogColors.CHAT} Booking with branch complete in ${progress.totalDurationMs}ms")
                }
                
                is AppointmentProgress.Error -> {
                    logger.error("${LogColors.CHAT} Booking error: ${progress.message}")
                    updateConversationState(sessionId, ConversationState.COLLECTING_JOURNEY_DETAILS)
                    
                    val errorMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        "${progress.message} Would you like me to try again?",
                        MessageType.ERROR
                    )
                    emit(ChatStreamEvent(sessionId = sessionId, type = "error", message = errorMessage))
                }
                
                else -> {
                    logger.debug("${LogColors.CHAT} Unhandled progress event in branch selection flow: ${progress::class.simpleName}")
                }
            }
        }
        
        // Handle final result
        finalResult?.let { result ->
            sessions.computeIfPresent(sessionId) { _, s ->
                s.copy(
                    appointmentResult = result,
                    conversationState = ConversationState.PRESENTING_PLAN,
                    updatedAt = System.currentTimeMillis()
                )
            }
            
            val resultMessage = addMessage(sessionId, MessageRole.ASSISTANT, result.bookingMessage, MessageType.APPOINTMENT_RESULT)
            emit(ChatStreamEvent(
                sessionId = sessionId,
                type = "appointment_result",
                message = resultMessage,
                appointmentResult = result
            ))
        }
        
        emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
    }
    
    /**
     * Handle booking flow after user confirms timeslot preference.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun handleBookingWithTimeslotConfirmation(
        sessionId: String,
        userId: String?,
        appointmentForm: AppointmentForm,
        selectedBranch: BranchInfo,
        workTypeGroupId: String,
        serviceTerritoryId: String,
        preferredTime: String?
    ): Flow<ChatStreamEvent> = flow {
        logger.info("${LogColors.CHAT} Continuing booking with timeslot confirmation: ${preferredTime ?: "suggested time"}")
        
        var finalResult: AppointmentResult? = null
        
        // Stream progress updates from orchestrator with timeslot confirmation
        orchestrator.bookAppointmentWithTimeslotConfirmation(
            appointmentForm, selectedBranch, workTypeGroupId, serviceTerritoryId, preferredTime
        ).collect { progress ->
            when (progress) {
                is AppointmentProgress.TimeslotConfirmed -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "✅ Searching for slots around ${progress.confirmedTime}..."
                    ))
                }
                
                is AppointmentProgress.ValidatingTimeslot -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "⏰ Checking available timeslots..."
                    ))
                }
                
                is AppointmentProgress.TimeslotValidationComplete -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "✅ Timeslot validation complete: ${progress.validationResult.message}"
                    ))
                }
                
                is AppointmentProgress.FetchingWeather -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "🌤️ Fetching weather forecast for your appointment day..."
                    ))
                }
                
                is AppointmentProgress.WeatherForecastComplete -> {
                    // Show weather as a separate detailed message
                    val weatherContent = buildString {
                        appendLine("🌤️ **Weather Forecast for ${progress.forecast.location} on ${progress.forecast.dateTime.date}**")
                        appendLine()
                        progress.forecast.summary?.let { appendLine(it) }
                    }
                    
                    val weatherMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        weatherContent,
                        MessageType.TEXT
                    )
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "weather_forecast",
                        message = weatherMessage
                    ))
                }
                
                is AppointmentProgress.FetchingParkingInfo -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "🅿️ Checking parking availability..."
                    ))
                }
                
                is AppointmentProgress.ParkingInfoComplete -> {
                    // Show parking info as a separate detailed message
                    val parkingContent = buildString {
                        appendLine("🅿️ **Parking Information for ${progress.parkingInfo.location}**")
                        appendLine()
                        appendLine(progress.parkingInfo.summary)
                        progress.parkingInfo.parkingFees?.let { 
                            appendLine()
                            appendLine("💰 **Fees:** $it")
                        }
                        progress.parkingInfo.parkingTips?.let { 
                            appendLine()
                            appendLine("💡 **Tips:** $it")
                        }
                    }
                    
                    val parkingMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        parkingContent,
                        MessageType.TEXT
                    )
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "parking_info",
                        message = parkingMessage
                    ))
                }
                
                is AppointmentProgress.PromptBookingConfirmation -> {
                    // Store booking details and update state
                    logger.info("${LogColors.CHAT} Received PromptBookingConfirmation: timeslotId=${progress.timeslotId}, timeslotInfo=${progress.timeslotInfo}, serviceTerritoryId=${progress.serviceTerritoryId}")
                    sessions.computeIfPresent(sessionId) { _, s ->
                        s.copy(
                            pendingTimeslotId = progress.timeslotId,
                            pendingSelectedTimeslot = progress.selectedTimeslot,
                            pendingTimeslotInfo = progress.timeslotInfo,
                            serviceTerritoryId = progress.serviceTerritoryId ?: s.serviceTerritoryId,
                            conversationState = ConversationState.AWAITING_BOOKING_CONFIRMATION,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    
                    val confirmMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        progress.message,
                        MessageType.TEXT
                    )
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "booking_confirmation",
                        message = confirmMessage,
                        awaitingResponse = true
                    ))
                    
                    // Emit done event since we're waiting for user response
                    emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true, awaitingResponse = true))
                }
                
                is AppointmentProgress.BookingConfirmed -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "✅ Booking confirmed! Processing your appointment..."
                    ))
                }
                
                is AppointmentProgress.BookingAppointment -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "📝 Booking your appointment..."
                    ))
                }
                
                is AppointmentProgress.BookingComplete -> {
                    finalResult = progress.result
                    logger.info("${LogColors.CHAT} Booking complete in ${progress.totalDurationMs}ms")
                }
                
                is AppointmentProgress.Error -> {
                    logger.error("${LogColors.CHAT} Booking error: ${progress.message}")
                    updateConversationState(sessionId, ConversationState.COLLECTING_JOURNEY_DETAILS)
                    
                    val errorMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        "${progress.message} Would you like me to try again?",
                        MessageType.ERROR
                    )
                    emit(ChatStreamEvent(sessionId = sessionId, type = "error", message = errorMessage))
                }
                
                else -> {
                    logger.debug("${LogColors.CHAT} Unhandled progress event in timeslot confirmation flow: ${progress::class.simpleName}")
                }
            }
        }
        
        // Handle final result (only if we didn't stop for booking confirmation)
        finalResult?.let { result ->
            sessions.computeIfPresent(sessionId) { _, s ->
                s.copy(
                    appointmentResult = result,
                    conversationState = ConversationState.PRESENTING_PLAN,
                    updatedAt = System.currentTimeMillis()
                )
            }
            
            val resultMessage = addMessage(sessionId, MessageRole.ASSISTANT, result.bookingMessage, MessageType.APPOINTMENT_RESULT)
            emit(ChatStreamEvent(
                sessionId = sessionId,
                type = "appointment_result",
                message = resultMessage,
                appointmentResult = result
            ))
            
            emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
        }
    }
    
    /**
     * Handle final booking after user confirms the timeslot.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun handleFinalBooking(
        sessionId: String,
        userId: String?,
        appointmentForm: AppointmentForm,
        selectedBranch: BranchInfo,
        workTypeGroupId: String,
        timeslotId: String?,
        selectedTimeslot: String,
        serviceTerritoryId: String? = null,
        timeslotInfo: TimeslotInfo? = null
    ): Flow<ChatStreamEvent> = flow {
        logger.info("${LogColors.CHAT} ========== FINAL BOOKING PARAMS ==========")
        logger.info("${LogColors.CHAT} Processing final booking for timeslot: $selectedTimeslot")
        logger.info("${LogColors.CHAT} WorkTypeGroupId: $workTypeGroupId")
        logger.info("${LogColors.CHAT} TimeslotId: $timeslotId")
        logger.info("${LogColors.CHAT} ServiceTerritoryId: $serviceTerritoryId")
        logger.info("${LogColors.CHAT} TimeslotInfo: $timeslotInfo")
        logger.info("${LogColors.CHAT} SelectedBranch: ${selectedBranch.branchName} (ST ID: ${selectedBranch.serviceTerritoryId})")
        logger.info("${LogColors.CHAT} ===========================================")
        
        var finalResult: AppointmentResult? = null
        
        // Stream progress updates from orchestrator
        orchestrator.bookAppointmentAfterConfirmation(
            appointmentForm, selectedBranch, workTypeGroupId, timeslotId, selectedTimeslot,
            serviceTerritoryId, timeslotInfo
        ).collect { progress ->
            when (progress) {
                is AppointmentProgress.BookingConfirmed -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "✅ Booking confirmed! Finalizing your appointment..."
                    ))
                }
                
                is AppointmentProgress.BookingAppointment -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "📝 Completing your booking..."
                    ))
                }
                
                is AppointmentProgress.BookingComplete -> {
                    finalResult = progress.result
                    logger.info("${LogColors.CHAT} Final booking complete in ${progress.totalDurationMs}ms")
                }
                
                is AppointmentProgress.Error -> {
                    logger.error("${LogColors.CHAT} Final booking error: ${progress.message}")
                    updateConversationState(sessionId, ConversationState.COLLECTING_JOURNEY_DETAILS)
                    
                    val errorMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        "${progress.message} Would you like me to try again?",
                        MessageType.ERROR
                    )
                    emit(ChatStreamEvent(sessionId = sessionId, type = "error", message = errorMessage))
                }
                
                else -> {
                    logger.debug("${LogColors.CHAT} Unhandled progress event in final booking flow: ${progress::class.simpleName}")
                }
            }
        }
        
        // Handle final result
        finalResult?.let { result ->
            sessions.computeIfPresent(sessionId) { _, s ->
                s.copy(
                    appointmentResult = result,
                    conversationState = ConversationState.PRESENTING_PLAN,
                    updatedAt = System.currentTimeMillis()
                )
            }
            
            val resultMessage = addMessage(sessionId, MessageRole.ASSISTANT, result.bookingMessage, MessageType.APPOINTMENT_RESULT)
            emit(ChatStreamEvent(
                sessionId = sessionId,
                type = "appointment_result",
                message = resultMessage,
                appointmentResult = result
            ))
        }
        
        emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
    }
    
    /**
     * Detect if user's message contains new appointment details (not just yes/no).
     * This helps identify when user wants to specify a different appointment instead of accepting/declining.
     */
    private fun detectNewAppointmentDetails(message: String): Boolean {
        val lowerMessage = message.lowercase().trim()
        
        // Keywords that indicate user is specifying appointment details
        val appointmentKeywords = listOf(
            "blood test", "mri", "ct scan", "x-ray", "diagnostic", "ultrasound",
            "hospital", "clinic", "medical center", "healthcare",
            "tomorrow", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "morning", "afternoon", "evening", "pm", "am",
            "book", "schedule", "appointment"
        )
        
        // Check if message is longer and contains specific details
        val hasAppointmentKeywords = appointmentKeywords.any { lowerMessage.contains(it) }
        val isLongEnough = message.split(" ").size >= 3
        
        return hasAppointmentKeywords && isLongEnough
    }
    
    /**
     * Handle booking flow with an accepted suggestion.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun handleBookingWithSuggestion(
        sessionId: String,
        userId: String?,
        originalForm: AppointmentForm,
        suggestion: WorkTypeSuggestion
    ): Flow<ChatStreamEvent> = flow {
        logger.info("${LogColors.CHAT} Resuming booking with suggestion: ${suggestion.suggestedWorkType} at ${suggestion.suggestedLocation}")
        
        val introContent = buildString {
            appendLine("✅ Great choice! Let me book your **${suggestion.suggestedWorkType}** appointment at **${suggestion.suggestedLocation}**.")
            appendLine()
            appendLine("Continuing with the booking process...")
        }
        
        val introMessage = addMessage(sessionId, MessageRole.ASSISTANT, introContent, MessageType.THINKING)
        emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = introMessage))
        
        var finalResult: AppointmentResult? = null
        
        // Stream progress updates from orchestrator with suggestion
        orchestrator.bookAppointmentWithSuggestion(originalForm, suggestion).collect { progress ->
            when (progress) {
                is AppointmentProgress.SuggestionAccepted -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "✅ Using suggested appointment: ${progress.suggestion.suggestedWorkType} at ${progress.suggestion.suggestedLocation}"
                    ))
                }
                
                is AppointmentProgress.ValidatingAppointment -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "✅ Validating suggested appointment type..."
                    ))
                }
                
                is AppointmentProgress.ValidationComplete -> {
                    // Store workTypeGroupId if validation was successful
                    if (progress.validationResult.isValid && progress.validationResult.workTypeGroupId != null) {
                        // Also update the appointment form with the suggestion values
                        val updatedForm = originalForm.copy(
                            appointmentGroup = suggestion.suggestedWorkType,
                            location = suggestion.suggestedLocation
                        )
                        sessions.computeIfPresent(sessionId) { _, s ->
                            s.copy(
                                workTypeGroupId = progress.validationResult.workTypeGroupId,
                                appointmentForm = updatedForm,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                    }
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "✅ Validation complete: ${progress.validationResult.message}"
                    ))
                }
                
                is AppointmentProgress.FetchingBranches -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "🏥 Fetching available branches..."
                    ))
                }
                
                is AppointmentProgress.PromptBranchSelection -> {
                    val branches = progress.availableBranches
                    logger.info("${LogColors.CHAT} Branch selection prompt (from suggestion flow): ${branches.size} branches available")
                    
                    // Store the pending branches in the session
                    sessions.computeIfPresent(sessionId) { _, s ->
                        s.copy(
                            pendingBranchSelection = branches,
                            pendingSuggestion = null, // Clear suggestion since it's been accepted
                            conversationState = ConversationState.AWAITING_BRANCH_SELECTION,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    
                    val branchMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        progress.message,
                        MessageType.TEXT
                    )
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "branch_selection",
                        message = branchMessage,
                        awaitingResponse = true
                    ))
                    
                    // Emit done event since we're waiting for user response
                    emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true, awaitingResponse = true))
                }
                
                is AppointmentProgress.ValidatingServiceTerritory -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "📍 Validating service territory..."
                    ))
                }
                
                is AppointmentProgress.ServiceTerritoryValidationComplete -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "✅ Service territory validation complete: ${progress.validationResult.message}"
                    ))
                }
                
                is AppointmentProgress.ValidatingTimeslot -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "⏰ Validating timeslot availability..."
                    ))
                }
                
                is AppointmentProgress.TimeslotValidationComplete -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "✅ Timeslot validation complete: ${progress.validationResult.message}"
                    ))
                }
                
                is AppointmentProgress.BookingAppointment -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "📝 Booking your appointment..."
                    ))
                }
                
                is AppointmentProgress.BookingComplete -> {
                    finalResult = progress.result
                    logger.info("${LogColors.CHAT} Booking with suggestion complete in ${progress.totalDurationMs}ms")
                }
                
                is AppointmentProgress.Error -> {
                    logger.error("${LogColors.CHAT} Booking error: ${progress.message}")
                    updateConversationState(sessionId, ConversationState.COLLECTING_JOURNEY_DETAILS)
                    
                    val errorMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        "${progress.message} Would you like me to try again?",
                        MessageType.ERROR
                    )
                    emit(ChatStreamEvent(sessionId = sessionId, type = "error", message = errorMessage))
                }
                
                // Other progress events (not expected in this flow but handle gracefully)
                else -> {
                    logger.debug("${LogColors.CHAT} Unhandled progress event in suggestion flow: ${progress::class.simpleName}")
                }
            }
        }
        
        // Handle final result
        finalResult?.let { result ->
            sessions.computeIfPresent(sessionId) { _, s ->
                s.copy(
                    appointmentResult = result,
                    conversationState = ConversationState.PRESENTING_PLAN,
                    updatedAt = System.currentTimeMillis()
                )
            }
            
            val resultMessage = addMessage(sessionId, MessageRole.ASSISTANT, result.bookingMessage, MessageType.APPOINTMENT_RESULT)
            emit(ChatStreamEvent(
                sessionId = sessionId,
                type = "appointment_result",
                message = resultMessage,
                appointmentResult = result
            ))
        }
        
        emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
    }
    
    private suspend fun buildGreeting(): String = buildString {
        appendLine("Hello! I'm your hospital appointment booking assistant. 🏥")
        appendLine()
        appendLine("I can help you book appointments and provide weather information for your visit.")
        appendLine()
        appendLine("To get started, I'll need:")
                appendLine("• **Appointment type**")
        appendLine("• **Location** (hospital name or address)")
        appendLine("• **Date and time** (e.g., tomorrow at 2 PM, next Monday morning)")
        appendLine()
        appendLine("Just tell me what you need, and I'll help you book it!")
    }
    
    /**
     * Handle negotiation flow - collect missing journey details from user.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun handleNegotiation(
        sessionId: String,
        userId: String?,
        message: String,
        currentPartial: PartialAppointmentInfo?
    ): Flow<ChatStreamEvent> = flow {
        // Extract details from user message
        val partial = currentPartial ?: PartialAppointmentInfo()
        val updated = extractAppointmentDetails(message, partial)
        
        updatePartialAppointment(sessionId, updated)
        updateConversationState(sessionId, ConversationState.COLLECTING_JOURNEY_DETAILS)
        
        val response = if (updated.isComplete()) {
            // All required info collected - confirm before booking
            updateConversationState(sessionId, ConversationState.CONFIRMING_DETAILS)
            buildString {
                appendLine("Great! I have all the details I need. Here's what I've got:")
                appendLine()
                appendLine(updated.summary())
                appendLine()
                appendLine("Does this look correct? Say **\"yes, book it\"** to proceed, or tell me what to change.")
            }
        } else {
            // Ask for missing information
            val missing = updated.missingFields()
            buildString {
                if (updated != partial) {
                    appendLine("Got it! ✅")
                    appendLine()
                    if (updated.appointmentGroup != null || updated.location != null) {
                        appendLine("So far I have:")
                        appendLine(updated.summary())
                        appendLine()
                    }
                }
                
                when {
                    missing.size == 1 -> appendLine("I just need one more thing: **${missing.first()}**")
                    missing.size == 2 -> appendLine("I still need: **${missing[0]}** and **${missing[1]}**")
                    else -> {
                        appendLine("To book your appointment, I still need:")
                        missing.forEach { appendLine("• $it") }
                    }
                }
                
                appendLine()
                when (missing.firstOrNull()) {
                    "appointment type" -> appendLine("What type of appointment?")
                    "location" -> appendLine("Where is the appointment? (hospital name or address)")
                    "date and time" -> appendLine("When is the appointment? (e.g., tomorrow at 2 PM, next Monday morning)")
                    else -> appendLine("Please provide the missing information.")
                }
            }
        }
        
        val assistantMessage = addMessage(sessionId, MessageRole.ASSISTANT, response)
        emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = assistantMessage))
        emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
    }
    
    /**
     * Extract appointment details from natural language message.
     */
    private fun extractAppointmentDetails(message: String, current: PartialAppointmentInfo): PartialAppointmentInfo {
        val msgLower = message.lowercase()
        var updated = current
        
        // Extract appointment type - use LLM-extracted value if available, otherwise try simple keyword matching
        // The LLM classification should handle most cases, but we keep this as a fallback
        // Common appointment type keywords - these are just hints, the actual extraction
        // should come from the LLM classification in applyExtractedDetails
        
        // Extract location
        val locationPatterns = listOf("at ", "location ", "hospital ", "address ", "in ")
        for (pattern in locationPatterns) {
            if (msgLower.contains(pattern) && updated.location == null) {
                val location = message.substringAfter(pattern, "")
                    .split(Regex("[,.]|\\s+(on|at|for|with|by)\\s+"))
                    .firstOrNull()?.trim()?.takeIf { it.isNotBlank() && it.length > 2 }
                if (location != null) {
                    updated = updated.copy(location = location)
                    break
                }
            }
        }
        
        // Extract time
        val timePatterns = listOf("at ", "time ", "morning", "afternoon", "evening")
        for (pattern in timePatterns) {
            if (msgLower.contains(pattern) && updated.timeOfDay == null) {
                val time = message.substringAfter(pattern, "")
                    .split(Regex("[,.]|\\s+(on|at|for|with|by)\\s+"))
                    .firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
                if (time != null) {
                    updated = updated.copy(timeOfDay = time)
                    break
                }
            }
        }
        
        // Extract date
        val datePatterns = listOf(
            Regex("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})"),
            Regex("(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})"),
            Regex("(tomorrow|today|next week|next monday|next tuesday|next wednesday|next thursday|next friday|next saturday|next sunday)", RegexOption.IGNORE_CASE)
        )
        for (pattern in datePatterns) {
            val match = pattern.find(message)
            if (match != null && updated.date == null) {
                updated = updated.copy(date = match.value)
                break
            }
        }
        
        return updated
    }
    
    private fun updatePartialAppointment(sessionId: String, partial: PartialAppointmentInfo) {
        sessions.computeIfPresent(sessionId) { _, session ->
            session.copy(partialAppointment = partial, updatedAt = System.currentTimeMillis())
        }
    }
    
    /**
     * Convert PartialAppointmentInfo to a complete AppointmentForm for booking.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun buildAppointmentFormFromPartial(partial: PartialAppointmentInfo): AppointmentForm {
        val now = java.time.LocalDateTime.now()
        
        // Parse appointment time or default to tomorrow at 2 PM
        val tomorrow = now.plusDays(1)
        val defaultTime = LocalDateTime(tomorrow.year, tomorrow.month, tomorrow.dayOfMonth, 14, 0)
        val appointmentTime = parseAppointmentDateTime(partial.date, partial.timeOfDay) ?: defaultTime
        
        return AppointmentForm(
            appointmentGroup = partial.appointmentGroup ?: "Unknown Appointment Type",
            location = partial.location ?: "Unknown Location",
            appointmentTime = appointmentTime.toString(),
            patientName = partial.patientName,
            notes = partial.notes
        )
    }
    
    private fun parseAppointmentDateTime(dateStr: String?, timeStr: String?): LocalDateTime? {
        val jnow = java.time.LocalDateTime.now()
        val now = LocalDateTime(jnow.year, jnow.month, jnow.dayOfMonth, jnow.hour, jnow.minute)
        
        // Parse relative dates
        dateStr?.let { date ->
            val dateLower = date.lowercase()
            when {
                dateLower.contains("tomorrow") -> {
                    val jtomorrow = jnow.plusDays(1)
                    val tomorrow = LocalDateTime(jtomorrow.year, jtomorrow.month, jtomorrow.dayOfMonth, jtomorrow.hour, jtomorrow.minute)
                    return parseTime(timeStr, tomorrow) ?: LocalDateTime(tomorrow.year, tomorrow.month, tomorrow.day, 14, 0)
                }
                dateLower.contains("today") -> {
                    return parseTime(timeStr, now) ?: LocalDateTime(now.year, now.month, now.day, 14, 0)
                }
                dateLower.contains("next") -> {
                    // Simple: add 7 days
                    val jnextWeek = jnow.plusDays(7)
                    val nextWeek = LocalDateTime(jnextWeek.year, jnextWeek.month, jnextWeek.dayOfMonth, jnextWeek.hour, jnextWeek.minute)
                    return parseTime(timeStr, nextWeek) ?: LocalDateTime(nextWeek.year, nextWeek.month, nextWeek.day, 14, 0)
                }
            }
        }
        
        // Try parsing specific date
        return try {
            val date = dateStr?.let { LocalDateTime.parse(it) } ?: now
            parseTime(timeStr, date) ?: LocalDateTime(date.year, date.month, date.day, 14, 0)
        } catch (_: Exception) {
            parseTime(timeStr, now) ?: LocalDateTime(now.year, now.month, now.day, 14, 0)
        }
    }
    
    private fun parseTime(timeStr: String?, baseDate: LocalDateTime): LocalDateTime? {
        if (timeStr == null) return null
        val timeLower = timeStr.lowercase()
        
        // Parse time of day
        when {
            timeLower.contains("morning") -> return LocalDateTime(baseDate.year, baseDate.month, baseDate.day, 9, 0)
            timeLower.contains("afternoon") -> return LocalDateTime(baseDate.year, baseDate.month, baseDate.day, 14, 0)
            timeLower.contains("evening") -> return LocalDateTime(baseDate.year, baseDate.month, baseDate.day, 18, 0)
        }
        
        // Try parsing specific time like "2 PM" or "14:00"
        val timeMatch = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(timeStr)
        timeMatch?.let {
            var hour = it.groupValues[1].toIntOrNull() ?: return null
            val minute = it.groupValues[2].toIntOrNull() ?: 0
            val ampm = it.groupValues[3].lowercase()
            
            if (ampm == "pm" && hour != 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            
            return LocalDateTime(baseDate.year, baseDate.month, baseDate.day, hour, minute)
        }
        
        return null
    }
    
    @OptIn(ExperimentalUuidApi::class)
    private fun handleBookingFlow(
        sessionId: String,
        userId: String?,
        appointmentForm: AppointmentForm
    ): Flow<ChatStreamEvent> = flow {
        updateConversationState(sessionId, ConversationState.PLANNING)
        logger.info("${LogColors.CHAT} User triggered booking flow")
        logger.info("${LogColors.CHAT} Booking: ${appointmentForm.appointmentGroup} at ${appointmentForm.location}")
        
        val introContent = buildString {
            appendLine("Great! Let me book your **${appointmentForm.appointmentGroup}** appointment.")
            appendLine()
            appendLine("I'll check the location and weather, then send you a confirmation with all the details...")
        }
        
        val introMessage = addMessage(sessionId, MessageRole.ASSISTANT, introContent, MessageType.THINKING)
        emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = introMessage))
        
        var finalResult: AppointmentResult? = null
        
        // Stream progress updates from orchestrator
        orchestrator.bookAppointmentWithProgress(appointmentForm).collect { progress ->
            when (progress) {
                is AppointmentProgress.Started -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId, 
                        type = "progress", 
                        content = "🚀 Starting appointment booking..."
                    ))
                }
                
                is AppointmentProgress.ValidatingAppointment -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId, 
                        type = "progress", 
                        content = "✅ Validating appointment type and work type group..."
                    ))
                }
                
                is AppointmentProgress.ValidationComplete -> {
                    // Store workTypeGroupId if validation was successful
                    if (progress.validationResult.isValid && progress.validationResult.workTypeGroupId != null) {
                        sessions.computeIfPresent(sessionId) { _, s ->
                            s.copy(
                                workTypeGroupId = progress.validationResult.workTypeGroupId,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                    }
                    emit(ChatStreamEvent(
                        sessionId = sessionId, 
                        type = "progress", 
                        content = "✅ Validation complete: ${progress.validationResult.message}"
                    ))
                }
                
                is AppointmentProgress.ValidatingServiceTerritory -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId, 
                        type = "progress", 
                        content = "📍 Validating service territory/location..."
                    ))
                }
                
                is AppointmentProgress.ServiceTerritoryValidationComplete -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId, 
                        type = "progress", 
                        content = "✅ Service territory validation complete: ${progress.validationResult.message}"
                    ))
                }
                
                is AppointmentProgress.ValidatingTimeslot -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId, 
                        type = "progress", 
                        content = "⏰ Validating timeslot availability..."
                    ))
                }
                
                is AppointmentProgress.TimeslotValidationComplete -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId, 
                        type = "progress", 
                        content = "✅ Timeslot validation complete: ${progress.validationResult.message}"
                    ))
                }
                
                is AppointmentProgress.CheckingLocationReviews -> {
                    // Location reviews step removed - only used for suggestions now
                }
                
                is AppointmentProgress.LocationReviewsComplete -> {
                    // Location reviews step removed - only used for suggestions now
                }
                
                is AppointmentProgress.FetchingParkingInfo -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "🅿️ Checking parking availability..."
                    ))
                }
                
                is AppointmentProgress.ParkingInfoComplete -> {
                    // Show parking info as a separate detailed message
                    val parkingContent = buildString {
                        appendLine("🅿️ **Parking Information for ${progress.parkingInfo.location}**")
                        appendLine()
                        appendLine(progress.parkingInfo.summary)
                        progress.parkingInfo.parkingFees?.let { 
                            appendLine()
                            appendLine("💰 **Fees:** $it")
                        }
                        progress.parkingInfo.parkingTips?.let { 
                            appendLine()
                            appendLine("💡 **Tips:** $it")
                        }
                    }
                    
                    val parkingMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        parkingContent,
                        MessageType.TEXT
                    )
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "parking_info",
                        message = parkingMessage
                    ))
                }
                
                is AppointmentProgress.BookingAppointment -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId, 
                        type = "progress", 
                        content = "📝 Booking your appointment..."
                    ))
                }
                
                is AppointmentProgress.BookingComplete -> {
                    finalResult = progress.result
                    logger.info("${LogColors.CHAT} Booking complete in ${progress.totalDurationMs}ms")
                }
                
                is AppointmentProgress.Error -> {
                    logger.error("${LogColors.CHAT} Booking error: ${progress.message}")
                    updateConversationState(sessionId, ConversationState.COLLECTING_JOURNEY_DETAILS)
                    
                    val errorMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        "${progress.message} Would you like me to try again?",
                        MessageType.ERROR
                    )
                    emit(ChatStreamEvent(sessionId = sessionId, type = "error", message = errorMessage))
                }
                
                is AppointmentProgress.SearchingForSuggestions -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "🔍 The requested work type is not available. Searching for suggestions..."
                    ))
                }
                
                is AppointmentProgress.SuggestionFound -> {
                    val suggestion = progress.suggestion
                    logger.info("${LogColors.CHAT} Suggestion found: ${suggestion.suggestedWorkType} at ${suggestion.suggestedLocation}")
                    
                    // Store the pending suggestion in the session
                    sessions.computeIfPresent(sessionId) { _, s ->
                        s.copy(
                            pendingSuggestion = suggestion,
                            conversationState = ConversationState.AWAITING_SUGGESTION_RESPONSE,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    
                    // Build suggestion message with reviews
                    val suggestionContent = buildString {
                        appendLine("⚠️ **${suggestion.suggestionMessage}**")
                        appendLine()
                        suggestion.locationReviews?.let { reviews ->
                            appendLine("📋 **Reviews for ${suggestion.suggestedLocation}:**")
                            appendLine(reviews.reviewSummary)
                            reviews.rating?.let { appendLine("\n⭐ **Rating:** $it/5") }
                            if (reviews.highlights.isNotEmpty()) {
                                appendLine("\n✅ **Highlights:** ${reviews.highlights.joinToString(", ")}")
                            }
                            reviews.tips?.let { appendLine("\n💡 **Tips:** $it") }
                        }
                        appendLine()
                        appendLine("---")
                        appendLine("**Would you like to proceed with ${suggestion.suggestedWorkType} at ${suggestion.suggestedLocation}?**")
                        appendLine()
                        appendLine("• Reply **\"Yes\"** to book this appointment")
                        appendLine("• Or tell me a different appointment type/location you'd prefer")
                    }
                    
                    val suggestionMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        suggestionContent,
                        MessageType.TEXT
                    )
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "suggestion",
                        message = suggestionMessage,
                        awaitingResponse = true
                    ))
                    
                    // Emit done event since we're waiting for user response
                    emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true, awaitingResponse = true))
                }
                
                is AppointmentProgress.SuggestionAccepted -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "✅ Great! Proceeding with ${progress.suggestion.suggestedWorkType} at ${progress.suggestion.suggestedLocation}..."
                    ))
                }
                
                is AppointmentProgress.SuggestionDeclined -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "❌ Suggestion declined. Please provide a different appointment type."
                    ))
                }
                
                is AppointmentProgress.FetchingBranches -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "🏥 Fetching available branches..."
                    ))
                }
                
                is AppointmentProgress.PromptBranchSelection -> {
                    val branches = progress.availableBranches
                    logger.info("${LogColors.CHAT} Branch selection prompt: ${branches.size} branches available")
                    
                    // Get the workTypeGroupId from the validation result stored earlier
                    // We'll need to store it when validation completes
                    
                    // Store the pending branches in the session
                    sessions.computeIfPresent(sessionId) { _, s ->
                        s.copy(
                            pendingBranchSelection = branches,
                            conversationState = ConversationState.AWAITING_BRANCH_SELECTION,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    
                    val branchMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        progress.message,
                        MessageType.TEXT
                    )
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "branch_selection",
                        message = branchMessage,
                        awaitingResponse = true
                    ))
                    
                    // Emit done event since we're waiting for user response
                    emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true, awaitingResponse = true))
                }
                
                is AppointmentProgress.FindingClosestBranch -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "📍 Finding the closest branch to your location..."
                    ))
                }
                
                is AppointmentProgress.BranchSelectionComplete -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "✅ ${progress.message}"
                    ))
                }
                
                is AppointmentProgress.PromptTimeslotConfirmation -> {
                    // Store suggested time and update state
                    sessions.computeIfPresent(sessionId) { _, s ->
                        s.copy(
                            suggestedTimeslot = progress.suggestedTime,
                            conversationState = ConversationState.AWAITING_TIMESLOT_CONFIRMATION,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    
                    val timeslotMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        progress.message,
                        MessageType.TEXT
                    )
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "timeslot_confirmation",
                        message = timeslotMessage,
                        awaitingResponse = true
                    ))
                    
                    // Emit done event since we're waiting for user response
                    emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true, awaitingResponse = true))
                }
                
                is AppointmentProgress.TimeslotConfirmed -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "✅ Looking for slots around ${progress.confirmedTime}..."
                    ))
                }
                
                is AppointmentProgress.FetchingWeather -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "🌤️ Fetching weather forecast for your appointment day..."
                    ))
                }
                
                is AppointmentProgress.WeatherForecastComplete -> {
                    // Show weather as a separate detailed message
                    val weatherContent = buildString {
                        appendLine("🌤️ **Weather Forecast for ${progress.forecast.location} on ${progress.forecast.dateTime.date}**")
                        appendLine()
                        progress.forecast.summary?.let { appendLine(it) }
                    }
                    
                    val weatherMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        weatherContent,
                        MessageType.TEXT
                    )
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "weather_forecast",
                        message = weatherMessage
                    ))
                }
                
                is AppointmentProgress.PromptBookingConfirmation -> {
                    // Store booking details and update state
                    logger.info("${LogColors.CHAT} Received PromptBookingConfirmation: timeslotId=${progress.timeslotId}, timeslotInfo=${progress.timeslotInfo}, serviceTerritoryId=${progress.serviceTerritoryId}")
                    sessions.computeIfPresent(sessionId) { _, s ->
                        s.copy(
                            pendingTimeslotId = progress.timeslotId,
                            pendingSelectedTimeslot = progress.selectedTimeslot,
                            pendingTimeslotInfo = progress.timeslotInfo,
                            serviceTerritoryId = progress.serviceTerritoryId ?: s.serviceTerritoryId,
                            conversationState = ConversationState.AWAITING_BOOKING_CONFIRMATION,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    
                    val confirmMessage = addMessage(
                        sessionId,
                        MessageRole.ASSISTANT,
                        progress.message,
                        MessageType.TEXT
                    )
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "booking_confirmation",
                        message = confirmMessage,
                        awaitingResponse = true
                    ))
                    
                    // Emit done event since we're waiting for user response
                    emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true, awaitingResponse = true))
                }
                
                is AppointmentProgress.BookingConfirmed -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId,
                        type = "progress",
                        content = "✅ Booking confirmed! Processing your appointment..."
                    ))
                }
            }
        }
        
        // Handle final result (only if we didn't stop for booking confirmation)
        finalResult?.let { result ->
            // Update session with result
            sessions.computeIfPresent(sessionId) { _, s ->
                s.copy(
                    appointmentResult = result, 
                    conversationState = ConversationState.PRESENTING_PLAN,
                    updatedAt = System.currentTimeMillis()
                )
            }
            
            val resultMessage = addMessage(sessionId, MessageRole.ASSISTANT, result.bookingMessage, MessageType.APPOINTMENT_RESULT)
            emit(ChatStreamEvent(
                sessionId = sessionId, 
                type = "appointment_result", 
                message = resultMessage,
                appointmentResult = result
            ))
            
            emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
        }
    }
    
    fun cleanupOldSessions(maxAgeMs: Long = 3600000) {
        val now = System.currentTimeMillis()
        val keysToRemove = mutableListOf<String>()
        sessions.forEach { entry ->
            val session = entry.value
            if ((now - session.createdAt) > maxAgeMs) {
                keysToRemove.add(entry.key)
            }
        }
        keysToRemove.forEach { sessions.remove(it) }
    }
}
