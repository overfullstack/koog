package org.salesforce.travel.agent.simple.strategy

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import org.salesforce.travel.dto.Day
import org.salesforce.travel.dto.InternetResource

@Serializable
data class ProposedTravelPlan(
    @param:LLMDescription("Catchy title appropriate to the travelers and input form")
    val title: String,
    @param:LLMDescription("Detailed travel plan in markdown format")
    val plan: String,
    @param:LLMDescription("List of days in the travel plan")
    val days: List<Day>,
    @param:LLMDescription("Links to images")
    val imageLinks: List<InternetResource>,
    @param:LLMDescription("Links to pages with more information about the travel plan")
    val pageLinks: List<InternetResource>,
    @param:LLMDescription("List of country names that the travelers will visit")
    val countriesVisited: List<String>,
) {
    fun toDomain() =
        org.salesforce.travel.dto.ProposedTravelPlan(
            title,
            plan,
            days,
            imageLinks,
            pageLinks,
            countriesVisited
        )
}

val ProposedTravelPlanProvider = SubgraphResultProvider<ProposedTravelPlan>(
    name = "provide_proposed_travel_plan",
    description = """
            Finish tool to compile the proposal travel plan.
            Call to provide the proposal travel plan result.
        """.trimIndent()
)
