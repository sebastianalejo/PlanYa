package com.example.planya

import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.operator.OperatorJournal
import com.example.planya.domain.model.*
import com.example.planya.presentation.screens.operator.*
import com.example.planya.presentation.screens.profile.ProfileScreen
import com.example.planya.ui.theme.PlanesTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class OperatorIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private val planId = "4cd6a6a2-642b-46fe-82e4-b7691eec3800"
    private val offer = OperatorOffer("pilot", "Beneficio de prueba", "Local de prueba", "Condición de prueba", "Solo prueba aislada", listOf(planId))
    private val member = OperatorMember("visitor", "Ana de prueba", false)
    private val plan = OperatorPlan("pilot", planId, "Café de prueba", listOf(member))
    private val requestIds = mutableMapOf<List<String>, String>()
    private val journal = OperatorRequestJournal { payload -> requestIds.getOrPut(payload) { UUID.randomUUID().toString() } }
    private inner class Source : OperatorRepository {
        var error: Exception? = null
        var writeError: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<String>()
        override suspend fun offers(nextKey: String?): OperatorCatalog { error?.let { throw it }; return OperatorCatalog(listOf(offer), null) }
        override suspend fun participants(offerId: String, planId: String): OperatorPlan { error?.let { throw it }; return plan }
        override suspend fun confirm(offerId: String, planId: String, userId: String, requestId: String): OperatorReceipt {
            writes += requestId; gate?.await(); writeError?.let { throw it }; return OperatorReceipt(requestId, "coupon")
        }
        override suspend fun redeem(code: String, requestId: String): OperatorReceipt = confirm("", "", "", requestId)
    }
    @After fun clear() { compose.runOnIdle { models.clear() } }
    private fun model(source: Source, key: String = "operator"): OperatorViewModel {
        lateinit var result: OperatorViewModel
        compose.runOnIdle { result = OperatorViewModel(source, journal); models.put(key, result); result.load() }
        compose.waitUntil(5000) { !result.state.value.loading }
        return result
    }
    private fun screen(state: OperatorUiState, back: () -> Unit = {}, review: () -> Unit = {}, confirm: () -> Unit = {},
        select: (OperatorOffer, String) -> Unit = { _, _ -> }, more: () -> Unit = {}) {
        compose.setContent { PlanesTheme {
            OperatorScreen(state, back, {}, more, {}, select, {}, {}, {}, review, confirm, {})
        } }
    }
    @Test fun ordinaryUserSeesRestrictionWithoutSensitiveForms() {
        val source = Source().apply { error = OperatorDeniedException() }
        val model = model(source)
        compose.runOnIdle { assertTrue(model.state.value.denied); assertTrue(model.state.value.offers.isEmpty()) }
        screen(model.state.value)
        compose.onNodeWithText("Acceso restringido").assertExists()
        compose.onNodeWithTag("operator-code").assertDoesNotExist()
        compose.onNodeWithText(offer.title).assertDoesNotExist()
    }
    @Test fun catalogSelectionKeepsOfferAndPlanAndHasNoFakeQrScanner() {
        var selected = ""
        screen(OperatorUiState(offers = listOf(offer), loaded = true), select = { selectedOffer, id ->
            assertEquals(offer, selectedOffer); selected = id
        })
        compose.onNodeWithText("Ver encuentro 1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(planId, selected) }
        compose.onNodeWithText("Escanear QR").assertDoesNotExist()
    }
    @Test fun emptyPageWithCursorStillOffersContinuation() {
        var more = 0
        screen(OperatorUiState(loaded = true, nextKey = "opaque"), more = { more++ })
        compose.onNodeWithText("Cargar más ofertas").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, more) }
    }
    @Test fun reviewingCodeDoesNotConsumeUntilExplicitConfirmation() {
        var reviews = 0; var sends = 0
        screen(OperatorUiState(loaded = true, redeemTab = true, code = "ABCDEF123456"), review = { reviews++ }, confirm = { sends++ })
        compose.onNodeWithText("Revisar canje").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, reviews); assertEquals(0, sends) }
        compose.onNodeWithText("Operación confirmada").assertDoesNotExist()
    }
    @Test fun uncertainWriteRetriesSameRequestEvenAfterRecreatingModel() {
        val source = Source().apply { writeError = OperatorUncertainException() }
        val first = model(source)
        compose.runOnIdle { first.code("ABCDEF123456"); first.reviewCode(); first.confirm() }
        compose.waitUntil { first.state.value.operationError != null }
        compose.runOnIdle { assertNull(first.state.value.success); first.confirm() }
        compose.waitUntil { source.writes.size == 2 && !first.state.value.writing }
        val second = model(source, "second")
        compose.runOnIdle { source.writeError = null; second.code("ABCDEF123456"); second.reviewCode(); second.confirm() }
        compose.waitUntil { second.state.value.success != null }
        compose.runOnIdle { assertEquals(3, source.writes.size); assertEquals(1, source.writes.distinct().size) }
    }
    @Test fun duplicateTapBlockedUntilWriteFinishesAndSuccessComesFromServer() {
        val source = Source().apply { gate = CompletableDeferred() }
        val model = model(source)
        compose.runOnIdle { model.code("ABCDEF123456"); model.reviewCode(); model.confirm(); model.confirm() }
        compose.waitUntil { source.writes.isNotEmpty() }
        compose.runOnIdle {
            assertTrue(model.state.value.writing); assertNull(model.state.value.success)
            model.code("000000000000"); assertEquals("ABCDEF123456", model.state.value.code)
            assertEquals(1, source.writes.size); source.gate!!.complete(Unit)
        }
        compose.waitUntil { model.state.value.success != null }
    }
    @Test fun expiredSessionWipesCodeParticipantsAndBlocksFurtherRequests() {
        val source = Source()
        val model = model(source)
        compose.runOnIdle { model.selectPlan(offer, planId) }
        compose.waitUntil { model.state.value.plan != null }
        compose.runOnIdle {
            model.code("ABCDEF123456"); model.reviewCode()
            source.writeError = AuthSessionRequiredException(); model.confirm()
        }
        compose.waitUntil { model.state.value.requiresLogin }
        compose.runOnIdle {
            assertEquals("", model.state.value.code); assertNull(model.state.value.plan)
            assertNull(model.state.value.pending); assertTrue(model.state.value.offers.isEmpty())
            model.confirm(); model.load(); assertEquals(1, source.writes.size)
        }
    }
    @Test fun participantMustBeSelectedFromCurrentPlanAndReviewDoesNotSend() {
        val source = Source()
        val model = model(source)
        compose.runOnIdle { model.selectPlan(offer, planId) }
        compose.waitUntil { model.state.value.plan != null }
        compose.runOnIdle {
            model.reviewAttendance(OperatorMember("intruder", "Ajeno", false)); assertNull(model.state.value.pending)
            model.reviewAttendance(member); assertNotNull(model.state.value.pending); assertTrue(source.writes.isEmpty())
            model.dismissReview(); model.code("bad"); model.reviewCode()
            assertNull(model.state.value.pending); assertTrue(source.writes.isEmpty())
        }
    }
    @Test fun journalSurvivesRecreationIsAccountBoundAndStoresNoRawCode() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("operator_test_requests", mode)
        }
        try {
            val payload = listOf("redemption", "ABCDEF123456")
            val first = OperatorJournal(testContext, "fake-operator").requestId(payload)
            assertEquals(first, OperatorJournal(testContext, "fake-operator").requestId(payload))
            assertNotEquals(first, OperatorJournal(testContext, "another-fake-operator").requestId(payload))
            val saved = app.getSharedPreferences("operator_test_requests", Context.MODE_PRIVATE).all.toString()
            assertFalse(saved.contains("ABCDEF123456")); assertFalse(saved.contains("fake-operator"))
        } finally { app.deleteSharedPreferences("operator_test_requests") }
    }
    @Test fun narrowLargeFontScreenScrollsAndCapturesActualLayout() {
        var back = 0
        compose.setContent { PlanesTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                Box(Modifier.width(320.dp).fillMaxHeight()) {
                    OperatorScreen(OperatorUiState(loaded = true, offers = listOf(offer)), { back++ }, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {})
                }
            }
        } }
        compose.onNodeWithText("Ver encuentro 1").performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Asistencias").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        File(app.cacheDir, "operator-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.runOnIdle { assertEquals(1, back) }
    }
    @Test fun uncertainReviewDoesNotOfferFalseCancellationAndKeepsConfirmAccessible() {
        screen(OperatorUiState(loaded = true, redeemTab = true, pending = OperatorCommand(code = "ABCDEF123456", requestId = "request"),
            operationError = "Resultado sin confirmar."))
        compose.onNodeWithText("Cerrar revisión").assertIsDisplayed()
        compose.onNodeWithText("Volver sin enviar").assertDoesNotExist()
        compose.onNodeWithText("Reintentar misma solicitud").assertIsDisplayed()
        compose.onNodeWithText("Cerrar esta revisión no revierte una solicitud ya enviada.").assertExists()
    }
    @Test fun profileHasOneOperatorEntryAndPreservesPersonalBenefits() {
        var opens = 0
        compose.setContent { PlanesTheme {
            ProfileScreen("Ana", null, emptySet(), null, false, false, null,
                {}, {}, {}, {}, {}, {}, {}, {}, onBenefits = {}, onOperator = { opens++ })
        } }
        compose.onNodeWithTag("perfil-lista").performScrollToNode(hasText("Acceso de operadores"))
        compose.onAllNodesWithText("Acceso de operadores").assertCountEquals(1)
        compose.onNodeWithText("Acceso de operadores").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }
}
