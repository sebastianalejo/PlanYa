package com.example.planya.data

import com.example.planya.domain.model.*
import com.example.planya.domain.usecase.plan.buildMapPins
import com.example.planya.domain.usecase.plan.countWithoutLocation
import org.junit.Assert.*
import org.junit.Test

// US-07: pines del mapa calculados solo con planes reales del listado.
class MapPinsTest {
    private val kennedy = GeoPoint(-12.1211, -77.0297)
    private val barranco = GeoPoint(-12.1490, -77.0210)
    private val now = 1_000L
    private fun plan(id: String, coords: GeoPoint? = kennedy, category: PlanCategory = PlanCategory.CAFE,
        expires: Long? = 2_000) = Plan(id, "Lucía", timeAgo = "", title = "Plan $id",
        category = category, coordinates = coords, expiresAt = expires)

    @Test fun activePlansWithCoordinatesBecomePins() {
        val pins = buildMapPins(listOf(plan("a"), plan("b", barranco)), null, now)
        assertEquals(2, pins.size)
        assertEquals(setOf("a", "b"), pins.flatMap { it.plans }.map { it.id }.toSet())
    }

    @Test fun plansWithoutCoordinatesAreNotInvented() {
        val plans = listOf(plan("a"), plan("sin", coords = null))
        val pins = buildMapPins(plans, null, now)
        assertEquals(listOf("a"), pins.flatMap { it.plans }.map { it.id })
        assertEquals(1, countWithoutLocation(plans, now))
    }

    @Test fun expiredPlansAreRemovedWhenClockAdvances() {
        val plans = listOf(plan("a", expires = 1_500), plan("b", barranco, expires = 5_000))
        assertEquals(2, buildMapPins(plans, null, now).size)
        // A los 1500 s vence "a": su pin desaparece.
        assertEquals(listOf("b"), buildMapPins(plans, null, 1_500).flatMap { it.plans }.map { it.id })
    }

    @Test fun planWithoutExpirationIsNotTreatedAsExpired() {
        assertEquals(1, buildMapPins(listOf(plan("a", expires = null)), null, now).size)
    }

    @Test fun categoryFilterAndBackToAll() {
        val plans = listOf(plan("cafe"), plan("run", barranco, PlanCategory.CAMINATA))
        assertEquals(listOf("run"), buildMapPins(plans, PlanCategory.CAMINATA, now).flatMap { it.plans }.map { it.id })
        assertTrue(buildMapPins(plans, PlanCategory.FIESTA, now).isEmpty())
        assertEquals(2, buildMapPins(plans, null, now).size)
    }

    @Test fun plansSharingCoordinatesShareOnePinAndCanBeChosen() {
        val pins = buildMapPins(listOf(plan("a"), plan("b", GeoPoint(-12.121100001, -77.0297)), plan("c", barranco)), null, now)
        assertEquals(2, pins.size)
        val group = pins.single { it.isGroup }
        assertEquals(setOf("a", "b"), group.plans.map { it.id }.toSet())
        assertEquals(PlanCategory.CAFE, group.category)
    }

    @Test fun mixedCategoriesInOnePinHaveNoSingleCategory() {
        val pin = buildMapPins(listOf(plan("a"), plan("b", category = PlanCategory.FIESTA)), null, now).single()
        assertNull(pin.category)
    }

    @Test fun duplicatedIdsFromPagesAreCountedOnce() {
        val pins = buildMapPins(listOf(plan("a"), plan("a")), null, now)
        assertEquals(1, pins.single().plans.size)
    }
}
