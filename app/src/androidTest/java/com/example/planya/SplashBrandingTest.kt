package com.example.planya

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.presentation.screens.splash.SplashScreen
import com.example.planya.ui.theme.PlanesTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

class SplashBrandingTest {
    @get:Rule val compose = createComposeRule()
    @Test fun splashShowsRealBrandAndSessionProgress() {
        compose.setContent { PlanesTheme { SplashScreen() } }
        compose.onNodeWithContentDescription("Logo de PlanYa").assertIsDisplayed()
        compose.onNodeWithText("Preparando tu sesión…").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "splash-branding.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
