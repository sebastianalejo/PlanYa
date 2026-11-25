package com.example.planya.presentation.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.profile.ReceivedRating
import com.example.planya.data.profile.ReceivedRatingsCursorException
import com.example.planya.data.profile.ReceivedRatingsGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReceivedRatingsState(
    val items: List<ReceivedRating> = emptyList(),
    val count: Int = 0,
    val average: Double? = null,
    val nextKey: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val pageError: String? = null,
    val requiresLogin: Boolean = false
)

class ReceivedRatingsViewModel(private val gateway: ReceivedRatingsGateway) : ViewModel() {
    private val _state = MutableStateFlow(ReceivedRatingsState())
    val state = _state.asStateFlow()
    private var failedAppend = false
    private var accessClosed = false

    fun load() {
        if (accessClosed) return
        if (_state.value.loaded) refresh() else fetch(append = false, refresh = false)
    }

    fun refresh() = fetch(append = false, refresh = _state.value.loaded)

    fun loadMore() {
        if (_state.value.nextKey != null) fetch(append = true, refresh = false)
    }

    fun retry() {
        if (failedAppend) fetch(append = true, refresh = false) else refresh()
    }

    fun acknowledgeLogin() {
        accessClosed = false
        failedAppend = false
        _state.value = ReceivedRatingsState()
    }

    private fun fetch(append: Boolean, refresh: Boolean) {
        val current = _state.value
        if (accessClosed || current.loading || current.refreshing || current.loadingMore) return
        val key = if (append) current.nextKey ?: return else null
        _state.update {
            it.copy(loading = !append && !refresh && !it.loaded, refreshing = refresh,
                loadingMore = append, error = if (!append && !it.loaded) null else it.error, pageError = null)
        }
        viewModelScope.launch {
            try {
                val page = gateway.received(key)
                if (accessClosed) return@launch
                // El servidor no repite notas al paginar; se conserva el orden recibido.
                val merged = if (append) _state.value.items + page.items else page.items
                val seen = mutableSetOf<String>()
                val items = merged.filter { rating ->
                    rating.id?.takeIf { it.isNotBlank() }?.let(seen::add) ?: true
                }
                _state.update {
                    it.copy(items = items, count = page.count, average = page.average, nextKey = page.nextKey,
                        loaded = true, loading = false, refreshing = false, loadingMore = false,
                        error = null, pageError = null)
                }
                failedAppend = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AuthSessionRequiredException) {
                accessClosed = true
                _state.value = ReceivedRatingsState(requiresLogin = true)
            } catch (expired: ReceivedRatingsCursorException) {
                failedAppend = false
                _state.update {
                    it.copy(nextKey = null, loading = false, refreshing = false, loadingMore = false,
                        pageError = expired.message)
                }
            } catch (failure: Exception) {
                failedAppend = append
                val message = failure.message ?: "No pudimos cargar tus opiniones."
                _state.update {
                    if (!append && it.items.isEmpty()) it.copy(loading = false, refreshing = false,
                        loadingMore = false, error = message)
                    else it.copy(loading = false, refreshing = false, loadingMore = false, pageError = message)
                }
            }
        }
    }

    class Factory(private val gateway: ReceivedRatingsGateway) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ReceivedRatingsViewModel::class.java))
            return ReceivedRatingsViewModel(gateway) as T
        }
    }
}
