package com.example.planya

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.planya.domain.model.*
import com.example.planya.domain.repository.*
import com.example.planya.domain.usecase.plan.*
import com.example.planya.presentation.navigation.ForegroundRefreshObserver
import com.example.planya.presentation.screens.home.HomeViewModel
import com.example.planya.presentation.screens.plandetail.PlanDetailScreen
import com.example.planya.ui.theme.PlanesTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class PlanLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private val plan = Plan("p1", "Ana", timeAgo = "", title = "Encuentro", ownerId = "host",
        capacity = 3, attendees = listOf("Ana"), attendeeIds = listOf("host"), expiresAt = 1000)
    private fun source(list: suspend () -> PlanPage = { PlanPage(listOf(plan), null) },
        detail: suspend () -> Plan) = object : PlanRepository {
        override suspend fun getPlans(nextKey: String?) = list()
        override suspend fun getPlan(id: String) = detail()
    }
    @Test fun onlyReturningToForegroundTriggersRefreshAndObserverCanBeRemoved() {
        compose.runOnIdle {
            val owner = object : LifecycleOwner {
                val registry = LifecycleRegistry(this)
                override val lifecycle: Lifecycle get() = registry
            }
            var calls = 0
            val observer = ForegroundRefreshObserver { calls++ }
            owner.registry.addObserver(observer)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertEquals(0, calls)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            assertEquals(0, calls)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertEquals(1, calls)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            assertEquals(1, calls)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertEquals(2, calls)
            owner.registry.removeObserver(observer)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertEquals(2, calls)
        }
    }
    @Test fun unavailableDetailRemovesCachedPlanAndAnOlderListCannotResurrectIt() {
        val pendingList = CompletableDeferred<PlanPage>()
        val repository = source(list = { pendingList.await() }) { throw PlanUnavailableException() }
        lateinit var home: HomeViewModel
        compose.runOnIdle {
            home = HomeViewModel(GetPlansUseCase(repository), GetPlanUseCase(repository))
            home.applyPlan(plan)
            home.openPlan(plan.id)
        }
        compose.waitUntil { !home.detail.value.isLoading }
        compose.runOnIdle {
            assertNull(home.detail.value.plan)
            assertEquals(plan.id, home.detail.value.unavailableId)
            assertTrue(home.uiState.value.plans.isEmpty())
            pendingList.complete(PlanPage(listOf(plan), null))
        }
        compose.waitUntil { !home.uiState.value.isLoading }
        compose.runOnIdle { assertTrue(home.uiState.value.plans.isEmpty()) }
    }
    @Test fun temporaryConnectionErrorDoesNotRemoveKnownPlan() {
        val repository = source { throw IOException("Sin red de prueba") }
        lateinit var home: HomeViewModel
        compose.runOnIdle {
            home = HomeViewModel(GetPlansUseCase(repository), GetPlanUseCase(repository))
            home.applyPlan(plan); home.openPlan(plan.id)
        }
        compose.waitUntil { !home.detail.value.isLoading }
        compose.runOnIdle {
            assertEquals(plan, home.detail.value.plan)
            assertNotNull(home.detail.value.error)
            assertNull(home.detail.value.unavailableId)
            assertEquals(listOf(plan), home.uiState.value.plans)
        }
    }
    @Test fun refreshReplacesOldPhotoLinkAndParticipantCount() {
        var remote = plan.copy(imageUrl = "https://example.invalid/old")
        val repository = source { remote }
        lateinit var home: HomeViewModel
        compose.runOnIdle {
            home = HomeViewModel(GetPlansUseCase(repository), GetPlanUseCase(repository))
            home.openPlan(plan.id)
        }
        compose.waitUntil { !home.detail.value.isLoading }
        compose.runOnIdle {
            remote = plan.copy(imageUrl = "https://example.invalid/renewed", attendees = listOf("Ana", "Luis"), revision = 2)
            home.openPlan(plan.id)
        }
        compose.waitUntil { !home.detail.value.isLoading }
        compose.runOnIdle {
            assertEquals(remote, home.detail.value.plan)
            assertEquals(2, home.detail.value.plan!!.attendees!!.size)
        }
    }
    @Test fun expiryClosesPendingCancellationWithoutSendingIt() {
        var now by mutableLongStateOf(100)
        var cancellations = 0
        compose.setContent { PlanesTheme {
            PlanDetailScreen(plan, false, null, {}, null, now, {}, {}, {}, {}, "host", false,
                {}, {}, {}, { cancellations++ })
        } }
        compose.onNodeWithText("Cancelar este plan").performScrollTo().performClick()
        compose.onNodeWithText("¿Cancelar este plan?").assertExists()
        compose.runOnIdle { now = 1000 }
        compose.onNodeWithText("¿Cancelar este plan?").assertDoesNotExist()
        compose.onNodeWithText("Este plan ya finalizó").assertExists()
        compose.runOnIdle { assertEquals(0, cancellations) }
    }
}
