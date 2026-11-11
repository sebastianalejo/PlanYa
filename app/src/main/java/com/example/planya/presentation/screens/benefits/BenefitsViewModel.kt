package com.example.planya.presentation.screens.benefits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.domain.model.BenefitStatus
import com.example.planya.domain.usecase.benefits.GetBenefitCodeUseCase
import com.example.planya.domain.usecase.benefits.GetBenefitUseCase
import com.example.planya.domain.usecase.benefits.GetBenefitsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BenefitsViewModel(private val getBenefits: GetBenefitsUseCase, private val getBenefit: GetBenefitUseCase,
    private val getCode: GetBenefitCodeUseCase, private val userId: String) : ViewModel() {
    private val _state = MutableStateFlow(BenefitsUiState())
    val state = _state.asStateFlow()
    private var listJob: Job? = null
    private var detailJob: Job? = null
    private var codeJob: Job? = null

    init { load() }

    /** Recarga el listado conservando lo ya mostrado mientras llega la respuesta. */
    fun load(filter: BenefitStatus = _state.value.filter) {
        listJob?.cancel()
        val changed = filter != _state.value.filter
        _state.update {
            it.copy(filter = filter, isLoading = true, isLoadingMore = false, errorMessage = null,
                benefits = if (changed) emptyList() else it.benefits, nextKey = if (changed) null else it.nextKey,
                loaded = if (changed) false else it.loaded)
        }
        listJob = viewModelScope.launch {
            try {
                val page = getBenefits(filter)
                check(page.items.all { it.ownerId == userId }) { "Los beneficios recibidos no corresponden a tu sesión." }
                _state.update { it.copy(benefits = page.items, nextKey = page.nextKey, isLoading = false, loaded = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: AuthSessionRequiredException) { _state.update { it.copy(isLoading = false, requiresLogin = true) } }
            catch (failure: Exception) {
                _state.update { it.copy(isLoading = false, errorMessage = failure.message ?: "No pudimos cargar tus beneficios.") }
            }
        }
    }

    fun retry() = load()

    fun loadMore() {
        val current = _state.value
        val key = current.nextKey ?: return
        if (current.isLoading || current.isLoadingMore) return
        _state.update { it.copy(isLoadingMore = true, errorMessage = null) }
        val filter = current.filter
        listJob = viewModelScope.launch {
            try {
                val page = getBenefits(filter, key)
                check(page.items.all { it.ownerId == userId }) { "Los beneficios recibidos no corresponden a tu sesión." }
                _state.update { state ->
                    if (state.filter != filter) state
                    else state.copy(benefits = (state.benefits + page.items).distinctBy { it.id }, nextKey = page.nextKey, isLoadingMore = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: AuthSessionRequiredException) { _state.update { it.copy(isLoadingMore = false, requiresLogin = true) } }
            catch (failure: Exception) {
                _state.update { it.copy(isLoadingMore = false, errorMessage = failure.message ?: "No pudimos cargar más beneficios.") }
            }
        }
    }

    fun openDetail(id: String) {
        detailJob?.cancel(); codeJob?.cancel()
        val cached = _state.value.benefits.firstOrNull { it.id == id }
        _state.update { it.copy(selected = cached, detailLoading = true, detailError = null, code = null, codeLoading = false, codeError = null) }
        detailJob = viewModelScope.launch {
            try {
                val benefit = getBenefit(id)
                check(benefit.id == id) { "La respuesta corresponde a otro beneficio." }
                check(benefit.ownerId == userId) { "El beneficio recibido no corresponde a tu sesión." }
                _state.update { it.copy(selected = benefit, detailLoading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: AuthSessionRequiredException) { _state.update { it.copy(detailLoading = false, requiresLogin = true) } }
            catch (failure: Exception) {
                _state.update { it.copy(detailLoading = false, detailError = failure.message ?: "No pudimos cargar el beneficio.") }
            }
        }
    }

    /** Pide al servidor un código temporal. Sin red o con error no se genera nada local. */
    fun requestCode(nowSeconds: Long) {
        val benefit = _state.value.selected ?: return
        if (_state.value.codeLoading || benefit.effectiveStatus(nowSeconds) != BenefitStatus.DISPONIBLE) return
        _state.update { it.copy(codeLoading = true, codeError = null) }
        codeJob?.cancel()
        codeJob = viewModelScope.launch {
            try {
                val code = getCode(benefit.id)
                _state.update { if (it.selected?.id == benefit.id) it.copy(code = code, codeLoading = false) else it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: AuthSessionRequiredException) { _state.update { it.copy(codeLoading = false, requiresLogin = true) } }
            catch (failure: Exception) {
                _state.update { it.copy(codeLoading = false, codeError = failure.message ?: "No pudimos generar el código.") }
            }
        }
    }

    /**
     * Consulta el estado mientras el código está visible. El canje solo se anuncia cuando el
     * servidor devuelve el cupón como USADO; un fallo de red no cambia nada.
     */
    fun refreshSelected() {
        val id = _state.value.selected?.id ?: return
        viewModelScope.launch {
            try {
                val benefit = getBenefit(id)
                if (benefit.id != id) return@launch
                if (benefit.ownerId != userId) return@launch
                _state.update { current ->
                    if (current.selected?.id != id) current
                    else current.copy(selected = benefit, code = if (benefit.status == BenefitStatus.DISPONIBLE) current.code else null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: AuthSessionRequiredException) { _state.update { it.copy(requiresLogin = true) } }
            catch (_: Exception) { /* Se vuelve a intentar en la siguiente consulta. */ }
        }
    }

    fun expireCode() { _state.update { it.copy(code = null) } }
    fun closeDetail() {
        detailJob?.cancel(); codeJob?.cancel()
        _state.update { it.copy(selected = null, detailLoading = false, detailError = null, code = null, codeLoading = false, codeError = null) }
    }
    fun acknowledgeLogin() { _state.update { it.copy(requiresLogin = false) } }
}
