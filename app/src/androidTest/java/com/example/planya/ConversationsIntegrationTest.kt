package com.example.planya

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.domain.model.*
import com.example.planya.domain.repository.ConversationsRepository
import com.example.planya.domain.usecase.chat.GetConversationsUseCase
import com.example.planya.presentation.screens.chat.*
import com.example.planya.presentation.screens.profile.ProfileScreen
import com.example.planya.ui.theme.PlanesTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException

/** Datos aislados: no usa la cuenta real ni hace peticiones a AWS. */
class ConversationsIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private val item = Conversation("8b3c2f4e-1d2a-4c55-9d3e-5f6a7b8c9d0e", "Café por Miraflores", "host", "Ana",
        PlanCategory.CAFE, 900, 8100, false)
    private inner class Source : ConversationsRepository {
        val calls = mutableListOf<String?>()
        var result: (String?) -> ConversationsPage = { ConversationsPage(listOf(item), null, 1000) }
        override suspend fun page(nextKey: String?): ConversationsPage { calls += nextKey; return result(nextKey) }
    }
    @After fun clearModels() { compose.runOnIdle { models.clear() } }
    private fun model(source: Source, key: String = "list"): ConversationsViewModel {
        lateinit var model: ConversationsViewModel
        compose.runOnIdle { model = ConversationsViewModel(GetConversationsUseCase(source), clock = { 1_000_000 }); models.put(key, model) }
        return model
    }
    private fun loaded(model: ConversationsViewModel) = compose.waitUntil(5000) {
        model.state.value.loaded && !model.state.value.loading && !model.state.value.loadingMore
    }
    @Test fun finishedPlansRemainAndSuccessfulRefreshRemovesRevokedMembership() {
        val source = Source()
        val model = model(source)
        compose.runOnIdle { model.refresh() }; loaded(model)
        compose.runOnIdle {
            assertEquals(listOf(item), model.state.value.items)
            source.result = { ConversationsPage(emptyList(), null, 1000) }; model.refresh()
        }
        loaded(model)
        compose.runOnIdle { assertTrue(model.state.value.items.isEmpty()) }
    }
    @Test fun emptyPageAndFailedAppendKeepCursorForRetry() {
        var fail = true
        val source = Source().apply { result = { cursor ->
            if (cursor == null) ConversationsPage(emptyList(), "next", 1000)
            else if (fail) throw IOException("Sin conexión") else ConversationsPage(listOf(item), null, 1000)
        } }
        val model = model(source)
        compose.runOnIdle { model.refresh() }; loaded(model)
        compose.runOnIdle { model.loadMore() }
        compose.waitUntil { model.state.value.error != null }
        compose.runOnIdle { assertEquals("next", model.state.value.nextKey); fail = false; model.retry() }
        loaded(model)
        compose.runOnIdle { assertEquals(listOf(null, "next", "next"), source.calls); assertEquals(listOf(item), model.state.value.items) }
    }
    @Test fun expiredCursorRetriesFromFirstPageNotFromExpiredKey() {
        val source = Source().apply { result = { cursor ->
            if (cursor == null) ConversationsPage(listOf(item), "old", 1000) else throw ConversationsCursorExpiredException()
        } }
        val model = model(source)
        compose.runOnIdle { model.refresh() }; loaded(model)
        compose.runOnIdle { model.loadMore() }
        compose.waitUntil { model.state.value.error != null }
        compose.runOnIdle { assertNull(model.state.value.nextKey); model.retry() }; loaded(model)
        compose.runOnIdle { assertEquals(listOf(null, "old", null), source.calls) }
    }
    @Test fun sessionExpiryClearsTitlesAndNewSessionUsesANewModel() {
        val source = Source()
        val old = model(source)
        compose.runOnIdle { old.refresh() }; loaded(old)
        compose.runOnIdle { source.result = { throw AuthSessionRequiredException() }; old.refresh() }
        compose.waitUntil { old.state.value.requiresLogin }
        compose.runOnIdle {
            assertTrue(old.state.value.items.isEmpty())
            old.acknowledgeLogin(); old.refresh()
            assertEquals(2, source.calls.size)
            source.result = { ConversationsPage(listOf(item), null, 1000) }
        }
        val fresh = model(source, "new-session")
        compose.runOnIdle { fresh.refresh() }; loaded(fresh)
        compose.runOnIdle { assertEquals(listOf(item), fresh.state.value.items) }
    }
    private fun screen(state: () -> ConversationsUiState, now: () -> Long = { 1000 }, open: (Conversation) -> Unit = {},
        more: () -> Unit = {}, retry: () -> Unit = {}, back: () -> Unit = {}, large: Boolean = false) {
        compose.setContent { PlanesTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.5f else density.fontScale)) {
                Box(Modifier.width(320.dp).fillMaxHeight()) {
                    ConversationsScreen(state(), now(), open, {}, retry, more, back)
                }
            }
        } }
    }
    @Test fun opensFinishedPlanChatDirectlyAndShowsTemporaryAvailability() {
        var opened: String? = null
        val active = item.copy(planId = "second", title = "Caminata en el parque", planExpiresAt = 5000, chatExpiresAt = 12200,
            isHost = true, category = PlanCategory.CAMINATA)
        screen({ ConversationsUiState(listOf(item, active), loaded = true) }, open = { opened = it.planId })
        compose.onNodeWithText("Plan finalizado · El chat sigue disponible").assertExists()
        compose.onNodeWithTag("conversation-${item.planId}").performClick()
        compose.runOnIdle { assertEquals(item.planId, opened) }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(cache, "conversations-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun exactCloseTimeRemovesConversationWithServerClockOffset() {
        var now by mutableLongStateOf(1000)
        screen({ ConversationsUiState(listOf(item), loaded = true, clockOffsetSeconds = 500) }, { now })
        compose.onNodeWithTag("conversation-${item.planId}").assertExists()
        compose.runOnIdle { now = item.chatExpiresAt - 500 }
        compose.onNodeWithTag("conversation-${item.planId}").assertDoesNotExist()
        compose.onNodeWithText("No tienes chats disponibles").assertExists()
    }
    @Test fun emptyPageWithCursorOffersMoreWithoutClaimingNoConversations() {
        var calls = 0
        screen({ ConversationsUiState(loaded = true, nextKey = "next") }, more = { calls++ })
        compose.onNodeWithText("No tienes chats disponibles").assertDoesNotExist()
        compose.onNodeWithText("Cargar más conversaciones").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun unavailableRouteIsShownAsErrorAndCanRetry() {
        var calls = 0
        screen({ ConversationsUiState(error = "La lista todavía no está disponible.") }, retry = { calls++ })
        compose.onNodeWithText("No tienes chats disponibles").assertDoesNotExist()
        compose.onNodeWithText("Reintentar").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun largeTextCanOpenChatAndReturnWithoutDuplicateBottomBars() {
        var opened = 0
        var backs = 0
        screen({ ConversationsUiState(listOf(item.copy(title = "Un encuentro con un título extenso para revisar el tamaño del texto")), loaded = true) },
            open = { opened++ }, back = { backs++ }, large = true)
        compose.onNodeWithTag("conversations-list").performScrollToNode(hasTestTag("conversation-${item.planId}"))
        compose.onNodeWithTag("conversation-${item.planId}").performClick()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithText("Inicio").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(1, backs) }
    }
    @Test fun profileOffersOneConversationsEntryWithItsCallback() {
        var calls = 0
        compose.setContent { PlanesTheme {
            ProfileScreen("Ana", "fake@example.com", emptySet(), null, false, false, null,
                {}, {}, {}, {}, {}, {}, {}, {}, onChat = { calls++ })
        } }
        compose.onNodeWithTag("perfil-lista").performScrollToNode(hasText("Mis conversaciones"))
        compose.onAllNodesWithText("Mis conversaciones").assertCountEquals(1)
        compose.onNodeWithText("Mis conversaciones").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }
}
