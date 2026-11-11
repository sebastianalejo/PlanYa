package com.example.planya.data

import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.profile.*
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RatingsTest {
    private val candidate = RatingCandidate("plan", "other", "Café", "Ana")
    private inner class Api : RatingsApi {
        var user = "me"
        var receipt = RatingReceipt(true, false)
        var failure: Exception? = null
        var body: RatingBody? = null
        override suspend fun pending(token: String): ApiResponse<RatingsCatalog> {
            failure?.let { throw it }
            assertEquals("Bearer fixture", token)
            return ApiResponse(true, RatingsCatalog(user, listOf(candidate)))
        }
        override suspend fun rate(token: String, body: RatingBody): ApiResponse<RatingReceipt> {
            failure?.let { throw it }
            this.body = body
            return ApiResponse(true, receipt)
        }
    }
    private fun repo(api: Api) = RatingsRepository(api, "me") { "fixture" }
    @Test fun pendingBelongsToExpectedSession() = runBlocking {
        val api = Api()
        assertEquals(listOf(candidate), repo(api).pending())
        api.user = "other"
        assertTrue(runCatching { repo(api).pending() }.exceptionOrNull() is AuthSessionRequiredException)
    }
    @Test fun sendsOnlyTargetPlanAndScoreAndAcceptsIdempotentReceipt() = runBlocking {
        val api = Api()
        api.receipt = RatingReceipt(true, true)
        repo(api).rate(candidate, 4)
        assertEquals(RatingBody("plan", "other", 4), api.body)
    }
    @Test fun rejectsSelfAndInvalidScoresBeforeRequest() = runBlocking {
        val api = Api()
        assertTrue(runCatching { repo(api).rate(candidate.copy(targetUserId = "me"), 5) }.isFailure)
        assertTrue(runCatching { repo(api).rate(candidate, 0) }.isFailure)
        assertNull(api.body)
    }
    @Test fun doesNotInventSuccessWithoutSavedReceipt() = runBlocking {
        val api = Api().apply { receipt = RatingReceipt(false, false) }
        assertTrue(runCatching { repo(api).rate(candidate, 5) }.isFailure)
    }
    @Test fun authAndConflictRemainFailures() = runBlocking {
        val api = Api()
        api.failure = HttpException(Response.error<Any>(401, "{}".toResponseBody()))
        assertTrue(runCatching { repo(api).pending() }.exceptionOrNull() is AuthSessionRequiredException)
        api.failure = HttpException(Response.error<Any>(409, "{}".toResponseBody()))
        assertTrue(runCatching { repo(api).rate(candidate, 5) }.exceptionOrNull()!!.message!!.contains("Actualiza"))
    }
}
