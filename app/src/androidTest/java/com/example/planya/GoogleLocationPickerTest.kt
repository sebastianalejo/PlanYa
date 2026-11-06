package com.example.planya

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.planya.data.location.*
import com.example.planya.domain.model.*
import com.example.planya.presentation.components.LocationPicker
import com.example.planya.ui.theme.PlanesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class GoogleLocationPickerTest {
    @get:Rule val compose = createComposeRule()
    private val area = LocatedArea(GeoPoint(-12.1, -77.03), "Lima", false,
        fullAddress = "Av. Larco 123, Miraflores, Perú", street = "Av. Larco", streetNumber = "123")
    private val nativeCalls = AtomicInteger()
    private val native = object : LocationRepository {
        override suspend fun current() = area
        override suspend fun suggestions(query: String, origin: LocatedArea?, nearbyOnly: Boolean): List<LocationSuggestion> {
            nativeCalls.incrementAndGet()
            return listOf(LocationSuggestion("Dirección del teléfono", "Lima, Perú", area))
        }
    }
    private class GoogleFake(val area: LocatedArea, val fail: Boolean = false) : GooglePlacesSource {
        val predictions = AtomicInteger()
        val details = AtomicInteger()
        val restores = AtomicInteger()
        override suspend fun predictions(query: String, origin: LocatedArea?, nearbyOnly: Boolean): List<GooglePlacePrediction> {
            predictions.incrementAndGet()
            if (fail) throw IOException("Límite de Google alcanzado")
            return listOf(GooglePlacePrediction("fake-place", "Café cercano", "Miraflores, Perú"))
        }
        override suspend fun select(prediction: GooglePlacePrediction): GooglePlaceSelection {
            details.incrementAndGet()
            return GooglePlaceSelection(prediction.placeId, area.copy(googlePlaceId = prediction.placeId))
        }
        override suspend fun restore(placeId: String): GooglePlaceSelection {
            restores.incrementAndGet()
            return GooglePlaceSelection(placeId, area.copy(googlePlaceId = placeId))
        }
    }
    @Test fun unifiedSearchUsesGoogleOnlyAfterTypingAndRequiresConfirmation() {
        val fake = GoogleFake(area)
        var selected: LocatedArea? = null
        compose.setContent { PlanesTheme {
            LocationPicker(area, { selected = it }, {}, native, fake)
        } }
        compose.waitForIdle()
        assertEquals(0, fake.predictions.get())
        compose.onNodeWithText("Teléfono").assertDoesNotExist()
        compose.onNodeWithText("Ampliar búsqueda").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("Larco")
        compose.waitUntil(6_000) { fake.predictions.get() > 0 }
        compose.onNodeWithTag("location-results").performScrollToNode(hasText("Café cercano"))
        compose.onNodeWithText("Café cercano").performClick()
        compose.onNodeWithText("Confirma la dirección").assertExists()
        compose.runOnIdle { assertNull(selected); assertEquals(1, fake.details.get()) }
        compose.onNodeWithText("Usar esta dirección").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(area.fullAddress, selected?.displayAddress)
            assertEquals("fake-place", selected?.googlePlaceId)
        }
    }
    @Test fun googleErrorAllowsPhoneFallbackWithoutChangingCurrentLocation() {
        val fake = GoogleFake(area, fail = true)
        var selected: LocatedArea? = null
        compose.setContent { PlanesTheme { LocationPicker(area, { selected = it }, {}, native, fake) } }
        compose.onNode(hasSetTextAction()).performTextInput("Larco")
        compose.waitUntil(6_000) { fake.predictions.get() > 0 }
        compose.runOnIdle { assertNull(selected) }
        compose.waitUntil(6_000) { nativeCalls.get() > 0 }
        compose.waitForIdle()
        compose.onNodeWithTag("location-results").performScrollToNode(hasText("Dirección del teléfono"))
        compose.onNodeWithText("Dirección del teléfono").performClick()
        compose.onNodeWithText("Usar esta dirección").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(area, selected); assertEquals(0, fake.details.get()) }
    }
    @Test fun storedGoogleIdIsOnlyResolvedAfterTappingRecover() {
        val fake = GoogleFake(area)
        compose.setContent { PlanesTheme {
            LocationPicker(null, {}, {}, native, fake, savedGooglePlaceId = "saved-place")
        } }
        compose.waitForIdle()
        assertEquals(0, fake.predictions.get())
        assertEquals(0, fake.restores.get())
        compose.onNodeWithTag("location-results").performScrollToNode(hasText("Recuperar mi dirección con Google"))
        compose.onNodeWithText("Recuperar mi dirección con Google").performClick()
        compose.onNodeWithText("Confirma la dirección").assertExists()
        assertEquals(1, fake.restores.get())
    }
}
