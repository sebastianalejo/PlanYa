package com.example.planya.presentation.screens.map

import com.example.planya.domain.model.GeoPoint
import com.example.planya.domain.model.LocatedArea
import com.example.planya.domain.model.Plan
import com.example.planya.domain.model.PlanCategory

/** Posición de cámara guardada para volver del detalle a la misma zona. */
data class MapCamera(val latitude: Double, val longitude: Double, val zoom: Float)

data class MapUiState(
    val plans: List<Plan> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** true cuando al menos una consulta a AWS terminó bien en esta sesión. */
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
    val nextKey: String? = null,
    val category: PlanCategory? = null,
    val selectedPinKey: String? = null,
    val camera: MapCamera? = null,
    /** Ubicación del GPS obtenida por acción explícita. No se envía al servidor. */
    val myLocation: LocatedArea? = null,
    val locating: Boolean = false,
    /** Aumenta cada vez que se obtiene el GPS para centrar la cámara una sola vez. */
    val recenterEvent: Int = 0,
    val locationMessage: String? = null
) {
    /** Hay datos previos en pantalla, pero la última actualización falló. */
    val showingStaleData: Boolean get() = errorMessage != null && plans.isNotEmpty()
    val origin: GeoPoint? get() = myLocation?.point
}
