package com.example.planya.data

import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.mapper.toDetail
import com.example.planya.data.mapper.toSummary
import com.example.planya.data.remote.api.ReportsApi
import com.example.planya.data.remote.dto.*
import com.example.planya.data.repository.ReportsNetworkException
import com.example.planya.data.repository.ReportsRepositoryImpl
import com.example.planya.domain.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

// US-13 Mis reportes: datos aislados, sin AWS ni cuentas reales.
class ReportsTest {
    private val id = "a7f21c3e-1d2a-4c55-9d3e-5f6a7b8c9d0e"
    private val dto = ReportDto(id, "A7F2", "PLAN", "Café por Miraflores", "INAPPROPRIATE", "EN_REVISION",
        1_795_000_000, 1_795_000_100, false, "Solicito revisar el contenido de este encuentro.", null, true)

    private class Api : ReportsApi {
        var page = ReportsResponseDto()
        var detail = ReportDetailResponseDto()
        var failure: Exception? = null
        var header: String? = null
        var filter: String? = null
        var cursor: String? = null
        var detailId: String? = null
        override suspend fun reports(token: String, filter: String, nextKey: String?): ReportsResponseDto {
            header = token; this.filter = filter; cursor = nextKey; failure?.let { throw it }; return page
        }
        override suspend fun report(token: String, id: String): ReportDetailResponseDto {
            header = token; detailId = id; failure?.let { throw it }; return detail
        }
    }

    private fun http(status: Int, body: String) =
        HttpException(Response.error<Any>(status, body.toResponseBody(null)))

    private fun repo(api: Api) = ReportsRepositoryImpl(api, "ana") { "token-test" }

    @Test fun mapperKeepsServerStateAndDropsMalformedRows() {
        val item = dto.toSummary()!!
        assertEquals(id, item.id)
        assertEquals("A7F2", item.shortCode)
        assertEquals(ReportStatus.EN_REVISION, item.status)
        assertEquals(ReportReason.INAPPROPRIATE, item.reason)
        assertTrue(item.status.isOpen)
        assertNull(dto.copy(id = "no-uuid").toSummary())
        assertNull(dto.copy(status = "BORRADO").toSummary())
        assertNull(dto.copy(targetType = "USER").toSummary())
        assertNull(dto.copy(createdAt = null).toSummary())
        // Motivo desconocido no se inventa: se muestra como "Otro motivo".
        assertEquals(ReportReason.OTHER, dto.copy(reason = "NUEVO").toSummary()!!.reason)
        // Código abreviado inválido: se deriva del id completo.
        assertEquals("A7F2", dto.copy(shortCode = "<script>").toSummary()!!.shortCode)
    }

    @Test fun responseIsShownOnlyForClosedReports() {
        val open = dto.copy(publicResponse = "No debería verse todavía").toDetail()!!
        assertNull(open.publicResponse)
        assertFalse(open.summary.hasResponse)
        val closed = dto.copy(status = "RESUELTO", hasResponse = true, publicResponse = "  Revisamos el contenido.  ").toDetail()!!
        assertEquals("Revisamos el contenido.", closed.publicResponse)
        assertTrue(closed.summary.hasResponse)
        assertFalse(dto.copy(targetAvailable = null).toDetail()!!.targetAvailable)
    }

    @Test fun filtersGroupStates() {
        assertEquals(listOf(ReportStatus.RECIBIDO, ReportStatus.EN_REVISION), ReportStatus.entries.filter { ReportFilter.ABIERTOS.accepts(it) })
        assertEquals(listOf(ReportStatus.RESUELTO, ReportStatus.DESCARTADO), ReportStatus.entries.filter { ReportFilter.CERRADOS.accepts(it) })
        assertTrue(ReportStatus.entries.all { ReportFilter.TODOS.accepts(it) })
    }

