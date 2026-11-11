package com.example.planya

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.domain.model.PlanFeedScope
import com.example.planya.domain.model.Plan
import com.example.planya.data.profile.ProfileActivity
import com.example.planya.data.profile.ProfileConfidence
import com.example.planya.data.profile.ProfileVerification
import com.example.planya.presentation.screens.home.HomeScreen
import com.example.planya.presentation.screens.profile.ProfileScreen
import com.example.planya.ui.theme.PlanesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProfileScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(guest: Boolean = false, busy: Boolean = false,
        interests: () -> Unit = {}, location: () -> Unit = {},
        feed: (PlanFeedScope) -> Unit = {}, login: () -> Unit = {}, logout: () -> Unit = {}) {
        compose.setContent { PlanesTheme {
            ProfileScreen(if (guest) null else "Andrea Pérez", if (guest) null else "andrea@example.com",
                setOf("CAFE"), null, false, busy, null, {}, interests, location, feed, login, logout, {}, {})
        } }
    }

    @Test fun profileShowsRealFieldsAndSelectedNavigation() {
        show()
        compose.onNodeWithText("Mi perfil").assertIsDisplayed()
        compose.onNodeWithText("Andrea Pérez").assertIsDisplayed()
        compose.onNodeWithText("andrea@example.com").assertIsDisplayed()
        compose.onNodeWithText("Perfil").assertIsSelected()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, "profile-layout.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun optionsOpenTheirActualDestinations() {
        var selected: PlanFeedScope? = null
        var interests = 0
        var locations = 0
        show(interests = { interests++ }, location = { locations++ }, feed = { selected = it })
        compose.onNodeWithText("Mis planes").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(PlanFeedScope.OWNED, selected) }
        compose.onNodeWithText("Me uní").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(PlanFeedScope.JOINED, selected) }
        compose.onNodeWithText("Mis intereses").performScrollTo().performClick()
        compose.onNodeWithText("Mi ubicación").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, interests); assertEquals(1, locations) }
    }

    @Test fun logoutNeedsExplicitConfirmationAndCanBeCancelled() {
        var logouts = 0
        show(logout = { logouts++ })
        scrollTo("Cerrar sesión")
        compose.onNodeWithText("Cerrar sesión").performClick()
        compose.runOnIdle { assertEquals(0, logouts) }
        compose.onNodeWithText("Quedarme").performClick()
        compose.runOnIdle { assertEquals(0, logouts) }
        scrollTo("Cerrar sesión")
        compose.onNodeWithText("Cerrar sesión").performClick()
        compose.onNodeWithText("Sí, cerrar sesión").performClick()
        compose.runOnIdle { assertEquals(1, logouts) }
    }

    @Test fun guestIsInvitedToLoginAndHasNoLogoutAction() {
        var logins = 0
        show(guest = true, login = { logins++ }, feed = { fail("Un invitado no debe abrir un filtro privado") })
        compose.onNodeWithText("Ingresar o crear cuenta").performClick()
        compose.onNodeWithText("Mis planes").performScrollTo().performClick()
        compose.onNodeWithText("Me uní").performScrollTo().performClick()
        compose.onNodeWithText("Cerrar sesión").assertDoesNotExist()
        compose.runOnIdle { assertEquals(3, logins) }
    }

    @Test fun logoutInProgressDisablesAccountOptions() {
        show(busy = true)
        compose.onNodeWithText("Mis planes").performScrollTo().assertIsNotEnabled()
        scrollTo("Cerrando sesión…")
        compose.onNodeWithText("Cerrando sesión…").assertIsNotEnabled()
    }

    @Test fun requestedProfileFeedOpensTheCorrectHomeFilter() {
        var consumed = false
        val own = Plan("mine", "Yo", timeAgo = "", title = "Mi encuentro", ownerId = "me")
        val other = Plan("other", "Ana", timeAgo = "", title = "Otro encuentro", ownerId = "ana")
        compose.setContent { PlanesTheme {
            HomeScreen(listOf(own, other), false, false, null, false, {}, null, 100,
                {}, {}, {}, {}, {}, {}, userId = "me", requestedScope = PlanFeedScope.OWNED,
                onScopeApplied = { consumed = true })
        } }
        compose.onNodeWithText("Mi encuentro").assertExists()
        compose.onNodeWithText("Otro encuentro").assertDoesNotExist()
        compose.runOnIdle { assertTrue(consumed) }
    }

    @Test fun profileShowsServerActivityAndEmptyReputationWithoutInventingStars() {
        compose.setContent { PlanesTheme {
            ProfileScreen(name = "Andrea Pérez", email = "andrea@example.com", interests = setOf("CAFE"),
                area = null, hasSavedGoogleLocation = false, busy = false, error = null,
                onBack = {}, onInterests = {}, onLocation = {}, onFeed = {}, onLogin = {}, onLogout = {},
                onNavigate = {}, onCreate = {},
                verification = ProfileVerification("NONE"),
                activity = ProfileActivity("u1", organized = 2, joined = 1, attended = 0),
                confidence = ProfileConfidence("u1", count = 0, average = null))
        } }
        compose.onNodeWithText("Tu actividad").performScrollTo().assertExists()
        compose.onNodeWithText("Organizados").assertExists()
        compose.onNodeWithText("Asistencias").assertExists()
        compose.onNodeWithText("Sin valoraciones").performScrollTo().assertExists()
        compose.onNodeWithText("Sin verificación aprobada").assertExists()
        compose.onNodeWithContentDescription("Cuenta verificada").assertDoesNotExist()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, "profile-trust-empty.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun profileShowsOnlyRealAverageAndCountAndApprovedVerification() {
        compose.setContent { PlanesTheme {
            ProfileScreen(name = "Andrea Pérez", email = "andrea@example.com", interests = setOf("CAFE"),
                area = null, hasSavedGoogleLocation = false, busy = false, error = null,
                onBack = {}, onInterests = {}, onLocation = {}, onFeed = {}, onLogin = {}, onLogout = {},
                onNavigate = {}, onCreate = {},
                verification = ProfileVerification("VERIFIED", method = "equipo", verifiedAt = "2026-11-09T00:00:00+00:00"),
                activity = ProfileActivity("u1", organized = 3, joined = 2, attended = 1),
                confidence = ProfileConfidence("u1", count = 12, average = 4.58))
        } }
        compose.onNodeWithText("Verificación aprobada").assertExists()
        compose.onNodeWithContentDescription("Cuenta verificada").assertExists()
        compose.onNodeWithText("Sin valoraciones").assertDoesNotExist()
        compose.onNodeWithText("4.6").performScrollTo().assertExists()
        compose.onNodeWithText("· 12 valoraciones").assertExists()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, "profile-trust-rated.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun chatAndBenefitsAreHiddenUntilTheirRoutesExist() {
        compose.setContent { PlanesTheme {
            ProfileScreen(name = "Andrea Pérez", email = "andrea@example.com", interests = setOf("CAFE"),
                area = null, hasSavedGoogleLocation = false, busy = false, error = null,
                onBack = {}, onInterests = {}, onLocation = {}, onFeed = {}, onLogin = {}, onLogout = {},
                onNavigate = {}, onCreate = {})
        } }
        compose.onNodeWithText("Mis conversaciones").assertDoesNotExist()
        compose.onNodeWithText("Mis beneficios").assertDoesNotExist()
    }

    @Test fun chatAndBenefitsOpenTheirRealRoutesWhenProvided() {
        var chat = 0
        var benefits = 0
        compose.setContent { PlanesTheme {
            ProfileScreen(name = "Andrea Pérez", email = "andrea@example.com", interests = setOf("CAFE"),
                area = null, hasSavedGoogleLocation = false, busy = false, error = null,
                onBack = {}, onInterests = {}, onLocation = {}, onFeed = {}, onLogin = {}, onLogout = {},
                onNavigate = {}, onCreate = {}, onChat = { chat++ }, onBenefits = { benefits++ })
        } }
        compose.onNodeWithTag("perfil-lista").performScrollToNode(hasText("Mis conversaciones"))
        compose.onNodeWithText("Mis conversaciones").performClick()
        compose.onNodeWithTag("perfil-lista").performScrollToNode(hasText("Mis beneficios"))
        compose.onNodeWithText("Mis beneficios").performClick()
        compose.runOnIdle { assertEquals(1, chat); assertEquals(1, benefits) }
    }

    private fun scrollTo(text: String) {
        compose.onNodeWithTag("perfil-lista").performScrollToNode(hasText(text))
    }
}
