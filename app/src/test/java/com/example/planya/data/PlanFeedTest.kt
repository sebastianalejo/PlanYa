package com.example.planya.data

import com.example.planya.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class PlanFeedTest {
    private val home = GeoPoint(-12.0, -77.0)
    private fun plan(id: String, owner: String = "other", category: PlanCategory = PlanCategory.CAFE,
        expires: Long? = 200, coords: GeoPoint? = null, members: List<String> = emptyList()) =
        Plan(id, "Lucía", timeAgo = "", title = "Café y charla", description = "Diseño",
            category = category, expiresAt = expires, coordinates = coords, ownerId = owner, attendeeIds = members)
    private fun filter(plans: List<Plan>, query: String = "", scope: PlanFeedScope = PlanFeedScope.ALL,
        order: PlanFeedOrder = PlanFeedOrder.RECOMMENDED, user: String? = "me", interests: Set<String> = emptySet()) =
        visibleFeed(plans, query, null, scope, order, user, interests, home, 100)

    @Test fun searchIgnoresAccentsAndMatchesMultipleWordsAndId() {
        val item = plan("abc-123")
        assertEquals(listOf(item), filter(listOf(item), "cafe diseno"))
        assertEquals(listOf(item), filter(listOf(item), "ABC-123"))
        assertTrue(filter(listOf(item), "cafe otro").isEmpty())
    }
    @Test fun ownedAndJoinedAreSeparateAndRequireAccount() {
        val own = plan("mine", "me", members = listOf("me"))
        val joined = plan("joined", members = listOf("me"))
        assertEquals(listOf(own), filter(listOf(own, joined), scope = PlanFeedScope.OWNED))
        assertEquals(listOf(joined), filter(listOf(own, joined), scope = PlanFeedScope.JOINED))
        assertTrue(filter(listOf(own, joined), scope = PlanFeedScope.OWNED, user = null).isEmpty())
    }
    @Test fun expiredPlansAreHiddenAndMissingDistanceSortsLast() {
        val near = plan("near", coords = home)
        val far = plan("far", coords = GeoPoint(-13.0, -77.0))
        val unknown = plan("unknown")
        assertEquals(listOf(near, far, unknown), filter(listOf(unknown, far, plan("old", expires = 100), near), order = PlanFeedOrder.DISTANCE))
    }
    @Test fun interestsPrioritizeWithoutExcludingOtherCategories() {
        val coffee = plan("coffee")
        val other = plan("other", category = PlanCategory.entries.first { it != PlanCategory.CAFE })
        assertEquals(listOf(other, coffee), filter(listOf(coffee, other), interests = setOf(other.category!!.name)))
    }
}
