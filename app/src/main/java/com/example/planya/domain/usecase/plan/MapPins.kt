package com.example.planya.domain.usecase.plan

import com.example.planya.domain.model.GeoPoint
import com.example.planya.domain.model.Plan
import com.example.planya.domain.model.PlanCategory
import com.example.planya.domain.model.isExpired
import java.util.Locale

/**
 * Un pin del mapa. Varios planes con las mismas coordenadas (redondeadas a ~1 m)
 * comparten pin para no quedar ocultos uno debajo de otro.
 */
data class MapPin(val key: String, val point: GeoPoint, val plans: List<Plan>) {
    val category: PlanCategory? get() = plans.map { it.category }.distinct().singleOrNull()
    val isGroup: Boolean get() = plans.size > 1
}

/** Clave estable para agrupar coordenadas iguales (5 decimales ≈ 1,1 m). */
fun pinKey(point: GeoPoint): String =
    String.format(Locale.ROOT, "%.5f,%.5f", point.latitude, point.longitude)

/**
 * Calcula los pines a partir de los planes ya consultados a AWS.
 * - Excluye planes sin coordenadas: no se inventa su ubicación.
 * - Excluye planes vencidos según [nowSeconds].
 * - Si [category] no es null, solo deja esa categoría.
 * - Deduplica por ID por si dos páginas devolvieran el mismo plan.
 */
fun buildMapPins(plans: List<Plan>, category: PlanCategory?, nowSeconds: Long): List<MapPin> =
    plans.asSequence()
        .distinctBy { it.id }
        .filter { it.coordinates != null && !it.isExpired(nowSeconds) }
        .filter { category == null || it.category == category }
        .groupBy { pinKey(it.coordinates!!) }
        .map { (key, group) -> MapPin(key, group.first().coordinates!!, group) }
        .sortedBy { it.key }

/** Cuántos planes cargados no se pueden mostrar porque no tienen coordenadas. */
fun countWithoutLocation(plans: List<Plan>, nowSeconds: Long): Int =
    plans.distinctBy { it.id }.count { it.coordinates == null && !it.isExpired(nowSeconds) }
