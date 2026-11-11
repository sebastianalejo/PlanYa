package com.example.planya.presentation.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.planya.data.location.LocationRepository
import com.example.planya.domain.model.Plan
import com.example.planya.domain.model.PlanCategory
import com.example.planya.domain.usecase.plan.GetPlansUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

/**
 * Estado del mapa de planes (US-07). Se crea a nivel de actividad para que la
 * zona, el filtro y la ficha se conserven al ir al detalle y regresar.
 * Solo usa GET /planes (con cursor nextKey); no consulta AWS al mover la cámara.
 */
class MapViewModel(private val getPlans: GetPlansUseCase,
    private val location: LocationRepository? = null) : ViewModel() {
    private val _state = MutableStateFlow(MapUiState())
    val state = _state.asStateFlow()
    private var lastFailedAppend = false
    private var mutationVersion = 0L
    private val recentMutations = mutableMapOf<String, Pair<Long, Plan?>>()

    fun ensureLoaded() { if (!_state.value.hasLoaded && _state.value.errorMessage == null) refresh() }
    fun refresh() = load(append = false)
    fun retry() = load(append = lastFailedAppend)
    fun loadMore() { if (_state.value.nextKey != null) load(append = true) }

    private fun load(append: Boolean) {
        val current = _state.value
        if (current.isLoading || current.isLoadingMore) return
        val key = if (append) current.nextKey else null
        val requestedVersion = mutationVersion
        _state.update { it.copy(isLoading = !append, isLoadingMore = append, errorMessage = null) }
        viewModelScope.launch {
            try {
                val page = getPlans(key)
                val changedWhileLoading = recentMutations.filterValues { it.first > requestedVersion }
                _state.update {
                    val received = (if (append) it.plans + page.items else page.items)
                        .associateBy { plan -> plan.id }.values.toList()
                    val reconciled = received.mapNotNull { plan ->
                        if (changedWhileLoading.containsKey(plan.id)) changedWhileLoading.getValue(plan.id).second else plan
                    }
                    val added = changedWhileLoading.values.mapNotNull { change -> change.second }
                        .filter { plan -> received.none { it.id == plan.id } }
                    it.copy(plans = added + reconciled, nextKey = page.nextKey, isLoading = false,
                        isLoadingMore = false, hasLoaded = true)
                }
                recentMutations.entries.removeAll { it.value.first <= requestedVersion }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                lastFailedAppend = append
                val message = when (error) {
                    is IOException -> "No se pudo conectar. Revisa tu conexión a Internet."
                    is HttpException -> "El servidor respondió con error ${error.code()}."
                    else -> "No se pudieron cargar los planes."
                }
                _state.update { it.copy(isLoading = false, isLoadingMore = false, errorMessage = message) }
            }
        }
    }

    /** Cambiar el filtro no modifica los intereses guardados del usuario. */
    fun selectCategory(category: PlanCategory?) {
        _state.update { it.copy(category = category, selectedPinKey = null) }
    }
    fun selectPin(key: String?) { _state.update { it.copy(selectedPinKey = key) } }
    fun saveCamera(camera: MapCamera) { _state.update { it.copy(camera = camera) } }

    /** Mantiene en memoria los cambios hechos en Detalle (unirse, editar, cancelar). */
    fun applyPlan(plan: Plan) {
        recentMutations[plan.id] = ++mutationVersion to plan
        _state.update { s -> s.copy(plans = if (s.plans.any { it.id == plan.id })
            s.plans.map { if (it.id == plan.id) plan else it } else listOf(plan) + s.plans) }
    }
    fun removePlan(id: String) {
        recentMutations[id] = ++mutationVersion to null
        _state.update { s -> s.copy(plans = s.plans.filterNot { it.id == id }) }
    }

    /** Se llama solo después de que el usuario pulsa Mi ubicación y concede permiso. */
    fun locate() {
        val repository = location ?: return
        if (_state.value.locating) return
        _state.update { it.copy(locating = true, locationMessage = null) }
        viewModelScope.launch {
            try {
                val found = repository.current()
                _state.update { it.copy(myLocation = found, locating = false, recenterEvent = it.recenterEvent + 1,
                    camera = MapCamera(found.point.latitude, found.point.longitude, 15f)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                _state.update { it.copy(locating = false, locationMessage =
                    failure.message ?: "No pudimos ubicarte. Puedes mover el mapa manualmente.") }
            }
        }
    }
    fun permissionDenied() {
        _state.update { it.copy(locationMessage = "Sin permiso de ubicación. Puedes mover el mapa manualmente.") }
    }
    fun dismissLocationMessage() { _state.update { it.copy(locationMessage = null) } }

    class Factory(private val getPlans: GetPlansUseCase, private val location: LocationRepository?) :
        ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MapViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return MapViewModel(getPlans, location) as T
        }
    }
}
