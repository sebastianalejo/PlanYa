package com.example.planya.data.repository

import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.mapper.toDetail
import com.example.planya.data.mapper.toSummary
import com.example.planya.data.remote.api.ReportsApi
import com.example.planya.domain.model.*
import com.example.planya.domain.repository.ReportsRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

class ReportsNetworkException(message: String) : IllegalStateException(message)

/**
 * US-13. Cada repositorio pertenece a una sola cuenta: si el servidor responde con otro userId
 * se trata como sesión cambiada y no se muestran datos.
 */
class ReportsRepositoryImpl(private val api: ReportsApi, private val userId: String,
    private val token: suspend () -> String) : ReportsRepository {

    override suspend fun page(filter: ReportFilter, nextKey: String?): ReportsPage = request(cursor = nextKey) {
        val response = api.reports("Bearer ${token()}", filter.query, nextKey)
        val data = response.data
        check(response.success == true && data != null) { "No se pudo cargar tu lista de reportes." }
        if (data.userId != userId) throw AuthSessionRequiredException()
        check(data.filter == null || data.filter == filter.query) { "La respuesta no corresponde al filtro elegido." }
        // Defensa extra: un estado incompatible con el filtro no se muestra.
        ReportsPage(data.items.orEmpty().mapNotNull { it.toSummary() }.filter { filter.accepts(it.status) }, data.nextKey)
    }

    override suspend fun detail(id: String): ReportDetail = request(detail = true) {
        val response = api.report("Bearer ${token()}", id)
        val data = response.data
        check(response.success == true && data != null) { "No se pudo cargar el detalle del reporte." }
        if (data.userId != userId) throw AuthSessionRequiredException()
        val detail = data.report?.toDetail() ?: error("El detalle del reporte no es válido.")
        check(detail.summary.id == id) { "El detalle recibido no corresponde a este reporte." }
        detail
    }

    private suspend fun <T> request(cursor: String? = null, detail: Boolean = false, block: suspend () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: HttpException) {
            val body = runCatching { failure.response()?.errorBody()?.string() }.getOrNull()
            val code = runCatching { Gson().fromJson(body, JsonObject::class.java)?.get("code")?.asString }.getOrNull()
            when {
                failure.code() == 401 -> throw AuthSessionRequiredException()
                failure.code() == 409 && code == "CURSOR_EXPIRED" -> throw ReportsCursorExpiredException()
                failure.code() == 400 && cursor != null -> throw ReportsCursorExpiredException()
                detail && failure.code() == 404 && code == "REPORT_NOT_FOUND" -> throw ReportNotFoundException()
                failure.code() == 404 -> error("Mis reportes todavía no está disponible en el servidor. Intenta más tarde.")
                code == "REPORTS_INDEX_UNAVAILABLE" -> error("Mis reportes todavía no está configurado en el servidor.")
                else -> error("No pudimos consultar tus reportes. Reintenta en un momento.")
            }
        } catch (_: IOException) {
            throw ReportsNetworkException("No se pudo conectar. Revisa tu conexión e intenta de nuevo.")
        }
}
