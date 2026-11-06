package com.example.planya

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.data.location.DeviceSelectedLocationStore
import com.example.planya.domain.model.GeoPoint
import com.example.planya.domain.model.LocatedArea
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@RunWith(AndroidJUnit4::class)
class SelectedLocationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "test_selected_location"
    private val alias = "test_planya_location"
    @After fun cleanup() {
        context.getSharedPreferences(name, 0).edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
    @Test fun confirmedAddressSurvivesRecreationAndIsNotStoredAsPlaintext() = runBlocking {
        val address = LocatedArea(GeoPoint(-12.1, -77.02), "Distrito", false,
            fullAddress = "Calle de prueba 123, Lima", street = "Calle de prueba", streetNumber = "123",
            reference = "Piso 2")
        DeviceSelectedLocationStore(context, name, alias).save(address)
        assertEquals(address, DeviceSelectedLocationStore(context, name, alias).read())
        val stored = context.getSharedPreferences(name, 0).all.values.joinToString()
        assertFalse(stored.contains("Calle de prueba"))
        assertFalse(stored.contains("Piso 2"))
    }
    @Test fun damagedStorageDoesNotCrashOrInventAnAddress() = runBlocking {
        context.getSharedPreferences(name, 0).edit().putString("data", "corrupto").commit()
        assertNull(DeviceSelectedLocationStore(context, name, alias).read())
    }
    @Test fun googleSelectionStoresOnlyPlaceIdAndReplacesPreviousNativeAddress() = runBlocking {
        val store = DeviceSelectedLocationStore(context, name, alias)
        val native = LocatedArea(GeoPoint(-12.1, -77.02), "Dirección privada anterior", false)
        store.save(native)
        store.save(native.copy(label = "Resultado de Google", fullAddress = "Dirección Google sin caché",
            googlePlaceId = "fake-id"))
        assertNull(store.read())
        assertEquals("fake-id", store.googlePlaceId())
        val prefs = context.getSharedPreferences(name, 0)
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(prefs.getString("iv", null), Base64.NO_WRAP)))
        val stored = String(cipher.doFinal(Base64.decode(prefs.getString("data", null), Base64.NO_WRAP)), Charsets.UTF_8)
        assertEquals("{\"googlePlaceId\":\"fake-id\"}", stored)
        store.save(native)
        assertNull(store.googlePlaceId())
        assertEquals(native, store.read())
    }
    @Test fun oldNativeSelectionMigratesNewFieldsWithoutCrashingComposition() = runBlocking {
        val native = LocatedArea(GeoPoint(-12.1, -77.02), "Ubicación de la versión anterior", false)
        val store = DeviceSelectedLocationStore(context, name, alias)
        store.save(native)
        val legacy = com.google.gson.Gson().toJsonTree(native).asJsonObject.apply { remove("googleAttributions") }
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(legacy.toString().toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(name, 0).edit()
            .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()
        val restored = store.read()!!
        assertEquals(native, restored)
        assertEquals(native.hashCode(), restored.hashCode())
        assertTrue(restored.googleAttributions.isEmpty())
    }
}
