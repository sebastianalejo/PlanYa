package com.example.planya.presentation.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.domain.model.*
import com.example.planya.domain.repository.ConversationsRepository
import com.example.planya.domain.usecase.chat.GetConversationsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConversationsUiState(
    val items: List<Conversation> = emptyList(), val loaded: Boolean = false,
    val loading: Boolean = false, val loadingMore: Boolean = false,
    val nextKey: String? = null, val clockOffsetSeconds: Long = 0,
    val error: String? = null, val requiresLogin: Boolean = false
)

class ConversationsViewModel(private val getConversations: GetConversationsUseCase,
    private val clock: () -> Long = { System.currentTimeMillis() }) : ViewModel() {
    private val _state = MutableStateFlow(ConversationsUiState())
    val state = _state.asStateFlow()
    private var failedAppend = false
    private var accessClosed = false

    fun refresh() = load(false)
    fun loadMore() { if (_state.value.nextKey != null) load(true) }
    fun retry() = load(failedAppend)
    fun acknowledgeLogin() { _state.update { ConversationsUiState() } }

    private fun load(append: Boolean) {
        val current = _state.value
        if (accessClosed || current.loading || current.loadingMore) return
        val key = if (append) current.nextKey ?: return else null
        _state.update { it.copy(loading = !append, loadingMore = append, error = null) }
        viewModelScope.launch {
            try {
                val page = getConversations(key)
                if (accessClosed) return@launch
                _state.update { it.copy(items = mergeConversations(if (append) it.items else emptyList(), page.items)
                    .filter { item -> item.isOpen(page.serverTime) }, nextKey = page.nextKey, loaded = true,
                    clockOffsetSeconds = page.serverTime - clock() / 1000,
                    loading = false, loadingMore = false, error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: AuthSessionRequiredException) {
                accessClosed = true
                _state.value = ConversationsUiState(requiresLogin = true)
            } catch (expired: ConversationsCursorExpiredException) {
                failedAppend = false
                _state.update { it.copy(nextKey = null, loading = false, loadingMore = false, error = expired.message) }
            } catch (failure: Exception) {
                failedAppend = append
                _state.update { it.copy(loading = false, loadingMore = false,
                    error = failure.message ?: "No se pudieron cargar tus conversaciones.") }
            }
        }
    }

    class Factory(private val repository: ConversationsRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ConversationsViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return ConversationsViewModel(GetConversationsUseCase(repository)) as T
        }
    }
}
