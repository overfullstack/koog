package org.salesforce.travel.agent.a2a

import ai.koog.agents.memory.model.Concept
import ai.koog.agents.memory.model.FactType
import ai.koog.agents.memory.model.MemoryScope
import ai.koog.agents.memory.model.MemorySubject
import ai.koog.agents.memory.model.MultipleFacts
import ai.koog.agents.memory.model.SingleFact
import ai.koog.agents.memory.providers.AgentMemoryProvider
import ai.koog.agents.memory.providers.LocalFileMemoryProvider
import ai.koog.agents.memory.providers.LocalMemoryConfig
import ai.koog.agents.memory.storage.SimpleStorage
import ai.koog.rag.base.files.JVMFileSystemProvider
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.salesforce.LogColors
import java.nio.file.Path

@Serializable
enum class TravelInterest {
    HISTORY, ART, NATURE, FOOD, ADVENTURE, RELAXATION, NIGHTLIFE, SHOPPING, ARCHITECTURE, SPORTS
}

@Serializable
enum class BudgetPreference {
    BUDGET, MODERATE, LUXURY
}

@Serializable
data class PastTrip(
    val fromCity: String,
    val toCity: String,
    val date: String,
    val rating: Int? = null,
    val notes: String? = null
)

@Serializable
enum class ConversationState {
    GREETING, COLLECTING_PREFERENCES, COLLECTING_JOURNEY_DETAILS, CONFIRMING_DETAILS,
    PLANNING, PRESENTING_PLAN, REFINING_PLAN, FOLLOW_UP
}

/**
 * Custom MemorySubject for travel user preferences
 */
@Serializable
data object TravelUser : MemorySubject() {
    override val name: String = "travel-user"
    override val promptDescription: String = "Travel user preferences including home city, transport, interests, and trip history"
    override val priorityLevel: Int = 1
}

/**
 * Concepts for user travel preferences - using Koog's AgentMemory model
 */
object TravelConcepts {
    val userName: Concept = Concept("user-name", "The user's preferred name for greeting", FactType.SINGLE)
    val homeCity: Concept = Concept("home-city", "User's home city for departure planning", FactType.SINGLE)
    val preferredTransport: Concept = Concept("preferred-transport", "User's preferred mode of transport", FactType.SINGLE)
    val travelInterests: Concept = Concept("travel-interests", "User's travel interests and preferences", FactType.MULTIPLE)
    val favoriteDestinations: Concept = Concept("favorite-destinations", "User's favorite travel destinations", FactType.MULTIPLE)
    val budgetPreference: Concept = Concept("budget-preference", "User's travel budget preference", FactType.SINGLE)
    val dietaryRestrictions: Concept = Concept("dietary-restrictions", "User's dietary restrictions", FactType.MULTIPLE)
    val pastTrips: Concept = Concept("past-trips", "User's past travel trips", FactType.MULTIPLE)
}

/**
 * User Preference Store using Koog's AgentMemory feature for persistent file-based storage.
 * Uses LocalFileMemoryProvider with SimpleStorage for durability across sessions.
 */
object UserPreferenceStore {
    private val logger = org.slf4j.LoggerFactory.getLogger("UserPreferenceStore")
    
    private val memoryProvider: AgentMemoryProvider = LocalFileMemoryProvider(
        config = LocalMemoryConfig("travel-user-preferences"),
        storage = SimpleStorage(JVMFileSystemProvider.ReadWrite),
        fs = JVMFileSystemProvider.ReadWrite,
        root = Path.of("data/memory")
    )
    
    private fun userScope(userId: String): MemoryScope = MemoryScope.Agent("user-$userId")
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
    
    private suspend fun saveSingleFact(userId: String, concept: Concept, value: String) {
        val fact = SingleFact(concept = concept, value = value, timestamp = now())
        memoryProvider.save(fact, TravelUser, userScope(userId))
        logger.info("${LogColors.SESSION} Saved [${concept.keyword}] for user: $userId")
    }
    
    private suspend fun saveMultiFact(userId: String, concept: Concept, values: List<String>) {
        val fact = MultipleFacts(concept = concept, values = values, timestamp = now())
        memoryProvider.save(fact, TravelUser, userScope(userId))
        logger.info("${LogColors.SESSION} Saved [${concept.keyword}] (${values.size} items) for user: $userId")
    }
    
    private suspend fun loadSingleFact(userId: String, concept: Concept): String? {
        val facts = memoryProvider.load(concept, TravelUser, userScope(userId))
        return facts.filterIsInstance<SingleFact>().maxByOrNull { it.timestamp }?.value
    }
    
    private suspend fun loadMultiFact(userId: String, concept: Concept): List<String> {
        val facts = memoryProvider.load(concept, TravelUser, userScope(userId))
        return facts.filterIsInstance<MultipleFacts>().maxByOrNull { it.timestamp }?.values ?: emptyList()
    }
    
    // Suspend API for coroutine contexts
    suspend fun setName(userId: String, name: String) = saveSingleFact(userId, TravelConcepts.userName, name)
    suspend fun getName(userId: String): String? = loadSingleFact(userId, TravelConcepts.userName)
    
