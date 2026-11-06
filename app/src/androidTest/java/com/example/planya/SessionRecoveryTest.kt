package com.example.planya

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.core.network.AuthConfig
import com.example.planya.data.auth.*
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** Todas las credenciales son sintéticas; no usa preferencias ni KeyStore reales. */
class SessionRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = 1_700_000_000L
    private class MemoryStore(var value: UserSession?) : SessionStore {
        var clears = 0
        override suspend fun read() = value
        override suspend fun write(value: UserSession) { this.value = value }
        override suspend fun clear() { clears++; value = null }
    }
    private fun jwt(user: String = "synthetic-user", expiry: Long = now + 3600): String {
        val body = JsonObject().apply {
            addProperty("iss", AuthConfig.issuer); addProperty("aud", AuthConfig.CLIENT_ID)
            addProperty("token_use", "id"); addProperty("exp", expiry); addProperty("sub", user)
            addProperty("name", "Cuenta de prueba"); addProperty("email", "fake@example.com")
        }
        return "header." + Base64.encodeToString(body.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".synthetic-signature"
    }
    private fun session(oauth: Boolean = false, expiry: Long = now - 10) = UserSession(jwt(expiry = expiry), "fake-access", "fake-refresh", expiry,
        "synthetic-user", "Cuenta de prueba", "fake@example.com", oauth)
    private fun result(user: String = "synthetic-user") = JsonObject().apply {
        addProperty("IdToken", jwt(user)); addProperty("AccessToken", "fake-renewed-access")
    }
    private fun failure(code: String, status: Int = 400, oauth: Boolean = false) = HttpException(Response.error<JsonObject>(status,
        """{"${if (oauth) "error" else "__type"}":"$code"}""".toResponseBody()))
    private fun native(block: suspend (String, Map<String, Any>) -> JsonObject) = object : CognitoApi {
        override suspend fun request(target: String, body: Map<String, Any>) = block(target, body)
    }
    private fun google(block: suspend () -> JsonObject) = object : CognitoOAuthApi {
        override suspend fun refresh(grant: String, client: String, refresh: String) = block()
    }

    @Test fun validStoredSessionDoesNotContactCognito() = runBlocking {
        val store = MemoryStore(session(expiry = now + 1000))
        val repo = AuthRepository(context, native { _, _ -> error("No debe consultar") }, google { error("No debe consultar") }, store) { now }
        assertEquals("synthetic-user", repo.restore()?.userId)
        assertEquals(store.value?.idToken, repo.token())
        assertTrue(runCatching { repo.token("different-user") }.exceptionOrNull() is AuthSessionRequiredException)
        assertNotNull(store.value); assertEquals(0, store.clears)
    }
    @Test fun nativeRefreshKeepsRefreshTokenAndSerializesConcurrentRequests() = runBlocking {
        var calls = 0
        var clock = now
        val store = MemoryStore(session(expiry = now + 1000))
        val repo = AuthRepository(context, native { target, body ->
            calls++; assertTrue(target.endsWith("GetTokensFromRefreshToken")); assertEquals("fake-refresh", body["RefreshToken"])
            delay(20)
            JsonObject().apply { add("AuthenticationResult", result()) }
        }, google { error("No Google") }, store) { clock }
        repo.restore()
        clock = now + 950
        coroutineScope { List(3) { async { repo.token() } }.awaitAll() }
        assertEquals(1, calls)
        assertEquals("fake-refresh", store.value?.refreshToken)
        assertEquals(0, store.clears)
    }
    @Test fun throttlingAndNetworkFailureDoNotEraseStoredSession() = runBlocking {
        for (transientFailure in listOf(failure("TooManyRequestsException"), IOException("Offline"), failure("InternalErrorException", 500))) {
            val store = MemoryStore(session())
            val repo = AuthRepository(context, native { _, _ -> throw transientFailure }, google { error("No Google") }, store) { now }
            assertTrue(runCatching { repo.restore() }.isFailure)
            assertNotNull(store.value); assertEquals(0, store.clears)
        }
    }
    @Test fun definitivelyExpiredNativeSessionIsCleared() = runBlocking {
        val store = MemoryStore(session())
        val repo = AuthRepository(context, native { _, _ -> throw failure("NotAuthorizedException") }, google { error("No Google") }, store) { now }
        assertTrue(runCatching { repo.restore() }.exceptionOrNull() is AuthSessionRequiredException)
        assertNull(store.value); assertEquals(1, store.clears)
    }
    @Test fun googleInvalidGrantIsDifferentFromInvalidClientConfiguration() = runBlocking {
        for (code in listOf("invalid_grant", "invalid_client")) {
            val store = MemoryStore(session(oauth = true))
            val repo = AuthRepository(context, native { _, _ -> error("No nativo") }, google { throw failure(code, if (code == "invalid_client") 401 else 400, oauth = true) }, store) { now }
            val error = runCatching { repo.restore() }.exceptionOrNull()
            if (code == "invalid_grant") { assertTrue(error is AuthSessionRequiredException); assertNull(store.value) }
            else { assertTrue(error is CognitoRequestException); assertNotNull(store.value); assertEquals(0, store.clears) }
        }
    }
    @Test fun refreshCannotReplaceSessionWithAnotherUser() = runBlocking {
        val store = MemoryStore(session())
        val before = store.value
        val repo = AuthRepository(context, native { _, _ -> JsonObject().apply { add("AuthenticationResult", result("different-user")) } }, google { error("No Google") }, store) { now }
        assertTrue(runCatching { repo.restore() }.isFailure)
        assertEquals(before, store.value); assertEquals(0, store.clears)
    }
    @Test fun googleRefreshPreservesProviderAndOptionalRefreshToken() = runBlocking {
        val store = MemoryStore(session(oauth = true))
        val repo = AuthRepository(context, native { _, _ -> error("No nativo") }, google {
            JsonObject().apply { addProperty("id_token", jwt()); addProperty("access_token", "fake-access") }
        }, store) { now }
        val restored = repo.restore()!!
        assertTrue(restored.oauth); assertEquals("fake-refresh", restored.refreshToken)
    }
    @Test fun registrationVerificationAndPasswordRecoveryUseExpectedOperations() = runBlocking {
        val operations = mutableListOf<String>()
        val repo = AuthRepository(context, native { target, body ->
            operations += target.substringAfterLast('.')
            assertEquals(AuthConfig.CLIENT_ID, body["ClientId"])
            JsonObject().apply { addProperty("UserConfirmed", false) }
        }, google { error("No Google") }, MemoryStore(null)) { now }
        assertFalse(repo.register("Nombre", "fake@example.com", "Synthetic-pass-123"))
        repo.confirm("fake@example.com", "123456"); repo.resend("fake@example.com")
        repo.forgot("fake@example.com"); repo.reset("fake@example.com", "123456", "Synthetic-pass-456")
        assertEquals(listOf("SignUp", "ConfirmSignUp", "ResendConfirmationCode", "ForgotPassword", "ConfirmForgotPassword"), operations)
    }
    @Test fun nativeLoginUsesPublicClientAndStoresOnlyReturnedSession() = runBlocking {
        val store = MemoryStore(null)
        val repo = AuthRepository(context, native { _, body ->
            assertEquals("USER_PASSWORD_AUTH", body["AuthFlow"])
            assertEquals(AuthConfig.CLIENT_ID, body["ClientId"])
            JsonObject().apply { add("AuthenticationResult", result().apply { addProperty("RefreshToken", "fake-native-refresh") }) }
        }, google { error("No Google") }, store) { now }
        val session = repo.login("fake@example.com", "Synthetic-password-123")
        assertEquals("synthetic-user", session.userId)
        assertEquals("fake-native-refresh", store.value?.refreshToken)
        assertFalse(store.value.toString().contains("Synthetic-password-123"))
    }
    @Test fun rotatedNativeRefreshTokenReplacesTheOldOne() = runBlocking {
        val store = MemoryStore(session())
        val repo = AuthRepository(context, native { _, _ ->
            JsonObject().apply { add("AuthenticationResult", result().apply { addProperty("RefreshToken", "fake-rotated-refresh") }) }
        }, google { error("No Google") }, store) { now }
        repo.restore()
        assertEquals("fake-rotated-refresh", store.value?.refreshToken)
    }
}
