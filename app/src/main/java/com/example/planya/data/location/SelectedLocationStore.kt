package com.example.planya.data.location

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.planya.domain.model.GeoPoint
import com.example.planya.domain.model.LocatedArea
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SelectedLocationStore {
    suspend fun read(): LocatedArea?
    suspend fun save(area: LocatedArea)
    suspend fun googlePlaceId(): String? = null
}

// Preferencia de este dispositivo, no una actualización del perfil remoto.
// Una dirección precisa no se envía a las copias de seguridad ni se guarda en texto plano.
class DeviceSelectedLocationStore(
    context: Context,
    preferencesName: String = "selected_location",
    private val keyAlias: String = "planya_selected_location"
) : SelectedLocationStore {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val gson = Gson()

    override suspend fun read(): LocatedArea? = withContext(Dispatchers.IO) {
        runCatching {
            val data = preferences.getString("data", null) ?: return@withContext null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(preferences.getString("iv", null), Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val json = String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
            val area = gson.fromJson(json, LocatedArea::class.java)
            if (area.googlePlaceId != null) return@withContext null
            // Gson puede omitir los constructores: volver a validar datos persistidos.
            GeoPoint(area.point.latitude, area.point.longitude)
            require(area.label.isNotBlank())
            // Migrar preferencias anteriores: Gson omite los defaults de Kotlin.
            area.copy(googleAttributions = emptyList())
        }.getOrNull()
    }

    override suspend fun save(area: LocatedArea) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        // Guardar solo el ID de Google, nunca su dirección ni coordenadas en disco.
        val stored: Any = if (area.googlePlaceId != null) mapOf("googlePlaceId" to area.googlePlaceId) else area
        val data = cipher.doFinal(gson.toJson(stored).toByteArray(Charsets.UTF_8))
        check(preferences.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(data, Base64.NO_WRAP)).commit()) {
            "La dirección cambió, pero no se pudo conservar al cerrar la app. Vuelve a seleccionarla."
        }
    }

    override suspend fun googlePlaceId(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val data = preferences.getString("data", null) ?: return@withContext null
            val iv = Base64.decode(preferences.getString("iv", null), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val json = String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
            gson.fromJson(json, com.google.gson.JsonObject::class.java).get("googlePlaceId")
                ?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
