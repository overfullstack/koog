package org.salesforce.travel.dto

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class ResearchedPointOfInterest(
    val pointOfInterest: PointOfInterest,
    val research: String,
    val links: List<InternetResource>,
    @property:LLMDescription("Links to images. Links must be the images themselves, not just links to them.")
    val imageLinks: List<InternetResource>,
)

@Serializable
data class PointOfInterestFindings(val pointsOfInterest: List<ResearchedPointOfInterest>)

@Serializable
data class Day(
    @property:LLMDescription("Date in ISO format")
    val date: LocalDate,
    @property:LLMDescription("Location where the traveler will stay on this day in Google Maps friendly format 'City,+Country'")
    val locationAndCountry: String,
) {
    /**
     * More readable location name, e.g. "Paris" rather than "Paris,+FR".
     */
    val stayingAt: String = locationAndCountry.split(",").firstOrNull()?.trim() ?: "Unknown location"
}

@Serializable
data class InternetResource(
    val url: String,
    val summary: String,
)

@Serializable
data class ProposedTravelPlan(
    @property:LLMDescription("Catchy title appropriate to the travelers and travel brief")
    val title: String,
    @property:LLMDescription("Detailed travel plan")
    val plan: String,
    @property:LLMDescription("List of days in the travel plan")
    val days: List<Day>,
    @property:LLMDescription("Links to images")
    val imageLinks: List<InternetResource>,
    @property:LLMDescription("Links to pages with more information about the travel plan")
    val pageLinks: List<InternetResource>,
    @property:LLMDescription("List of country names that the travelers will visit")
    val countriesVisited: List<String>,
)

