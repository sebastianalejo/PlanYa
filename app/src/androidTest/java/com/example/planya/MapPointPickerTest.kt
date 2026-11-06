package com.example.planya

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.planya.domain.model.GeoPoint
import com.example.planya.domain.model.LocatedArea
import com.example.planya.data.location.LocationRepository
import com.example.planya.domain.model.LocationSuggestion
import com.example.planya.presentation.components.MapPointPicker
import com.example.planya.ui.theme.PlanesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Superficie inyectada: estas pruebas no consumen Maps ni Places.
@RunWith(AndroidJUnit4::class)
class MapPointPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun requiresExplicitPointAndLabelBeforeConfirming() {
        var result: LocatedArea? = null
        val point = GeoPoint(-12.12, -77.03)
        compose.setContent { PlanesTheme {
            MapPointPicker(null, { result = it }, {}, mapContent = { _, _, choose ->
                Button(onClick = { choose(point) }) { Text("Seleccionar punto de prueba") }
            })
        } }
        compose.onNodeWithText("Elegir este punto").assertIsNotEnabled()
        compose.onNodeWithText("Seleccionar punto de prueba").performClick()
        compose.onNodeWithText("Elegir este punto").assertIsNotEnabled()
        compose.onNodeWithTag("map-address").performScrollTo().performTextInput("Entrada del parque")
        compose.runOnIdle { assertNull(result) }
        compose.onNodeWithText("Elegir este punto").performClick()
        compose.runOnIdle {
            assertEquals(point, result?.point)
            assertEquals("Entrada del parque", result?.displayAddress)
            assertTrue(result?.manuallySelected == true)
            assertNull(result?.googlePlaceId)
        }
    }

    @Test fun closingDoesNotChangeLocation() {
        var dismissed = false
        var result: LocatedArea? = null
        compose.setContent { PlanesTheme {
            MapPointPicker(null, { result = it }, { dismissed = true }, mapContent = { _, _, _ -> Text("Mapa de prueba") })
        } }
        compose.onNodeWithContentDescription("Cerrar mapa").performClick()
        compose.runOnIdle { assertTrue(dismissed); assertNull(result) }
    }

    @Test fun tappingMapResolvesStreetWithoutMovingSelectedPoint() {
        val point = GeoPoint(-12.11, -77.02)
        var result: LocatedArea? = null
        val repository = object : LocationRepository {
            override suspend fun current(): LocatedArea = error("No se debe consultar GPS en esta prueba")
            override suspend fun suggestions(query: String, origin: LocatedArea?, nearbyOnly: Boolean) = emptyList<LocationSuggestion>()
            override suspend fun addressAt(point: GeoPoint) = LocatedArea(point, "Calle encontrada", false,
                fullAddress = "Av. de Prueba 456", street = "Av. de Prueba", streetNumber = "456", manuallySelected = true)
        }
        compose.setContent { PlanesTheme {
            MapPointPicker(null, { result = it }, {}, source = repository, allowGoogle = false,
                mapContent = { _, _, choose -> Button(onClick = { choose(point) }) { Text("Tocar mapa") } })
        } }
        compose.onNodeWithText("Tocar mapa").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Av. de Prueba 456").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Elegir este punto").performClick()
        compose.runOnIdle {
            assertEquals(point, result?.point)
            assertEquals("Av. de Prueba 456", result?.displayAddress)
            assertEquals("456", result?.streetNumber)
        }
    }

    @Test fun mapUsesRemainingHeightAndConfirmationStaysVisible() {
        compose.setContent { PlanesTheme {
            MapPointPicker(null, {}, {}, mapContent = { _, _, _ ->
                Surface(Modifier.fillMaxSize(), color = Color(0xFFE3E9E6)) { Text("Mapa (superficie aislada de prueba)") }
            })
        } }
        compose.onNodeWithText("Elegir este punto").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("point-map").fetchSemanticsNode().boundsInRoot
        // Evita regresar al mapa fijo pequeño y al gran espacio vacío anterior.
        assertTrue("El mapa debe ocupar una altura útil", bounds.height > 500f)
        val root = compose.onNodeWithText("Ubicación del encuentro").fetchSemanticsNode()
        assertTrue(root.boundsInRoot.top < bounds.top)
        val screenshot = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "map-picker-layout.png")
        file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
