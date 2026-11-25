package com.example.planya

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.domain.model.*
import com.example.planya.domain.repository.ReportsRepository
import com.example.planya.presentation.screens.report.*
import com.example.planya.presentation.screens.reports.*
import com.example.planya.presentation.screens.operator.*
import com.example.planya.ui.theme.PlanesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Review-only tests, with isolated data and no AWS writes. */
class ReviewNewScreensTest {
    @get:Rule val compose = createComposeRule()
    private val target = ReportTarget(ReportTargetType.PLAN, "review-plan", "Encuentro de prueba")
    @Test fun embeddedBarcodeModelDecodesTheRealCouponQrFormat() {
        val code = "A1B2C3D4E5F6"
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(code,
            com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        for (y in 0 until 512) for (x in 0 until 512) bitmap.setPixel(x, y,
            if (matrix[x,y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        val scanner = com.google.mlkit.vision.barcode.BarcodeScanning.getClient()
        try {
            val codes = com.google.android.gms.tasks.Tasks.await(scanner.process(
                com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)), 15,
                java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(code, codes.first().rawValue)
            assertEquals(code, parseOperatorCode(codes.first().rawValue))
        } finally { scanner.close(); bitmap.recycle() }
    }
    @Test fun reportFormKeepsDraftAndShowsRetryAfterAnUncertainSend() {
        var sends = 0
        val state = ReportProblemUiState(target, reason = ReportReason.SPAM,
            description = "Descripción de prueba para revisar el formulario.", uncertain = true, error = "Sin respuesta del servidor")
        compose.setContent { PlanesTheme {
            ReportProblemScreen(state, onReason = {}, onDescription = {}, onSend = { sends++ }, onClose = {})
        } }
        compose.onNodeWithText("Reintentar envío").assertIsDisplayed().performClick()
        assertEquals(1, sends)
        compose.onNodeWithText(state.description).assertExists()
    }
    @Test fun reportSuccessRequiresTheReceiptAndShowsItsIdentifier() {
        val receipt = ReportReceipt("11111111-1111-4111-8111-111111111111", ReportStatus.RECIBIDO,
            1795262400, ReportTargetType.PLAN, target.planId, target.planTitle, ReportReason.SPAM)
        var opens = 0
        compose.setContent { PlanesTheme {
            ReportProblemScreen(ReportProblemUiState(target, receipt = receipt), {}, {}, {}, {},
                onViewReport = { opens++ })
        } }
        compose.onNodeWithText("Reporte enviado").assertIsDisplayed()
        compose.onNodeWithText("#1111").assertExists()
        compose.onNodeWithText("Ver mi reporte").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }
    @Test fun scannerReceiptShowsConfirmedStateAndRescanAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
        var rescans = 0
        compose.setContent { PlanesTheme {
            OperatorScanScreen(OperatorUiState(receipt = OperatorReceipt("request-review", "benefit-review")),
                onBack = {}, onManual = {}, onScanned = {}, onConfirm = {}, onRescan = { rescans++ })
        } }
        compose.onNodeWithText("Cupón USADO").assertIsDisplayed()
        compose.onNodeWithText("Escanear otro cupón").performClick()
        assertEquals(1, rescans)
    }
    @Test fun openedReportThatWasClosedByModeratorMustLeaveTheOpenFilter() {
        val item = ReportSummary("11111111-1111-4111-8111-111111111111", "1111", ReportTargetType.PLAN,
            "Encuentro de prueba", ReportReason.SPAM, ReportStatus.RECIBIDO, 1795262400, 1795262400, false)
        val repository = object : ReportsRepository {
            override suspend fun page(filter: ReportFilter, nextKey: String?) = ReportsPage(listOf(item), null)
            override suspend fun detail(id: String) = ReportDetail(item.copy(status = ReportStatus.RESUELTO,
                hasResponse = true), "Descripción de prueba", "Respuesta de prueba de moderación", true)
        }
        val store = ViewModelStore()
        lateinit var model: MyReportsViewModel
        compose.runOnIdle { model = MyReportsViewModel(repository); store.put("audit", model) }
        try {
            compose.setContent { PlanesTheme {
                val state by model.state.collectAsState()
                MyReportsScreen(state, {}, model::refresh, model::retry, model::selectFilter,
                    model::toggle, model::retryDetail, model::loadMore)
            } }
            compose.runOnIdle { model.selectFilter(ReportFilter.ABIERTOS) }
            compose.waitUntil(5000) { model.state.value.loaded && !model.state.value.busy }
            compose.runOnIdle { model.toggle(item.id) }
            compose.waitUntil(5000) { model.state.value.details[item.id]?.loading != true }
            assertTrue("Un reporte resuelto no pertenece al filtro Abiertos", model.state.value.items.isEmpty())
        } finally { compose.runOnIdle { store.clear() } }
    }
}
