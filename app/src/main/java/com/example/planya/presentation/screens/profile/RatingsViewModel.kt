package com.example.planya.presentation.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.profile.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RatingsState(val items: List<RatingCandidate> = emptyList(), val loading: Boolean = false,
    val loaded: Boolean = false, val busy: Boolean = false, val error: String? = null,
    val selected: RatingCandidate? = null, val score: Int = 0, val review: Boolean = false,
    val locked: Boolean = false, val success: String? = null, val requiresLogin: Boolean = false)

class RatingsViewModel(private val repository: RatingsGateway) : ViewModel() {
    private val _state = MutableStateFlow(RatingsState())
    val state = _state.asStateFlow()
    fun load() {
        if (_state.value.loading || _state.value.busy || _state.value.selected != null || _state.value.requiresLogin) return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val items = repository.pending()
                _state.value = _state.value.copy(items = items, loaded = true, loading = false)
            } catch (failure: Exception) { failure(failure) }
        }
    }
    fun select(candidate: RatingCandidate) {
        val current = _state.value
        if (current.busy || current.loading || current.locked || candidate !in current.items) return
        _state.value = current.copy(selected = candidate, score = 0, review = false, error = null, success = null)
    }
    fun score(score: Int) {
        if (_state.value.busy || _state.value.locked || score !in 1..5) return
        _state.value = _state.value.copy(score = score, error = null)
    }
    fun review() { if (!_state.value.busy && _state.value.score in 1..5) _state.value = _state.value.copy(review = true) }
    fun dismiss() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(selected = null, review = false, locked = false, error = null)
    }
    fun save() {
        val current = _state.value
        val candidate = current.selected ?: return
        if (current.busy || !current.review || current.score !in 1..5 || current.requiresLogin) return
        // Una respuesta perdida conserva candidato y nota: el servidor identifica
        // el voto por plan/autor/destinatario, no por una UUID nueva al reintentar.
        _state.value = current.copy(busy = true, locked = true, error = null)
        viewModelScope.launch {
            try {
                repository.rate(candidate, current.score)
                _state.value = _state.value.copy(busy = false, locked = false, selected = null, review = false,
                    items = _state.value.items.filterNot { it.planId == candidate.planId && it.targetUserId == candidate.targetUserId },
                    success = "Valoración guardada por el servidor.")
            } catch (failure: Exception) { failure(failure) }
        }
    }
    private fun failure(error: Exception) {
        if (error is CancellationException) throw error
        _state.value = if (error is AuthSessionRequiredException) RatingsState(requiresLogin = true)
        else _state.value.copy(loading = false, busy = false, error = error.message ?: "No se pudo completar la operación.")
    }
    class Factory(private val repository: RatingsGateway) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = RatingsViewModel(repository) as T
    }
}
