package com.example.planya

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.data.location.DeviceLocationRepository
import com.example.planya.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationSearchIntegrationTest {
    // Zona de prueba pública. No se consulta el GPS del teléfono ni se escribe en AWS.
    private val lima = LocatedArea(GeoPoint(-12.1211, -77.0297), "Miraflores, Lima", true, "Lima", "Perú", "PE")

    @Test fun localSuggestionsUseTheRealDeviceGeocoder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = DeviceLocationRepository(context)
        val results = repository.suggestions("Miraflores", lima, true)
        assertTrue("El geocodificador no devolvió Miraflores", results.isNotEmpty())
        assertTrue(results.all { it.area.countryCode == "PE" && lima.point.metersTo(it.area.point) <= 100_000 })
        val partial = repository.suggestions("Miraf", lima, true)
        assertTrue("La búsqueda parcial no devolvió sugerencias", partial.isNotEmpty())
        val unrelated = repository.suggestions("Hola", lima, true)
        assertTrue("Hola no debe seleccionar otro país como zona cercana",
            unrelated.all { it.area.countryCode == "PE" && lima.point.metersTo(it.area.point) <= 100_000 })
    }
}
