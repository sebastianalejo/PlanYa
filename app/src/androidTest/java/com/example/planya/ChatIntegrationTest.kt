package com.example.planya

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.domain.model.*
import com.example.planya.domain.repository.ChatRepository
import com.example.planya.domain.usecase.chat.*
import com.example.planya.presentation.screens.chat.*
import com.example.planya.ui.theme.PlanesTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/** Fixtures aislados: no envía mensajes ni utiliza la sesión real de AWS. */
class ChatIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val message = ChatMessage("m1", "plan", "u1", "Ana", "Hola", 1000, "0000000001000#m1", "cid")
    private fun page(messages: List<ChatMessage> = emptyList(), older: String? = null) =
        ChatPage(messages, older, null, 10000, 1, "Encuentro de prueba", false)

    private inner class Gateway : ChatRepository {
        var latestPage = page()
        var newerPage = page()
        var olderResult: CompletableDeferred<ChatPage>? = null
        var refreshResult: CompletableDeferred<ChatPage>? = null
        var sendFailure: Exception? = null
        val sentIds = mutableListOf<String>()
        override suspend fun latest(planId: String) = latestPage
        override suspend fun newer(planId: String, cursor: String) =
            refreshResult?.let { withContext(NonCancellable) { it.await() } } ?: newerPage
        override suspend fun older(planId: String, cursor: String) =
            withContext(NonCancellable) { olderResult!!.await() }
        override suspend fun send(planId: String, text: String, clientMessageId: String): ChatMessage {
            sentIds += clientMessageId
            sendFailure?.let { throw it }
            return message.copy(text = text, clientMessageId = clientMessageId)
        }
    }
    private fun model(gateway: Gateway): ChatViewModel {
        lateinit var model: ChatViewModel
        compose.runOnIdle { model = ChatViewModel("plan", "u1", GetChatMessagesUseCase(gateway),
            SendChatMessageUseCase(gateway), clock = { 1000 }, newClientId = { "cid" }) }
        compose.waitUntil(5000) { model.state.value.loaded }
        return model
    }
    @Test fun retryUsesSameRequestAndConfirmsOnlyOnce() {
        val gateway = Gateway().apply { sendFailure = IOException("Sin respuesta") }
        val model = model(gateway)
        compose.runOnIdle { model.draft("Hola"); model.send() }
        compose.waitUntil { model.state.value.outgoing.firstOrNull()?.status == OutgoingStatus.FAILED }
        compose.runOnIdle { gateway.sendFailure = null; model.retry("cid") }
        compose.waitUntil { model.state.value.messages.size == 1 && model.state.value.outgoing.isEmpty() }
        compose.runOnIdle { assertEquals(listOf("cid", "cid"), gateway.sentIds) }
    }
    @Test fun refreshReconcilesUnconfirmedMessageAlreadyStoredOnServer() {
        val gateway = Gateway().apply { sendFailure = IOException("Respuesta perdida") }
        val model = model(gateway)
        compose.runOnIdle { model.draft("Hola"); model.send() }
        compose.waitUntil { model.state.value.outgoing.firstOrNull()?.status == OutgoingStatus.FAILED }
        compose.runOnIdle { gateway.latestPage = page(listOf(message)); model.refresh() }
        compose.waitUntil { model.state.value.messages.size == 1 && model.state.value.outgoing.isEmpty() }
        compose.runOnIdle { assertEquals(1, gateway.sentIds.size) }
    }
    @Test fun lateHistoryCannotRestoreMessagesAfterMembershipIsRevoked() {
        val pending = CompletableDeferred<ChatPage>()
        val gateway = Gateway().apply {
            latestPage = page(listOf(message), "older"); olderResult = pending
            sendFailure = ChatBlockedException(ChatBlockReason.NOT_MEMBER, "Ya no participas")
        }
        val model = model(gateway)
        compose.runOnIdle { model.loadOlder(); model.draft("Hola"); model.send() }
        compose.waitUntil { model.state.value.blocked != null }
        compose.runOnIdle { pending.complete(page(listOf(message.copy(id = "old")))) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(model.state.value.messages.isEmpty())
            assertTrue(model.state.value.outgoing.isEmpty())
            assertFalse(model.state.value.canWrite)
        }
    }
    @Test fun lateRefreshCannotRestoreMessagesAfterSessionExpires() {
        val pending = CompletableDeferred<ChatPage>()
        val gateway = Gateway().apply {
            latestPage = page(listOf(message)); refreshResult = pending
            sendFailure = AuthSessionRequiredException()
        }
        val model = model(gateway)
        compose.runOnIdle { model.refresh(); model.draft("Hola"); model.send() }
        compose.waitUntil { model.state.value.requiresLogin }
        compose.runOnIdle { model.acknowledgeLogin(); pending.complete(page(listOf(message.copy(id = "late")))) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(model.state.value.messages.isEmpty()); assertFalse(model.state.value.canWrite) }
    }
    private fun screen(state: ChatUiState, retry: (String) -> Unit = {}, discard: (String) -> Unit = {}) {
        compose.setContent { PlanesTheme {
            ChatScreen(state, "u1", "host", null, {}, {}, retry, discard, {}, {}, {}, nowMillis = { 1000 })
        } }
    }
    @Test fun expiredChatHidesHistoryAndComposerEvenWithoutNetwork() {
        screen(ChatUiState(loaded = true, loading = false, messages = listOf(message), chatExpiresAt = 1))
        compose.onNodeWithText("Este chat se cerró").assertExists()
        compose.onNodeWithText("Hola").assertDoesNotExist()
        compose.onNodeWithContentDescription("Enviar mensaje").assertDoesNotExist()
    }
    @Test fun unconfirmedBubbleOffersRetryAndDiscardWithoutClaimingFailureWasCertain() {
        var retries = 0
        var discards = 0
        screen(ChatUiState(loaded = true, loading = false,
            outgoing = listOf(OutgoingMessage("cid", "Hola", OutgoingStatus.FAILED))),
            retry = { retries++ }, discard = { discards++ })
        compose.onNodeWithText("Envío sin confirmar.").assertExists()
        compose.onNodeWithText("Reintentar").performClick()
        compose.onNodeWithText("Eliminar").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, discards) }
    }
}
