package com.example.planya.domain.model

import kotlin.math.*
import java.util.Locale

data class LocatedArea(
    val point: GeoPoint,
    val label: String,
    val approximate: Boolean,
    val city: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val fullAddress: String? = null,
    val street: String? = null,
    val streetNumber: String? = null,
    val accuracyMeters: Float? = null,
    val reference: String? = null,
    val googlePlaceId: String? = null,
    val googleAttributions: List<String> = emptyList(),
    val manuallySelected: Boolean = false
) {
    // La cabecera y el selector muestran el mismo lugar confirmado, no otro barrio.
    val displayAddress: String get() = fullAddress?.takeIf { it.isNotBlank() } ?: label
}

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }

    // Distancia geográfica en línea recta; no es una ruta a pie o en auto.
    fun metersTo(other: GeoPoint): Double {
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(latitude)) *
            cos(Math.toRadians(other.latitude)) * sin(dLon / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

fun distanceLabel(origin: GeoPoint?, destination: GeoPoint?): String? {
    if (origin == null || destination == null) return null
    val meters = origin.metersTo(destination)
    return if (meters < 1000) "${meters.roundToInt()} m"
    else String.format(Locale.forLanguageTag("es-PE"), "%.1f km", meters / 1000)
}

fun Plan.isExpired(nowSeconds: Long): Boolean = expiresAt?.let { it <= nowSeconds } ?: false

fun Plan.expirationLabel(nowSeconds: Long): String = expiresAt?.let {
    val remaining = it - nowSeconds
    if (remaining <= 0) "Finalizado" else "${ceil(remaining / 60.0).toInt()} min"
} ?: "Sin horario"
