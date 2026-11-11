package com.example.planya.data.repository

import com.example.planya.data.mapper.toDomain
import com.example.planya.data.remote.api.PlanApiService
import com.example.planya.domain.model.PlanPage
import com.example.planya.domain.repository.PlanRepository
import com.example.planya.domain.repository.PlanUnavailableException
import com.example.planya.domain.repository.PageCursorException
import com.google.gson.JsonObject
import com.google.gson.Gson
import com.example.planya.data.remote.dto.PlanResponseDto
import retrofit2.HttpException

class PlanRepositoryImpl(private val api: PlanApiService) : PlanRepository {
    override suspend fun getPlan(id: String): com.example.planya.domain.model.Plan {
        val response = try { api.getPlan(id) } catch (failure: HttpException) {
            val error = runCatching { Gson().fromJson(failure.response()?.errorBody()?.string(), PlanResponseDto::class.java) }.getOrNull()
            if (failure.code() == 404 && error?.success == false) throw PlanUnavailableException()
            throw failure
        }
        check(response.success == true) { "No se pudo consultar el plan" }
        val plan = checkNotNull(response.data) { "Respuesta sin plan" }.toDomain()
        check(plan.id == id) { "La respuesta corresponde a otro plan" }
        return plan
    }
    override suspend fun getPlans(nextKey: String?): PlanPage {
        val response = try { api.getPlans(nextKey) } catch (failure: HttpException) {
            val error = runCatching { Gson().fromJson(failure.response()?.errorBody()?.string(), JsonObject::class.java) }.getOrNull()
            if (nextKey != null && failure.code() in setOf(400, 409) &&
                error?.get("code")?.asString in setOf("CURSOR_INVALID", "CURSOR_EXPIRED")) throw PageCursorException()
            throw failure
        }
        check(response.success == true) { "La API no pudo listar los planes" }
        val data = checkNotNull(response.data) { "Respuesta sin datos" }
        val items = checkNotNull(data.items) { "Respuesta sin lista de planes" }
        return PlanPage(items.map { it.toDomain() }, data.nextKey)
    }
}
