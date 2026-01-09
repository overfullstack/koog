package org.jetbrains.demo.agent.a2a

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.executeStructured
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.demo.JourneyForm
import org.jetbrains.demo.LLM_MODEL
import org.jetbrains.demo.TransportType
import org.jetbrains.demo.Traveler
import org.jetbrains.demo.agent.a2a.model.TravelPlanResult
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
    /** User wants to start planning a trip or confirms they're ready to plan */
    START_PLANNING,
    /** User is providing trip details (destination, dates, travelers, etc.) */
    PROVIDE_TRIP_DETAILS,
    /** User confirms the collected details are correct */
    CONFIRM_DETAILS,
    /** User wants to modify or correct previously provided details */
    MODIFY_DETAILS,
    /** User wants to update their saved preferences */
    UPDATE_PREFERENCES,
    /** User is asking about their past trips */
    QUERY_PAST_TRIPS,
    /** User wants to modify an existing travel plan */
    REFINE_PLAN,
    /** User is greeting or making small talk */
    GREETING,
    /** User is asking a question about travel or the service */
    ASK_QUESTION,
    /** Intent is unclear or doesn't fit other categories */
    OTHER
}

@Serializable
data class IntentClassification(
    val intent: UserIntent,
    val confidence: String,
    val extractedDetails: ExtractedTripDetails? = null
)

@Serializable
data class ExtractedTripDetails(
    val destination: String? = null,
    val origin: String? = null,
    val dates: String? = null,
    val duration: String? = null,
    val travelers: String? = null,
    val transport: String? = null,
    val interests: String? = null
)

@Serializable
enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}

