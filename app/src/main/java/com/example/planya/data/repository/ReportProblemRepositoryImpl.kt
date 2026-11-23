package com.example.planya.data.repository

import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.mapper.toReceipt
import com.example.planya.data.remote.api.ReportProblemApi
import com.example.planya.data.remote.dto.CreateReportDto
import com.example.planya.domain.model.ReportConflictException
import com.example.planya.domain.model.ReportNetworkException
import com.example.planya.domain.model.ReportReason
import com.example.planya.domain.model.ReportReceipt
import com.example.planya.domain.model.ReportRejectedException
import com.example.planya.domain.model.ReportTarget
import com.example.planya.domain.model.ReportTargetType
import com.example.planya.domain.repository.ReportProblemRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import retrofit2.HttpException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Convierte una respuesta de error de POST /reportes en una excepción con un mensaje claro. */
fun reportProblemFailure(status: Int, body: String?): Exception {
    val json = runCatching { Gson().fromJson(body, JsonObject::class.java) }.getOrNull()
    val server = runCatching { json?.get("error")?.asString }.getOrNull()
    val code = runCatching { json?.get("code")?.asString }.getOrNull()
    return when {
        status == 401 -> AuthSessionRequiredException()
        status == 400 -> IllegalArgumentException(server ?: "Revisa el motivo y la descripción.")
        status == 409 -> ReportConflictException(server ?: "Ese envío ya se usó con otro contenido. Vuelve a enviar.")
        status in listOf(403, 404, 410) && code != null -> ReportRejectedException(code, server ?: "Ese contenido ya no se puede reportar.")
        // 404 de API Gateway sin código: la ruta todavía no está desplegada.
        status == 404 -> IllegalStateException("Los reportes todavía no están disponibles en AWS. Comprueba que la ruta POST /reportes esté desplegada.")
        status in 500..599 -> IllegalStateException("AWS devolvió un error (HTTP $status). Comprueba que el laboratorio esté activo y vuelve a intentarlo.")
        else -> IllegalStateException(server ?: "No se pudo enviar el reporte (HTTP $status).")
    }
}

/**
 * @param userId cuenta dueña de la pantalla.
 * @param token devuelve el ID token de la sesión actual; debe fallar si cambió el usuario.
 */
class ReportProblemRepositoryImpl(
    private val api: ReportProblemApi,
    private val userId: String,
    private val token: suspend () -> String
) : ReportProblemRepository {
    override suspend fun create(target: ReportTarget, reason: ReportReason, description: String, requestId: String): ReportReceipt {
        val body = CreateReportDto(
            requestId = requestId,
            targetType = target.type.name,
            planId = target.planId,
            reason = reason.name,
            description = description,
            messageId = target.messageId.takeIf { target.type == ReportTargetType.MESSAGE }
        )
        val response = try {
            api.create("Bearer ${token()}", body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: HttpException) {
            throw reportProblemFailure(failure.code(), runCatching { failure.response()?.errorBody()?.string() }.getOrNull())
        } catch (_: IOException) {
            throw ReportNetworkException("No hubo respuesta del servidor. Tu borrador se conserva; reintenta y, si ya se había guardado, recuperaremos el mismo reporte.")
        }
        val receipt = response.data?.toReceipt()
        // Solo se confirma con un recibo verificable del servidor.
        check(response.success == true && receipt != null) { response.error ?: "El servidor no confirmó el reporte." }
        check(receipt.planId == target.planId && receipt.targetType == target.type) { "La confirmación no corresponde a este contenido." }
        return receipt
    }
}
