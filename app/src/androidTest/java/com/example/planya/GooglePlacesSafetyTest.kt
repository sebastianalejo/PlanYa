package com.example.planya

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.core.network.GooglePlacesConfig
import com.example.planya.data.location.GooglePlacePrediction
import com.example.planya.data.location.GooglePlacesService
import com.google.android.libraries.places.api.Places
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GooglePlacesSafetyTest {
    @Test fun disabledServiceRejectsRequestsBeforeInitializingGoogle() = runBlocking {
        // Nunca hace llamadas facturables: únicamente prueba el estado desactivado.
        val initialized = Places.isInitialized()
        val service = GooglePlacesService(InstrumentationRegistry.getInstrumentation().targetContext, enabled = false)
        assertEquals(initialized, Places.isInitialized())
        assertTrue(runCatching { service.predictions("Miraflores", null, true) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { service.select(GooglePlacePrediction("no-network", "Lugar", "Perú")) }
            .exceptionOrNull() is IllegalStateException)
        assertEquals(initialized, Places.isInitialized())
    }
}
