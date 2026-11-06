package com.example.planya.domain.model

import java.text.Normalizer
import java.util.Locale
import kotlin.math.round

data class LocationSuggestion(
    val title: String,
    val subtitle: String,
    val area: LocatedArea,
    val searchableText: String = "$title $subtitle"
)

// El geocodificador puede devolver lugares fuera del área solicitada.
// Esta regla garantiza que la lista local no incluya resultados de otro país
// o a cientos de kilómetros. Ampliar la búsqueda es una decisión explícita.
object LocationSearchPolicy {
    const val LOCAL_RADIUS_METERS = 100_000.0
    const val INITIAL_COUNTRY = "PE"

    fun rank(
        candidates: List<LocationSuggestion>, query: String,
        origin: LocatedArea?, nearbyOnly: Boolean
    ): List<LocationSuggestion> {
        val ignored = setOf("de", "del", "la", "el", "en", "los", "las")
        val words = normalize(query).split(Regex("[^a-z0-9]+"))
            .filter { (it.length >= 2 || it.all(Char::isDigit)) && it.isNotBlank() && it !in ignored }
        val countryCode = origin?.countryCode ?: if (origin == null) INITIAL_COUNTRY else null
        return candidates.asSequence()
            .filter { candidate ->
                val searchable = normalize(candidate.searchableText)
                val matches = words.all { word ->
                    if (word.all(Char::isDigit)) Regex("(?<![0-9])${Regex.escape(word)}(?![0-9])").containsMatchIn(searchable)
                    else searchable.contains(word)
                }
                val inCountry = countryCode == null || candidate.area.countryCode.equals(countryCode, true)
                val near = origin == null || origin.point.metersTo(candidate.area.point) <= LOCAL_RADIUS_METERS
                matches && (!nearbyOnly || (inCountry && near))
            }
            .distinctBy { Triple(round(it.area.point.latitude * 100_000),
                round(it.area.point.longitude * 100_000), normalize(it.title)) }
            .sortedBy { origin?.point?.metersTo(it.area.point) ?: 0.0 }
            .take(6).toList()
    }

    fun contextualQuery(query: String, origin: LocatedArea?, nearbyOnly: Boolean): String {
        if (!nearbyOnly) return query.trim()
        val hints = if (origin == null) listOf("Perú") else listOfNotNull(origin.city, origin.country)
        val normalized = normalize(query)
        return (listOf(query.trim()) + hints.filter { !normalized.contains(normalize(it)) })
            .joinToString(", ")
    }

    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
}
