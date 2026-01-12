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

@Serializable
data class LocationWeatherRequest(
    val location: String,
    val appointmentTime: LocalDateTime
)

@Serializable
data class LocationWeatherResult(
    @property:LLMDescription("Location information")
    val location: String,
    @property:LLMDescription("Weather forecast for the appointment time")
    val weather: String,
    @property:LLMDescription("Temperature in Celsius")
    val temperature: Double? = null,
    @property:LLMDescription("Weather conditions description")
    val conditions: String? = null,
    @property:LLMDescription("Any relevant travel or preparation advice based on weather")
    val advice: String? = null
)

@Serializable
data class AppointmentBookingRequest(
    val appointmentForm: AppointmentForm,
    val weatherInfo: LocationWeatherResult?,
    val workTypeGroupId: String? = null,
    val timeslotInfo: TimeslotInfo? = null,
    val serviceTerritoryId: String? = null
)

@Serializable
data class TimeslotInfo(
    @property:LLMDescription("The timeslot ID")
    val timeslotId: String,
    @property:LLMDescription("The start time in ISO 8601 format (UTC)")
    val startTime: String,
    @property:LLMDescription("The end time in ISO 8601 format (UTC)")
    val endTime: String,
    @property:LLMDescription("The service resource ID")
    val serviceResourceId: String? = null
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
    @property:LLMDescription("Weather information for the appointment")
    val weatherInfo: LocationWeatherResult?,
    @property:LLMDescription("Appointment details")
    val appointment: AppointmentForm
)

@Serializable
data class AppointmentValidationRequest(
    @property:LLMDescription("The work type group name to validate (e.g., 'blood test')")
    val workTypeGroupName: String
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

@Serializable
data class ServiceTerritoryValidationRequest(
    @property:LLMDescription("The service territory/location name to validate (e.g., 'San Francisco')")
    val location: String
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
    @property:LLMDescription("The start time of the validated timeslot in ISO 8601 format (e.g., '2026-01-12T02:30:00.000Z')")
    val startTime: String? = null,
    @property:LLMDescription("The end time of the validated timeslot in ISO 8601 format (e.g., '2026-01-12T03:30:00.000Z')")
    val endTime: String? = null,
    @property:LLMDescription("The service resource ID associated with the validated timeslot")
    val serviceResourceId: String? = null
)

