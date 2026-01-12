package org.jetbrains.demo.agent.a2a.model

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class AppointmentForm(
    @property:LLMDescription("Type of appointment")
    val appointmentGroup: String,
    @property:LLMDescription("Location/address of the appointment")
    val location: String,
    @property:LLMDescription("Date and time of the appointment")
    val appointmentTime: LocalDateTime,
    @property:LLMDescription("Patient name")
    val patientName: String? = null,
    @property:LLMDescription("Additional notes or requirements")
    val notes: String? = null
)

// ============================================
// Location Review Agent Models (Tavily)
// ============================================

@Serializable
data class LocationReviewRequest(
    @property:LLMDescription("The location/address to search reviews for")
    val location: String,
    @property:LLMDescription("The type of appointment (e.g., 'blood test', 'dental checkup')")
    val appointmentType: String? = null
)

@Serializable
data class LocationReviewResult(
    @property:LLMDescription("Location that was searched")
    val location: String,
    @property:LLMDescription("Summary of reviews and information about the location")
    val reviewSummary: String,
    @property:LLMDescription("Average rating if found (out of 5)")
    val rating: Double? = null,
    @property:LLMDescription("Key highlights from reviews")
    val highlights: List<String> = emptyList(),
    @property:LLMDescription("Any concerns or warnings from reviews")
    val concerns: List<String> = emptyList(),
    @property:LLMDescription("Tips for visiting this location")
    val tips: String? = null
)

@Serializable
data class AppointmentBookingRequest(
    val appointmentForm: AppointmentForm,
    val locationReviewInfo: LocationReviewResult?,
    val workTypeGroupId: String? = null
)

@Serializable
data class AppointmentBookingResult(
    @property:LLMDescription("Confirmation message with all appointment details")
    val confirmationMessage: String,
    @property:LLMDescription("Appointment ID or reference number")
    val appointmentId: String? = null,
    @property:LLMDescription("All appointment details formatted for display")
    val details: AppointmentForm
)

@Serializable
data class AppointmentResult(
    @property:LLMDescription("Final booking confirmation message")
    val bookingMessage: String,
    @property:LLMDescription("Location review information")
    val locationReviewInfo: LocationReviewResult?,
    @property:LLMDescription("Appointment details")
    val appointment: AppointmentForm
)

@Serializable
data class AppointmentValidationRequest(
    @property:LLMDescription("The work type group name to validate (e.g., 'blood test')")
    val workTypeGroupName: String,
    @property:LLMDescription("The hospital/location name (e.g., 'Yashoda', 'Apollo')")
    val hospitalName: String = "yashoda"
)

@Serializable
data class AppointmentValidationResult(
    @property:LLMDescription("Whether the work type group is valid")
    val isValid: Boolean,
    @property:LLMDescription("Validation message explaining the result")
    val message: String,
    @property:LLMDescription("The work type group ID of the matching work type group, if found")
    val workTypeGroupId: String? = null
)

// ============================================
// Suggestion Models (for validation failures)
// ============================================

@Serializable
data class WorkTypeSuggestion(
    @property:LLMDescription("The suggested work type group name")
    val suggestedWorkType: String,
    @property:LLMDescription("The suggested hospital/location name")
    val suggestedLocation: String,
    @property:LLMDescription("Suggestion message to display to user")
    val suggestionMessage: String,
    @property:LLMDescription("Reviews and information about the suggested location")
    val locationReviews: LocationReviewResult? = null,
    @property:LLMDescription("Whether the user has accepted the suggestion")
    val accepted: Boolean = false
)

@Serializable
data class ServiceTerritoryValidationRequest(
    @property:LLMDescription("The service territory/location name to validate (e.g., 'San Francisco')")
    val location: String
)

// ============================================
// Branch Selection Models
// ============================================

@Serializable
data class BranchInfo(
    @property:LLMDescription("The service territory ID")
    val serviceTerritoryId: String,
    @property:LLMDescription("The branch/service territory name")
    val branchName: String,
    @property:LLMDescription("The branch address")
    val address: String? = null,
    @property:LLMDescription("Distance from user's location in kilometers")
    val distanceKm: Double? = null,
    @property:LLMDescription("Travel time from user's location in minutes")
    val travelTimeMinutes: Int? = null
)

@Serializable
data class BranchSelectionRequest(
    @property:LLMDescription("User's current location")
    val userCurrentLocation: String,
    @property:LLMDescription("User's preferred branch (if specified)")
    val preferredBranch: String? = null,
    @property:LLMDescription("Whether user wants the closest branch")
    val findClosest: Boolean = false
)