    @Test fun mergeKeepsNewestFirstWithoutDuplicates() {
        val older = dto.toSummary()!!.copy(id = "00000000-0000-4000-8000-000000000001", createdAt = 10)
        val newer = dto.toSummary()!!.copy(id = "00000000-0000-4000-8000-000000000002", createdAt = 20)
        val updated = older.copy(status = ReportStatus.RESUELTO)
        val merged = mergeReports(listOf(newer, older), listOf(updated))
        assertEquals(listOf(newer.id, older.id), merged.map { it.id })
        assertEquals(ReportStatus.RESUELTO, merged.last().status)
    }

    @Test fun repositoryUsesSessionFilterAndOpaqueCursor() = runBlocking<Unit> {
        val api = Api().apply { page = ReportsResponseDto(true, ReportsPageDto("ana", "ABIERTOS", listOf(dto,
            dto.copy(id = "b8c31c3e-1d2a-4c55-9d3e-5f6a7b8c9d0e", status = "RESUELTO")), "cursor-2")) }
        val page = repo(api).page(ReportFilter.ABIERTOS, "cursor-1")
        assertEquals("Bearer token-test", api.header)
        assertEquals("ABIERTOS", api.filter)
        assertEquals("cursor-1", api.cursor)
        // Un estado cerrado que llegue con el filtro Abiertos no se muestra.
        assertEquals(listOf(id), page.items.map { it.id })
        assertEquals("cursor-2", page.nextKey)
    }

    @Test fun otherAccountOrWrongFilterIsRejected() = runBlocking<Unit> {
        val api = Api().apply { page = ReportsResponseDto(true, ReportsPageDto("beto", "TODOS", listOf(dto), null)) }
        assertThrows(AuthSessionRequiredException::class.java) { runBlocking { repo(api).page(ReportFilter.TODOS) } }
        api.page = ReportsResponseDto(true, ReportsPageDto("ana", "CERRADOS", listOf(dto), null))
        assertThrows(IllegalStateException::class.java) { runBlocking { repo(api).page(ReportFilter.ABIERTOS) } }
        api.detail = ReportDetailResponseDto(true, ReportDetailDataDto("ana", dto.copy(id = "b8c31c3e-1d2a-4c55-9d3e-5f6a7b8c9d0e")))
        assertThrows(IllegalStateException::class.java) { runBlocking { repo(api).detail(id) } }
    }

    @Test fun httpAndNetworkFailuresAreNotEmptyLists() = runBlocking<Unit> {
        val api = Api()
        api.failure = http(401, "{}")
        assertThrows(AuthSessionRequiredException::class.java) { runBlocking { repo(api).page(ReportFilter.TODOS) } }
        api.failure = http(409, """{"success":false,"code":"CURSOR_EXPIRED"}""")
        assertThrows(ReportsCursorExpiredException::class.java) { runBlocking { repo(api).page(ReportFilter.TODOS, "old") } }
        api.failure = http(404, """{"success":false,"code":"REPORT_NOT_FOUND"}""")
        assertThrows(ReportNotFoundException::class.java) { runBlocking { repo(api).detail(id) } }
        api.failure = http(503, """{"success":false,"code":"REPORTS_INDEX_UNAVAILABLE"}""")
        val index = assertThrows(IllegalStateException::class.java) { runBlocking { repo(api).page(ReportFilter.TODOS) } }
        assertTrue(index.message!!.contains("configurado"))
        api.failure = IOException("sin red")
        assertThrows(ReportsNetworkException::class.java) { runBlocking { repo(api).page(ReportFilter.TODOS) } }
    }

    @Test fun detailReturnsSameReport() = runBlocking<Unit> {
        val api = Api().apply { detail = ReportDetailResponseDto(true, ReportDetailDataDto("ana", dto.copy(targetAvailable = false))) }
        val detail = repo(api).detail(id)
        assertEquals(id, api.detailId)
        assertEquals(id, detail.summary.id)
        assertFalse(detail.targetAvailable)
        assertEquals("Solicito revisar el contenido de este encuentro.", detail.description)
    }
}
