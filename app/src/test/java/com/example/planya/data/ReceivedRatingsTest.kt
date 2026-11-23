package com.example.planya.data

import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.profile.*
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class ReceivedRatingsTest {
    private val rating = ReceivedRating("plan-1", "Café por Miraflores", 5, 1200, true)
    private val page = ReceivedRatingsPage("me", 2, 4.5, listOf(rating, rating.copy(score = 4, createdAt = 1100)), "cursor-2")

    private inner class Api : ReceivedRatingsApi {
        var value = page
        var failure: Exception? = null
        var sentNextKey: String? = null
        override suspend fun received(token: String, nextKey: String?): ApiResponse<ReceivedRatingsPage> {
            failure?.let { throw it }
            assertEquals("Bearer fixture", token)
            sentNextKey = nextKey
            return ApiResponse(true, value)
        }
    }

    private fun repo(api: Api) = ReceivedRatingsRepository(api, "me") { "fixture" }

    @Test fun mapsOnlyAllowedFieldsAndKeepsTheOpaqueCursor() = runBlocking {
        val api = Api()
        val loaded = repo(api).received(null)
        assertEquals(2, loaded.count)
        assertEquals(4.5, loaded.average!!, 0.0)
        assertEquals(listOf("Café por Miraflores", "Café por Miraflores"), loaded.items.map { it.planTitle })
        assertEquals("cursor-2", loaded.nextKey)
        assertEquals(null, api.sentNextKey)
        repo(api).received("cursor-2")
        assertEquals("cursor-2", api.sentNextKey)
    }

    @Test fun rejectsForeignAccountBeforeShowingPrivateHistory() = runBlocking {
        val api = Api().apply { value = page.copy(userId = "otra-cuenta") }
        assertTrue(runCatching { repo(api).received() }.exceptionOrNull() is AuthSessionRequiredException)
    }

    @Test fun rejectsInconsistentSummaryAndInvalidCards() = runBlocking {
        val api = Api()
        api.value = page.copy(count = 0, average = 4.5)
        assertTrue(runCatching { repo(api).received() }.isFailure)
        api.value = page.copy(count = 2, average = null)
        assertTrue(runCatching { repo(api).received() }.isFailure)
        api.value = page.copy(average = 6.0)
        assertTrue(runCatching { repo(api).received() }.isFailure)
        api.value = page.copy(items = listOf(rating.copy(score = 0)))
        assertTrue(runCatching { repo(api).received() }.isFailure)
        api.value = page.copy(items = listOf(rating.copy(createdAt = 0)))
        assertTrue(runCatching { repo(api).received() }.isFailure)
        api.value = page.copy(items = listOf(rating.copy(planTitle = "")))
        assertTrue(runCatching { repo(api).received() }.isFailure)
    }

    @Test fun emptyHistoryIsAValidConfirmedResponse() = runBlocking {
        val api = Api().apply {
            value = ReceivedRatingsPage("me", 0, null, emptyList(), null)
        }
        val loaded = repo(api).received()
        assertEquals(0, loaded.count)
        assertNull(loaded.average)
        assertTrue(loaded.items.isEmpty())
    }

    @Test fun mapsSessionExpiryAndPrivateCursorErrors() = runBlocking {
        val api = Api()
        api.failure = HttpException(Response.error<Any>(401, "{}".toResponseBody()))
        assertTrue(runCatching { repo(api).received() }.exceptionOrNull() is AuthSessionRequiredException)
        api.failure = HttpException(Response.error<Any>(409,
            """{"success":false,"error":"La continuación venció","code":"CURSOR_EXPIRED"}""".toResponseBody()))
        val expired = runCatching { repo(api).received("cursor") }.exceptionOrNull() as ReceivedRatingsCursorException
        assertTrue(expired.expired)
        assertEquals("La continuación venció", expired.message)
        api.failure = HttpException(Response.error<Any>(400,
            """{"success":false,"error":"Cursor inválido","code":"CURSOR_INVALID"}""".toResponseBody()))
        val invalid = runCatching { repo(api).received("cursor") }.exceptionOrNull() as ReceivedRatingsCursorException
        assertFalse(invalid.expired)
    }

    @Test fun mapsMissingRouteAndOfflineErrors() = runBlocking {
        val api = Api()
        api.failure = HttpException(Response.error<Any>(404, "{}".toResponseBody()))
        assertTrue(runCatching { repo(api).received() }.exceptionOrNull()!!.message!!.contains("no está habilitado"))
        api.failure = IOException("offline")
        assertTrue(runCatching { repo(api).received() }.exceptionOrNull()!!.message!!.contains("Sin conexión"))
    }
}