@Serializable
enum class MessageType {
    TEXT, THINKING, TOOL_USE, TOOL_RESULT, PLAN_RESULT, ERROR
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
 * Tracks partially collected journey information during negotiation.
 * Fields are nullable - once all required fields are filled, we can create a JourneyForm.
 */
@Serializable
data class PartialJourneyInfo(
    val fromCity: String? = null,
    val toCity: String? = null,
    val transport: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val travelerNames: List<String> = emptyList(),
    val details: String? = null
) {
    fun missingFields(): List<String> = buildList {
        if (toCity == null) add("destination")
        if (fromCity == null) add("origin/departure city")
        if (startDate == null) add("travel dates")
        if (travelerNames.isEmpty()) add("who is traveling")
    }
    
    fun isComplete(): Boolean = toCity != null && fromCity != null && startDate != null && travelerNames.isNotEmpty()
    
    fun summary(): String = buildString {
        fromCity?.let { append("From: **$it**") }
        toCity?.let { if (isNotEmpty()) append(" → "); append("To: **$it**") }
        startDate?.let { append("\n📅 Dates: **$it**") }
        endDate?.let { append(" to **$it**") }
        transport?.let { append("\n🚂 Transport: **$it**") }
        if (travelerNames.isNotEmpty()) append("\n👥 Travelers: **${travelerNames.joinToString(", ")}**")
        details?.let { append("\n📝 Notes: $it") }
    }
}

@Serializable
data class ChatSession(
    val sessionId: String,
    val userId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val journeyForm: JourneyForm? = null,
    val partialJourney: PartialJourneyInfo? = null,
    val planResult: TravelPlanResult? = null,
    val conversationState: ConversationState = ConversationState.GREETING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class ChatRequest(
    val sessionId: String? = null,
    val userId: String? = null,
    val message: String,
    val journeyForm: JourneyForm? = null
)

@Serializable
data class ChatStreamEvent(
    val sessionId: String,
    val type: String,
    val content: String? = null,
    val message: ChatMessage? = null,
    val done: Boolean = false,
    val planResult: TravelPlanResult? = null
)

class ChatService(
    private val orchestrator: TravelOrchestratorAgent,
    private val promptExecutor: PromptExecutor,
    private val model: LLModel = LLM_MODEL
) {
    private val sessions = ConcurrentHashMap<String, ChatSession>()
    
    /**
     * Use LLM to semantically classify user intent and extract trip details.
     */
    private suspend fun classifyIntent(
        message: String,
        conversationState: ConversationState,
        hasJourneyForm: Boolean,
        hasPlanResult: Boolean
    ): IntentClassification {
        val contextInfo = buildString {
            appendLine("Current conversation state: $conversationState")
            appendLine("Has journey details collected: $hasJourneyForm")
            appendLine("Has existing travel plan: $hasPlanResult")
        }
        
        val classificationPrompt = prompt("intent-classifier") {
            system {
                +"""
                You are an intent classifier for a travel planning assistant.
                Analyze the user's message and classify their intent.
                
                Context:
                $contextInfo
                
                Available intents:
                - START_PLANNING: User wants to start planning or confirms ready to plan (e.g., "let's plan", "yes, go ahead", "create my trip", "start")
                - PROVIDE_TRIP_DETAILS: User is giving trip info like destination, dates, travelers (e.g., "I want to go to Rome", "from Paris", "next week", "just me")
                - CONFIRM_DETAILS: User confirms collected details are correct (e.g., "yes that's right", "looks good", "correct", "perfect")
                - MODIFY_DETAILS: User wants to change something (e.g., "actually change the date", "not Rome, I meant Paris")
                - UPDATE_PREFERENCES: User wants to save preferences (e.g., "remember my home city", "save my preferences")
                - QUERY_PAST_TRIPS: User asks about previous trips (e.g., "show my past trips", "where did I go before")
                - REFINE_PLAN: User wants to modify an existing plan (e.g., "add more restaurants", "change day 2")
                - GREETING: Simple greeting or small talk (e.g., "hello", "hi there", "how are you")
                - ASK_QUESTION: User is asking a question (e.g., "what can you do?", "how does this work?")
                - OTHER: Doesn't fit other categories
                
                Also extract any trip details mentioned in the message.
                
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
            msgLower.containsAny("preference", "settings", "update my", "remember") -> UserIntent.UPDATE_PREFERENCES
            msgLower.containsAny("past trip", "previous trip", "history", "traveled before") -> UserIntent.QUERY_PAST_TRIPS
            state == ConversationState.PRESENTING_PLAN && 
                msgLower.containsAny("change", "modify", "different", "instead", "add", "remove") -> UserIntent.REFINE_PLAN
            state == ConversationState.CONFIRMING_DETAILS && 
                msgLower.containsAny("yes", "correct", "right", "plan it", "go ahead", "looks good", "perfect") -> UserIntent.CONFIRM_DETAILS
            msgLower.containsAny("plan", "start", "yes", "go", "create", "make") -> UserIntent.START_PLANNING
            msgLower.containsAny("trip", "travel", "go to", "visit", "vacation", "holiday", "from", "to") -> UserIntent.PROVIDE_TRIP_DETAILS
            msgLower.containsAny("hello", "hi", "hey", "good morning", "good afternoon") -> UserIntent.GREETING
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

    fun updateJourneyForm(sessionId: String, journeyForm: JourneyForm) {
        sessions.computeIfPresent(sessionId) { _, session ->
            session.copy(journeyForm = journeyForm, updatedAt = System.currentTimeMillis())
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun chat(request: ChatRequest): Flow<ChatStreamEvent> = flow {
        logger.info(LogColors.chatBanner("CHAT REQUEST"))
        logger.info("${LogColors.CHAT} Session: ${request.sessionId ?: "new"}, User: ${request.userId ?: "anonymous"}")
        logger.info("${LogColors.USER_INPUT} Message: ${LogColors.userInput(request.message)}")
        logJourneyFormIfPresent(request.journeyForm)
        
        // Get or create session, linking to user if provided
        val session = request.sessionId?.let { sessions[it] } 
            ?: createSession(request.userId).also { sessions[it.sessionId] = it }
        val sessionId = session.sessionId
        val userId = request.userId ?: session.userId
        
        // Load user preferences if userId is available
        val isReturningUser = userId != null && UserPreferenceStore.hasPreferences(userId)
        
        logger.info("${LogColors.CHAT} Returning user: $isReturningUser")
        
        // Add user message to history
        val userMessage = addMessage(sessionId, MessageRole.USER, request.message)
        emit(ChatStreamEvent(sessionId = sessionId, type = "user_message", message = userMessage))

        // Update journey form if provided
        if (request.journeyForm != null) {
            logger.info("${LogColors.CHAT} Updating journey form for session $sessionId")
            updateJourneyForm(sessionId, request.journeyForm)
        }

        val journeyForm = request.journeyForm ?: session.journeyForm
        val currentState = session.conversationState
        
        logger.debug("${LogColors.CHAT} Current state: $currentState, Journey form: ${journeyForm != null}")

        // Use LLM to classify user intent semantically
        val classification = classifyIntent(
            message = request.message,
            conversationState = currentState,
            hasJourneyForm = journeyForm != null,
            hasPlanResult = session.planResult != null
        )
        logger.info("${LogColors.CHAT} Intent: ${classification.intent} (confidence: ${classification.confidence})")

        // Route based on classified intent
        when {
            // New conversation - greet and check for returning user
            currentState == ConversationState.GREETING && journeyForm == null && 
            classification.intent == UserIntent.GREETING -> {
                val greeting = buildGreeting(userId, isReturningUser)
                val assistantMessage = addMessage(sessionId, MessageRole.ASSISTANT, greeting)
                updateConversationState(sessionId, ConversationState.COLLECTING_JOURNEY_DETAILS)
                emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = assistantMessage))
                emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
            }
            
            // User wants to update preferences
            classification.intent == UserIntent.UPDATE_PREFERENCES -> {
                handlePreferenceUpdate(sessionId, userId, request.message)
                    .collect { emit(it) }
            }
            
            // User asks about their past trips
            classification.intent == UserIntent.QUERY_PAST_TRIPS -> {
                handlePastTripsQuery(sessionId, userId)
                    .collect { emit(it) }
            }
            
            // User wants to refine an existing plan
            classification.intent == UserIntent.REFINE_PLAN && session.planResult != null -> {
                handlePlanRefinement(sessionId, request.message, session.planResult)
                    .collect { emit(it) }
            }
            
            // User confirms they want to start planning (with full JourneyForm)
            classification.intent == UserIntent.START_PLANNING && journeyForm != null -> {
                handlePlanningFlow(sessionId, userId, journeyForm)
                    .collect { emit(it) }
            }
            
            // User confirms partial journey details - convert to JourneyForm and plan
            classification.intent == UserIntent.CONFIRM_DETAILS && 
            currentState == ConversationState.CONFIRMING_DETAILS -> {
                val partial = session.partialJourney
                if (partial != null && partial.isComplete()) {
                    val form = buildJourneyFormFromPartial(partial)
                    updateJourneyForm(sessionId, form)
                    handlePlanningFlow(sessionId, userId, form)
                        .collect { emit(it) }
                } else {
                    handleNegotiation(sessionId, userId, request.message, partial)
                        .collect { emit(it) }
                }
            }
            
            // User wants to start planning but we need to collect details first
            classification.intent == UserIntent.START_PLANNING && journeyForm == null -> {
                val partial = session.partialJourney
                if (partial != null && partial.isComplete()) {
                    val form = buildJourneyFormFromPartial(partial)
                    updateJourneyForm(sessionId, form)
                    handlePlanningFlow(sessionId, userId, form)
                        .collect { emit(it) }
                } else {
                    handleNegotiation(sessionId, userId, request.message, partial)
                        .collect { emit(it) }
                }
            }
            
            // Have journey form but user is discussing/asking questions
            classification.intent == UserIntent.ASK_QUESTION && journeyForm != null -> {
                handleConversation(sessionId, userId, request.message, journeyForm)
                    .collect { emit(it) }
            }
            
            // User is providing trip details or modifying - continue negotiation
            classification.intent in listOf(UserIntent.PROVIDE_TRIP_DETAILS, UserIntent.MODIFY_DETAILS) ||
            currentState == ConversationState.COLLECTING_JOURNEY_DETAILS ||
            currentState == ConversationState.CONFIRMING_DETAILS -> {
                // Apply extracted details from LLM classification
                val enrichedPartial = applyExtractedDetails(session.partialJourney, classification.extractedDetails)
                handleNegotiation(sessionId, userId, request.message, enrichedPartial)
                    .collect { emit(it) }
            }
            
            // Default - prompt user to tell us about their trip
            else -> {
                val response = buildString {
                    appendLine("I'm here to help you plan amazing trips! 🌍")
                    appendLine()
                    appendLine("Just tell me where you'd like to go, and I'll help you plan everything.")
                    appendLine()
                    appendLine("For example, you could say:")
                    appendLine("• *\"I want to visit Rome\"*")
                    appendLine("• *\"Plan a trip to Tokyo from London\"*")
                    appendLine("• *\"I'm thinking about a beach vacation\"*")
                }
                val assistantMessage = addMessage(sessionId, MessageRole.ASSISTANT, response)
                emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = assistantMessage))
                emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
            }
        }
    }
    
    /**
     * Apply extracted trip details from LLM classification to partial journey info.
     */
    private fun applyExtractedDetails(
        current: PartialJourneyInfo?,
        extracted: ExtractedTripDetails?
    ): PartialJourneyInfo {
        if (extracted == null) return current ?: PartialJourneyInfo()
        val base = current ?: PartialJourneyInfo()
        return base.copy(
            toCity = extracted.destination ?: base.toCity,
            fromCity = extracted.origin ?: base.fromCity,
            startDate = extracted.dates ?: base.startDate,
            endDate = extracted.duration ?: base.endDate,
            transport = extracted.transport ?: base.transport,
            travelerNames = if (extracted.travelers != null) listOf(extracted.travelers) else base.travelerNames,
            details = extracted.interests ?: base.details
        )
    }
    
    private fun logJourneyFormIfPresent(journeyForm: JourneyForm?) {
        if (journeyForm != null) {
            logger.info("${LogColors.USER_INPUT} Journey Details:")
            logger.info("${LogColors.USER_INPUT}   From: ${LogColors.userInput(journeyForm.fromCity)}")
            logger.info("${LogColors.USER_INPUT}   To: ${LogColors.userInput(journeyForm.toCity)}")
            logger.info("${LogColors.USER_INPUT}   Dates: ${LogColors.userInput("${journeyForm.startDate} to ${journeyForm.endDate}")}")
            logger.info("${LogColors.USER_INPUT}   Transport: ${LogColors.userInput(journeyForm.transport.name)}")
            logger.info("${LogColors.USER_INPUT}   Travelers: ${LogColors.userInput(journeyForm.travelers.joinToString { it.name })}")
            journeyForm.details?.let { logger.info("${LogColors.USER_INPUT}   Notes: ${LogColors.userInput(it)}") }
        }
    }
    
    private fun String.containsAny(vararg keywords: String): Boolean = 
        keywords.any { this.contains(it, ignoreCase = true) }
    
    private suspend fun buildGreeting(userId: String?, isReturningUser: Boolean): String = buildString {
        if (isReturningUser && userId != null) {
            val name = UserPreferenceStore.getName(userId) ?: "traveler"
            appendLine("Welcome back, **$name**! 🌍")
            appendLine()
            appendLine("Great to see you again! Here's what I remember about your preferences:")
            appendLine()
            UserPreferenceStore.buildPreferenceSummary(userId)?.let { appendLine(it) }
            appendLine()
            val pastTrips = UserPreferenceStore.getPastTrips(userId)
            if (pastTrips.isNotEmpty()) {
                val lastTrip = pastTrips.last()
                appendLine("Your last trip was to **${lastTrip.toCity}** - how was it?")
                appendLine()
            }
            appendLine("Ready to plan your next adventure? Tell me where you'd like to go!")
        } else {
            appendLine("Hello! I'm your AI travel planning assistant. 🌍")
            appendLine()
            appendLine("I can help you create personalized travel plans with:")
            appendLine("• Optimal routes between destinations")
            appendLine("• Points of interest research")
            appendLine("• Daily itineraries tailored to your interests")
            appendLine()
            if (userId != null) {
                appendLine("Since this is your first time, I'll remember your preferences for future trips!")
                appendLine()
            }
            appendLine("To get started, tell me:")
            appendLine("• **Where** are you starting from and where do you want to go?")
            appendLine("• **When** do you want to travel?")
            appendLine("• **Who** is traveling with you?")
        }
    }
    
    @OptIn(ExperimentalUuidApi::class)
    private fun handlePreferenceUpdate(
        sessionId: String, 
        userId: String?, 
        message: String
    ): Flow<ChatStreamEvent> = flow {
        if (userId == null) {
            val response = "To save your preferences, please provide a user ID when starting the chat. " +
                          "This helps me remember your preferences for future sessions!"
            val assistantMessage = addMessage(sessionId, MessageRole.ASSISTANT, response)
            emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = assistantMessage))
            emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
            return@flow
        }
        
        val response = buildString {
            appendLine("I can update your preferences! What would you like to change?")
            appendLine()
            appendLine("You can tell me things like:")
            appendLine("• \"My home city is Paris\"")
            appendLine("• \"I prefer traveling by train\"")
            appendLine("• \"I'm interested in history and food\"")
            appendLine("• \"My name is Alex\"")
            appendLine("• \"I have a moderate budget\"")
            appendLine()
            appendLine("Or provide details in your message and I'll save them!")
        }
        
        // Simple preference extraction from message
        val msgLower = message.lowercase()
        val updates = mutableListOf<String>()
        
        when {
            msgLower.contains("name is") -> {
                val name = message.substringAfter("name is").trim().split(" ").first()
                UserPreferenceStore.setName(userId, name)
                updates.add("Name: $name")
            }
            msgLower.contains("home") && msgLower.containsAny("city", "from", "live") -> {
                val city = extractCity(message)
                city?.let { 
                    UserPreferenceStore.setHomeCity(userId, it)
                    updates.add("Home city: $it")
                }
            }
            msgLower.containsAny("prefer", "like") && msgLower.containsAny("train", "car", "flight", "bus") -> {
                val transport = extractTransport(message)
                transport?.let {
                    UserPreferenceStore.setPreferredTransport(userId, it)
                    updates.add("Preferred transport: $it")
                }
            }
        }
        
        val finalResponse = if (updates.isNotEmpty()) {
            "Got it! I've updated:\n${updates.joinToString("\n") { "• $it" }}\n\nAnything else?"
        } else {
            response
        }
        
        val assistantMessage = addMessage(sessionId, MessageRole.ASSISTANT, finalResponse)
        emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = assistantMessage))
        emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
    }
    
    private fun extractCity(message: String): String? {
        val patterns = listOf("city is ", "from ", "live in ", "based in ")
        for (pattern in patterns) {
            if (message.lowercase().contains(pattern)) {
                return message.substringAfter(pattern, "")
                    .split(Regex("[,.]"))
                    .firstOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }
    
    private fun extractTransport(message: String): String? {
        val msgLower = message.lowercase()
        return when {
            msgLower.contains("train") -> "Train"
            msgLower.contains("car") || msgLower.contains("driv") -> "Car"
            msgLower.contains("flight") || msgLower.contains("fly") || msgLower.contains("plane") -> "Flight"
            msgLower.contains("bus") -> "Bus"
            msgLower.contains("boat") || msgLower.contains("ferry") || msgLower.contains("cruise") -> "Boat"
            else -> null
        }
    }
    
    /**
     * Extract journey details from natural language message.
     * Returns updated PartialJourneyInfo with any new information found.
     */
    private fun extractJourneyDetails(message: String, current: PartialJourneyInfo, userId: String?): PartialJourneyInfo {
        val msgLower = message.lowercase()
        var updated = current
        
        // Extract destination (to city)
        val toPatterns = listOf("to ", "visit ", "go to ", "travel to ", "trip to ", "heading to ", "destination ")
        for (pattern in toPatterns) {
            if (msgLower.contains(pattern)) {
                val city = message.substringAfter(pattern, "")
                    .split(Regex("[,.]|\\s+(from|on|in|for|with|by|around|between)\\s+"))
                    .firstOrNull()?.trim()?.takeIf { it.isNotBlank() && it.length > 1 }
                if (city != null && updated.toCity == null) {
                    updated = updated.copy(toCity = city.replaceFirstChar { it.uppercase() })
                    break
                }
            }
        }
        
        // Extract origin (from city) - also check user's home city as default
        val fromPatterns = listOf("from ", "leaving ", "departing ", "starting from ", "origin ")
        for (pattern in fromPatterns) {
            if (msgLower.contains(pattern)) {
                val city = message.substringAfter(pattern, "")
                    .split(Regex("[,.]|\\s+(to|on|in|for|with|by)\\s+"))
                    .firstOrNull()?.trim()?.takeIf { it.isNotBlank() && it.length > 1 }
                if (city != null) {
                    updated = updated.copy(fromCity = city.replaceFirstChar { it.uppercase() })
                    break
                }
            }
        }
        
        // Extract dates - look for patterns like "March 15", "15th March", "2026-03-15", "next week"
        val datePatterns = listOf(
            Regex("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})"),  // DD/MM/YYYY or MM/DD/YYYY
            Regex("(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})"),    // YYYY-MM-DD
            Regex("(january|february|march|april|may|june|july|august|september|october|november|december)\\s+(\\d{1,2})", RegexOption.IGNORE_CASE),
            Regex("(\\d{1,2})(?:st|nd|rd|th)?\\s+(january|february|march|april|may|june|july|august|september|october|november|december)", RegexOption.IGNORE_CASE)
        )
        for (pattern in datePatterns) {
            val match = pattern.find(message)
            if (match != null && updated.startDate == null) {
                updated = updated.copy(startDate = match.value)
                break
            }
        }
        
        // Look for duration or end date
        if (msgLower.contains("for ") && msgLower.containsAny("day", "week", "night")) {
            val durationMatch = Regex("for\\s+(\\d+|a|one|two|three|four|five|six|seven)\\s+(day|week|night)", RegexOption.IGNORE_CASE).find(message)
            durationMatch?.let { updated = updated.copy(endDate = it.value) }
        }
        
        // Extract transport
        val transport = extractTransport(message)
        if (transport != null && updated.transport == null) {
            updated = updated.copy(transport = transport)
        }
        
        // Extract travelers - look for names or "solo", "alone", "with family", etc.
        when {
            msgLower.containsAny("solo", "alone", "by myself", "just me") -> {
                if (updated.travelerNames.isEmpty()) {
                    val userName = userId?.let { runCatching { kotlinx.coroutines.runBlocking { UserPreferenceStore.getName(it) } }.getOrNull() } ?: "Me"
                    updated = updated.copy(travelerNames = listOf(userName))
                }
            }
            msgLower.containsAny("with my partner", "with my wife", "with my husband", "couple") -> {
                updated = updated.copy(travelerNames = listOf("Traveler 1", "Traveler 2"))
            }
            msgLower.containsAny("family", "kids", "children") -> {
                updated = updated.copy(travelerNames = listOf("Family"))
            }
            msgLower.containsAny("friends", "group") -> {
                updated = updated.copy(travelerNames = listOf("Group"))
            }
            msgLower.contains("with ") -> {
                val withMatch = Regex("with\\s+([A-Z][a-z]+(?:\\s+and\\s+[A-Z][a-z]+)*)").find(message)
                withMatch?.let { 
                    val names = it.groupValues[1].split(Regex("\\s+and\\s+")).map { n -> n.trim() }
                    if (names.isNotEmpty()) updated = updated.copy(travelerNames = names)
                }
            }
        }
        
        // Extract additional details/interests
        val interestKeywords = listOf("interested in", "love", "enjoy", "want to see", "looking for", "prefer")
        for (keyword in interestKeywords) {
            if (msgLower.contains(keyword)) {
                val detail = message.substringAfter(keyword).trim().take(100)
                if (detail.isNotBlank()) {
                    updated = updated.copy(details = (updated.details?.plus("; ") ?: "") + detail)
                    break
                }
            }
        }
        
        return updated
    }
    
    private fun updatePartialJourney(sessionId: String, partial: PartialJourneyInfo) {
        sessions.computeIfPresent(sessionId) { _, session ->
            session.copy(partialJourney = partial, updatedAt = System.currentTimeMillis())
        }
    }
    
    /**
     * Convert PartialJourneyInfo to a complete JourneyForm for planning.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun buildJourneyFormFromPartial(partial: PartialJourneyInfo): JourneyForm {
        val now = Clock.System.now()
        val tz = TimeZone.currentSystemDefault()
        
        // Parse start date or default to tomorrow
        val startDateTime = parseDate(partial.startDate) 
            ?: (now + 1.days).toLocalDateTime(tz)
        
        // Parse end date or default to start + 3 days
        val endDateTime = parseDate(partial.endDate) 
            ?: parseDuration(partial.endDate, startDateTime)
            ?: (now + 4.days).toLocalDateTime(tz)
        
        // Parse transport type
        val transport = when (partial.transport?.lowercase()) {
            "train" -> TransportType.Train
            "car", "drive" -> TransportType.Car
            "flight", "plane", "fly" -> TransportType.Plane
            "bus" -> TransportType.Bus
            "boat", "ferry", "cruise" -> TransportType.Boat
            else -> TransportType.Train // default
        }
        
        // Build travelers list
        val travelers = partial.travelerNames.map { name ->
            Traveler(id = Uuid.random().toString(), name = name)
        }.toPersistentList()
        
        return JourneyForm(
            fromCity = partial.fromCity ?: "Unknown",
            toCity = partial.toCity ?: "Unknown",
            transport = transport,
            startDate = startDateTime,
            endDate = endDateTime,
            travelers = travelers,
            details = partial.details
        )
    }
    
    private fun parseDate(dateStr: String?): LocalDateTime? {
        if (dateStr == null) return null
        return try {
            // Try ISO format first
            LocalDateTime.parse(dateStr)
        } catch (_: Exception) {
            try {
                // Try parsing common formats
                val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                val months = mapOf(
                    "january" to 1, "february" to 2, "march" to 3, "april" to 4,
                    "may" to 5, "june" to 6, "july" to 7, "august" to 8,
                    "september" to 9, "october" to 10, "november" to 11, "december" to 12
                )
                val lower = dateStr.lowercase()
                for ((month, num) in months) {
                    if (lower.contains(month)) {
                        val dayMatch = Regex("(\\d{1,2})").find(dateStr)
                        val day = dayMatch?.value?.toIntOrNull() ?: 1
                        return LocalDateTime(now.year, num, day, 9, 0)
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }
    
    private fun parseDuration(durationStr: String?, startDate: LocalDateTime): LocalDateTime? {
        if (durationStr == null) return null
        val lower = durationStr.lowercase()
        val numMatch = Regex("(\\d+|one|two|three|four|five|six|seven)").find(lower)
        val num = when (numMatch?.value) {
            "one", "a" -> 1
            "two" -> 2
            "three" -> 3
            "four" -> 4
            "five" -> 5
            "six" -> 6
            "seven" -> 7
            else -> numMatch?.value?.toIntOrNull() ?: return null
        }
        val daysToAdd = when {
            lower.contains("week") -> num * 7
            lower.contains("day") || lower.contains("night") -> num
            else -> num
        }
        return LocalDateTime(
            startDate.year, startDate.monthNumber, startDate.dayOfMonth + daysToAdd,
            startDate.hour, startDate.minute
        )
    }
    
    /**
     * Handle negotiation flow - collect missing journey details from user.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun handleNegotiation(
        sessionId: String,
        userId: String?,
        message: String,
        currentPartial: PartialJourneyInfo?
    ): Flow<ChatStreamEvent> = flow {
        // Extract details from user message
        val partial = currentPartial ?: PartialJourneyInfo()
        val updated = extractJourneyDetails(message, partial, userId)
        
        // Apply user's home city as default origin if not specified
        val withDefaults = if (updated.fromCity == null && userId != null) {
            val homeCity = UserPreferenceStore.getHomeCity(userId)
            if (homeCity != null) updated.copy(fromCity = homeCity) else updated
        } else updated
        
        // Apply user's preferred transport as default
        val withTransport = if (withDefaults.transport == null && userId != null) {
            val prefTransport = UserPreferenceStore.getPreferredTransport(userId)
            if (prefTransport != null) withDefaults.copy(transport = prefTransport) else withDefaults
        } else withDefaults
        
        updatePartialJourney(sessionId, withTransport)
        updateConversationState(sessionId, ConversationState.COLLECTING_JOURNEY_DETAILS)
        
        val response = if (withTransport.isComplete()) {
            // All required info collected - confirm before planning
            updateConversationState(sessionId, ConversationState.CONFIRMING_DETAILS)
            buildString {
                appendLine("Great! I have all the details I need. Here's what I've got:")
                appendLine()
                appendLine(withTransport.summary())
                appendLine()
                appendLine("Does this look correct? Say **\"yes, plan it\"** to proceed, or tell me what to change.")
            }
        } else {
            // Ask for missing information
            val missing = withTransport.missingFields()
            buildString {
                if (withTransport != partial) {
                    appendLine("Got it! ✅")
                    appendLine()
                    if (withTransport.toCity != null || withTransport.fromCity != null) {
                        appendLine("So far I have:")
                        appendLine(withTransport.summary())
                        appendLine()
                    }
                }
                
                when {
                    missing.size == 1 -> appendLine("I just need one more thing: **${missing.first()}**")
                    missing.size == 2 -> appendLine("I still need: **${missing[0]}** and **${missing[1]}**")
                    else -> {
                        appendLine("To plan your trip, I still need:")
                        missing.forEach { appendLine("• $it") }
                    }
                }
                
                appendLine()
                when (missing.first()) {
                    "destination" -> appendLine("Where would you like to go?")
                    "origin/departure city" -> appendLine("Where will you be traveling from?")
                    "travel dates" -> appendLine("When do you want to travel? (e.g., \"March 15 for 5 days\")")
                    "who is traveling" -> appendLine("Who's going on this trip? (solo, couple, family, etc.)")
                }
            }
        }
        
        val assistantMessage = addMessage(sessionId, MessageRole.ASSISTANT, response)
        emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = assistantMessage))
        emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
    }
    
    @OptIn(ExperimentalUuidApi::class)
    private fun handlePastTripsQuery(sessionId: String, userId: String?): Flow<ChatStreamEvent> = flow {
        val response = if (userId == null) {
            "I don't have your trip history since you're not logged in. " +
            "Provide a user ID to track and remember your trips!"
        } else {
            val pastTrips = UserPreferenceStore.getPastTrips(userId)
            if (pastTrips.isEmpty()) {
                "You haven't taken any trips with me yet! Let's plan your first adventure. " +
                "Where would you like to go?"
            } else {
                buildString {
                    appendLine("Here are your past trips:")
                    appendLine()
                    pastTrips.forEachIndexed { idx, trip ->
                        appendLine("**${idx + 1}. ${trip.fromCity} → ${trip.toCity}** (${trip.date})")
                        trip.rating?.let { appendLine("   Rating: ${"⭐".repeat(it)}") }
                        trip.notes?.let { appendLine("   Notes: $it") }
                    }
                    appendLine()
                    appendLine("Would you like to revisit any of these destinations or try somewhere new?")
                }
            }
        }
        
        val assistantMessage = addMessage(sessionId, MessageRole.ASSISTANT, response)
        emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = assistantMessage))
        emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
    }
    
    @OptIn(ExperimentalUuidApi::class)
    private fun handlePlanRefinement(
        sessionId: String, 
        message: String, 
        existingPlan: TravelPlanResult?
    ): Flow<ChatStreamEvent> = flow {
        updateConversationState(sessionId, ConversationState.REFINING_PLAN)
        
        val response = if (existingPlan == null) {
            "I don't have a plan to modify yet. Would you like me to create one first?"
        } else {
            buildString {
                appendLine("I understand you'd like to modify the plan. You mentioned: *\"$message\"*")
                appendLine()
                appendLine("Current plan: **${existingPlan.title}**")
                appendLine()
                appendLine("To help me refine it, could you tell me:")
                appendLine("• Which specific day or activity you'd like to change?")
                appendLine("• What you'd prefer instead?")
                appendLine()
                appendLine("Or if you'd like a completely new approach, just say **\"start fresh\"**!")
            }
        }
        
        val assistantMessage = addMessage(sessionId, MessageRole.ASSISTANT, response)
        emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = assistantMessage))
        emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
    }
    
    @OptIn(ExperimentalUuidApi::class)
    private fun handleConversation(
        sessionId: String,
        userId: String?,
        message: String, 
        journeyForm: JourneyForm
    ): Flow<ChatStreamEvent> = flow {
        val response = buildString {
            appendLine("Thanks for the additional input! Here's your current trip summary:")
            appendLine()
            appendLine("• **From:** ${journeyForm.fromCity}")
            appendLine("• **To:** ${journeyForm.toCity}")
            appendLine("• **Dates:** ${journeyForm.startDate} to ${journeyForm.endDate}")
            appendLine("• **Transport:** ${journeyForm.transport}")
            appendLine("• **Travelers:** ${journeyForm.travelers.joinToString { it.name }}")
            journeyForm.details?.let { appendLine("• **Notes:** $it") }
            appendLine()
            
            // Add personalized suggestions based on preferences
            if (userId != null) {
                val interests = UserPreferenceStore.getTravelInterests(userId)
                if (interests.isNotEmpty()) {
                    appendLine("Based on your interests in ${interests.joinToString(", ") { it.name.lowercase() }}, " +
                              "I'll make sure to include relevant recommendations!")
                    appendLine()
                }
            }
            
            appendLine("Ready to create your personalized travel plan? Just say **\"start planning\"**!")
            appendLine()
            appendLine("Or tell me more about what you're looking for in this trip.")
        }
        
        val assistantMessage = addMessage(sessionId, MessageRole.ASSISTANT, response)
        emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = assistantMessage))
        emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
    }
    
    @OptIn(ExperimentalUuidApi::class)
    private fun handlePlanningFlow(
        sessionId: String,
        userId: String?,
        journeyForm: JourneyForm
    ): Flow<ChatStreamEvent> = flow {
        updateConversationState(sessionId, ConversationState.PLANNING)
        logger.info("${LogColors.CHAT} User triggered planning flow")
        logger.info("${LogColors.CHAT} Planning: ${journeyForm.fromCity} -> ${journeyForm.toCity}")
        
        // Personalized intro message
        val introContent = buildString {
            append("Great! Let me plan your trip from **${journeyForm.fromCity}** to **${journeyForm.toCity}**")
            if (userId != null) {
                val interests = UserPreferenceStore.getTravelInterests(userId)
                if (interests.isNotEmpty()) {
                    append(", focusing on ${interests.take(2).joinToString(" and ") { it.name.lowercase() }}")
                }
            }
            appendLine(".")
            appendLine()
            appendLine("I'll keep you updated as I work through the planning steps...")
        }
        
        val introMessage = addMessage(sessionId, MessageRole.ASSISTANT, introContent, MessageType.THINKING)
        emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = introMessage))
        
        var finalPlan: TravelPlanResult? = null
        
        // Stream progress updates from orchestrator
        orchestrator.planTravelWithProgress(journeyForm).collect { progress ->
            when (progress) {
                is PlanningProgress.Started -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId, 
                        type = "progress", 
                        content = "🚀 Starting trip planning..."
                    ))
                }
                
                is PlanningProgress.RoutePlannerStarted -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId, 
                        type = "progress", 
                        content = "🗺️ Finding the best route and points of interest..."
                    ))
                }
                
                is PlanningProgress.RoutePlannerComplete -> {
                    val poiList = progress.pointsOfInterest.take(5).joinToString(", ")
                    val moreText = if (progress.pointsOfInterest.size > 5) 
                        " and ${progress.pointsOfInterest.size - 5} more" else ""
                    
                    val routeMessage = buildString {
                        appendLine("**Found ${progress.pointsOfInterest.size} interesting places!**")
                        appendLine()
                        appendLine("Including: $poiList$moreText")
                        appendLine()
                        appendLine("Now researching each location for you...")
                    }
                    val msg = addMessage(sessionId, MessageRole.ASSISTANT, routeMessage, MessageType.THINKING)
                    emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = msg))
                }
                
                is PlanningProgress.ResearchingPOI -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId, 
                        type = "progress", 
                        content = "🔍 Researching **${progress.poiName}** (${progress.index}/${progress.total})..."
                    ))
                }
                
                is PlanningProgress.POIResearchComplete -> {
                    if (progress.highlights.isNotEmpty()) {
                        val highlightText = progress.highlights.take(2).joinToString("; ")
                        emit(ChatStreamEvent(
                            sessionId = sessionId, 
                            type = "progress", 
                            content = "✅ **${progress.poiName}**: $highlightText"
                        ))
                    }
                }
                
                is PlanningProgress.AllResearchComplete -> {
                    val researchSummary = "📚 Research complete! Gathered insights on ${progress.totalPOIs} locations."
                    val msg = addMessage(sessionId, MessageRole.ASSISTANT, researchSummary, MessageType.THINKING)
                    emit(ChatStreamEvent(sessionId = sessionId, type = "assistant_message", message = msg))
                }
                
                is PlanningProgress.ComposingPlan -> {
                    emit(ChatStreamEvent(
                        sessionId = sessionId, 
                        type = "progress", 
                        content = "📝 Putting together your personalized travel plan..."
                    ))
                }
                
                is PlanningProgress.PlanComplete -> {
                    finalPlan = progress.plan
                    logger.info("${LogColors.CHAT} Plan complete in ${progress.totalDurationMs}ms")
                }
                
                is PlanningProgress.Error -> {
                    logger.error("${LogColors.CHAT} Planning error: ${progress.message}")
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
        
        // Handle final plan
        finalPlan?.let { travelPlan ->
            // Update session with result
            sessions.computeIfPresent(sessionId) { _, s ->
                s.copy(
                    planResult = travelPlan, 
                    conversationState = ConversationState.PRESENTING_PLAN,
                    updatedAt = System.currentTimeMillis()
                )
            }
            
            // Record trip for user if logged in
            userId?.let {
                UserPreferenceStore.addPastTrip(it, PastTrip(
                    fromCity = journeyForm.fromCity,
                    toCity = journeyForm.toCity,
                    date = journeyForm.startDate.toString()
                ))
                UserPreferenceStore.addFavoriteDestination(it, journeyForm.toCity)
            }

            val planSummary = buildString {
                appendLine("# ${travelPlan.title}")
                appendLine()
                appendLine(travelPlan.plan)
                appendLine()
                if (travelPlan.days.isNotEmpty()) {
                    appendLine("## Daily Itinerary")
                    travelPlan.days.forEachIndexed { index, day ->
                        appendLine("### Day ${index + 1}: ${day.locationAndCountry} (${day.date})")
                        appendLine()
                    }
                }
                appendLine()
                appendLine("---")
                appendLine("*Would you like me to modify anything? You can ask to change specific days, add activities, or adjust the pace.*")
            }

            val resultMessage = addMessage(sessionId, MessageRole.ASSISTANT, planSummary, MessageType.PLAN_RESULT)
            emit(ChatStreamEvent(
                sessionId = sessionId, 
                type = "plan_result", 
                message = resultMessage,
                planResult = travelPlan
            ))
        }
        
        emit(ChatStreamEvent(sessionId = sessionId, type = "done", done = true))
    }

    fun cleanupOldSessions(maxAgeMs: Long = 3600000) {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { (_, session) ->
            now - session.createdAt > maxAgeMs
        }
    }
}
