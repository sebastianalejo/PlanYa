package com.example.planya.presentation.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.domain.model.ChatBlockedException
import com.example.planya.domain.model.ChatPage
import com.example.planya.domain.model.MAX_CHAT_TEXT
import com.example.planya.domain.model.mergeChatMessages
import com.example.planya.domain.repository.ChatRepository
import com.example.planya.domain.usecase.chat.GetChatMessagesUseCase
import com.example.planya.domain.usecase.chat.SendChatMessageUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Chat temporal de un plan (US-08).
 *
 * La actualización es manual (al abrir, al volver, al enviar y con el botón Actualizar):
 * no se presenta como tiempo real. El servidor decide quién puede leer/escribir y hasta cuándo.
 */
class ChatViewModel(
    private val planId: String,
    private val userId: String,
    private val getMessages: GetChatMessagesUseCase,
    private val sendMessage: SendChatMessageUseCase,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newClientId: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState())
    val state = _state.asStateFlow()

    private var loadJob: Job? = null
    private var olderJob: Job? = null
    private val sending = mutableMapOf<String, Job>()
    private var accessClosed = false

    /**
     * Último mensaje obtenido por consulta. Los mensajes confirmados al enviar no lo mueven,
     * para no saltarse mensajes de otras personas que llegaron antes que el nuestro.
     */
    private var syncCursor: String? = null

    init { refresh() }

    fun draft(text: String) {
        val limited = text.take(MAX_CHAT_TEXT).let { if (it.isNotEmpty() && it.last().isHighSurrogate()) it.dropLast(1) else it }
        _state.update { it.copy(draft = limited) }
    }

    /** Carga inicial o mensajes nuevos desde la última consulta. */
    fun refresh() {
        val current = _state.value
        if (loadJob?.isActive == true || accessClosed) return
        _state.update { it.copy(loading = !it.loaded, refreshing = it.loaded, error = null, refreshError = null) }
        loadJob = viewModelScope.launch {
            try {
                val cursor = syncCursor
                val page = if (cursor == null) getMessages.latest(planId) else getMessages.newer(planId, cursor)
                if (accessClosed) return@launch
                page.messages.lastOrNull()?.let { syncCursor = it.cursor }
                apply(page, firstPage = cursor == null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure) { message -> copy(loading = false, refreshing = false,
                error = if (loaded) null else message, refreshError = if (loaded) message else null) } }
        }
    }

    fun loadOlder() {
        val current = _state.value
        val key = current.olderKey ?: return
        if (current.loadingOlder || accessClosed) return
        _state.update { it.copy(loadingOlder = true, olderError = null) }
        olderJob = viewModelScope.launch {
            try {
                val page = getMessages.older(planId, key)
                if (accessClosed) return@launch
                _state.update { it.copy(messages = mergeChatMessages(it.messages, page.messages),
                    outgoing = reconcile(it.outgoing, page), olderKey = page.olderKey,
                    loadingOlder = false, chatExpiresAt = page.chatExpiresAt,
                    clockOffsetSeconds = page.serverTimeSeconds - clock() / 1000) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure) { message -> copy(loadingOlder = false, olderError = message) } }
        }
    }

    /** Envía el borrador. El texto pasa a una burbuja "Enviando…"; nunca se anuncia éxito antes de la confirmación. */
    fun send() {
        val current = _state.value
        val text = current.draft.trim()
        if (text.isEmpty() || text.length > MAX_CHAT_TEXT || !current.canWrite || accessClosed) return
        val closesIn = current.secondsLeft(clock())
        if (closesIn != null && closesIn <= 0) { refresh(); return }
        val outgoing = OutgoingMessage(newClientId(), text, OutgoingStatus.SENDING)
        _state.update { it.copy(draft = "", outgoing = it.outgoing + outgoing) }
        deliver(outgoing.clientMessageId)
    }

    /** Reintenta con el mismo clientMessageId: el servidor no guarda el mensaje dos veces. */
    fun retry(clientMessageId: String) {
        if (!_state.value.canWrite || accessClosed) return
        if (_state.value.secondsLeft(clock())?.let { it <= 0 } == true) { refresh(); return }
        _state.update { state -> state.copy(outgoing = state.outgoing.map {
            if (it.clientMessageId == clientMessageId) it.copy(status = OutgoingStatus.SENDING, error = null) else it
        }) }
        deliver(clientMessageId)
    }

    /** Quita un envío fallido de la pantalla (no se borró nada en el servidor). */
    fun discard(clientMessageId: String) {
        if (sending[clientMessageId]?.isActive == true) return
        _state.update { state -> state.copy(outgoing = state.outgoing.filterNot { it.clientMessageId == clientMessageId }) }
    }

    fun acknowledgeLogin() {
        _state.update { it.copy(requiresLogin = false, loaded = false) }
    }

    private fun deliver(clientMessageId: String) {
        val item = _state.value.outgoing.firstOrNull { it.clientMessageId == clientMessageId } ?: return
        if (sending[clientMessageId]?.isActive == true) return
        sending[clientMessageId] = viewModelScope.launch {
            try {
                val confirmed = sendMessage(planId, item.text, clientMessageId)
                if (accessClosed) return@launch
                _state.update { state -> state.copy(
                    messages = mergeChatMessages(state.messages, listOf(confirmed)),
                    outgoing = state.outgoing.filterNot { it.clientMessageId == clientMessageId }) }
                refresh() // trae también lo que escribieron los demás
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                fail(failure) { message -> copy(outgoing = outgoing.map {
                    if (it.clientMessageId == clientMessageId) it.copy(status = OutgoingStatus.FAILED, error = message) else it
                }) }
            } finally {
                sending.remove(clientMessageId)
            }
        }
    }

    private fun apply(page: ChatPage, firstPage: Boolean) {
        _state.update {
            it.copy(
                planTitle = page.planTitle ?: it.planTitle,
                messages = mergeChatMessages(it.messages, page.messages),
                outgoing = reconcile(it.outgoing, page),
                olderKey = if (firstPage) page.olderKey else it.olderKey,
                chatExpiresAt = page.chatExpiresAt,
                clockOffsetSeconds = page.serverTimeSeconds - clock() / 1000,
                isHost = page.isHost,
                loading = false, refreshing = false, loaded = true,
                error = null, refreshError = null,
                lastUpdatedMillis = clock()
            )
        }
    }

    private fun reconcile(outgoing: List<OutgoingMessage>, page: ChatPage): List<OutgoingMessage> {
        val confirmed = page.messages.filter { it.senderId == userId }.mapNotNull { it.clientMessageId }.toSet()
        return outgoing.filterNot { it.clientMessageId in confirmed }
    }

    /** Errores comunes: bloqueo del servidor y sesión vencida cierran el acceso; lo demás se muestra. */
    private fun fail(failure: Exception, otherwise: ChatUiState.(String) -> ChatUiState) {
        when (failure) {
            is ChatBlockedException -> {
                accessClosed = true
                loadJob?.cancel()
                sending.values.toList().forEach { it.cancel() }
                olderJob?.cancel()
                // El acceso se cierra también en la app: no se conserva el historial en pantalla.
                _state.update { it.copy(blocked = failure.reason, blockedMessage = failure.message,
                    messages = emptyList(), outgoing = emptyList(), draft = "", olderKey = null,
                    loading = false, refreshing = false, loadingOlder = false) }
            }
            is AuthSessionRequiredException -> {
                accessClosed = true
                loadJob?.cancel()
                olderJob?.cancel()
                sending.values.toList().forEach { it.cancel() }
                _state.update { it.copy(requiresLogin = true, loading = false, refreshing = false,
                    loadingOlder = false, messages = emptyList(), outgoing = emptyList(), draft = "", olderKey = null) }
            }
            else -> _state.update { it.otherwise(failure.message ?: "No se pudo completar la operación.") }
        }
    }

    class Factory(private val planId: String, private val userId: String, private val repository: ChatRepository) :
        ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ChatViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(planId, userId, GetChatMessagesUseCase(repository), SendChatMessageUseCase(repository)) as T
        }
    }
}
