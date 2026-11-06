package com.example.planya.data

import com.example.planya.data.mapper.toDomain
import com.example.planya.data.remote.dto.PlanDto
import com.example.planya.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class PlanLocationTest {
    @Test fun `header uses confirmed full address rather than an old neighborhood label`() {
        val area = LocatedArea(GeoPoint(-12.0, -77.0), "Otra zona", false,
            fullAddress = "Av. Bausate y Meza 911, La Victoria, Perú")
        assertEquals(area.fullAddress, area.displayAddress)
        assertEquals("Otra zona", area.copy(fullAddress = " ").displayAddress)
    }
    @Test fun `distance is symmetric and identical points have zero distance`() {
        val a = GeoPoint(-12.12, -77.03)
        val b = GeoPoint(-12.13, -77.04)
        assertEquals(0.0, a.metersTo(a), 0.01)
        assertEquals(a.metersTo(b), b.metersTo(a), 0.01)
        assertEquals(1555.0, a.metersTo(b), 20.0)
    }
    @Test fun `distance formatting handles meters kilometers and missing coordinates`() {
        val a = GeoPoint(0.0, 0.0)
        assertEquals("0 m", distanceLabel(a, a))
        assertEquals("1.1 km", distanceLabel(a, GeoPoint(0.01, 0.0)))
        assertNull(distanceLabel(null, a))
        assertNull(distanceLabel(a, null))
    }
    @Test fun `bad coordinates and insecure images do not break legacy plans`() {
        val plan = PlanDto(id = "p1", title = "Plan", latitude = 100.0,
            longitude = -77.0, imageUrl = "http://example.com/a.jpg").toDomain()
        assertNull(plan.coordinates)
        assertNull(plan.imageUrl)
        assertNull(plan.expiresAt)
        assertNull(plan.attendees)
    }
    @Test fun `expiry becomes final at the exact deadline`() {
        val plan = PlanDto(id = "p1", title = "Plan", expiresAt = 1000).toDomain()
        assertFalse(plan.isExpired(999))
        assertTrue(plan.isExpired(1000))
        assertEquals("1 min", plan.expirationLabel(999))
        assertEquals("Finalizado", plan.expirationLabel(1000))
        assertFalse(plan.copy(expiresAt = null).isExpired(1000))
    }
    @Test fun `full detail fields map without inventing participants`() {
        val plan = PlanDto(id = "p1", title = "Plan", latitude = -12.12, longitude = -77.03,
            imageUrl = "https://example.com/plan.jpg", capacity = 5, attendees = emptyList()).toDomain()
        assertEquals(GeoPoint(-12.12, -77.03), plan.coordinates)
        assertEquals(5, plan.capacity)
        assertTrue(plan.attendees!!.isEmpty())
    }
}
