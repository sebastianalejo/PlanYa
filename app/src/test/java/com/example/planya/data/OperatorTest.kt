package com.example.planya.data

import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.operator.*
import com.example.planya.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class OperatorTest {
    private val planId = "4cd6a6a2-642b-46fe-82e4-b7691eec3800"
    private val requestId = "2cb3f90e-e167-49de-adf1-2a5325c9c911"
    private inner class Api : OperatorApi {
        var failure: Exception? = null
        var header: String? = null
        var key: String? = null
        var catalog = CatalogDto("staff", listOf(OfferDto("pilot", "Prueba", "Local", "Regla", "Condiciones", listOf(planId))), "opaque")
        var plan = OperatorPlanDto("staff", "pilot", planId, "Encuentro", listOf(MemberDto("visitor", "Ana", false), MemberDto("staff", "Yo", false)))
        var receipt = OperatorReceiptDto(requestId, "coupon", true, "USADO")
        var attendance: AttendanceBody? = null
        var redemption: RedemptionBody? = null
        private fun check(token: String) { header = token; failure?.let { throw it } }
        override suspend fun offers(token: String, nextKey: String?): OperatorResponse<CatalogDto> { check(token); key = nextKey; return OperatorResponse(true, catalog) }
        override suspend fun participants(token: String, offerId: String, planId: String): OperatorResponse<OperatorPlanDto> { check(token); return OperatorResponse(true, plan) }
        override suspend fun confirm(token: String, body: AttendanceBody): OperatorResponse<OperatorReceiptDto> { check(token); attendance = body; return OperatorResponse(true, receipt) }
        override suspend fun redeem(token: String, body: RedemptionBody): OperatorResponse<OperatorReceiptDto> { check(token); redemption = body; return OperatorResponse(true, receipt) }
    }
    private fun repo(api: Api) = OperatorRepositoryImpl(api, "staff") { "test-token" }
    private fun http(status: Int) = HttpException(Response.error<Any>(status, "{}".toResponseBody()))
    @Test fun catalogUsesExpectedAccountCurrentTokenAndOpaqueKey() = runBlocking {
        val api = Api()
        val page = repo(api).offers("opaque-before")
        assertEquals("Bearer test-token", api.header)
        assertEquals("opaque-before", api.key)
        assertEquals("opaque", page.nextKey)
        assertEquals(planId, page.items.single().planIds.single())
    }
    @Test fun wrongAccountRequiresLoginAndMalformedPlanIsNotAccepted() = runBlocking {
        val api = Api()
        api.catalog = api.catalog.copy(userId = "other")
        assertTrue(runCatching { repo(api).offers() }.exceptionOrNull() is AuthSessionRequiredException)
        api.catalog = api.catalog.copy(userId = "staff", items = listOf(api.catalog.items!!.single().copy(planIds = listOf("bad"))))
        assertTrue(runCatching { repo(api).offers() }.isFailure)
    }
    @Test fun participantsExcludeSelfAndRejectDifferentPlan() = runBlocking {
        val api = Api()
        assertEquals(listOf("visitor"), repo(api).participants("pilot", planId).members.map { it.userId })
        api.plan = api.plan.copy(offerId = "different")
        assertTrue(runCatching { repo(api).participants("pilot", planId) }.isFailure)
    }
    @Test fun writesSendExactRequestIdentityAndRequireMatchingConfirmation() = runBlocking {
        val api = Api()
        val repository = repo(api)
        assertEquals(requestId, repository.confirm("pilot", planId, "visitor", requestId).requestId)
        assertEquals(AttendanceBody("pilot", planId, "visitor", requestId), api.attendance)
        repository.redeem("ABCDEF123456", requestId)
        assertEquals(RedemptionBody("ABCDEF123456", requestId), api.redemption)
        api.receipt = api.receipt.copy(requestId = "other")
        assertTrue(runCatching { repository.redeem("ABCDEF123456", requestId) }.isFailure)
    }
    @Test fun successHttpWithoutBusinessConfirmationIsNotSuccess() = runBlocking {
        val api = Api()
        api.receipt = api.receipt.copy(attendanceConfirmed = false, status = "DISPONIBLE")
        assertTrue(runCatching { repo(api).confirm("pilot", planId, "visitor", requestId) }.isFailure)
        assertTrue(runCatching { repo(api).redeem("ABCDEF123456", requestId) }.isFailure)
    }
    @Test fun deniedMissingServiceAndExpiredCursorHaveDistinctFailures() = runBlocking {
        val api = Api()
        api.failure = http(403)
        assertTrue(runCatching { repo(api).offers() }.exceptionOrNull() is OperatorDeniedException)
        api.failure = http(401)
        assertTrue(runCatching { repo(api).offers() }.exceptionOrNull() is AuthSessionRequiredException)
        api.failure = http(404)
        assertTrue(runCatching { repo(api).offers() }.exceptionOrNull()!!.message!!.contains("aún no está disponible"))
        api.failure = http(409)
        assertTrue(runCatching { repo(api).offers("old") }.exceptionOrNull() is OperatorCursorException)
    }
    @Test fun offlineAndServerErrorsNeverConfirmWrites() = runBlocking {
        val api = Api()
        for (failure in listOf(IOException("offline"), http(503))) {
            api.failure = failure
            assertTrue(runCatching { repo(api).redeem("ABCDEF123456", requestId) }.exceptionOrNull() is OperatorUncertainException)
        }
    }
    @Test fun cancellationIsPropagated() = runBlocking {
        val api = Api().apply { failure = CancellationException("stop") }
        assertTrue(runCatching { repo(api).offers() }.exceptionOrNull() is CancellationException)
    }
}
