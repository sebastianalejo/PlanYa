package com.example.planya.data.profile

import com.example.planya.data.auth.AuthSessionRequiredException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import retrofit2.http.*
import java.io.IOException

data class RatingCandidate(val planId: String, val targetUserId: String, val planTitle: String, val name: String)
data class RatingsCatalog(val userId: String, val items: List<RatingCandidate>)
data class RatingBody(val planId: String, val targetUserId: String, val score: Int)
data class RatingReceipt(val saved: Boolean, val alreadySaved: Boolean)

interface RatingsApi {
    @GET("valoraciones") suspend fun pending(@Header("Authorization") token: String): ApiResponse<RatingsCatalog>
    @POST("valoraciones") suspend fun rate(@Header("Authorization") token: String, @Body body: RatingBody): ApiResponse<RatingReceipt>
}

interface RatingsGateway {
    suspend fun pending(): List<RatingCandidate>
    suspend fun rate(candidate: RatingCandidate, score: Int)
}

class RatingsRepository(private val api: RatingsApi, private val userId: String,
    private val token: suspend () -> String) : RatingsGateway {
    override suspend fun pending(): List<RatingCandidate> = request {
        val response = api.pending("Bearer ${token()}")
        check(response.success && response.data != null) { "El servidor no devolvió las valoraciones pendientes." }
        if (response.data.userId != userId) throw AuthSessionRequiredException()
        check(response.data.items.all { it.targetUserId != userId && it.planId.isNotBlank() && it.targetUserId.isNotBlank() }) {
            "La lista de participantes no es válida."
        }
        response.data.items.distinctBy { it.planId to it.targetUserId }
    }
    override suspend fun rate(candidate: RatingCandidate, score: Int) {
        require(score in 1..5 && candidate.targetUserId != userId) { "Elige otra persona y una nota de 1 a 5." }
        request {
            val response = api.rate("Bearer ${token()}", RatingBody(candidate.planId, candidate.targetUserId, score))
            check(response.success && response.data?.saved == true) { "No se confirmó el guardado. Reintenta la misma nota." }
        }
    }
    private suspend fun <T> request(block: suspend () -> T): T {
        try { return block() }
        catch (failure: CancellationException) { throw failure }
        catch (failure: HttpException) {
            if (failure.code() == 401) throw AuthSessionRequiredException()
            throw IllegalStateException(when (failure.code()) {
                403 -> "Ambas personas deben tener asistencia acreditada. No puedes valorarte a ti mismo."
                409 -> "El encuentro no terminó, ya valoraste a esta persona o cambiaron sus datos. Actualiza la lista."
                404 -> "Valoraciones todavía no está habilitado en el servidor."
                else -> "No pudimos confirmar la operación. Si estabas guardando, reintenta la misma nota."
            })
        } catch (_: IOException) {
            throw IllegalStateException("Sin conexión. Si estabas guardando, reintenta la misma nota; no se duplicará.")
        }
    }
}
