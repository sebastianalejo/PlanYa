package com.example.planya.data

import com.example.planya.data.mapper.toDomain
import com.example.planya.data.remote.api.PlanApiService
import com.example.planya.data.remote.dto.PlanDto
import com.example.planya.data.remote.dto.PlanResponseDto
import com.example.planya.data.remote.dto.PlansDataDto
import com.example.planya.data.remote.dto.PlansResponseDto
import com.example.planya.data.repository.PlanRepositoryImpl
import com.example.planya.domain.model.PlanCategory
import com.example.planya.domain.repository.PlanUnavailableException
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import retrofit2.HttpException
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlanRepositoryTest {
    @Test fun `unavailable plan is distinct from an undeployed route`() = runBlocking {
        fun failingApi(body: String) = object : PlanApiService {
            override suspend fun getPlans(nextKey: String?) = PlansResponseDto()
            override suspend fun getPlan(id: String): PlanResponseDto {
                throw HttpException(Response.error<PlanResponseDto>(404, body.toResponseBody()))
            }
        }
        val missingPlan = runCatching { PlanRepositoryImpl(failingApi("""{"success":false,"error":"Plan no disponible"}""")).getPlan("p1") }.exceptionOrNull()
        assertTrue(missingPlan is PlanUnavailableException)
        val missingRoute = runCatching { PlanRepositoryImpl(failingApi("""{"message":"Not Found"}""")).getPlan("p1") }.exceptionOrNull()
        assertTrue(missingRoute is HttpException)
    }
    @Test fun `detail is fetched by its identifier`() = runBlocking {
        val plan = PlanRepositoryImpl(fakeApi(PlansResponseDto())).getPlan("p123")
        assertEquals("p123", plan.id)
        assertEquals("Detalle", plan.title)
    }

    @Test fun `detail rejects a mismatched identifier`() = runBlocking {
        val api = object : PlanApiService {
            override suspend fun getPlans(nextKey: String?) = PlansResponseDto()
            override suspend fun getPlan(id: String) = PlanResponseDto(true, PlanDto(id = "otro", title = "Plan"))
        }
        assertTrue(runCatching { PlanRepositoryImpl(api).getPlan("p123") }.isFailure)
    }
    @Test
    fun `maps the AWS envelope and does not invent author or distance`() = runBlocking {
        val json = """{"success":true,"data":{"items":[{
          "id":"aws-1","title":"Cafe por Miraflores",
          "description":"Busco compania","category":"CAFE",
          "createdAt":"2026-11-05T01:10:57.329084+00:00"
        }],"nextKey":"pagina-2"}}"""
        val response = Gson().fromJson(json, PlansResponseDto::class.java)
        var receivedKey: String? = null
        val api = object : PlanApiService {
            override suspend fun getPlan(id: String) = PlanResponseDto(true, PlanDto(id = id, title = "Detalle"))
            override suspend fun getPlans(nextKey: String?): PlansResponseDto {
                receivedKey = nextKey
                return response
            }
        }
        val page = PlanRepositoryImpl(api).getPlans("pagina-1")
        assertEquals("pagina-1", receivedKey)
        assertEquals("pagina-2", page.nextKey)
        val plan = page.items.single()
        assertEquals("aws-1", plan.id)
        assertEquals("Cafe por Miraflores", plan.title)
        assertEquals(PlanCategory.CAFE, plan.category)
        assertEquals("Anfitrión por confirmar", plan.authorName)
        assertNull(plan.distance)
        assertFalse(plan.startsSoon)
    }

    @Test
    fun `empty response is a valid empty list`() = runBlocking {
        val repository = PlanRepositoryImpl(fakeApi(PlansResponseDto(true, PlansDataDto(emptyList()))))
        val page = repository.getPlans()
        assertTrue(page.items.isEmpty())
        assertNull(page.nextKey)
    }

    @Test
    fun `failed response is not displayed as an empty list`() = runBlocking {
        val repository = PlanRepositoryImpl(fakeApi(PlansResponseDto(success = false)))
        val error = runCatching { repository.getPlans() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun `missing list is an invalid API response`() = runBlocking {
        val repository = PlanRepositoryImpl(fakeApi(PlansResponseDto(true, PlansDataDto())))
        assertTrue(runCatching { repository.getPlans() }.isFailure)
    }

    @Test
    fun `unknown category and empty description do not break mapping`() {
        val plan = PlanDto(id = "1", title = "Otro plan", category = "NUEVA", description = " ").toDomain()
        assertNull(plan.category)
        assertNull(plan.description)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a plan without ID is rejected instead of duplicating list keys`() {
        PlanDto(title = "Sin id").toDomain()
    }

    private fun fakeApi(response: PlansResponseDto) = object : PlanApiService {
        override suspend fun getPlan(id: String) = PlanResponseDto(true, PlanDto(id = id, title = "Detalle"))
        override suspend fun getPlans(nextKey: String?) = response
    }
}
