package com.example.planya.data

import com.example.planya.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class LocationSearchPolicyTest {
    @Test fun `house number one does not match house number twelve`() {
        val origin = LocatedArea(GeoPoint(-12.12, -77.03), "Lima", true, countryCode = "PE")
        val one = LocationSuggestion("Av Larco 1", "Lima, Perú", origin.copy(streetNumber = "1"))
        val twelve = LocationSuggestion("Av Larco 12", "Lima, Perú", origin.copy(streetNumber = "12"))
        assertEquals(listOf(one), LocationSearchPolicy.rank(listOf(twelve, one), "Larco 1", origin, true))
    }
    private val lima = LocatedArea(GeoPoint(-12.12, -77.03), "Miraflores, Lima", false,
        "Lima", "Perú", "PE")

    private fun suggestion(name: String, point: GeoPoint, countryCode: String, country: String) =
        LocationSuggestion(name, country, LocatedArea(point, name, true, country = country, countryCode = countryCode))

    @Test fun `local search never presents China as a nearby Hola result`() {
        val china = suggestion("Hola", GeoPoint(31.23, 121.47), "CN", "China")
        assertTrue(LocationSearchPolicy.rank(listOf(china), "Hola", lima, true).isEmpty())
        assertEquals(listOf(china), LocationSearchPolicy.rank(listOf(china), "Hola", lima, false))
    }

    @Test fun `same-name districts are filtered by country and proximity then sorted nearest first`() {
        val near = suggestion("Miraflores", GeoPoint(-12.121, -77.03), "PE", "Perú")
        val further = suggestion("Miraflores", GeoPoint(-12.20, -77.02), "PE", "Perú")
        val distantPeru = suggestion("Miraflores", GeoPoint(-5.2, -80.6), "PE", "Perú")
        val foreign = suggestion("Miraflores", GeoPoint(-16.5, -68.1), "BO", "Bolivia")
        assertEquals(listOf(near, further),
            LocationSearchPolicy.rank(listOf(foreign, distantPeru, further, near), "miraf", lima, true))
    }

    @Test fun `after moving region the search uses the new country instead of hardcoded Peru`() {
        val santiago = LocatedArea(GeoPoint(-33.45, -70.67), "Santiago", false,
            "Santiago", "Chile", "CL")
        val chile = suggestion("Centro", GeoPoint(-33.44, -70.67), "CL", "Chile")
        val peru = suggestion("Centro", lima.point, "PE", "Perú")
        assertEquals(listOf(chile), LocationSearchPolicy.rank(listOf(peru, chile), "centro", santiago, true))
        assertEquals("Centro, Santiago, Chile", LocationSearchPolicy.contextualQuery("Centro", santiago, true))
    }

    @Test fun `without GPS the pilot region is explicit and foreign results are excluded`() {
        val peru = suggestion("Miraflores", lima.point, "PE", "Perú")
        val foreign = suggestion("Miraflores", GeoPoint(-16.5, -68.1), "BO", "Bolivia")
        assertEquals(listOf(peru), LocationSearchPolicy.rank(listOf(foreign, peru), "Miraflores", null, true))
        assertEquals("Miraflores, Perú", LocationSearchPolicy.contextualQuery("Miraflores", null, true))
    }

    @Test fun `unrelated fallback city is not offered as a matching search`() {
        val city = suggestion("Lima", lima.point, "PE", "Perú")
        assertTrue(LocationSearchPolicy.rank(listOf(city), "Hola", lima, true).isEmpty())
    }

    @Test fun `accents partial words and duplicate responses are handled`() {
        val cafe = suggestion("Café Central", lima.point, "PE", "Perú")
        assertEquals(listOf(cafe), LocationSearchPolicy.rank(listOf(cafe, cafe), "cafe cent", lima, true))
        assertEquals("Miraflores, Lima, Perú", LocationSearchPolicy.contextualQuery("Miraflores, Lima, Perú", lima, true))
        assertEquals("Hola China", LocationSearchPolicy.contextualQuery("Hola China", lima, false))
    }
}
