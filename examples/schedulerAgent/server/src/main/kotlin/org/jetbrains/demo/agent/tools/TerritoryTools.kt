package org.jetbrains.demo.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.Serializable

@Serializable
data class GeocodedLocation(
    val location: String,
    val latitude: Double,
    val longitude: Double,
    val formattedAddress: String
)

@Serializable
data class ServiceTerritory(
    val territoryId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Double
)

@LLMDescription("Tools for service territory management and geocoding")
class TerritoryTools : ToolSet {

    @Tool
    @LLMDescription("Retrieves all available service territories with their geographic boundaries")
    fun getServiceTerritories(): List<ServiceTerritory> {
        // Mock implementation - returns sample territories
        return listOf(
            ServiceTerritory(
                territoryId = "ST-001",
                name = "Downtown Medical District",
                latitude = 40.7589,
                longitude = -73.9851,
                radius = 5.0
            ),
            ServiceTerritory(
                territoryId = "ST-002",
                name = "West Side Health Zone",
                latitude = 40.7614,
                longitude = -73.9776,
                radius = 3.5
            ),
            ServiceTerritory(
                territoryId = "ST-003",
                name = "East Side Medical Area",
                latitude = 40.7484,
                longitude = -73.9680,
                radius = 4.0
            ),
            ServiceTerritory(
                territoryId = "ST-004",
                name = "Central Hospital District",
                latitude = 40.7505,
                longitude = -73.9934,
                radius = 6.0
            )
        )
    }

    @Tool
    @LLMDescription("Converts a location address or place name to latitude and longitude coordinates")
    fun geocodeLocation(
        @LLMDescription("The location address or place name to geocode")
        location: String
    ): GeocodedLocation {
        // Mock implementation - returns sample coordinates based on common locations
        // In a real implementation, this would call a geocoding API like Google Maps
        
        val normalized = location.lowercase()
        
        return when {
            normalized.contains("new york") || normalized.contains("manhattan") -> {
                GeocodedLocation(
                    location = location,
                    latitude = 40.7580 + (Math.random() - 0.5) * 0.02,
                    longitude = -73.9855 + (Math.random() - 0.5) * 0.02,
                    formattedAddress = "Times Square, Manhattan, New York, NY 10036"
                )
            }
            normalized.contains("san francisco") -> {
                GeocodedLocation(
                    location = location,
                    latitude = 37.7749 + (Math.random() - 0.5) * 0.02,
                    longitude = -122.4194 + (Math.random() - 0.5) * 0.02,
                    formattedAddress = "San Francisco, CA 94102"
                )
            }
            normalized.contains("los angeles") || normalized.contains("la") -> {
                GeocodedLocation(
                    location = location,
                    latitude = 34.0522 + (Math.random() - 0.5) * 0.02,
                    longitude = -118.2437 + (Math.random() - 0.5) * 0.02,
                    formattedAddress = "Los Angeles, CA 90012"
                )
            }
            normalized.contains("chicago") -> {
                GeocodedLocation(
                    location = location,
                    latitude = 41.8781 + (Math.random() - 0.5) * 0.02,
                    longitude = -87.6298 + (Math.random() - 0.5) * 0.02,
                    formattedAddress = "Chicago, IL 60601"
                )
            }
            normalized.contains("boston") -> {
                GeocodedLocation(
                    location = location,
                    latitude = 42.3601 + (Math.random() - 0.5) * 0.02,
                    longitude = -71.0589 + (Math.random() - 0.5) * 0.02,
                    formattedAddress = "Boston, MA 02108"
                )
            }
            else -> {
                // Default to New York area for unknown locations
                GeocodedLocation(
                    location = location,
                    latitude = 40.7589 + (Math.random() - 0.5) * 0.05,
                    longitude = -73.9851 + (Math.random() - 0.5) * 0.05,
                    formattedAddress = "$location (approximated)"
                )
            }
        }
    }

    @Tool
    @LLMDescription("Finds the nearest service territory to a given latitude and longitude")
    fun findNearestTerritory(
        @LLMDescription("Latitude coordinate")
        latitude: Double,
        @LLMDescription("Longitude coordinate")
        longitude: Double
    ): ServiceTerritory {
        val territories = getServiceTerritories()
        
        // Find closest territory by distance
        return territories.minByOrNull { territory ->
            val latDiff = territory.latitude - latitude
            val lonDiff = territory.longitude - longitude
            Math.sqrt(latDiff * latDiff + lonDiff * lonDiff)
        } ?: territories.first()
    }
}

