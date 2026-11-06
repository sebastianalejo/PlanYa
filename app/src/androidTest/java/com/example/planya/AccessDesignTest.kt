package com.example.planya

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.presentation.screens.access.*
import com.example.planya.ui.theme.PlanesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AccessDesignTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun welcomeSeparatesGoogleFromEmailAndKeepsBothActionsWorking() {
        var google = 0
        var submissions = 0
        compose.setContent { PlanesTheme { AccessScreen(AccountState(ready = true), {},
            { _, _, _, _ -> submissions++ }, {}, {}, onGoogle = { google++ }) } }
        compose.onNodeWithContentDescription("Logo de PlanYa").assertIsDisplayed()
        compose.onNodeWithText("Conéctate al instante").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Correo electrónico")).assertDoesNotExist()
        capture("access-welcome.png")
        compose.onNodeWithText("Continuar con Google").performClick()
        compose.runOnIdle { assertEquals(1, google); assertEquals(0, submissions) }
        compose.onNodeWithText("Correo electrónico").performClick()
        compose.onNodeWithText("Ingresa con tu correo").assertExists()
        compose.onNode(hasSetTextAction() and hasText("Correo electrónico")).performScrollTo().performTextInput("fake@example.com")
        compose.onNode(hasSetTextAction() and hasText("Contraseña", substring = false)).performScrollTo().performTextInput("Synthetic-pass-123")
        compose.onNodeWithText("Ingresar").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, submissions) }
        capture("access-email.png")
        compose.onNodeWithText("Otras formas de ingresar").performScrollTo().performClick()
        compose.onNodeWithText("Conéctate al instante").assertExists()
        compose.onNodeWithText("Correo electrónico").performScrollTo().performClick()
        compose.onNodeWithText("Ingresar").performScrollTo().performClick()
        compose.onNodeWithText("Escribe tu contraseña.").assertExists()
    }
    @Test fun largeTextCanReachEmailAndExploreWithoutClippingActions() {
        var explored = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                PlanesTheme { AccessScreen(AccountState(ready = true), {}, { _, _, _, _ -> }, {}, { explored = true }) }
            }
        }
        compose.onNodeWithText("Correo electrónico").performScrollTo().performClick()
        compose.onNodeWithText("Ingresar").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Explorar planes sin cuenta").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(explored) }
    }
}
