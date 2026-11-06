package com.example.planya

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.core.network.AuthConfig
import com.example.planya.data.auth.CognitoGoogleAuth
import com.example.planya.presentation.screens.access.*
import com.example.planya.ui.theme.PlanesTheme
import net.openid.appauth.AuthorizationResponse
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/** Nunca abre Google, intercambia códigos ni crea usuarios en AWS. */
@RunWith(AndroidJUnit4::class)
class GoogleAuthFlowTest {
    @get:Rule val compose = createComposeRule()
    @Test fun requestUsesCognitoCodeFlowAndGoogleProviderWithoutClientSecret() {
        val request = CognitoGoogleAuth.request()
        assertEquals(AuthConfig.CLIENT_ID, request.clientId)
        assertEquals(AuthConfig.REDIRECT_URI, request.redirectUri.toString())
        assertEquals("code", request.responseType)
        assertEquals("Google", request.additionalParameters["identity_provider"])
        assertEquals(setOf("openid", "email", "profile"), request.scope!!.split(" ").toSet())
        assertFalse(request.toUri().queryParameterNames.contains("client_secret"))
    }
    @Test fun pkceStateAndNonceAreFreshAndChallengeMatchesVerifier() {
        val first = CognitoGoogleAuth.request()
        val second = CognitoGoogleAuth.request()
        assertNotNull(first.state); assertNotNull(first.nonce)
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.nonce, second.nonce)
        assertNotEquals(first.codeVerifier, second.codeVerifier)
        val digest = MessageDigest.getInstance("SHA-256").digest(first.codeVerifier!!.toByteArray(Charsets.US_ASCII))
        assertEquals(Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), first.codeVerifierChallenge)
        assertEquals("S256", first.codeVerifierChallengeMethod)
    }
    @Test fun callbackMustMatchPendingTransactionAndCannotBeStale() {
        val expected = CognitoGoogleAuth.request()
        val valid = AuthorizationResponse.Builder(expected).setState(expected.state).setAuthorizationCode("fake-no-network").build()
        CognitoGoogleAuth.validate(expected, valid, 1000)
        assertTrue(runCatching { CognitoGoogleAuth.validate(expected, valid, 600_001) }.isFailure)
        val wrongState = AuthorizationResponse.Builder(expected).setState("wrong-state").setAuthorizationCode("fake").build()
        assertTrue(runCatching { CognitoGoogleAuth.validate(expected, wrongState, 1000) }.isFailure)
        val other = CognitoGoogleAuth.request()
        val wrongRequest = AuthorizationResponse.Builder(other).setState(expected.state).setAuthorizationCode("fake").build()
        assertTrue(runCatching { CognitoGoogleAuth.validate(expected, wrongRequest, 1000) }.isFailure)
    }
    @Test fun androidRegistersExactCallbackNotArbitraryPaths() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun handler(uri: String) = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(context.packageName)
                .addCategory(Intent.CATEGORY_BROWSABLE), 0)?.activityInfo?.name
        assertEquals("net.openid.appauth.RedirectUriReceiverActivity", handler(AuthConfig.REDIRECT_URI))
        assertNull(handler("com.example.planya://auth/other"))
    }
    @Test fun googleButtonDoesNotRequireTypingAnEmailOrPassword() {
        var clicked = false
        compose.setContent { PlanesTheme { AccessScreen(AccountState(ready = true), {}, { _, _, _, _ -> }, {}, {},
            onGoogle = { clicked = true }) } }
        compose.onNodeWithText("Continuar con Google").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(clicked) }
    }
    private class IsolatedApplication(context: Context) : Application() {
        init { attachBaseContext(context) }
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("google_flow_test_$name", mode)
    }
    @Test fun cancellingClearsPendingStateAndCannotCreateASessionByReplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val states = SavedStateHandle()
        val store = ViewModelStore()
        lateinit var account: AccountViewModel
        compose.runOnIdle {
            account = AccountViewModel(IsolatedApplication(context), states)
            store.put("account", account)
        }
        compose.waitUntil(5_000) { account.state.value.ready }
        compose.runOnIdle {
            val request = account.beginGoogle()!!
            assertTrue(account.state.value.googleInProgress)
            assertTrue(states.contains("google_request"))
            account.googleResult(null)
            assertFalse(account.state.value.busy)
            assertFalse(states.contains("google_request"))
            val replay = AuthorizationResponse.Builder(request).setState(request.state).setAuthorizationCode("fake").build().toIntent()
            account.googleResult(replay)
            assertNull(account.state.value.session)
            store.clear()
        }
        context.getSharedPreferences("google_flow_test_user_preferences", 0).edit().clear().commit()
    }
}
