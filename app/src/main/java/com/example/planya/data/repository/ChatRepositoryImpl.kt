package com.example.planya.data.repository

import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.mapper.toDomain
import com.example.planya.data.remote.api.ChatApi
import com.example.planya.data.remote.dto.SendChatMessageDto
import com.example.planya.domain.model.ChatBlockReason
import com.example.planya.domain.model.ChatBlockedException
import com.example.planya.domain.model.ChatMessage
import com.example.planya.domain.model.ChatPage
import com.example.planya.domain.repository.ChatRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import retrofit2.HttpException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Sin conexión o sin respuesta: no se sabe si el servidor recibió la operación. */
class ChatNetworkException(message: String) : IllegalStateException(message)

/**
 * Convierte una respuesta de error de PlanYaApi en una excepción con mensaje claro.
 * Sin "code" en un 404, la ruta probablemente no está desplegada en API Gateway.
 */
fun chatFailure(status: Int, body: String?): Exception {
    val json = runCatching { Gson().fromJson(body, JsonObject::class.java) }.getOrNull()
    val server = runCatching { json?.get("error")?.asString }.getOrNull()
    val code = runCatching { json?.get("code")?.asString }.getOrNull()
    val reason = when (code) {
        "NOT_MEMBER" -> ChatBlockReason.NOT_MEMBER
        "PLAN_CANCELLED" -> ChatBlockReason.PLAN_CANCELLED
        "CHAT_CLOSED" -> ChatBlockReason.CHAT_CLOSED
        "NO_SCHEDULE" -> ChatBlockReason.NO_SCHEDULE
        "PLAN_NOT_FOUND" -> ChatBlockReason.PLAN_NOT_FOUND
        else -> when (status) {
            403 -> ChatBlockReason.NOT_MEMBER
            410 -> ChatBlockReason.CHAT_CLOSED
            else -> null
        }
    }
    return when {
        status == 401 -> AuthSessionRequiredException()
        reason != null -> ChatBlockedException(reason, server ?: "Este chat no está disponible.")
        status == 404 -> IllegalStateException(
            "El chat todavía no está disponible en AWS. Comprueba que las rutas de mensajes estén desplegadas.")
        status == 400 -> IllegalArgumentException(server ?: "El mensaje no es válido.")
        status == 409 -> IllegalStateException(server ?: "No se pudo confirmar el envío. Actualiza el chat.")
        status in 500..599 -> IllegalStateException(
            "AWS devolvió un error (HTTP $status). Comprueba que el laboratorio esté activo.")
        else -> IllegalStateException(server ?: "No se pudo completar la operación (HTTP $status).")
    }
}

/**
 * @param token devuelve el ID token de la sesión actual; debe fallar si cambió el usuario.
 * @param userId cuenta dueña de esta pantalla: un mensaje propio confirmado debe ser suyo.
 */
class ChatRepositoryImpl(
    private val api: ChatApi,
    private val userId: String,
    private val token: suspend () -> String
) : ChatRepository {
    override suspend fun latest(planId: String) = page(planId) { api.messages(bearer(), planId, null, null) }
    override suspend fun older(planId: String, cursor: String) = page(planId) { api.messages(bearer(), planId, cursor, null) }
    override suspend fun newer(planId: String, cursor: String) = page(planId) { api.messages(bearer(), planId, null, cursor) }

    override suspend fun send(planId: String, text: String, clientMessageId: String): ChatMessage = call {
        val response = api.send(bearer(), planId, SendChatMessageDto(text, clientMessageId))
        val message = response.data?.toDomain()
        check(response.success == true && message != null) { "El servidor no confirmó el mensaje." }
        check(message.senderId == userId && message.planId == planId) { "La confirmación no corresponde a tu sesión." }
        check(message.clientMessageId == clientMessageId && message.text == bodyText(text)) {
            "La confirmación no corresponde al envío. Actualiza el chat antes de reintentar."
        }
        message
    }

    private suspend fun bearer() = "Bearer ${token()}"

    private fun bodyText(text: String) = text.replace("\r\n", "\n").replace("\r", "\n").trim()

    private suspend fun page(planId: String, block: suspend () -> com.example.planya.data.remote.dto.ChatPageResponseDto): ChatPage = call {
        val response = block()
        val data = response.data
        check(response.success == true && data != null) { response.error ?: "No se pudieron cargar los mensajes." }
        check(data.items.orEmpty().all { it.planId == planId }) { "La respuesta no corresponde a este chat." }
        data.toDomain()
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: HttpException) {
        throw chatFailure(failure.code(), runCatching { failure.response()?.errorBody()?.string() }.getOrNull())
    } catch (_: IOException) {
        throw ChatNetworkException("Sin respuesta del servidor. Revisa tu conexión y que el laboratorio de AWS esté activo.")
    }
}
