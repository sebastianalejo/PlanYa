package com.example.planya

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.profile.*
import com.example.planya.presentation.screens.profile.*
import com.example.planya.ui.theme.PlanesTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class RatingsIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private val candidate = RatingCandidate("52b13d85-3994-5eb5-aa67-1e412dbc9528", "other", "Café de prueba aislada", "Andrea de prueba")
    private inner class Source : RatingsGateway {
        var items = listOf(candidate)
        var readError: Exception? = null
        var writeError: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<Pair<RatingCandidate, Int>>()
        override suspend fun pending(): List<RatingCandidate> { readError?.let { throw it }; return items }
        override suspend fun rate(candidate: RatingCandidate, score: Int) {
            writes += candidate to score
            gate?.await()
            writeError?.let { throw it }
        }
    }
    @After fun cleanup() { compose.runOnIdle { models.clear() } }
    private fun model(source: Source): RatingsViewModel {
        lateinit var model: RatingsViewModel
        compose.runOnIdle { model = RatingsViewModel(source); models.put("ratings", model); model.load() }
        compose.waitUntil(5000) { !model.state.value.loading }
        return model
    }
    private fun screen(model: RatingsViewModel) {
        compose.setContent { PlanesTheme {
            val state by model.state.collectAsState()
            RatingsScreen(state, {}, model::load, model::select, model::score, model::review, model::dismiss, model::save)
        } }
    }
    @Test fun emptyStateDoesNotInventPeopleOrVotes() {
        val source = Source().apply { items = emptyList() }
        val model = model(source); screen(model)
        compose.onNodeWithText("Sin valoraciones pendientes").assertIsDisplayed()
        compose.onNodeWithText("Valorar participante").assertDoesNotExist()
        compose.runOnIdle { assertTrue(source.writes.isEmpty()) }
    }
    @Test fun failureIsNotPresentedAsEmptyAndRefreshRecovers() {
        val source = Source().apply { readError = IllegalStateException("Sin conexión de prueba") }
        val model = model(source); screen(model)
        compose.onNodeWithText("Sin conexión de prueba").assertIsDisplayed()
        compose.onNodeWithText("Sin valoraciones pendientes").assertDoesNotExist()
        compose.runOnIdle { source.readError = null }
        compose.onNodeWithText("Actualizar encuentros").performClick()
        compose.waitUntil(5000) { model.state.value.items.isNotEmpty() }
        compose.onNodeWithText(candidate.name).assertIsDisplayed()
    }
    @Test fun choosingANoteRequiresReviewAndExplicitServerConfirmation() {
        val source = Source(); val model = model(source); screen(model)
        compose.onNodeWithText("Valorar participante").performClick()
        compose.onNodeWithText("Revisar").assertIsNotEnabled()
        compose.onNodeWithText("5", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertTrue(source.writes.isEmpty()) }
        compose.onNodeWithText("Revisar").performClick()
        compose.onNodeWithText("Confirma tu valoración").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "ratings-review.png").outputStream().use {
            compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Confirmar valoración").performClick()
        compose.waitUntil(5000) { model.state.value.success != null }
        compose.onNodeWithText("Valoración guardada por el servidor.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(candidate to 5), source.writes); assertTrue(model.state.value.items.isEmpty()) }
    }
    @Test fun uncertainWriteKeepsSameTargetAndNoteForRetry() {
        val source = Source().apply { writeError = IllegalStateException("Respuesta sin confirmar") }
        val model = model(source)
        compose.runOnIdle { model.select(candidate); model.score(4); model.review(); model.save() }
        compose.waitUntil(5000) { !model.state.value.busy }
        compose.runOnIdle {
            assertNull(model.state.value.success)
            assertEquals(candidate, model.state.value.selected)
            model.score(1)
            assertEquals(4, model.state.value.score)
            source.writeError = null
            model.save()
        }
        compose.waitUntil(5000) { model.state.value.success != null }
        compose.runOnIdle { assertEquals(listOf(candidate to 4, candidate to 4), source.writes) }
    }
    @Test fun duplicateTapsDoNotSendConcurrentWrites() {
        val source = Source().apply { gate = CompletableDeferred() }
        val model = model(source)
        compose.runOnIdle { model.select(candidate); model.score(3); model.review(); model.save(); model.save() }
        compose.waitUntil(5000) { source.writes.isNotEmpty() }
        compose.runOnIdle { assertEquals(1, source.writes.size); source.gate!!.complete(Unit) }
        compose.waitUntil(5000) { model.state.value.success != null }
    }
    @Test fun expiredSessionClearsPrivateCandidatesAndStopsWrites() {
        val source = Source().apply { writeError = AuthSessionRequiredException() }
        val model = model(source)
        compose.runOnIdle { model.select(candidate); model.score(5); model.review(); model.save() }
        compose.waitUntil(5000) { model.state.value.requiresLogin }
        compose.runOnIdle { assertTrue(model.state.value.items.isEmpty()); assertNull(model.state.value.selected); model.save(); assertEquals(1, source.writes.size) }
    }
    @Test fun profileEntryOpensRatingsCallback() {
        var opened = false
        compose.setContent { PlanesTheme {
            ProfileScreen("Andrea", "andrea@example.com", emptySet(), null, false, false, null,
                {}, {}, {}, {}, {}, {}, {}, {}, onRatings = { opened = true })
        } }
        compose.onNodeWithTag("perfil-lista").performScrollToNode(hasText("Valorar encuentros"))
        compose.onNodeWithText("Valorar encuentros").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }
}
