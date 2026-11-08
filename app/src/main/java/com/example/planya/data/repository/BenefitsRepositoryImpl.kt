package com.example.planya.data.repository

import com.example.planya.data.auth.AuthRepository
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.mapper.toDomain
import com.example.planya.data.remote.api.BenefitsApi
import com.example.planya.data.remote.dto.BenefitResponseDto
import com.example.planya.domain.model.Benefit
import com.example.planya.domain.model.BenefitCode
import com.example.planya.domain.model.BenefitPage
import com.example.planya.domain.model.BenefitStatus
import com.example.planya.domain.repository.BenefitsRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

fun benefitMessage(status: Int, serverMessage: String?): String = serverMessage ?: when (status) {
    403 -> "No tienes acceso a este beneficio."
    404 -> "Los beneficios todavía no están habilitados en el servidor o el cupón ya no existe."
    409 -> "Este beneficio ya fue utilizado."
    410 -> "Este beneficio o su código ya venció."
    in 500..599 -> "El servidor devolvió un error (HTTP $status). Intenta de nuevo en unos minutos."
    else -> "No pudimos consultar tus beneficios (HTTP $status). Intenta de nuevo."
}

/** Las lecturas siempre usan el token de la sesión esperada; el servidor decide qué cupones pertenecen al usuario. */
class BenefitsRepositoryImpl(private val api: BenefitsApi, private val auth: AuthRepository,
    private val userId: String) : BenefitsRepository {
    override suspend fun getBenefits(status: BenefitStatus, nextKey: String?): BenefitPage = request { token ->
        val data = api.list(token, status.name, nextKey).payload()
        BenefitPage(data.items.orEmpty().map { it.toDomain(userId) }, data.nextKey?.takeIf { it.isNotBlank() })
    }
    override suspend fun getBenefit(id: String): Benefit = request { token -> api.get(token, id).payload().toDomain(userId) }
    override suspend fun getCode(id: String): BenefitCode = request { token -> api.code(token, id).payload().toDomain() }

    private fun <T> BenefitResponseDto<T>.payload(): T {
        check(success == true) { "El servidor no confirmó la operación." }
        return checkNotNull(data) { "El servidor no devolvió la información del beneficio." }
    }

    private suspend fun <T> request(block: suspend (String) -> T): T {
        try {
            return block("Bearer ${auth.token(userId)}")
        } catch (failure: HttpException) {
            if (failure.code() == 401) throw AuthSessionRequiredException()
            val message = runCatching { Gson().fromJson(failure.response()?.errorBody()?.string(), JsonObject::class.java).get("error")?.asString }.getOrNull()
            throw IllegalStateException(benefitMessage(failure.code(), message))
        } catch (_: IOException) {
            throw IllegalStateException("Sin conexión. No se generó ningún código ni se registró ningún canje. Intenta de nuevo.")
        }
    }
}
