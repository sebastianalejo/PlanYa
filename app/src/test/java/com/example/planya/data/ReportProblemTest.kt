package com.example.planya.data

import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.mapper.toReceipt
import com.example.planya.data.remote.api.ReportProblemApi
import com.example.planya.data.remote.dto.CreateReportDto
import com.example.planya.data.remote.dto.CreateReportResponseDto
import com.example.planya.data.remote.dto.ReportReceiptDto
import com.example.planya.data.repository.ReportProblemRepositoryImpl
import com.example.planya.data.repository.reportProblemFailure
import com.example.planya.domain.model.ReportConflictException
import com.example.planya.domain.model.ReportNetworkException
import com.example.planya.domain.model.ReportReason
import com.example.planya.domain.model.ReportRejectedException
import com.example.planya.domain.model.ReportStatus
import com.example.planya.domain.model.ReportTarget
import com.example.planya.domain.model.ReportTargetType
import com.example.planya.domain.model.reportDescriptionError
import com.example.planya.domain.model.reportTextLength
import com.example.planya.presentation.screens.report.ReportProblemUiState
import com.example.planya.presentation.screens.report.ReportRequestKeys
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** US-12: reglas del formulario, contrato con POST /reportes y manejo de errores. */
class ReportProblemTest {
    private val plan = "8b3c2f4e-1d2a-4c55-9d3e-5f6a7b8c9d0e"
    private val message = "1f2e3d4c-5b6a-4987-8a7b-6c5d4e3f2a1b"
    private val planTarget = ReportTarget(ReportTargetType.PLAN, plan, "Café por Miraflores")
    private val messageTarget = ReportTarget(ReportTargetType.MESSAGE, plan, "Café por Miraflores",
        messageId = "1800000000000#$message", messageAuthor = "Ana")

    private fun dto(type: String = "PLAN") = ReportReceiptDto(id = "0c9b8a7d-6e5f-4a3b-9c2d-1e0f9a8b7c6d", status = "RECIBIDO",
        createdAt = 1_800_000_000, targetType = type, planId = plan, planTitle = "Café por Miraflores", reason = "SPAM")

    @Test fun `description is counted like the server and validated between 10 and 500`() {
        assertEquals(10, reportTextLength("  Diez letra  "))
        assertEquals(10, reportTextLength("😀😀😀😀😀😀😀😀😀😀"))  // cada emoji cuenta 1, como en Python
        assertNotNull(reportDescriptionError(""))
        assertNotNull(reportDescriptionError("   corto   "))
        assertNull(reportDescriptionError("Diez letra"))
        assertNull(reportDescriptionError("x".repeat(500)))
        assertTrue(reportDescriptionError("x".repeat(501))!!.contains("500"))
    }

    @Test fun `form state requires a reason and a valid description`() {
        val empty = ReportProblemUiState(planTarget)
        assertFalse(empty.isValid)
        assertFalse(empty.hasDraft)
        assertEquals("Elige un motivo", empty.reasonError)
        val draft = empty.copy(description = "Algo pasó")
        assertTrue(draft.hasDraft)  // pedirá confirmar antes de descartar
        assertFalse(draft.isValid)
        val ready = draft.copy(reason = ReportReason.SAFETY, description = "Algo pasó en el encuentro")
        assertTrue(ready.isValid)
    }

    @Test fun `same content keeps the request id and new content gets another`() {
        var counter = 0
        val keys = ReportRequestKeys { "id-${++counter}" }
        val first = keys.idFor(ReportReason.SPAM, "Publicidad repetida")
        assertEquals(first, keys.idFor(ReportReason.SPAM, "  Publicidad repetida "))  // reintento: mismo reporte
        val changed = keys.idFor(ReportReason.OTHER, "Publicidad repetida")
        assertNotEquals(first, changed)
        keys.reset()
        assertNotEquals(changed, keys.idFor(ReportReason.OTHER, "Publicidad repetida"))
    }

    @Test fun `reasons and statuses match the backend names and Spanish labels`() {
        assertEquals(listOf("INAPPROPRIATE", "SPAM", "SAFETY", "OTHER"), ReportReason.entries.map { it.name })
        assertEquals(listOf("Contenido inapropiado", "Spam o publicidad", "Riesgo de seguridad", "Otro motivo"),
            ReportReason.entries.map { it.label })
        assertEquals(listOf("RECIBIDO", "EN_REVISION", "RESUELTO", "DESCARTADO"), ReportStatus.entries.map { it.name })
    }

