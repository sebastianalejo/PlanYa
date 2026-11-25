package com.example.planya.presentation.screens.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.domain.model.*
import com.example.planya.domain.repository.ReportsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Estado del detalle de una tarjeta expandida. */
data class ReportDetailState(val loading: Boolean = false, val detail: ReportDetail? = null, val error: String? = null)

data class MyReportsUiState(
    val filter: ReportFilter = ReportFilter.TODOS,
    val items: List<ReportSummary> = emptyList(),
    val nextKey: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** Error de la lista. Si hay items, se muestran con aviso de que no están actualizados. */
    val error: String? = null,
    val expandedId: String? = null,
    val details: Map<String, ReportDetailState> = emptyMap(),
    val requiresLogin: Boolean = false
) {
    val busy: Boolean get() = loading || loadingMore
    val showingStaleData: Boolean get() = error != null && items.isNotEmpty()
}

/**
 * US-13 Mis reportes. Solo lectura: no hay acciones para cambiar estados, borrar ni sancionar.
 * La actualización es manual (Actualizar / Cargar más), no tiempo real ni push.
 */
class MyReportsViewModel(private val repository: ReportsRepository) : ViewModel() {
    private val _state = MutableStateFlow(MyReportsUiState())
    val state = _state.asStateFlow()
    private var listJob: Job? = null
    private var detailJob: Job? = null
    /** Cambia al elegir otro filtro o refrescar: descarta respuestas atrasadas. */
    private var generation = 0
    private var failedAppend = false
    private var accessClosed = false

    fun refresh() = loadList(append = false)
    fun loadMore() { if (_state.value.nextKey != null) loadList(append = true) }
    fun retry() = loadList(append = failedAppend && _state.value.nextKey != null)

    fun selectFilter(filter: ReportFilter) {
        if (accessClosed || filter == _state.value.filter) return
        listJob?.cancel(); detailJob?.cancel()
        generation++
        failedAppend = false
        // Un cursor pertenece a un filtro: al cambiar se empieza de cero, sin mezclar listas.
        _state.update { MyReportsUiState(filter = filter) }
        loadList(append = false, force = true)
    }

    fun toggle(id: String) {
        if (accessClosed) return
        val current = _state.value
        if (current.expandedId == id) {
            detailJob?.cancel()
            _state.update { it.copy(expandedId = null, details = it.details.stopLoading()) }
            return
        }
        _state.update { it.copy(expandedId = id) }
        val cached = current.details[id]
        if (cached?.detail == null || cached.error != null) loadDetail(id)
    }

    fun retryDetail(id: String) { if (_state.value.expandedId == id) loadDetail(id) }

    /** Tras volver a ingresar: estado limpio, sin listas ni cursores de la sesión anterior. */
    fun acknowledgeLogin() {
        listJob?.cancel(); detailJob?.cancel()
        generation++
        _state.value = MyReportsUiState()
    }

    private fun loadList(append: Boolean, force: Boolean = false) {
        val current = _state.value
        if (accessClosed || (!force && current.busy)) return
        val key = if (append) current.nextKey ?: return else null
        val filter = current.filter
        val ticket = if (append) generation else ++generation
        if (!append) detailJob?.cancel()
        _state.update { s -> s.copy(loading = !append, loadingMore = append, error = null,
            // Un detalle cancelado por el refresco no debe quedar "cargando" si el refresco falla.
            details = if (append) s.details else s.details.stopLoading()) }
        listJob = viewModelScope.launch {
            try {
                val page = repository.page(filter, key)
                if (accessClosed || ticket != generation || _state.value.filter != filter) return@launch
                failedAppend = false
                _state.update {
                    val items = if (append) mergeReports(it.items, page.items) else mergeReports(emptyList(), page.items)
                    val ids = items.map { item -> item.id }.toSet()
                    it.copy(items = items, nextKey = page.nextKey, loaded = true, loading = false, loadingMore = false,
                        error = null,
                        // Al refrescar se pide el detalle de nuevo para ver la respuesta más reciente.
                        details = if (append) it.details else emptyMap(),
                        expandedId = it.expandedId?.takeIf { id -> id in ids })
                }
                if (!append) _state.value.expandedId?.let { loadDetail(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AuthSessionRequiredException) {
                closeAccess()
            } catch (expired: ReportsCursorExpiredException) {
                if (ticket != generation) return@launch
                failedAppend = false
                _state.update { it.copy(nextKey = null, loading = false, loadingMore = false, error = expired.message) }
            } catch (failure: Exception) {
                if (ticket != generation) return@launch
                failedAppend = append
                // Los datos ya cargados se conservan; la UI avisa que no están actualizados.
                _state.update { it.copy(loading = false, loadingMore = false,
                    error = failure.message ?: "No se pudieron cargar tus reportes.") }
            }
        }
    }

    private fun loadDetail(id: String) {
        detailJob?.cancel()
        val ticket = generation
        // El detalle de otra tarjeta que se estaba cargando se cancela y deja de mostrarse "cargando".
        _state.update { it.copy(details = it.details.stopLoading() + (id to ReportDetailState(loading = true,
            detail = it.details[id]?.detail))) }
        detailJob = viewModelScope.launch {
            try {
                val detail = repository.detail(id)
                if (accessClosed || ticket != generation) return@launch
                _state.update { s ->
                    val matchesFilter = s.filter.accepts(detail.summary.status)
                    s.copy(details = if (matchesFilter) s.details + (id to ReportDetailState(detail = detail)) else s.details - id,
                        expandedId = s.expandedId?.takeUnless { it == id && !matchesFilter },
                        // La tarjeta adopta el estado más reciente que trae el detalle.
                        items = s.items.map { if (it.id == id) detail.summary else it }
                            .filter { s.filter.accepts(it.status) })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AuthSessionRequiredException) {
                closeAccess()
            } catch (failure: Exception) {
                if (ticket != generation) return@launch
                _state.update { s -> s.copy(details = s.details + (id to ReportDetailState(
                    detail = s.details[id]?.detail, error = failure.message ?: "No se pudo cargar el detalle."))) }
            }
        }
    }

    private fun Map<String, ReportDetailState>.stopLoading() = mapValues { it.value.copy(loading = false) }

    private fun closeAccess() {
        accessClosed = true
        listJob?.cancel(); detailJob?.cancel()
        generation++
        _state.value = MyReportsUiState(requiresLogin = true)
    }

    class Factory(private val repository: ReportsRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MyReportsViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return MyReportsViewModel(repository) as T
        }
    }
}
