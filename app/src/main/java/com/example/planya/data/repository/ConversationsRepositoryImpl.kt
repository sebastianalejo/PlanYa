package com.example.planya.data.repository

import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.mapper.toDomain
import com.example.planya.data.remote.api.ChatApi
import com.example.planya.domain.model.ConversationsCursorExpiredException
import com.example.planya.domain.model.ConversationsPage
import com.example.planya.domain.repository.ConversationsRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

class ConversationsRepositoryImpl(private val api: ChatApi, private val userId: String,
    private val token: suspend () -> String) : ConversationsRepository {
    override suspend fun page(nextKey: String?): ConversationsPage = try {
        val response = api.conversations("Bearer ${token()}", nextKey)
        val data = response.data
        check(response.success == true && data != null) { "No se pudo cargar la lista de conversaciones." }
        if (data.userId != userId) throw AuthSessionRequiredException()
        ConversationsPage(data.items.orEmpty().mapNotNull { it.toDomain() }, data.nextKey,
            checkNotNull(data.serverTime) { "No se pudo comprobar la hora de cierre." })
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: HttpException) {
        val body = runCatching { failure.response()?.errorBody()?.string() }.getOrNull()
        val code = runCatching { Gson().fromJson(body, JsonObject::class.java)?.get("code")?.asString }.getOrNull()
        when {
            failure.code() == 409 && code == "CURSOR_EXPIRED" -> throw ConversationsCursorExpiredException()
            failure.code() == 400 && nextKey != null -> throw ConversationsCursorExpiredException()
            failure.code() == 404 -> error("La lista de conversaciones todavía no está disponible. Intenta más tarde.")
            else -> throw chatFailure(failure.code(), body)
        }
    } catch (_: IOException) { throw ChatNetworkException("No se pudo conectar. Revisa tu conexión e intenta de nuevo.") }
}
