package com.example.planya.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.planya.core.network.AuthConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Field
import net.openid.appauth.TokenResponse
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CognitoApi {
    @POST("/")
    @Headers("Content-Type: application/x-amz-json-1.1")
    suspend fun request(@Header("X-Amz-Target") target: String, @Body body: Map<String, @JvmSuppressWildcards Any>): JsonObject
}
interface CognitoOAuthApi {
    @FormUrlEncoded @POST("oauth2/token")
    suspend fun refresh(@Field("grant_type") grant: String, @Field("client_id") client: String,
        @Field("refresh_token") refresh: String): JsonObject
}

data class UserSession(val idToken: String, val accessToken: String, val refreshToken: String,
    val expiresAt: Long, val userId: String, val name: String, val email: String,
    val oauth: Boolean = false)
class AuthSessionRequiredException : IllegalStateException("Tu sesión venció. Vuelve a ingresar.")

class AuthRepository(context: Context, nativeApi: CognitoApi? = null, googleApi: CognitoOAuthApi? = null,
    private val sessionStore: SessionStore? = null, private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private val preferences = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val mutex = Mutex()
    private var session: UserSession? = null
    private val api = nativeApi ?: Retrofit.Builder().baseUrl("https://cognito-idp.${AuthConfig.REGION}.amazonaws.com/")
        .client(OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build())
        .addConverterFactory(GsonConverterFactory.create()).build().create(CognitoApi::class.java)
    private val oauthApi = googleApi ?: Retrofit.Builder().baseUrl("${AuthConfig.DOMAIN}/")
        .client(OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build())
        .addConverterFactory(GsonConverterFactory.create()).build().create(CognitoOAuthApi::class.java)

    suspend fun acceptGoogle(tokens: TokenResponse, nonce: String): UserSession = mutex.withLock {
        val result = JsonObject().apply {
            addProperty("IdToken", tokens.idToken ?: error("Google no devolvió una sesión de Cognito."))
            addProperty("AccessToken", tokens.accessToken ?: error("Falta el token de acceso."))
            addProperty("RefreshToken", tokens.refreshToken ?: error("Falta el token para conservar la sesión."))
        }
        val id = tokens.idToken!!
        val payload = gson.fromJson(String(Base64.decode(id.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP)), JsonObject::class.java)
        check(payload.get("nonce")?.asString == nonce) { "No se pudo validar el acceso con Google." }
        save(parseSession(result).copy(oauth = true))
    }

    suspend fun restore(): UserSession? = mutex.withLock {
        if (!AuthConfig.configured) return@withLock null
        session = sessionStore?.read() ?: if (sessionStore == null) withContext(Dispatchers.IO) { readStored() } else null
        if (session == null) return@withLock null
        if (session!!.expiresAt > nowSeconds() + 60) session
        else refresh()
    }

    suspend fun token(expectedUserId: String? = null): String = mutex.withLock {
        val current = session ?: throw AuthSessionRequiredException()
        if (expectedUserId != null && expectedUserId != current.userId) throw AuthSessionRequiredException()
        if (current.expiresAt <= nowSeconds() + 60) refresh().idToken else current.idToken
    }

    suspend fun login(email: String, password: String): UserSession = mutex.withLock {
        val result = call("InitiateAuth", mapOf("AuthFlow" to "USER_PASSWORD_AUTH",
            "AuthParameters" to mapOf("USERNAME" to email.trim(), "PASSWORD" to password)))
        check(result.has("AuthenticationResult")) { "La cuenta requiere completar un paso de acceso en Cognito." }
        save(parseSession(result.getAsJsonObject("AuthenticationResult")))
    }

    suspend fun register(name: String, email: String, password: String): Boolean {
        val response = call("SignUp", mapOf("Username" to email.trim(), "Password" to password,
            "UserAttributes" to listOf(mapOf("Name" to "email", "Value" to email.trim()), mapOf("Name" to "name", "Value" to name.trim()))))
        return response.get("UserConfirmed")?.asBoolean == true
    }
    suspend fun confirm(email: String, code: String) { call("ConfirmSignUp", mapOf("Username" to email.trim(), "ConfirmationCode" to code.trim())) }
    suspend fun resend(email: String) { call("ResendConfirmationCode", mapOf("Username" to email.trim())) }
    suspend fun forgot(email: String) { call("ForgotPassword", mapOf("Username" to email.trim())) }
    suspend fun reset(email: String, code: String, password: String) {
        call("ConfirmForgotPassword", mapOf("Username" to email.trim(), "ConfirmationCode" to code.trim(), "Password" to password))
    }
    suspend fun clearExpiredSession(expectedToken: String?) = mutex.withLock {
        // No borrar un ingreso nuevo si un error antiguo llega tarde.
        if (session != null && session?.idToken != expectedToken) return@withLock
        session = null
        clearStored()
    }
    suspend fun logout() = mutex.withLock {
        val old = session
        session = null
        if (sessionStore != null) sessionStore.clear() else withContext(Dispatchers.IO) {
            preferences.edit().clear().commit()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("planya_session") }
        }
        if (old != null) try { call("RevokeToken", mapOf("Token" to old.refreshToken)) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { /* La sesión local ya se eliminó aunque no haya red. */ }
    }

    private suspend fun refresh(): UserSession {
        val old = session ?: throw AuthSessionRequiredException()
        return try {
            if (old.oauth) {
                val result = try { oauthApi.refresh("refresh_token", AuthConfig.CLIENT_ID, old.refreshToken) }
                    catch (failure: HttpException) { throw cognitoFailure(failure) }
                val normalized = JsonObject().apply {
                    add("IdToken", result.get("id_token"))
                    add("AccessToken", result.get("access_token"))
                    result.get("refresh_token")?.let { add("RefreshToken", it) }
                }
                return save(parseSession(normalized, old.refreshToken, old.userId).copy(oauth = true))
            }
            val response = call("GetTokensFromRefreshToken", mapOf("RefreshToken" to old.refreshToken))
            save(parseSession(response.getAsJsonObject("AuthenticationResult"), old.refreshToken, old.userId))
        } catch (error: CognitoRequestException) {
            val expired = if (old.oauth) error.code == "invalid_grant" else error.code in setOf("NotAuthorizedException", "UserNotFoundException", "RefreshTokenReuseException")
            if (expired) {
                session = null
                clearStored()
                throw AuthSessionRequiredException()
            }
            throw error
        }
    }

    private suspend fun call(action: String, values: Map<String, Any>): JsonObject {
        check(AuthConfig.configured) { "El acceso por cuenta aún no está habilitado. Puedes explorar los planes." }
        return try { api.request("AWSCognitoIdentityProviderService.$action", values + ("ClientId" to AuthConfig.CLIENT_ID)) }
        catch (failure: HttpException) { throw cognitoFailure(failure) }
    }
    private fun parseSession(result: JsonObject, previousRefresh: String = "", expectedUserId: String? = null): UserSession {
        val id = result.get("IdToken").asString
        // Se leen atributos para la interfaz. La autorización real se verifica en API Gateway.
        val payload = gson.fromJson(String(Base64.decode(id.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP)), JsonObject::class.java)
        check(payload.get("iss")?.asString == AuthConfig.issuer &&
            payload.get("aud")?.asString == AuthConfig.CLIENT_ID &&
            payload.get("token_use")?.asString == "id" && payload.get("exp")?.asLong?.let {
                it > nowSeconds()
            } == true) { "La sesión recibida no pertenece a PlanYa o ya venció." }
        check(expectedUserId == null || payload.get("sub")?.asString == expectedUserId) { "La renovación no corresponde a la misma cuenta. No se reemplazó tu sesión." }
        val access = result.get("AccessToken")?.asString.orEmpty()
        val refresh = result.get("RefreshToken")?.takeUnless { it.isJsonNull }?.asString ?: previousRefresh
        check(access.isNotBlank() && refresh.isNotBlank() && payload.get("sub")?.asString?.isNotBlank() == true) { "Cognito devolvió una sesión incompleta. Vuelve a ingresar." }
        return UserSession(id, access, refresh,
            payload.get("exp").asLong, payload.get("sub").asString, payload.get("name")?.asString ?: "Usuario PlanYa",
            payload.get("email")?.asString.orEmpty())
    }
    private suspend fun save(value: UserSession): UserSession {
        if (sessionStore != null) sessionStore.write(value) else withContext(Dispatchers.IO) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(gson.toJson(value).toByteArray(Charsets.UTF_8))
            check(preferences.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "No se pudo guardar la sesión." }
        }
        session = value
        return value
    }
    private suspend fun clearStored() {
        if (sessionStore != null) sessionStore.clear()
        else withContext(Dispatchers.IO) { check(preferences.edit().clear().commit()) { "No se pudo limpiar la sesión local." } }
    }
    private fun readStored(): UserSession? = runCatching {
        val data = preferences.getString("data", null) ?: return null
        val iv = Base64.decode(preferences.getString("iv", null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        gson.fromJson(String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8), UserSession::class.java)
    }.getOrNull()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("planya_session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("planya_session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
