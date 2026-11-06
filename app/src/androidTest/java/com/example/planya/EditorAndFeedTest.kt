package com.example.planya

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.data.auth.AuthRepository
import com.example.planya.data.repository.PlanActionsRepository
import com.example.planya.di.NetworkModule
import com.example.planya.domain.model.*
import com.example.planya.domain.repository.PlanRepository
import com.example.planya.domain.usecase.plan.GetPlanUseCase
import com.example.planya.presentation.screens.createplan.*
import com.example.planya.presentation.screens.home.HomeScreen
import com.example.planya.presentation.screens.interests.InterestsScreen
import com.example.planya.ui.theme.PlanesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorAndFeedTest {
    @get:Rule val compose = createComposeRule()
    private val point = LocatedArea(GeoPoint(-12.1, -77.03), "Parque", false, fullAddress = "Calle del Parque 123")

    @Test fun previewDoesNotPublishUntilExplicitConfirmation() {
        var saves = 0
        compose.setContent { PlanesTheme {
            PlanEditorScreen(EditorState(draft = PlanDraft(title = "Café en grupo", location = point)), false,
                point, {}, { saves++ }, {}, {})
        } }
        compose.onNodeWithText("Revisar plan").performClick()
        compose.onNodeWithText("Revisa tu plan").assertExists()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithText("Seguir editando").performClick()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithText("Revisar plan").performClick()
        compose.onNodeWithText("Confirmar publicación").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun interestsSurviveStateRestorationBeforeSaving() {
        val restore = StateRestorationTester(compose)
        var result: Set<String>? = null
        restore.setContent { PlanesTheme { InterestsScreen(emptySet()) { result = it } } }
        compose.onNodeWithText("Café").performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Continuar").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("CAFE"), result) }
    }

    @Test fun feedButtonsSeparateOwnedFromJoinedPlans() {
        val own = Plan("mine", "Yo", timeAgo = "", title = "Mi café", ownerId = "me", attendeeIds = listOf("me"))
        val joined = Plan("joined", "Ana", timeAgo = "", title = "Café de Ana", ownerId = "ana", attendeeIds = listOf("me"))
        compose.setContent { PlanesTheme {
            HomeScreen(listOf(own, joined), false, false, null, false, {}, point, 100,
                {}, {}, {}, {}, {}, {}, userId = "me")
        } }
        compose.onNodeWithText("Mis planes").performClick()
        compose.onNodeWithText("Mi café").assertExists()
        compose.onNodeWithText("Café de Ana").assertDoesNotExist()
        compose.onNodeWithText("Me uní").performClick()
        compose.onNodeWithText("Mi café").assertDoesNotExist()
        compose.onNodeWithText("Café de Ana").assertExists()
    }

    @Test fun draftAndRequestIdSurviveViewModelRecreationWithoutNetwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = object : PlanRepository {
            override suspend fun getPlans(nextKey: String?): PlanPage = error("No se debe consultar la red")
            override suspend fun getPlan(id: String): Plan = error("No se debe consultar la red")
        }
        val actions = PlanActionsRepository(NetworkModule.planActions, AuthRepository(context))
        compose.runOnIdle {
            val handle = SavedStateHandle()
            val editor = PlanEditorViewModel(null, GetPlanUseCase(source), actions, handle)
            val draft = PlanDraft(title = "Borrador sin publicar", location = point)
            editor.change(draft)
            val before = handle.get<String>("requestId")
            val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
            val restored = PlanEditorViewModel(null, GetPlanUseCase(source), actions, restoredHandle)
            assertEquals(draft, restored.state.value.draft)
            assertTrue(restored.state.value.dirty)
            assertEquals(before, restoredHandle.get<String>("requestId"))
            assertNull(restored.state.value.saved)
        }
    }
}
