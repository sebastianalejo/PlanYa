package com.example.planya.data

import com.example.planya.data.profile.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Audit regression: two people can submit equal scores in the same second. */
class ReviewRatingsCollisionTest {
    @Test fun twoLegitimateVotesFromTheSamePlanAndSecondMustBothRemainVisible() = runBlocking {
        val first = ReceivedRating("plan-1", "Encuentro de prueba", 5, 1795262400, true)
        val api = object : ReceivedRatingsApi {
            override suspend fun received(token: String, nextKey: String?) = ApiResponse(true,
                ReceivedRatingsPage("review-user", 2, 5.0, listOf(first, first.copy()), null))
        }
        val page = ReceivedRatingsRepository(api, "review-user") { "fixture" }.received()
        assertEquals("Dos votos reales no son una fila duplicada de paginación", 2, page.items.size)
    }
}
