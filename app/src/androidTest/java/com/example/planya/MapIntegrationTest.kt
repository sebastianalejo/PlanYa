package com.example.planya

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.example.planya.data.location.LocationRepository
import com.example.planya.domain.model.*
import com.example.planya.domain.repository.PlanRepository
import com.example.planya.domain.usecase.plan.*
import com.example.planya.presentation.screens.map.*
import com.example.planya.ui.theme.PlanesTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/** Repositorios y lienzo aislados: sin cuentas, GPS real ni cargas del SDK de Maps. */
class MapIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private val point = GeoPoint(-12.1211, -77.0297)
    private val plan = Plan("p1", "Ana", timeAgo = "", title = "Café de prueba",
        category = PlanCategory.CAFE, coordinates = point, expiresAt = 2000,
        meetingPlace = "Lugar de prueba")
    private inner class Source : PlanRepository {
        val calls = mutableListOf<String?>()
        var response: suspend (String?) -> PlanPage = { PlanPage(listOf(plan), null) }
        override suspend fun getPlans(nextKey: String?): PlanPage { calls += nextKey; return response(nextKey) }
        override suspend fun getPlan(id: String) = plan
    }
    @After fun clearModels() { compose.runOnIdle { models.clear() } }
    private fun model(source: Source, location: LocationRepository? = null): MapViewModel {
        lateinit var model: MapViewModel
        compose.runOnIdle { model = MapViewModel(GetPlansUseCase(source), location); models.put("map", model) }
        return model
    }
    private fun loaded(model: MapViewModel) = compose.waitUntil(5000) {
        model.state.value.hasLoaded && !model.state.value.isLoading && !model.state.value.isLoadingMore
    }
    @Test fun staleListCannotUndoEditCreationOrCancellation() {
        val pending = CompletableDeferred<PlanPage>()
        val source = Source().apply { response = { pending.await() } }
        val model = model(source)
        val changed = plan.copy(title = "Título actualizado", revision = 2)
        val created = plan.copy(id = "new", title = "Recién creado")
        compose.runOnIdle {
            model.refresh(); model.applyPlan(changed); model.applyPlan(created); model.removePlan("cancelled")
            pending.complete(PlanPage(listOf(plan, plan.copy(id = "cancelled")), null))
        }
        loaded(model)
        compose.runOnIdle {
            assertEquals(setOf("p1", "new"), model.state.value.plans.map { it.id }.toSet())
            assertEquals(changed, model.state.value.plans.single { it.id == "p1" })
        }
    }
    @Test fun emptyPageKeepsCursorAndLaterPageUpdatesDuplicatePlan() {
        val source = Source().apply { response = { key -> when (key) {
            null -> PlanPage(emptyList(), "page2")
            "page2" -> PlanPage(listOf(plan), "page3")
            else -> PlanPage(listOf(plan.copy(title = "Cambio del servidor")), null)
        } } }
        val model = model(source)
        compose.runOnIdle { model.ensureLoaded() }; loaded(model)
        compose.runOnIdle { assertEquals("page2", model.state.value.nextKey); model.loadMore() }; loaded(model)
        compose.runOnIdle { model.loadMore() }; loaded(model)
        compose.runOnIdle {
            assertEquals(listOf(null, "page2", "page3"), source.calls)
            assertEquals("Cambio del servidor", model.state.value.plans.single().title)
        }
    }
    @Test fun failedAppendRetriesItsCursorWithoutLosingExistingPins() {
        var fail = true
        val source = Source().apply { response = { key ->
            if (key == null) PlanPage(listOf(plan), "page2")
            else if (fail) throw IOException("Sin red")
            else PlanPage(listOf(plan.copy(id = "p2")), null)
        } }
        val model = model(source)
        compose.runOnIdle { model.refresh() }; loaded(model)
        compose.runOnIdle { model.loadMore() }
        compose.waitUntil { model.state.value.errorMessage != null }
        compose.runOnIdle {
            assertTrue(model.state.value.showingStaleData)
            assertEquals(listOf(plan), model.state.value.plans)
            fail = false; model.retry()
        }
        loaded(model)
        compose.runOnIdle {
            assertEquals(listOf(null, "page2", "page2"), source.calls)
            assertEquals(2, model.state.value.plans.size)
            assertNull(model.state.value.errorMessage)
        }
    }
    @Test fun cameraAndFilterSurviveRefreshAndGpsIsOnlyRequestedExplicitly() {
        var gpsCalls = 0
        val location = object : LocationRepository {
            override suspend fun current(): LocatedArea { gpsCalls++; return LocatedArea(point, "Zona de prueba", true) }
            override suspend fun suggestions(query: String, origin: LocatedArea?, nearbyOnly: Boolean) = emptyList<LocationSuggestion>()
        }
        val model = model(Source(), location)
        val camera = MapCamera(-12.1, -77.1, 14f)
        compose.runOnIdle {
            model.permissionDenied(); model.saveCamera(camera); model.selectCategory(PlanCategory.CAFE); model.refresh()
        }
        loaded(model)
        compose.runOnIdle {
            assertEquals(0, gpsCalls)
            assertEquals(camera, model.state.value.camera)
            assertEquals(PlanCategory.CAFE, model.state.value.category)
            assertNotNull(model.state.value.locationMessage)
            model.locate()
        }
        compose.waitUntil { model.state.value.myLocation != null }
        compose.runOnIdle { assertEquals(1, gpsCalls); assertEquals(1, model.state.value.recenterEvent) }
    }
    private fun screen(state: () -> MapUiState, now: () -> Long = { 1000 },
        select: (String?) -> Unit = {}, open: (Plan) -> Unit = {}, more: () -> Unit = {},
        retry: () -> Unit = {}, fakeMap: Boolean = true, largeText: Boolean = false) {
        compose.setContent { PlanesTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 1.5f else density.fontScale)) {
                Box(Modifier.width(320.dp).fillMaxHeight()) {
                    MapScreen(state(), null, now(), {}, retry, more, {}, select, {}, {}, {}, {}, open, {}, {},
                        mapsAvailable = false, mapContent = if (!fakeMap) null else { pins, onSelect ->
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                pins.forEach { pin -> Button(onClick = { onSelect(pin.key) }) { Text("Pin ${pin.plans.first().id}") } }
                            }
                        })
                }
            }
        } }
    }
    @Test fun selectingPinDoesNotJoinAndDetailsUsesTheChosenId() {
        var state by mutableStateOf(MapUiState(plans = listOf(plan), hasLoaded = true))
        var opened: String? = null
        screen({ state }, select = { state = state.copy(selectedPinKey = it) }, open = { opened = it.id })
        compose.onNodeWithText("Pin p1").performClick()
        compose.onNodeWithTag("map-sheet").assertExists()
        compose.runOnIdle { assertNull(opened) }
        compose.onNodeWithText("Ver detalles").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("p1", opened) }
    }
    @Test fun expiryRemovesPinAndClosesSelectedSheet() {
        var now by mutableLongStateOf(1000)
        var state by mutableStateOf(MapUiState(listOf(plan), hasLoaded = true, selectedPinKey = pinKey(point)))
        screen({ state }, { now }, select = { state = state.copy(selectedPinKey = it) })
        compose.onNodeWithTag("map-sheet").assertExists()
        compose.runOnIdle { now = 2000 }
        compose.onNodeWithTag("map-sheet").assertDoesNotExist()
        compose.onNodeWithText("Pin p1").assertDoesNotExist()
        compose.runOnIdle { assertNull(state.selectedPinKey) }
    }
    @Test fun groupedPinCanChoosePlanAndRecoversWhenChosenPlanDisappears() {
        val other = plan.copy(id = "p2", title = "Segundo encuentro")
        var state by mutableStateOf(MapUiState(listOf(plan, other), hasLoaded = true, selectedPinKey = pinKey(point)))
        var opened: String? = null
        screen({ state }, open = { opened = it.id })
        compose.onNodeWithText("2 planes en este punto").assertExists()
        compose.onNodeWithText("Café de prueba").performScrollTo().performClick()
        compose.onNodeWithText("Ver detalles").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("p1", opened); state = state.copy(plans = listOf(other)) }
        compose.onNodeWithText("Ver detalles").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("p2", opened) }
    }
    @Test fun initialAreaWarningAndLocationButtonDoNotOverlapOnNarrowScreen() {
        screen({ MapUiState(plans = listOf(plan), hasLoaded = true) }, largeText = true)
        val notice = compose.onNodeWithTag("map-origin-notice").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("map-locate").fetchSemanticsNode().boundsInRoot
        assertTrue("El aviso debe quedar encima del botón", notice.bottom <= button.top)
    }
    @Test fun emptyFilteredPageStillOffersNextPage() {
        var calls = 0
        screen({ MapUiState(hasLoaded = true, nextKey = "next", category = PlanCategory.CAFE) }, more = { calls++ })
        compose.onNodeWithText("Cargar más").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun unavailableSdkDoesNotOverlayFalseEmptyResults() {
        screen({ MapUiState(hasLoaded = true) }, fakeMap = false)
        compose.onNodeWithText("El mapa no está disponible en esta versión. Puedes consultar los planes en Inicio.").assertExists()
        compose.onNodeWithText("No hay planes activos con ubicación.").assertDoesNotExist()
    }
    @Test fun cachedPlansShowNetworkWarningAndRetry() {
        var calls = 0
        screen({ MapUiState(plans = listOf(plan), hasLoaded = true, errorMessage = "Sin conexión.") }, retry = { calls++ })
        compose.onNodeWithText("Sin conexión. Se muestran los planes cargados antes.").assertExists()
        compose.onNodeWithText("Reintentar").performClick()
        compose.onNodeWithText("Pin p1").assertExists()
        compose.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun detailActionRemainsReachableWithLargeText() {
        var opened = false
        screen({ MapUiState(plans = listOf(plan.copy(title = "Un título largo para comprobar la ficha del encuentro")),
            hasLoaded = true, selectedPinKey = pinKey(point)) }, largeText = true, open = { opened = true })
        compose.onNodeWithText("Ver detalles").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(opened) }
    }
}
