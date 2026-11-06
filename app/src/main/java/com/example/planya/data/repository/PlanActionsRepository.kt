package com.example.planya.data.repository

import com.example.planya.data.auth.AuthRepository
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.mapper.toDomain
import com.example.planya.data.remote.api.PlanActionsApi
import com.example.planya.data.remote.dto.PlanResponseDto
import com.example.planya.domain.model.Plan
import com.example.planya.domain.repository.PlanActions
import com.google.gson.Gson
import com.google.gson.JsonObject
import retrofit2.HttpException
import java.io.IOException

class PlanConflictException(message: String) : IllegalStateException(message)

fun planActionMessage(status: Int, serverMessage: String?, operation: String): String = serverMessage ?: when (status) {
    401 -> "Tu sesión venció. Vuelve a ingresar."
    403 -> "No tienes permiso para realizar este cambio."
    404 -> "El plan o esta operación todavía no están disponibles. Actualiza el listado."
    409 -> "El plan cambió. Actualiza y vuelve a intentar."
    in 500..599 -> "AWS devolvió un error (HTTP $status) al $operation. Comprueba que el laboratorio esté activo. Actualiza el plan antes de repetir la operación."
    else -> "No se pudo completar la operación (HTTP $status). Revisa los datos e intenta de nuevo."
}

class PlanActionsRepository(private val api: PlanActionsApi, private val auth: AuthRepository) : PlanActions {
    override suspend fun create(data: Map<String, Any>) = request("publicar") { api.create(token(), data) }
    override suspend fun edit(id: String, data: Map<String, Any>) = request("guardar cambios") { api.edit(token(), id, data) }
    override suspend fun join(id: String) = request("unirte") { api.join(token(), id) }
    override suspend fun leave(id: String) = request("salir del plan") { api.leave(token(), id) }
    override suspend fun cancel(id: String) = request("cancelar el plan") { api.cancel(token(), id) }
    private suspend fun token() = "Bearer ${auth.token()}"
    private suspend fun request(operation: String, block: suspend () -> PlanResponseDto): Plan {
        try {
            val response = block()
            check(response.success == true) { "No se pudo guardar el cambio." }
            return checkNotNull(response.data) { "El servidor no devolvió el plan." }.toDomain()
        } catch (failure: HttpException) {
            if (failure.code() == 401) throw AuthSessionRequiredException()
            val message = runCatching { Gson().fromJson(failure.response()?.errorBody()?.string(), JsonObject::class.java).get("error")?.asString }.getOrNull()
            if (failure.code() == 409) throw PlanConflictException(message ?: "El plan cambió. Actualiza antes de volver a guardar.")
            throw IllegalStateException(planActionMessage(failure.code(), message, operation))
        } catch (_: IOException) {
            throw IllegalStateException("No pudimos confirmar la operación. Revisa la conexión y que AWS esté activo; actualiza el plan antes de repetirla.")
        }
    }
}
