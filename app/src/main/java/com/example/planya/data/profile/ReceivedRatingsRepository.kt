package com.example.planya.data.profile

import com.example.planya.data.auth.AuthSessionRequiredException
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.io.IOException

data class ReceivedRating(
    val planId: String = "",
    val planTitle: String = "",
    val score: Int = 0,
    val createdAt: Long = 0,
    val planAvailable: Boolean = true,
    val id: String? = null
)

data class ReceivedRatingsPage(
    val userId: String = "",
    val count: Int = 0,
    val average: Double? = null,
    val items: List<ReceivedRating> = emptyList(),
    val nextKey: String? = null
)

interface ReceivedRatingsApi {
    @GET("perfil/valoraciones-recibidas")
    suspend fun received(@Header("Authorization") token: String,
        @Query("nextKey") nextKey: String? = null): ApiResponse<ReceivedRatingsPage>
}

interface ReceivedRatingsGateway {
    suspend fun received(nextKey: String? = null): ReceivedRatingsPage
}

class ReceivedRatingsCursorException(message: String, val expired: Boolean) : IllegalStateException(message)

class ReceivedRatingsRepository(private val api: ReceivedRatingsApi, private val userId: String,
    private val token: suspend () -> String) : ReceivedRatingsGateway {
    override suspend fun received(nextKey: String?): ReceivedRatingsPage {
        try {
            val response = api.received("Bearer ${token()}", nextKey)
            val page = response.data
            check(response.success && page != null) { "El servidor no devolvió tus opiniones recibidas." }
            if (page.userId != userId) throw AuthSessionRequiredException()
            validate(page)
            return page
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: HttpException) {
            val message = errorMessage(failure)
            when (failure.code()) {
                401 -> throw AuthSessionRequiredException()
                400, 409 -> throw ReceivedRatingsCursorException(
                    message ?: "La lista cambió. Actualiza para continuar.", failure.code() == 409)
                404 -> throw IllegalStateException(
                    "Opiniones recibidas todavía no está habilitado en el servidor.")
                else -> throw IllegalStateException(message
                    ?: "No pudimos cargar tus opiniones. Revisa tu conexión e intenta de nuevo.")
            }
        } catch (_: IOException) {
            throw IllegalStateException("Sin conexión. Revisa tu red e intenta de nuevo.")
        }
    }

    private fun validate(page: ReceivedRatingsPage) {
        check(page.count >= 0) { "El resumen de opiniones no es válido." }
        check(page.average == null || (page.average.isFinite() && page.average in 1.0..5.0)) {
            "El promedio de opiniones no es válido."
        }
        check((page.count == 0) == (page.average == null)) { "El resumen de opiniones no es válido." }
        check(page.items.all {
            it.planId.isNotBlank() && it.planTitle.isNotBlank() && it.score in 1..5 && it.createdAt > 0
        }) { "Una de las opiniones recibidas no es válida." }
        check(page.nextKey == null || page.nextKey.isNotBlank()) { "La continuación no es válida." }
    }

    private fun errorMessage(failure: HttpException): String? = runCatching {
        Gson().fromJson(failure.response()?.errorBody()?.string(), JsonObject::class.java)
            ?.get("error")?.asString
    }.getOrNull()
}