@Serializable
data class BranchSelectionResult(
    @property:LLMDescription("List of available branches")
    val availableBranches: List<BranchInfo>,
    @property:LLMDescription("The selected branch")
    val selectedBranch: BranchInfo? = null,
    @property:LLMDescription("Message about the selection")
    val message: String
)

@Serializable
data class ServiceTerritoryValidationResult(
    @property:LLMDescription("Whether the service territory/location is valid")
    val isValid: Boolean,
    @property:LLMDescription("Validation message explaining the result")
    val message: String,
    @property:LLMDescription("The service territory ID of the matching service territory, if found")
    val serviceTerritoryId: String? = null
)

@Serializable
data class TimeslotValidationRequest(
    @property:LLMDescription("The appointment time to validate")
    val appointmentTime: LocalDateTime,
    @property:LLMDescription("The service territory ID (from service territory validation)")
    val serviceTerritoryId: String,
    @property:LLMDescription("The work type group ID (from work type group validation)")
    val workTypeGroupId: String
)

@Serializable
data class TimeslotValidationResult(
    @property:LLMDescription("Whether the timeslot is valid")
    val isValid: Boolean,
    @property:LLMDescription("Validation message explaining the result")
    val message: String,
    @property:LLMDescription("The timeslot ID if a valid timeslot is found")
    val timeslotId: String? = null,
    @property:LLMDescription("The start time of the validated timeslot")
    val startTime: LocalDateTime? = null,
    @property:LLMDescription("The end time of the validated timeslot")
    val endTime: LocalDateTime? = null,
    @property:LLMDescription("Whether the exact requested time was matched, or closest was selected")
    val wasExactMatch: Boolean = true
)

// ============================================
// Maps Agent Models (Google Maps)
// ============================================

@Serializable
data class MapsLocationRequest(
    @property:LLMDescription("The address or location to look up")
    val location: String,
    @property:LLMDescription("Whether to include place details like phone, hours, etc.")
    val includeDetails: Boolean = true
)

@Serializable
data class MapsLocationResult(
    @property:LLMDescription("The original location query")
    val location: String,
    @property:LLMDescription("Formatted address from Google Maps")
    val formattedAddress: String? = null,
    @property:LLMDescription("Latitude coordinate")
    val latitude: Double? = null,
    @property:LLMDescription("Longitude coordinate")
    val longitude: Double? = null,
    @property:LLMDescription("Place details (phone, hours, rating, etc.)")
    val placeDetails: String? = null,
    @property:LLMDescription("Directions or travel information")
    val directions: String? = null,
    @property:LLMDescription("Travel time in minutes (if calculated)")
    val travelTimeMinutes: Int? = null,
    @property:LLMDescription("Distance in kilometers (if calculated)")
    val distanceKm: Double? = null
)

@Serializable
data class MapsDistanceMatrixRequest(
    @property:LLMDescription("Origin address or location")
    val origin: String,
    @property:LLMDescription("Destination address or location")
    val destination: String,
    @property:LLMDescription("Travel mode (driving, walking, transit)")
    val mode: String = "driving"
)

@Serializable
data class MapsDistanceMatrixResult(
    @property:LLMDescription("Origin location")
    val origin: String,
    @property:LLMDescription("Destination location")
    val destination: String,
    @property:LLMDescription("Distance in kilometers")
    val distanceKm: Double? = null,
    @property:LLMDescription("Travel time in minutes")
    val travelTimeMinutes: Int? = null,
    @property:LLMDescription("Distance text (e.g., '5.2 km')")
    val distanceText: String? = null,
    @property:LLMDescription("Duration text (e.g., '15 mins')")
    val durationText: String? = null
)

// ============================================
// Weather Agent Models (OpenWeather)
// ============================================

@Serializable
data class WeatherForecastRequest(
    @property:LLMDescription("The location to get weather for")
    val location: String,
    @property:LLMDescription("The date and time for the forecast")
    val dateTime: LocalDateTime
)

@Serializable
data class WeatherForecastResult(
    @property:LLMDescription("The location for the weather forecast")
    val location: String,
    @property:LLMDescription("The date and time of the forecast")
    val dateTime: LocalDateTime,
    @property:LLMDescription("Temperature in Celsius")
    val temperature: Double? = null,
    @property:LLMDescription("Feels like temperature in Celsius")
    val feelsLike: Double? = null,
    @property:LLMDescription("Weather conditions (sunny, cloudy, rainy, etc.)")
    val conditions: String? = null,
    @property:LLMDescription("Humidity percentage")
    val humidity: Int? = null,
    @property:LLMDescription("Wind speed in km/h")
    val windSpeed: Double? = null,
    @property:LLMDescription("Weather summary and advice")
    val summary: String
)
