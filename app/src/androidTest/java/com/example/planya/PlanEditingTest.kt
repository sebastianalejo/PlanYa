package com.example.planya

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.example.planya.data.repository.PlanConflictException
import com.example.planya.domain.model.*
import com.example.planya.domain.repository.*
import com.example.planya.domain.usecase.plan.*
import com.example.planya.presentation.screens.createplan.*
import com.example.planya.presentation.screens.home.HomeViewModel
import com.example.planya.ui.theme.PlanesTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlanEditingTest {
    @get:Rule val compose = createComposeRule()
    private val original = Plan("p1", "Ana", timeAgo = "", title = "Café", description = "Conversar",
        coordinates = GeoPoint(-12.1, -77.03), meetingPlace = "Parque", capacity = 5,
        attendeeIds = listOf("u1", "u2", "u3"), startsAt = 4102440000,
        expiresAt = 4102444800, imageKey = "old-photo", revision = 4)
    private class Actions : PlanActions {
        var received: Map<String, Any>? = null
        var failure: Exception? = null
        var calls = 0
        override suspend fun edit(id: String, data: Map<String, Any>): Plan {
            calls++; failure?.let { throw it }; received = data
            return Plan(id, "Ana", timeAgo = "", title = data["title"] as String)
        }
        override suspend fun create(data: Map<String, Any>): Plan = error("No publicar")
        override suspend fun join(id: String): Plan = error("Sin red")
        override suspend fun leave(id: String): Plan = error("Sin red")
        override suspend fun cancel(id: String): Plan = error("Sin red")
    }
    private fun source(read: suspend () -> Plan) = object : PlanRepository {
        override suspend fun getPlan(id: String) = read()
        override suspend fun getPlans(nextKey: String?) = PlanPage(emptyList(), null)
    }
    @Test fun loadingDoesNotAllowTypingThatWouldBeOverwritten() {
        val pending = CompletableDeferred<Plan>()
        lateinit var editor: PlanEditorViewModel
        compose.runOnIdle { editor = PlanEditorViewModel("p1", GetPlanUseCase(source { pending.await() }), Actions()) }
        compose.setContent { PlanesTheme { PlanEditorScreen(editor.state.value, true, null, editor::change, editor::save, editor::load, {}) } }
        compose.onNodeWithText("Título del plan").assertIsNotEnabled()
        compose.runOnIdle { editor.change(PlanDraft(title = "No debe aceptar")); assertEquals("", editor.state.value.draft.title); pending.complete(original) }
        compose.waitUntil { editor.state.value.loaded }
        compose.runOnIdle { assertEquals("Café", editor.state.value.draft.title) }
    }
    @Test fun editKeepsPhotoRevisionAndTimingAndRejectsTooSmallCapacity() {
        val actions = Actions()
        lateinit var editor: PlanEditorViewModel
        compose.runOnIdle { editor = PlanEditorViewModel("p1", GetPlanUseCase(source { original }), actions) }
        compose.waitUntil { editor.state.value.loaded }
        compose.runOnIdle {
            val draft = editor.state.value.draft
            assertEquals(4102444800L, draft.originalExpiresAt)
            assertEquals(4102440000L, draft.originalStartsAt)
            assertEquals(3, draft.minimumCapacity)
            editor.change(draft.copy(capacity = "2")); editor.save()
            assertEquals(0, actions.calls)
            editor.change(draft.copy(title = "Café editado", capacity = "3")); editor.save()
        }
        compose.waitUntil { editor.state.value.saved != null }
        compose.runOnIdle {
            assertEquals("Café editado", actions.received?.get("title"))
            assertEquals(4L, actions.received?.get("expectedRevision"))
            assertEquals("old-photo", actions.received?.get("imageKey"))
            assertFalse(actions.received!!.containsKey("expiresAt"))
        }
    }
    @Test fun conflictKeepsDraftAndReloadFailureDoesNotDiscardIt() {
        val actions = Actions().apply { failure = PlanConflictException("Cambió el plan") }
        var readFails = false
        lateinit var editor: PlanEditorViewModel
        compose.runOnIdle { editor = PlanEditorViewModel("p1", GetPlanUseCase(source { if (readFails) error("Sin conexión") else original }), actions, SavedStateHandle()) }
        compose.waitUntil { editor.state.value.loaded }
        compose.runOnIdle { editor.change(editor.state.value.draft.copy(title = "Mis cambios")); editor.save() }
        compose.waitUntil { editor.state.value.conflict }
        compose.runOnIdle {
            assertEquals("Mis cambios", editor.state.value.draft.title)
            editor.save(); assertEquals(1, actions.calls)
            readFails = true; editor.load()
        }
        compose.waitUntil { !editor.state.value.loading }
        compose.runOnIdle { assertEquals("Mis cambios", editor.state.value.draft.title); assertTrue(editor.state.value.conflict); readFails = false; editor.load() }
        compose.waitUntil { !editor.state.value.loading }
        compose.runOnIdle { assertEquals("Café", editor.state.value.draft.title); assertFalse(editor.state.value.conflict); assertFalse(editor.state.value.dirty) }
    }
    @Test fun expiredEditNeverWrites() {
        val actions = Actions()
        lateinit var editor: PlanEditorViewModel
        compose.runOnIdle { editor = PlanEditorViewModel("p1", GetPlanUseCase(source { original.copy(expiresAt = 1) }), actions) }
        compose.waitUntil { editor.state.value.loaded }
        compose.runOnIdle { editor.save(); assertEquals(0, actions.calls); assertTrue(editor.state.value.error!!.contains("finalizó")) }
    }
    @Test fun pendingListCannotUndoEditOrResurrectCancelledPlan() {
        val pending = CompletableDeferred<PlanPage>()
        val repository = object : PlanRepository {
            override suspend fun getPlans(nextKey: String?) = pending.await()
            override suspend fun getPlan(id: String) = original
        }
        lateinit var home: HomeViewModel
        compose.runOnIdle {
            home = HomeViewModel(GetPlansUseCase(repository), GetPlanUseCase(repository))
            home.applyPlan(original.copy(title = "Cambio confirmado"))
            home.removePlan("cancelled")
            pending.complete(PlanPage(listOf(original, original.copy(id = "cancelled")), null))
        }
        compose.waitUntil { !home.uiState.value.isLoading }
        compose.runOnIdle {
            assertEquals(listOf("Cambio confirmado"), home.uiState.value.plans.map { it.title })
        }
    }
    @Test fun reloadingConflictedEditorRequiresConfirmation() {
        var reloads = 0
        compose.setContent { PlanesTheme {
            PlanEditorScreen(EditorState(draft = PlanDraft(title = "Borrador"), conflict = true, error = "El plan cambió"),
                true, null, {}, {}, { reloads++ }, {})
        } }
        compose.onNodeWithText("Revisar cambios").assertIsNotEnabled()
        compose.onNodeWithText("Recargar versión actual").performClick()
        compose.onNodeWithText("Conservar borrador").performClick()
        compose.runOnIdle { assertEquals(0, reloads) }
        compose.onNodeWithText("Recargar versión actual").performClick()
        compose.onNodeWithText("Sí, recargar").performClick()
        compose.runOnIdle { assertEquals(1, reloads) }
    }
}
