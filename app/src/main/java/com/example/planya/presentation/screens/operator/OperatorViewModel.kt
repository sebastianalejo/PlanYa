package com.example.planya.presentation.screens.operator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class OperatorCommand(val offerId: String? = null, val planId: String? = null,
    val userId: String? = null, val name: String? = null, val code: String? = null,
    val requestId: String)
data class OperatorUiState(val offers: List<OperatorOffer> = emptyList(), val nextKey: String? = null,
    val loaded: Boolean = false, val loading: Boolean = false, val writing: Boolean = false,
    val denied: Boolean = false, val requiresLogin: Boolean = false, val error: String? = null,
    val offer: OperatorOffer? = null, val plan: OperatorPlan? = null,
    val redeemTab: Boolean = false, val code: String = "", val pending: OperatorCommand? = null,
    val operationError: String? = null, val success: String? = null)

class OperatorViewModel(private val repository: OperatorRepository,
    private val journal: OperatorRequestJournal) : ViewModel() {
    private val _state = MutableStateFlow(OperatorUiState())
    val state = _state.asStateFlow()
    private var closed = false

    fun load(more: Boolean = false) {
        val current = _state.value
        if (closed || current.loading || current.writing || current.pending != null) return
        val key = if (more) current.nextKey ?: return else null
        _state.value = current.copy(loading = true, error = null, success = null, denied = false,
            offer = null, plan = null, offers = if (more) current.offers else emptyList())
        viewModelScope.launch {
            try {
                val page = repository.offers(key)
                if (!closed) _state.value = _state.value.copy(loading = false, loaded = true,
                    offers = ((if (more) current.offers else emptyList()) + page.items).distinctBy { it.id }, nextKey = page.nextKey)
            } catch (failure: Exception) { readFailure(failure) }
        }
    }
    fun selectPlan(offer: OperatorOffer, planId: String) {
        val current = _state.value
        if (closed || current.loading || current.writing || current.pending != null || planId !in offer.planIds) return
        _state.value = current.copy(loading = true, error = null, success = null, offer = offer, plan = null)
        viewModelScope.launch {
            try {
                val plan = repository.participants(offer.id, planId)
                if (!closed) _state.value = _state.value.copy(loading = false, plan = plan)
            } catch (failure: Exception) { readFailure(failure) }
        }
    }
    fun showCatalog() { if (!blocked()) _state.value = _state.value.copy(plan = null, offer = null, error = null, success = null) }
    fun tab(redeem: Boolean) { if (!blocked()) _state.value = _state.value.copy(redeemTab = redeem, error = null, success = null) }
    fun code(value: String) { if (!blocked()) _state.value = _state.value.copy(code = value.take(32).uppercase(Locale.ROOT), success = null, error = null) }
    private fun blocked() = closed || _state.value.loading || _state.value.writing || _state.value.pending != null

    fun reviewAttendance(member: OperatorMember) {
        if (blocked()) return
        val current = _state.value
        val plan = current.plan ?: return
        if (member !in plan.members) return
        try {
            val id = journal.requestId(listOf("attendance", plan.offerId, plan.planId, member.userId))
            _state.value = current.copy(pending = OperatorCommand(plan.offerId, plan.planId, member.userId, member.name, requestId = id), operationError = null, success = null)
        } catch (failure: Exception) { _state.value = current.copy(error = failure.message) }
    }
    fun reviewCode() {
        if (blocked()) return
        val current = _state.value
        val code = current.code.trim()
        if (!Regex("(?:[A-F0-9]{12}|[A-F0-9]{32})").matches(code)) {
            _state.value = current.copy(error = "Escribe o pega el código completo del cupón."); return
        }
        try {
            _state.value = current.copy(pending = OperatorCommand(code = code,
                requestId = journal.requestId(listOf("redemption", code))), operationError = null, success = null)
        } catch (failure: Exception) { _state.value = current.copy(error = failure.message) }
    }
    fun dismissReview() { if (!_state.value.writing) _state.value = _state.value.copy(pending = null, operationError = null) }
    fun confirm() {
        if (closed || _state.value.writing) return
        val command = _state.value.pending ?: return
        _state.value = _state.value.copy(writing = true, operationError = null)
        viewModelScope.launch {
            try {
                if (command.code != null) repository.redeem(command.code, command.requestId)
                else repository.confirm(command.offerId!!, command.planId!!, command.userId!!, command.requestId)
                if (!closed) _state.value = _state.value.copy(writing = false, pending = null, code = "",
                    success = if (command.code != null) "Canje confirmado por el servidor." else "Asistencia confirmada y cupón emitido por el servidor.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: AuthSessionRequiredException) { closeForLogin() }
            catch (_: OperatorDeniedException) { _state.value = OperatorUiState(denied = true, loaded = true) }
            catch (failure: Exception) { if (!closed) _state.value = _state.value.copy(writing = false,
                operationError = failure.message ?: "No se pudo confirmar. Conservamos la solicitud para reintentar.") }
        }
    }
    private fun readFailure(failure: Exception) {
        when (failure) {
            is CancellationException -> throw failure
            is AuthSessionRequiredException -> closeForLogin()
            is OperatorDeniedException -> if (!closed) _state.value = OperatorUiState(denied = true, loaded = true)
            else -> if (!closed) _state.value = _state.value.copy(loading = false,
                nextKey = if (failure is OperatorCursorException) null else _state.value.nextKey,
                error = failure.message ?: "No se pudo consultar el servicio.")
        }
    }
    private fun closeForLogin() { closed = true; _state.value = OperatorUiState(requiresLogin = true) }
    class Factory(private val repository: OperatorRepository, private val journal: OperatorRequestJournal) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = OperatorViewModel(repository, journal) as T
    }
}