    suspend fun setHomeCity(userId: String, city: String) = saveSingleFact(userId, TravelConcepts.homeCity, city)
    suspend fun getHomeCity(userId: String): String? = loadSingleFact(userId, TravelConcepts.homeCity)
    
    suspend fun setPreferredTransport(userId: String, transport: String) = 
        saveSingleFact(userId, TravelConcepts.preferredTransport, transport)
    suspend fun getPreferredTransport(userId: String): String? = 
        loadSingleFact(userId, TravelConcepts.preferredTransport)
    
    suspend fun setTravelInterests(userId: String, interests: List<TravelInterest>) =
        saveMultiFact(userId, TravelConcepts.travelInterests, interests.map { it.name })
    suspend fun getTravelInterests(userId: String): List<TravelInterest> =
        loadMultiFact(userId, TravelConcepts.travelInterests)
            .mapNotNull { runCatching { TravelInterest.valueOf(it) }.getOrNull() }
    
    suspend fun addFavoriteDestination(userId: String, destination: String) {
        val current = loadMultiFact(userId, TravelConcepts.favoriteDestinations)
        if (destination !in current) {
            saveMultiFact(userId, TravelConcepts.favoriteDestinations, current + destination)
        }
    }
    suspend fun getFavoriteDestinations(userId: String): List<String> =
        loadMultiFact(userId, TravelConcepts.favoriteDestinations)
    
    suspend fun setBudgetPreference(userId: String, budget: BudgetPreference) =
        saveSingleFact(userId, TravelConcepts.budgetPreference, budget.name)
    suspend fun getBudgetPreference(userId: String): BudgetPreference? =
        loadSingleFact(userId, TravelConcepts.budgetPreference)
            ?.let { runCatching { BudgetPreference.valueOf(it) }.getOrNull() }
    
    suspend fun addPastTrip(userId: String, trip: PastTrip) {
        val current = loadMultiFact(userId, TravelConcepts.pastTrips)
        val tripStr = "${trip.fromCity}|${trip.toCity}|${trip.date}|${trip.rating ?: ""}|${trip.notes ?: ""}"
        saveMultiFact(userId, TravelConcepts.pastTrips, current + tripStr)
    }
    
    suspend fun getPastTrips(userId: String): List<PastTrip> =
        loadMultiFact(userId, TravelConcepts.pastTrips).mapNotNull { tripStr ->
            val parts = tripStr.split("|")
            if (parts.size >= 3) PastTrip(
                fromCity = parts[0], toCity = parts[1], date = parts[2],
                rating = parts.getOrNull(3)?.toIntOrNull(),
                notes = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
            ) else null
        }
    
    suspend fun hasPreferences(userId: String): Boolean =
        getName(userId) != null || getHomeCity(userId) != null || 
        getPreferredTransport(userId) != null || getTravelInterests(userId).isNotEmpty()
    
    suspend fun buildPreferenceSummary(userId: String): String? {
        if (!hasPreferences(userId)) return null
        return buildString {
            getName(userId)?.let { appendLine("- Name: $it") }
            getHomeCity(userId)?.let { appendLine("- Home city: $it") }
            getPreferredTransport(userId)?.let { appendLine("- Preferred transport: $it") }
            getBudgetPreference(userId)?.let { appendLine("- Budget: ${it.name.lowercase()}") }
            getTravelInterests(userId).takeIf { it.isNotEmpty() }?.let { interests ->
                appendLine("- Interests: ${interests.joinToString(", ") { it.name.lowercase() }}")
            }
            getFavoriteDestinations(userId).takeIf { it.isNotEmpty() }?.let { dests ->
                appendLine("- Favorite destinations: ${dests.joinToString(", ")}")
            }
            getPastTrips(userId).takeIf { it.isNotEmpty() }?.let { trips ->
                appendLine("- Past trips: ${trips.size} recorded")
            }
        }.trim()
    }
    
    // Blocking API for backward compatibility with non-suspend code
    fun getNameBlocking(userId: String): String? = runBlocking { getName(userId) }
    fun getHomeCityBlocking(userId: String): String? = runBlocking { getHomeCity(userId) }
    fun getPreferredTransportBlocking(userId: String): String? = runBlocking { getPreferredTransport(userId) }
    fun hasPreferencesBlocking(userId: String): Boolean = runBlocking { hasPreferences(userId) }
    fun buildPreferenceSummaryBlocking(userId: String): String? = runBlocking { buildPreferenceSummary(userId) }
    fun setNameBlocking(userId: String, name: String): Unit = runBlocking { setName(userId, name) }
    fun setHomeCityBlocking(userId: String, city: String): Unit = runBlocking { setHomeCity(userId, city) }
    fun setPreferredTransportBlocking(userId: String, transport: String): Unit = runBlocking { setPreferredTransport(userId, transport) }
    fun getPastTripsBlocking(userId: String): List<PastTrip> = runBlocking { getPastTrips(userId) }
}
