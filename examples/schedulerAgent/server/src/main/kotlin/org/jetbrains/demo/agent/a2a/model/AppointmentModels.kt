package org.jetbrains.demo.agent.a2a.model

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
enum class AppointmentGroup {
    BLOOD_TEST,
    DIAGNOSTIC_SCAN,
    X_RAY,
    ULTRASOUND,
    MRI,
    CT_SCAN,
    PHYSICAL_EXAM,
    CONSULTATION
}

@Serializable
data class AppointmentForm(
    @property:LLMDescription("Type of appointment (e.g., blood test, diagnostic scan)")
    val appointmentGroup: AppointmentGroup,
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
data class ServiceTerritoryRequest(
    val location: String
)

@Serializable
data class ServiceTerritoryResult(
    @property:LLMDescription("Original location string")
    val location: String,
    @property:LLMDescription("Latitude coordinate")
    val latitude: Double,
    @property:LLMDescription("Longitude coordinate")
    val longitude: Double
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
    val weatherInfo: LocationWeatherResult?
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

