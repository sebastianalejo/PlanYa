package com.example.planya

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.profile.ReceivedRating
import com.example.planya.data.profile.ReceivedRatingsCursorException
import com.example.planya.data.profile.ReceivedRatingsGateway
import com.example.planya.data.profile.ReceivedRatingsPage
import com.example.planya.presentation.screens.profile.ReceivedRatingsScreen
import com.example.planya.presentation.screens.profile.ReceivedRatingsViewModel
import com.example.planya.ui.theme.PlanesTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReceivedRatingsIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()

    private fun rating(title: String, created: Long = 1795262400, score: Int = 5,
        available: Boolean = true) = ReceivedRating("plan-$title", title, score, created, available)

    private inner class Source : ReceivedRatingsGateway {
        val pages = mutableMapOf<String?, ReceivedRatingsPage>()
        var error: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<String?>()
        override suspend fun received(nextKey: String?): ReceivedRatingsPage {
            calls += nextKey
            gate?.await()
            error?.let { throw it }
            return pages[nextKey] ?: ReceivedRatingsPage("me", 0, null)
        }
    }

    @After fun cleanup() { compose.runOnIdle { models.clear() } }

    private fun model(source: Source): ReceivedRatingsViewModel {
        lateinit var model: ReceivedRatingsViewModel
        compose.runOnIdle { model = ReceivedRatingsViewModel(source); models.put("received", model); model.load() }
        compose.waitUntil(5000) { !model.state.value.loading && !model.state.value.refreshing }
        return model
    }

    private fun screen(model: ReceivedRatingsViewModel) {
        compose.setContent { PlanesTheme {
            val state by model.state.collectAsState()
            ReceivedRatingsScreen(state, {}, model::refresh, model::loadMore, model::retry)
        } }
    }

    private fun scrollTo(text: String) {
        compose.onNodeWithTag("opiniones-lista").performScrollToNode(hasText(text))
    }

    @Test fun emptyHistoryShowsZeroWithoutInventingStarsOrNotes() {
        val source = Source().apply { pages[null] = ReceivedRatingsPage("me", 0, null) }
        val model = model(source); screen(model)
        compose.onNodeWithText("0 valoraciones · Sin nota todavía").assertIsDisplayed()
        compose.onNodeWithText("Sin opiniones recibidas").assertIsDisplayed()
        compose.onNodeWithText("Cargar más").assertDoesNotExist()
    }

    @Test fun summaryAndCardsUseServerAverageAndNeverShowTheEvaluator() {
        val source = Source().apply {
            pages[null] = ReceivedRatingsPage("me", 2, 4.5, listOf(
                rating("Café por Miraflores", 1795262400, 5),
                rating("Caminata por el parque", 1795176000, 4)), null)
        }
        val model = model(source); screen(model)
        compose.onNodeWithText("4.5").assertIsDisplayed()
        compose.onNodeWithText("2 valoraciones").assertIsDisplayed()
        compose.onNodeWithText("Solo tú puedes consultar este historial").assertIsDisplayed()
        compose.onAllNodesWithText("Participante del encuentro").assertCountEquals(2)
        compose.onNodeWithText("Café por Miraflores").assertIsDisplayed()
        compose.onNodeWithText("Excelente experiencia").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, "us11-opiniones-recibidas.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        scrollTo("Muy buena experiencia")
        compose.onNodeWithText("Muy buena experiencia").assertIsDisplayed()
        compose.onNodeWithText("Andrea de prueba").assertDoesNotExist()
    }

    @Test fun loadMoreKeepsOrderAppendsOnceAndDoesNotOverlap() {
        val first = (1..20).map { rating("Plan $it", 1000L + it) }
        val source = Source().apply {
            pages[null] = ReceivedRatingsPage("me", 21, 4.0, first, "next")
            pages["next"] = ReceivedRatingsPage("me", 21, 4.0, listOf(rating("Plan 0", 1000)), null)
        }
        val model = model(source); screen(model)
        source.gate = CompletableDeferred()
        scrollTo("Cargar más")
        compose.onNodeWithText("Cargar más").performClick()
        compose.runOnIdle { model.loadMore() }
        compose.runOnIdle { assertEquals(listOf<String?>(null, "next"), source.calls) }
        source.gate!!.complete(Unit)
        compose.waitUntil(5000) { !model.state.value.loadingMore }
        compose.runOnIdle {
            assertEquals(21, model.state.value.items.size)
            assertEquals("Plan 0", model.state.value.items.last().planTitle)
        }
        scrollTo("Mostrando 21 de 21 opiniones.")
        compose.onNodeWithText("Mostrando 21 de 21 opiniones.").assertIsDisplayed()
        compose.onNodeWithText("Cargar más").assertDoesNotExist()
    }

    @Test fun failureIsNotPresentedAsEmptyAndRetryRecovers() {
        val source = Source().apply { error = IllegalStateException("Sin conexión de prueba") }
        val model = model(source); screen(model)
        compose.onNodeWithText("Sin conexión de prueba").assertIsDisplayed()
        compose.onNodeWithText("Sin opiniones recibidas").assertDoesNotExist()
        compose.onNodeWithText("0 valoraciones · Sin nota todavía").assertDoesNotExist()
        compose.runOnIdle {
            source.error = null
            source.pages[null] = ReceivedRatingsPage("me", 1, 5.0, listOf(rating("Plan recuperado")), null)
        }
        compose.onNodeWithText("Reintentar").performClick()
        compose.waitUntil(5000) { model.state.value.items.isNotEmpty() }
        compose.onNodeWithText("Plan recuperado").assertIsDisplayed()
    }

    @Test fun pageFailureKeepsLoadedCardsAndCanRetryTheSamePage() {
        val source = Source().apply {
            pages[null] = ReceivedRatingsPage("me", 2, 4.5, listOf(rating("Primera página")), "next")
            pages["next"] = ReceivedRatingsPage("me", 2, 4.5,
                listOf(rating("Primera página"), rating("Segunda página", 1000)), null)
        }
        val model = model(source); screen(model)
        compose.runOnIdle { source.error = IllegalStateException("Falló la página 2") }
        scrollTo("Cargar más")
        compose.onNodeWithText("Cargar más").performClick()
        compose.waitUntil(5000) { model.state.value.pageError != null }
        scrollTo("Falló la página 2")
        compose.onNodeWithText("Falló la página 2").assertIsDisplayed()
        scrollTo("Primera página")
        compose.onNodeWithText("Primera página").assertIsDisplayed()
        compose.runOnIdle { source.error = null }
        scrollTo("Reintentar")
        compose.onNodeWithText("Reintentar").performClick()
        compose.waitUntil(5000) { model.state.value.items.size == 2 }
        scrollTo("Segunda página")
        compose.onNodeWithText("Segunda página").assertIsDisplayed()
    }

    @Test fun expiredCursorClearsContinuationButKeepsVisibleCards() {
        val source = Source().apply {
            pages[null] = ReceivedRatingsPage("me", 2, 4.5, listOf(rating("Primera página")), "next")
        }
        val model = model(source); screen(model)
        compose.runOnIdle { source.error = ReceivedRatingsCursorException("La lista cambió. Actualiza.", true) }
        scrollTo("Cargar más")
        compose.onNodeWithText("Cargar más").performClick()
        compose.waitUntil(5000) { model.state.value.pageError != null }
        scrollTo("La lista cambió. Actualiza.")
        compose.onNodeWithText("La lista cambió. Actualiza.").assertIsDisplayed()
        scrollTo("Primera página")
        compose.onNodeWithText("Primera página").assertIsDisplayed()
        compose.runOnIdle {
            assertNull(model.state.value.nextKey)
            assertEquals(1, model.state.value.items.size)
        }
    }

    @Test fun unavailablePlanKeepsScoreWithNeutralReference() {
        val source = Source().apply {
            pages[null] = ReceivedRatingsPage("me", 1, 5.0,
                listOf(rating("Encuentro no disponible", available = false)), null)
        }
        val model = model(source); screen(model)
        compose.onNodeWithText("Encuentro no disponible").assertIsDisplayed()
        compose.onNodeWithText("Excelente experiencia").assertIsDisplayed()
        compose.onNodeWithText("El encuentro ya no está disponible; la nota se conserva.").assertIsDisplayed()
    }

    @Test fun positiveSummaryWithNoCardsIsNotPresentedAsZero() {
        val source = Source().apply { pages[null] = ReceivedRatingsPage("me", 3, 4.0, emptyList(), null) }
        val model = model(source); screen(model)
        compose.onNodeWithText("El historial todavía no está disponible").assertIsDisplayed()
        compose.onNodeWithText("0 valoraciones · Sin nota todavía").assertDoesNotExist()
        compose.onNodeWithText("Sin opiniones recibidas").assertDoesNotExist()
    }

    @Test fun expiredSessionClearsPrivateHistoryAndRequestsLogin() {
        val source = Source().apply {
            pages[null] = ReceivedRatingsPage("me", 1, 5.0, listOf(rating("Privada")), null)
        }
        val model = model(source); screen(model)
        compose.runOnIdle { source.error = AuthSessionRequiredException() }
        source.gate = null
        compose.runOnIdle { model.refresh() }
        compose.waitUntil(5000) { model.state.value.requiresLogin }
        compose.runOnIdle {
            assertTrue(model.state.value.items.isEmpty())
            assertEquals(0, model.state.value.count)
            assertNull(model.state.value.nextKey)
        }
    }
}
