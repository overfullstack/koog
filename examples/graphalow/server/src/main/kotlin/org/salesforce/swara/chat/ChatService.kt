package org.salesforce.swara.chat

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.executeStructured
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.demo.agent.a2a.AppointmentProgress
import org.jetbrains.demo.agent.a2a.SchedulerAgentOrchestrator
import org.jetbrains.demo.agent.a2a.model.*
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
        val now = Clock.System.now()
        val tz = TimeZone.currentSystemDefault()
        
        // Parse appointment time or default to tomorrow at 2 PM
        val defaultTime = (now + 1.days).toLocalDateTime(tz)
        val appointmentTime = parseAppointmentDateTime(partial.date, partial.timeOfDay)
            ?: LocalDateTime(defaultTime.year, defaultTime.monthNumber, defaultTime.dayOfMonth, 14, 0)
        
        return AppointmentForm(
            appointmentGroup = partial.appointmentGroup ?: "Unknown Appointment Type",
            location = partial.location ?: "Unknown Location",
            appointmentTime = appointmentTime,
            patientName = partial.patientName,
            notes = partial.notes
        )
    }
    
    private fun parseAppointmentDateTime(dateStr: String?, timeStr: String?): LocalDateTime? {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        
        // Parse relative dates
        dateStr?.let { date ->
            val dateLower = date.lowercase()
            when {
                dateLower.contains("tomorrow") -> {
                    val tomorrow = LocalDateTime(now.year, now.monthNumber, now.dayOfMonth + 1, now.hour, now.minute)
                    return parseTime(timeStr, tomorrow) ?: LocalDateTime(tomorrow.year, tomorrow.monthNumber, tomorrow.dayOfMonth, 14, 0)
                }
                dateLower.contains("today") -> {
                    return parseTime(timeStr, now) ?: LocalDateTime(now.year, now.monthNumber, now.dayOfMonth, 14, 0)
                }
                dateLower.contains("next") -> {
                    // Simple: add 7 days
                    val nextWeek = LocalDateTime(now.year, now.monthNumber, now.dayOfMonth + 7, now.hour, now.minute)
                    return parseTime(timeStr, nextWeek) ?: LocalDateTime(nextWeek.year, nextWeek.monthNumber, nextWeek.dayOfMonth, 14, 0)
                }
            }
        }
        
        // Try parsing specific date
        return try {
            val date = dateStr?.let { LocalDateTime.parse(it) } ?: now
            parseTime(timeStr, date) ?: LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, 14, 0)
        } catch (_: Exception) {
            parseTime(timeStr, now) ?: LocalDateTime(now.year, now.monthNumber, now.dayOfMonth, 14, 0)
        }
    }
    
    private fun parseTime(timeStr: String?, baseDate: LocalDateTime): LocalDateTime? {
        if (timeStr == null) return null
        val timeLower = timeStr.lowercase()
        
        // Parse time of day
        when {
            timeLower.contains("morning") -> return LocalDateTime(baseDate.year, baseDate.monthNumber, baseDate.dayOfMonth, 9, 0)
            timeLower.contains("afternoon") -> return LocalDateTime(baseDate.year, baseDate.monthNumber, baseDate.dayOfMonth, 14, 0)
            timeLower.contains("evening") -> return LocalDateTime(baseDate.year, baseDate.monthNumber, baseDate.dayOfMonth, 18, 0)
        }
        
        // Try parsing specific time like "2 PM" or "14:00"
        val timeMatch = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(timeStr)
        timeMatch?.let {
            var hour = it.groupValues[1].toIntOrNull() ?: return null
            val minute = it.groupValues[2].toIntOrNull() ?: 0
            val ampm = it.groupValues[3].lowercase()
            
            if (ampm == "pm" && hour != 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            
            return LocalDateTime(baseDate.year, baseDate.monthNumber, baseDate.dayOfMonth, hour, minute)
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
                
                is AppointmentProgress.CheckingLocationWeather -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId, 
                        type = "progress", 
                        content = "📍 Checking location and weather..."
                    ))
                }
                
                is AppointmentProgress.LocationWeatherComplete -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId, 
                        type = "progress", 
                        content = "✅ Weather checked: ${progress.weatherInfo.weather}"
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
                        "I encountered an issue: ${progress.message}\n\nWould you like me to try again?",
                        MessageType.ERROR
                    )
                    emit(ChatStreamEvent(sessionId = sessionId, type = "error", message = errorMessage))
                }
            }
        }
        
        // Handle final result
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
        }
        
        emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
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