    @Test fun `mapper rejects incomplete or unknown values instead of inventing data`() {
        val receipt = dto().toReceipt()!!
        assertEquals(ReportStatus.RECIBIDO, receipt.status)
        assertEquals("0C9B", receipt.shortCode)  // mismo código corto que «Mis reportes»
        assertNull(dto().copy(status = "ABIERTO").toReceipt())
        assertNull(dto().copy(reason = null).toReceipt())
        assertNull(dto().copy(reason = "OFENSA").toReceipt())
        assertNull(dto().copy(id = " ").toReceipt())
        assertNull(dto().copy(createdAt = null).toReceipt())
    }

    @Test fun `server errors become clear exceptions`() {
        assertTrue(reportProblemFailure(401, null) is AuthSessionRequiredException)
        assertEquals("Elige un motivo", reportProblemFailure(400, """{"error":"Elige un motivo","code":"INVALID"}""").message)
        assertTrue(reportProblemFailure(400, null) is IllegalArgumentException)
        assertTrue(reportProblemFailure(409, """{"code":"REQUEST_CONFLICT"}""") is ReportConflictException)
        assertEquals("NOT_MEMBER", (reportProblemFailure(403, """{"error":"No","code":"NOT_MEMBER"}""") as ReportRejectedException).code)
        assertTrue(reportProblemFailure(410, """{"code":"CHAT_CLOSED"}""") is ReportRejectedException)
        // 404 de API Gateway sin código: la ruta no está desplegada.
        val undeployed = reportProblemFailure(404, """{"message":"Not Found"}""")
        assertFalse(undeployed is ReportRejectedException)
        assertTrue(undeployed.message!!.contains("POST /reportes"))
        assertTrue(reportProblemFailure(502, "<html>").message!!.contains("laboratorio"))
    }

    private class FakeApi(private val answer: suspend (CreateReportDto) -> CreateReportResponseDto) : ReportProblemApi {
        var lastToken: String? = null
        var lastBody: CreateReportDto? = null
        override suspend fun create(token: String, body: CreateReportDto): CreateReportResponseDto {
            lastToken = token; lastBody = body
            return answer(body)
        }
    }

    @Test fun `message report sends only the reference and confirms with the server receipt`() = runBlocking {
        val api = FakeApi { CreateReportResponseDto(success = true, data = dto("MESSAGE")) }
        val report = ReportProblemRepositoryImpl(api, "u1") { "token-1" }
            .create(messageTarget, ReportReason.SPAM, "Publicidad repetida en el plan", "req-1")
        assertEquals("Bearer token-1", api.lastToken)
        val body = api.lastBody!!
        assertEquals(listOf("req-1", "MESSAGE", plan, "SPAM", "1800000000000#$message"),
            listOf(body.requestId, body.targetType, body.planId, body.reason, body.messageId))
        assertEquals(ReportTargetType.MESSAGE, report.targetType)
    }

    @Test fun `plan report never sends message fields`() = runBlocking {
        val api = FakeApi { CreateReportResponseDto(success = true, data = dto()) }
        ReportProblemRepositoryImpl(api, "u1") { "t" }.create(planTarget.copy(messageId = "1800000000000#$message"),
            ReportReason.SPAM, "Publicidad repetida en el plan", "req-2")
        assertNull(api.lastBody!!.messageId)
    }

    @Test fun `no receipt means no success`() = runBlocking {
        val repository = ReportProblemRepositoryImpl(FakeApi { CreateReportResponseDto(success = true, data = null) }, "u1") { "t" }
        val failure = runCatching { repository.create(planTarget, ReportReason.OTHER, "Descripción válida", "r") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        val mismatch = ReportProblemRepositoryImpl(FakeApi { CreateReportResponseDto(success = true, data = dto().copy(planId = "otro")) }, "u1") { "t" }
        assertTrue(runCatching { mismatch.create(planTarget, ReportReason.OTHER, "Descripción válida", "r") }.isFailure)
    }

    @Test fun `network failure is uncertain and http errors keep the server message`() = runBlocking {
        val offline = ReportProblemRepositoryImpl(FakeApi { throw IOException("sin red") }, "u1") { "t" }
        val uncertain = runCatching { offline.create(planTarget, ReportReason.OTHER, "Descripción válida", "r") }.exceptionOrNull()
        assertTrue(uncertain is ReportNetworkException)
        val gone = ReportProblemRepositoryImpl(FakeApi {
            throw HttpException(Response.error<CreateReportResponseDto>(410,
                """{"success":false,"error":"El chat de este plan ya cerró.","code":"CHAT_CLOSED"}""".toResponseBody()))
        }, "u1") { "t" }
        val rejected = runCatching { gone.create(messageTarget, ReportReason.SPAM, "Descripción válida", "r") }.exceptionOrNull()
        assertTrue(rejected is ReportRejectedException)
        assertEquals("El chat de este plan ya cerró.", rejected!!.message)
    }
}
