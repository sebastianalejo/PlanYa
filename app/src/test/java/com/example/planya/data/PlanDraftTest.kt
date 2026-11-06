package com.example.planya.data

import com.example.planya.domain.model.*
import com.example.planya.presentation.screens.createplan.PlanDraft
import com.example.planya.presentation.screens.createplan.DraftPhoto
import org.junit.Assert.*
import org.junit.Test

class PlanDraftTest {
    private val address = LocatedArea(GeoPoint(-12.12, -77.03), "Miraflores", true,
        fullAddress = "Av. Larco 123, Miraflores", street = "Av. Larco", streetNumber = "123")
    private val valid = PlanDraft(title = "Un café", location = address)
    @Test fun `gallery preserves legacy cover orders photos and clears explicitly`() {
        val legacy = valid.copy(imageKey = "old")
        assertEquals("old", legacy.gallery().single().imageKey)
        val four = legacy.withPhotos((1..4).map { DraftPhoto(imageKey = "key-$it") })
        assertNull(four.error())
        assertEquals("key-1", four.imageKey)
        assertEquals(4, (four.payload("request")["photos"] as List<*>).size)
        assertNotNull(four.withPhotos(four.gallery() + DraftPhoto(imageKey = "fifth")).error())
        val cleared = four.withPhotos(emptyList()).payload("request")
        assertEquals(emptyList<Any>(), cleared["photos"])
        assertFalse(cleared.containsKey("imageKey"))
    }
    @Test fun `local paths never enter gallery payload`() {
        assertThrows(IllegalStateException::class.java) { valid.withPhotos(listOf(DraftPhoto(localName = "private.jpg"))).payload("request") }
        val payload = valid.withPhotos(listOf(DraftPhoto(imageKey = "key", localName = "private.jpg"))).payload("request")
        assertFalse(payload.toString().contains("private.jpg"))
    }
    @Test fun `publishing requires a title location and valid capacity`() {
        assertNotNull(PlanDraft().error())
        assertNotNull(valid.copy(location = null).error())
        assertNotNull(valid.copy(capacity = "abc").error())
        assertNotNull(valid.copy(capacity = "1").error())
        assertNull(valid.error())
    }
    @Test fun `the start must be before expiry`() {
        assertNotNull(valid.copy(startsIn = 60, duration = 30).error())
        assertNotNull(valid.copy(startsIn = 30, duration = 30).error())
        assertNull(valid.copy(startsIn = 0, duration = 30).error())
    }
    @Test fun `payload retains the full address and reference instead of district only`() {
        val payload = valid.copy(location = address.copy(reference = "Entrada principal")).payload("request-1")
        assertEquals("Av. Larco 123, Miraflores · Entrada principal", payload["meetingPlace"])
        assertEquals(-12.12, payload["latitude"])
        assertEquals("request-1", payload["requestId"])
    }
    @Test fun `optional photographs require https`() {
        assertNotNull(valid.copy(imageUrl = "http://example.com/photo.jpg").error())
        assertNull(valid.copy(imageUrl = "https://example.com/photo.jpg").error())
    }
    @Test fun `editing retains revision but never sends original schedule as editable fields`() {
        val payload = valid.copy(expectedRevision = 7, originalStartsAt = 1000, originalExpiresAt = 2000).payload("request")
        assertEquals(7L, payload["expectedRevision"])
        assertFalse(payload.containsKey("startsAt"))
        assertFalse(payload.containsKey("expiresAt"))
        assertFalse(valid.payload("request").containsKey("expectedRevision"))
    }
    @Test fun `editing cannot reduce capacity below confirmed participants`() {
        assertNotNull(valid.copy(capacity = "3", minimumCapacity = 4).error())
        assertNull(valid.copy(capacity = "4", minimumCapacity = 4).error())
    }
}
