package com.example.planya.data

import com.example.planya.data.remote.api.*
import com.example.planya.data.remote.dto.*
import com.example.planya.data.repository.*
import com.example.planya.domain.model.BenefitStatus
import com.example.planya.domain.repository.PageCursorException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class PageCursorTest {
    private fun failure(status: Int, code: String) = HttpException(Response.error<Any>(status,
        """{"success":false,"code":"$code","error":"Actualizar lista"}""".toResponseBody()))
    private class Plans : PlanApiService {
        var error: Exception? = null
        var key: String? = null
        override suspend fun getPlan(id: String): PlanResponseDto = error("No usado")
        override suspend fun getPlans(nextKey: String?): PlansResponseDto {
            key = nextKey; error?.let { throw it }
            return PlansResponseDto(true, PlansDataDto(emptyList(), "opaque-next"))
        }
    }
    private class Benefits : BenefitsApi {
        var error: Exception? = null
        var key: String? = null
        var filter: String? = null
        var header: String? = null
        override suspend fun list(token: String, status: String, nextKey: String?): BenefitResponseDto<BenefitListDto> {
            header = token; key = nextKey; filter = status; error?.let { throw it }
            return BenefitResponseDto(true, BenefitListDto(emptyList(), "opaque-next"))
        }
        override suspend fun get(token: String, id: String): BenefitResponseDto<BenefitDto> = error("No usado")
        override suspend fun code(token: String, id: String): BenefitResponseDto<BenefitCodeDto> = error("No usado")
    }
    @Test fun planContinuationIsPassedAsOpaqueWithoutInterpretingIt() = runBlocking {
        val api = Plans()
        val page = PlanRepositoryImpl(api).getPlans("opaque-old")
        assertEquals("opaque-old", api.key); assertEquals("opaque-next", page.nextKey)
        assertTrue(page.items.isEmpty())
    }
    @Test fun expiredAndInvalidPlanContinuationsGetRecoverableFailure() = runBlocking {
        val api = Plans()
        for ((status, code) in listOf(409 to "CURSOR_EXPIRED", 400 to "CURSOR_INVALID")) {
            api.error = failure(status, code)
            assertTrue(runCatching { PlanRepositoryImpl(api).getPlans("old") }.exceptionOrNull() is PageCursorException)
        }
    }
    @Test fun unrelatedPlanErrorsDoNotResetPaginationAsIfExpired() = runBlocking {
        val api = Plans().apply { error = failure(503, "SERVER_ERROR") }
        assertTrue(runCatching { PlanRepositoryImpl(api).getPlans("old") }.exceptionOrNull() is HttpException)
        api.error = failure(409, "OTHER_CONFLICT")
        assertTrue(runCatching { PlanRepositoryImpl(api).getPlans("old") }.exceptionOrNull() is HttpException)
    }
    @Test fun benefitsPreserveTokenFilterAndOpaqueContinuation() = runBlocking {
        val api = Benefits()
        val page = BenefitsRepositoryImpl(api, "u1") { "test-session" }.getBenefits(BenefitStatus.USADO, "old")
        assertEquals("Bearer test-session", api.header); assertEquals("USADO", api.filter)
        assertEquals("old", api.key); assertEquals("opaque-next", page.nextKey)
    }
    @Test fun benefitCursorDoesNotMasqueradeAsAlreadyUsedCoupon() = runBlocking {
        val api = Benefits().apply { error = failure(409, "CURSOR_EXPIRED") }
        val error = runCatching { BenefitsRepositoryImpl(api, "u1") { "t" }.getBenefits(BenefitStatus.DISPONIBLE, "old") }.exceptionOrNull()
        assertTrue(error is PageCursorException)
        assertFalse(error!!.message!!.contains("utilizado"))
    }
    @Test fun noCursorMeansOrdinaryErrorNotAContinuationReset() = runBlocking {
        val api = Benefits().apply { error = failure(409, "CURSOR_EXPIRED") }
        val error = runCatching { BenefitsRepositoryImpl(api, "u1") { "t" }.getBenefits(BenefitStatus.DISPONIBLE, null) }.exceptionOrNull()
        assertFalse(error is PageCursorException)
    }
}
