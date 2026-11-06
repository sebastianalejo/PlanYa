package com.example.planya

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.planya.data.location.LocationRepository
import com.example.planya.domain.model.*
import com.example.planya.presentation.components.LocationPicker
import com.example.planya.ui.theme.PlanesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class LocationPickerTest {
    @get:Rule val compose = createComposeRule()
    private val lima = LocatedArea(GeoPoint(-12.12, -77.03), "Lima", true, "Lima", "Perú", "PE")

    @Test fun typingOffersSuggestionsButOnlyTappingOneChangesTheArea() {
        var selection: LocatedArea? = null
        var dismissed = false
        val searched = AtomicBoolean(false)
        val source = object : LocationRepository {
            override suspend fun current() = lima
            override suspend fun suggestions(query: String, origin: LocatedArea?, nearbyOnly: Boolean): List<LocationSuggestion> {
                searched.set(true)
                return listOf(LocationSuggestion("Miraflores", "Lima, Perú", lima.copy(label = "Miraflores, Lima")))
            }
        }
        compose.setContent { PlanesTheme {
            LocationPicker(lima, { selection = it }, { dismissed = true }, source)
        } }
        compose.onNode(hasSetTextAction()).performTextInput("miraf")
        // La selección no puede cambiar con el mero ingreso de texto.
        compose.runOnIdle { assertNull(selection) }
        compose.waitUntil(5_000) { searched.get() }
        compose.waitForIdle()
        compose.onNodeWithTag("location-results").performScrollToNode(hasText("Miraflores"))
        compose.onNodeWithText("Miraflores").performClick()
        compose.runOnIdle { assertNull(selection) }
        compose.onNodeWithText("Usar esta zona aproximada").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("Miraflores, Lima", selection?.label)
            assertTrue(dismissed)
        }
    }

    @Test fun anEmptySearchDoesNotChooseAnUnrelatedPlace() {
        var selection: LocatedArea? = null
        val searched = AtomicBoolean(false)
        val source = object : LocationRepository {
            override suspend fun current() = lima
            override suspend fun suggestions(query: String, origin: LocatedArea?, nearbyOnly: Boolean): List<LocationSuggestion> {
                searched.set(true)
                return emptyList()
            }
        }
        compose.setContent { PlanesTheme { LocationPicker(lima, { selection = it }, {}, source) } }
        compose.onNode(hasSetTextAction()).performTextInput("Hola")
        compose.waitUntil(5_000) { searched.get() }
        compose.waitForIdle()
        compose.onNodeWithTag("location-results").performScrollToNode(hasText("No encontramos coincidencias en esta zona"))
        compose.runOnIdle { assertNull(selection) }
    }

    @Test fun streetNumberAndFullAddressArePreservedWhenConfirmed() {
        val place = lima.copy(label = "Av. José Larco 123", fullAddress = "Av. José Larco 123, Miraflores, Lima, Perú",
            street = "Av. José Larco", streetNumber = "123")
        val searched = AtomicBoolean(false)
        var selection: LocatedArea? = null
        val source = object : LocationRepository {
            override suspend fun current() = place
            override suspend fun suggestions(query: String, origin: LocatedArea?, nearbyOnly: Boolean): List<LocationSuggestion> {
                searched.set(true)
                return listOf(LocationSuggestion(place.label, "Miraflores, Lima, Perú", place))
            }
        }
        compose.setContent { PlanesTheme { LocationPicker(lima, { selection = it }, {}, source) } }
        compose.onNode(hasSetTextAction()).performTextInput("Larco 123")
        compose.waitUntil(5_000) { searched.get() }
        compose.onNodeWithTag("location-results").performScrollToNode(hasText(place.label))
        compose.onNodeWithText(place.label).performClick()
        compose.onNodeWithText(place.fullAddress!!).assertExists()
        compose.onNodeWithText("Usar esta dirección").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("123", selection?.streetNumber)
            assertEquals(place.fullAddress, selection?.fullAddress)
        }
    }
}
