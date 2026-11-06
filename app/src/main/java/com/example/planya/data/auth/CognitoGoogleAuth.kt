package com.example.planya.data.auth

import android.content.Context
import android.net.Uri
import com.example.planya.core.network.AuthConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import net.openid.appauth.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Google autentica en el navegador; Cognito entrega los tokens que usa PlanYa. */
object CognitoGoogleAuth {
    fun configuration() = AuthorizationServiceConfiguration(
        Uri.parse("${AuthConfig.DOMAIN}/oauth2/authorize"),
        Uri.parse("${AuthConfig.DOMAIN}/oauth2/token"))

    fun request(): AuthorizationRequest {
        check(AuthConfig.googleConfigured) { "Falta configurar Google en Cognito." }
        // AppAuth genera state, nonce y PKCE S256 aleatorios por intento.
        return AuthorizationRequest.Builder(configuration(), AuthConfig.CLIENT_ID,
            ResponseTypeValues.CODE, Uri.parse(AuthConfig.REDIRECT_URI))
            .setScopes("openid", "email", "profile")
            .setPrompt("select_account")
            .setAdditionalParameters(mapOf("identity_provider" to "Google"))
            .build()
    }

    fun validate(expected: AuthorizationRequest, response: AuthorizationResponse, ageMillis: Long) {
        require(ageMillis in 0..600_000) { "El intento de acceso venció. Vuelve a tocar Continuar con Google." }
        val received = response.request
        require(!response.authorizationCode.isNullOrBlank() &&
            expected.state != null && expected.state == response.state && expected.state == received.state &&
            expected.nonce == received.nonce && !expected.nonce.isNullOrBlank() &&
            expected.codeVerifier == received.codeVerifier && !expected.codeVerifier.isNullOrBlank() &&
            received.codeVerifierChallengeMethod == "S256" && received.clientId == AuthConfig.CLIENT_ID &&
            received.redirectUri.toString() == AuthConfig.REDIRECT_URI &&
            received.configuration.authorizationEndpoint == configuration().authorizationEndpoint &&
            received.configuration.tokenEndpoint == configuration().tokenEndpoint) {
            "No se pudo validar el regreso de Google. Intenta ingresar de nuevo."
        }
    }

    suspend fun exchange(context: Context, response: AuthorizationResponse): TokenResponse {
        val service = AuthorizationService(context)
        return try {
            withTimeout(30_000) {
                suspendCancellableCoroutine { continuation ->
                    service.performTokenRequest(response.createTokenExchangeRequest()) { tokens, _ ->
                        if (continuation.isActive) {
                            if (tokens != null) continuation.resume(tokens)
                            else continuation.resumeWithException(IllegalStateException(
                                "Cognito no pudo completar el acceso con Google. Revisa el proveedor, el cliente y la URL de retorno."))
                        }
                    }
                }
            }
        } finally { service.dispose() }
    }
}
