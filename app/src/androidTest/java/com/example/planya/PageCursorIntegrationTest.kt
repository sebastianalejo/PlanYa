package com.example.planya

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.example.planya.domain.model.*
import com.example.planya.domain.repository.*
import com.example.planya.domain.usecase.plan.*
import com.example.planya.domain.usecase.benefits.*
import com.example.planya.presentation.screens.home.HomeViewModel
import com.example.planya.presentation.screens.map.MapViewModel
import com.example.planya.presentation.screens.benefits.BenefitsViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class PageCursorIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private val plan = Plan("p1", "Ana", timeAgo = "", title = "Prueba")
    private inner class Plans : PlanRepository {
        val calls = mutableListOf<String?>()
        var failure: Exception = PageCursorException()
        override suspend fun getPlans(nextKey: String?): PlanPage {
            calls += nextKey
            if (nextKey != null) throw failure
            return PlanPage(listOf(plan), "old")
        }
        override suspend fun getPlan(id: String) = plan
    }
    @After fun clear() { compose.runOnIdle { models.clear() } }
    @Test fun homeKeepsCardsAndRetryStartsAtFirstPageAfterCursorExpires() {
        val source = Plans()
        lateinit var home: HomeViewModel
        compose.runOnIdle { home = HomeViewModel(GetPlansUseCase(source), GetPlanUseCase(source)); models.put("home", home) }
        compose.waitUntil { !home.uiState.value.isLoading }
        compose.runOnIdle { home.loadMore() }
        compose.waitUntil { home.uiState.value.errorMessage != null }
        compose.runOnIdle {
            assertEquals(listOf(plan), home.uiState.value.plans); assertNull(home.uiState.value.nextKey)
            home.retry()
        }
        compose.waitUntil { !home.uiState.value.isLoading }
        compose.runOnIdle { assertEquals(listOf(null, "old", null), source.calls) }
    }
    @Test fun mapKeepsPinsAndResetsOnlyExpiredCursor() {
        val source = Plans()
        lateinit var map: MapViewModel
        compose.runOnIdle { map = MapViewModel(GetPlansUseCase(source), null); models.put("map", map); map.refresh() }
        compose.waitUntil { map.state.value.hasLoaded }
        compose.runOnIdle { map.loadMore() }
        compose.waitUntil { map.state.value.errorMessage != null }
        compose.runOnIdle { assertEquals(listOf(plan), map.state.value.plans); assertNull(map.state.value.nextKey); map.retry() }
        compose.waitUntil { !map.state.value.isLoading }
        compose.runOnIdle { assertEquals(listOf(null, "old", null), source.calls) }
    }
    @Test fun networkFailureRetainsCursorForRetryRatherThanStartingOver() {
        val source = Plans().apply { failure = IOException("offline") }
        lateinit var map: MapViewModel
        compose.runOnIdle { map = MapViewModel(GetPlansUseCase(source), null); models.put("map", map); map.refresh() }
        compose.waitUntil { map.state.value.hasLoaded }
        compose.runOnIdle { map.loadMore() }
        compose.waitUntil { map.state.value.errorMessage != null }
        compose.runOnIdle { assertEquals("old", map.state.value.nextKey); map.retry() }
        compose.waitUntil { !map.state.value.isLoadingMore }
        compose.runOnIdle { assertEquals(listOf(null, "old", "old"), source.calls) }
    }
    @Test fun benefitsKeepCouponsAndReloadWithoutExpiredContinuation() {
        val coupon = Benefit("c1", "u1", "Local de prueba", "Prueba", "Regla", status = BenefitStatus.DISPONIBLE)
        val calls = mutableListOf<String?>()
        val source = object : BenefitsRepository {
            override suspend fun getBenefits(status: BenefitStatus, nextKey: String?): BenefitPage {
                calls += nextKey
                if (nextKey != null) throw PageCursorException()
                return BenefitPage(listOf(coupon), "old")
            }
            override suspend fun getBenefit(id: String) = coupon
            override suspend fun getCode(id: String) = BenefitCode("ABCDEF123456", 2000)
        }
        lateinit var model: BenefitsViewModel
        compose.runOnIdle {
            model = BenefitsViewModel(GetBenefitsUseCase(source), GetBenefitUseCase(source), GetBenefitCodeUseCase(source), "u1")
            models.put("benefits", model)
        }
        compose.waitUntil { model.state.value.loaded }
        compose.runOnIdle { model.loadMore() }
        compose.waitUntil { model.state.value.errorMessage != null }
        compose.runOnIdle { assertEquals(listOf(coupon), model.state.value.benefits); assertNull(model.state.value.nextKey); model.retry() }
        compose.waitUntil { !model.state.value.isLoading }
        compose.runOnIdle { assertEquals(listOf(null, "old", null), calls) }
    }
}
