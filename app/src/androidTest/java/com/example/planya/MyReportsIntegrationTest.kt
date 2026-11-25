package com.example.planya

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.domain.model.*
import com.example.planya.domain.repository.ReportsRepository
import com.example.planya.presentation.screens.reports.MyReportsScreen
import com.example.planya.presentation.screens.reports.MyReportsViewModel
import com.example.planya.ui.theme.PlanesTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/** US-13 Mis reportes. Datos aislados: no usa la cuenta real ni hace peticiones a AWS. */
class MyReportsIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()

    private fun summary(n: Int, status: ReportStatus, created: Long = 1_795_000_000L + n) = ReportSummary(
        "00000000-0000-4000-8000-%012d".format(n), "%04d".format(n), ReportTargetType.PLAN,
        "Plan de prueba $n", ReportReason.SPAM, status, created, created, false)

    private val open = summary(2, ReportStatus.EN_REVISION)
    private val closed = summary(1, ReportStatus.RESUELTO).copy(hasResponse = true)

    private inner class Source : ReportsRepository {
        val pages = mutableListOf<Pair<ReportFilter, String?>>()
        val details = mutableListOf<String>()
        var page: suspend (ReportFilter, String?) -> ReportsPage = { filter, _ ->
            ReportsPage(listOf(open, closed).filter { filter.accepts(it.status) }, null)
        }
        var detail: suspend (String) -> ReportDetail = { id ->
            val item = listOf(open, closed).first { it.id == id }
            ReportDetail(item, "Descripción propia del reporte $id",
                if (item.status.isOpen) null else "Revisamos el contenido y atendimos tu reporte.", item.id != closed.id)
        }
        override suspend fun page(filter: ReportFilter, nextKey: String?): ReportsPage { pages += filter to nextKey; return page.invoke(filter, nextKey) }
        override suspend fun detail(id: String): ReportDetail { details += id; return detail.invoke(id) }
    }

    @After fun clear() { compose.runOnIdle { models.clear() } }

    private fun show(source: Source): MyReportsViewModel {
        lateinit var model: MyReportsViewModel
        compose.runOnIdle { model = MyReportsViewModel(source); models.put("reports", model) }
        compose.setContent {
            PlanesTheme {
                val state by model.state.collectAsStateWithLifecycle()
                MyReportsScreen(state, onBack = {}, onRefresh = model::refresh, onRetry = model::retry,
                    onFilter = model::selectFilter, onToggle = model::toggle, onRetryDetail = model::retryDetail,
                    onLoadMore = model::loadMore)
            }
        }
        compose.runOnIdle { model.refresh() }
        idle(model)
        return model
    }

    private fun idle(model: MyReportsViewModel) = compose.waitUntil(5000) {
        val s = model.state.value
        !s.loading && !s.loadingMore && s.details.values.none { it.loading } && (s.loaded || s.error != null || s.requiresLogin)
    }

    // CA 13-01
    @Test fun emptyHistoryShowsConfirmedEmptyWithoutDemoCases() {
        val source = Source().apply { page = { _, _ -> ReportsPage(emptyList(), null) } }
        show(source)
        compose.onNodeWithText("Aún no has enviado reportes").assertIsDisplayed()
        compose.onAllNodesWithText("Plan de prueba", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("reports-more").assertDoesNotExist()
    }

    // CA 13-02 y 13-04
    @Test fun expandShowsOwnDescriptionAndPublicResponseOfSameId() {
        val source = Source()
        val model = show(source)
        compose.onNodeWithText("Solo se muestran los reportes de tu cuenta").assertIsDisplayed()
        compose.onNodeWithText("#0001").assertIsDisplayed()
        compose.onNodeWithText("Resuelto").assertIsDisplayed()
        compose.onNodeWithTag("report-toggle-${closed.id}").performClick()
        idle(model)
        assertEquals(listOf(closed.id), source.details)
        compose.onNodeWithText("Descripción propia del reporte ${closed.id}").assertIsDisplayed()
        compose.onNodeWithText("Revisamos el contenido y atendimos tu reporte.").assertIsDisplayed()
        compose.onNodeWithText("Ocultar detalle").performClick()
        compose.onNodeWithTag("report-detail-${closed.id}").assertDoesNotExist()
        // Un reporte abierto indica que sigue pendiente.
        compose.onNodeWithTag("report-toggle-${open.id}").performClick()
        idle(model)
        compose.onNodeWithText("pendiente de revisión", substring = true).assertIsDisplayed()
    }

    // CA 13-03: el filtro cambia el pedido y una respuesta atrasada del filtro anterior no se mezcla.
    @Test fun filtersRequestOwnStatesAndIgnoreLateResponses() {
        val late = CompletableDeferred<ReportsPage>()
        val source = Source()
        val model = show(source)
        source.page = { filter, _ ->
            if (filter == ReportFilter.ABIERTOS) late.await()
            else ReportsPage(listOf(closed), null)
        }
        compose.onNodeWithTag("reports-filter-ABIERTOS").performClick()
        compose.onNodeWithTag("reports-filter-CERRADOS").performClick()
        idle(model)
        compose.runOnIdle { late.complete(ReportsPage(listOf(open), null)) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(ReportFilter.CERRADOS, model.state.value.filter)
            assertEquals(listOf(closed.id), model.state.value.items.map { it.id })
        }
        assertEquals(listOf(ReportFilter.TODOS, ReportFilter.ABIERTOS, ReportFilter.CERRADOS), source.pages.map { it.first })
    }

    // CA 13-03: continuación sin duplicados ni solicitudes simultáneas; página vacía con cursor.
    @Test fun loadMoreContinuesWithoutDuplicates() {
        val gate = CompletableDeferred<Unit>()
        val source = Source().apply {
            page = { _, key ->
                if (key == null) ReportsPage(emptyList(), "c1")
                else { gate.await(); ReportsPage(listOf(open, open), null) }
            }
        }
        val model = show(source)
        compose.onNodeWithText("Continúa la búsqueda").assertIsDisplayed()
        compose.onNodeWithTag("reports-more").performClick()
        compose.runOnIdle { model.loadMore() } // doble toque
        compose.runOnIdle { gate.complete(Unit) }
        idle(model)
        assertEquals(listOf<String?>(null, "c1"), source.pages.map { it.second })
        compose.runOnIdle { assertEquals(listOf(open.id), model.state.value.items.map { it.id }) }
        compose.onNodeWithTag("reports-more").assertDoesNotExist()
    }

    // CA 13-06
    @Test fun networkFailureKeepsLoadedReportsWithNotice() {
        val source = Source()
        val model = show(source)
        source.page = { _, _ -> throw IOException("sin red") }
        compose.onNodeWithTag("reports-refresh").performClick()
        idle(model)
        compose.onNodeWithText("Ves los reportes cargados antes; pueden no estar actualizados.").assertIsDisplayed()
        compose.onNodeWithText("#0002").assertIsDisplayed()
        source.page = { _, _ -> ReportsPage(listOf(open), null) }
        compose.onNodeWithText("Reintentar").performClick()
        idle(model)
        compose.onNodeWithTag("reports-error").assertDoesNotExist()
    }

    // CA 13-07: la sesión vencida limpia lista, expansión y cursores; no hay controles de moderación.
    @Test fun expiredSessionClearsPrivateState() {
        val source = Source().apply { page = { _, _ -> ReportsPage(listOf(open), "c1") } }
        val model = show(source)
        compose.onNodeWithTag("report-toggle-${open.id}").performClick()
        idle(model)
        compose.onAllNodesWithText("Resolver", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Eliminar", substring = true).assertCountEquals(0)
        source.page = { _, _ -> throw AuthSessionRequiredException() }
        compose.onNodeWithTag("reports-refresh").performClick()
        idle(model)
        compose.runOnIdle {
            val s = model.state.value
            assertTrue(s.requiresLogin)
            assertTrue(s.items.isEmpty() && s.details.isEmpty())
            assertNull(s.nextKey); assertNull(s.expandedId)
        }
        compose.onNodeWithText("#0002").assertDoesNotExist()
    }

    // CA 13-08
    @Test fun unavailableTargetIsMarkedWithoutLosingReport() {
        val source = Source().apply {
            detail = { id -> ReportDetail(open, "Descripción", null, targetAvailable = false) }
        }
        val model = show(source)
        compose.onNodeWithTag("report-toggle-${open.id}").performClick()
        idle(model)
        compose.onNodeWithText("Contenido no disponible").assertIsDisplayed()
        compose.onNodeWithText("#0002").assertIsDisplayed()
    }

    @Test fun detailFailureOffersRetryInsideCard() {
        var fail = true
        val source = Source()
        source.detail = { id -> if (fail) throw IOException("sin red") else ReportDetail(open, "Descripción", null, true) }
        val model = show(source)
        compose.onNodeWithTag("report-toggle-${open.id}").performClick()
        idle(model)
        compose.onNodeWithText("Reintentar detalle").assertIsDisplayed()
        fail = false
        compose.onNodeWithText("Reintentar detalle").performClick()
        idle(model)
        compose.onNodeWithText("Descripción").assertIsDisplayed()
    }
}
