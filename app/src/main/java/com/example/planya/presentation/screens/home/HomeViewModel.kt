package com.example.planya.presentation.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.planya.domain.usecase.plan.GetPlansUseCase
import com.example.planya.domain.model.LocatedArea
import com.example.planya.domain.model.Plan
import com.example.planya.domain.usecase.plan.GetPlanUseCase
import kotlinx.coroutines.Job
import com.example.planya.domain.repository.PlanUnavailableException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import com.example.planya.data.location.SelectedLocationStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PlanDetailState(val plan: Plan? = null, val isLoading: Boolean = false, val error: String? = null)

class HomeViewModel(private val getPlans: GetPlansUseCase, private val getPlan: GetPlanUseCase,
    private val locationStore: SelectedLocationStore? = null) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState = _uiState.asStateFlow()
    private val _detail = MutableStateFlow(PlanDetailState())
    val detail = _detail.asStateFlow()
    private var detailJob: Job? = null
    private var lastFailedAppend = false
    private val locationMutex = Mutex()
    private var locationVersion = 0
    private var mutationVersion = 0L
    private val recentMutations = mutableMapOf<String, Pair<Long, Plan?>>()

    init {
        refresh()
        viewModelScope.launch {
            val version = locationVersion
            val (restored, googleId) = locationMutex.withLock { Pair(locationStore?.read(), locationStore?.googlePlaceId()) }
            // Una lectura lenta del disco no puede reemplazar una elección reciente.
            if (version == locationVersion) _uiState.update { it.copy(area = restored, savedGooglePlaceId = googleId) }
        }
    }

    fun setArea(area: LocatedArea) {
        locationVersion++
        _uiState.update { it.copy(area = area, locationSaveError = null, savedGooglePlaceId = area.googlePlaceId) }
        viewModelScope.launch {
            try { locationMutex.withLock { locationStore?.save(area) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _uiState.update { it.copy(locationSaveError =
                "La dirección cambió, pero no se pudo guardar en este teléfono. Vuelve a seleccionarla.") } }
        }
    }
    fun dismissLocationError() { _uiState.update { it.copy(locationSaveError = null) } }
    fun applyPlan(plan: Plan) {
        recentMutations[plan.id] = ++mutationVersion to plan
        _uiState.update { it.copy(plans = if (it.plans.any { p -> p.id == plan.id })
            it.plans.map { p -> if (p.id == plan.id) plan else p } else listOf(plan) + it.plans) }
        if (_detail.value.plan?.id == plan.id) {
            detailJob?.cancel()
            _detail.value = PlanDetailState(plan)
        }
    }
    fun removePlan(id: String) {
        recentMutations[id] = ++mutationVersion to null
        _uiState.update { it.copy(plans = it.plans.filterNot { p -> p.id == id }) }
        if (_detail.value.plan?.id == id) {
            detailJob?.cancel()
            _detail.value = PlanDetailState(error = "El plan finalizó o ya no está disponible.")
        }
    }

    fun openPlan(id: String) {
        detailJob?.cancel()
        val cached = _uiState.value.plans.firstOrNull { it.id == id }
        _detail.value = PlanDetailState(cached, isLoading = true)
        detailJob = viewModelScope.launch {
            try {
                _detail.value = PlanDetailState(getPlan(id))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: PlanUnavailableException) {
                // Un detalle cancelado/vencido en otra cuenta tampoco sigue en Inicio.
                removePlan(id)
                _detail.value = PlanDetailState(error = "El plan finalizó o ya no está disponible.")
            }
            catch (failure: Exception) {
                // API Gateway también devuelve 404 si todavía no se desplegó esta ruta.
                _detail.value = PlanDetailState(cached, error =
                    "No se pudo actualizar el detalle. ${if (cached != null) "Ves la información del listado." else "Intenta de nuevo."}")
            }
        }
    }

    fun refresh() = load(append = false)
    fun retry() = load(append = lastFailedAppend)
    fun loadMore() {
        if (_uiState.value.nextKey != null) load(append = true)
    }

    private fun load(append: Boolean) {
        val current = _uiState.value
        if (current.isLoading || current.isLoadingMore) return
        val key = if (append) current.nextKey else null
        val requestedVersion = mutationVersion
        _uiState.update {
            it.copy(isLoading = !append, isLoadingMore = append, errorMessage = null)
        }
        viewModelScope.launch {
            try {
                val page = getPlans(key)
                // Una lectura iniciada antes de editar/cancelar no puede deshacer ese cambio.
                val changedWhileLoading = recentMutations.filterValues { it.first > requestedVersion }
                _uiState.update {
                    val received = (if (append) it.plans + page.items else page.items).distinctBy { plan -> plan.id }
                    val reconciled = received.mapNotNull { plan ->
                        if (changedWhileLoading.containsKey(plan.id)) changedWhileLoading.getValue(plan.id).second else plan
                    }
                    val added = changedWhileLoading.values.mapNotNull { change -> change.second }.filter { plan -> received.none { it.id == plan.id } }
                    it.copy(
                        plans = added + reconciled,
                        nextKey = page.nextKey,
                        isLoading = false,
                        isLoadingMore = false
                    )
                }
                recentMutations.entries.removeAll { it.value.first <= requestedVersion }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastFailedAppend = append
                val message = when (error) {
                    is IOException -> "No se pudo conectar. Revisa tu conexión a Internet."
                    is HttpException -> "El servidor respondió con error ${error.code()}."
                    else -> "No se pudieron leer los planes. Inténtalo de nuevo."
                }
                _uiState.update {
                    it.copy(isLoading = false, isLoadingMore = false, errorMessage = message)
                }
            }
        }
    }

    class Factory(private val getPlans: GetPlansUseCase, private val getPlan: GetPlanUseCase,
        private val locationStore: SelectedLocationStore? = null) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java))
            return HomeViewModel(getPlans, getPlan, locationStore) as T
        }
    }
}
