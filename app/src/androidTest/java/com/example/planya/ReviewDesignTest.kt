package com.example.planya

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.data.photos.PlanPhotoStore
import com.example.planya.domain.model.*
import com.example.planya.presentation.components.PlanReviewSheet
import com.example.planya.presentation.screens.createplan.PlanDraft
import com.example.planya.presentation.screens.home.HomeScreen
import com.example.planya.presentation.screens.plandetail.PlanDetailScreen
import com.example.planya.ui.theme.PlanesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReviewDesignTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val area = LocatedArea(GeoPoint(-12.1, -77.03), "Miraflores", false, fullAddress = "Av. del Parque 123, Miraflores")

    @Test fun reviewShowsPhotoAndKeepsPrimaryAndSecondaryActionsVisible() {
        val store = PlanPhotoStore(context)
        val (name, _) = store.newCameraFile()
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(75, 110, 95)) }
        store.file(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }; bitmap.recycle()
        var confirms = 0
        try {
            compose.setContent { PlanesTheme {
                PlanReviewSheet(PlanDraft(title = "Café y charla en el parque", description = "Un encuentro para conversar y conocer gente cerca.",
                    location = area, localPhotoName = name), false, { confirms++ }, {})
            } }
            compose.onNodeWithContentDescription("Foto del plan para revisar").assertExists()
            compose.onNodeWithText("Confirmar publicación").assertIsDisplayed()
            compose.onNodeWithText("Seguir editando").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, confirms) }
            val screenshot = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
            File(context.cacheDir, "plan-review-layout.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithText("Confirmar publicación").performClick()
            compose.runOnIdle { assertEquals(1, confirms) }
        } finally { store.delete(name) }
    }

    @Test fun largeTextAndLongDescriptionDoNotHideConfirmationButtons() {
        var dismissed = false
        compose.setContent { PlanesTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                PlanReviewSheet(PlanDraft(title = "Un plan para compartir", location = area,
                    description = "Información del encuentro. ".repeat(30)), true, {}, { dismissed = true })
            }
        } }
        compose.onNodeWithText("Confirmar cambios").assertIsDisplayed()
        compose.onNodeWithText("Seguir editando").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun ownerHasWorkingEditActionAndDistanceRequestsUserLocation() {
        var edits = 0
        var joins = 0
        var locations = 0
        val plan = Plan("mine", "Anfitrión", timeAgo = "", title = "Mi encuentro", ownerId = "me",
            attendees = listOf("Anfitrión"), attendeeIds = listOf("me"), capacity = 5, expiresAt = 1000, coordinates = area.point)
        compose.setContent { PlanesTheme {
            PlanDetailScreen(plan, false, null, {}, null, 100, { locations++ }, {}, {}, {},
                "me", false, { joins++ }, {}, { edits++ }, {})
        } }
        compose.onNodeWithText("Editar mi plan").assertIsEnabled().performClick()
        compose.onNodeWithText("Unirme ahora").assertDoesNotExist()
        compose.onNodeWithText("Elegir ubicación").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, edits); assertEquals(0, joins); assertEquals(1, locations) }
    }

    @Test fun homeStartsWithLocationAndKeepsAccountFiltersBelowIt() {
        compose.setContent { PlanesTheme {
            HomeScreen(emptyList(), false, false, null, false, {}, area, 100, {}, {}, {}, {}, {}, {}, userId = "me")
        } }
        val location = compose.onNodeWithText("UBICACIÓN").fetchSemanticsNode().boundsInRoot.top
        val tabs = compose.onNodeWithText("Explorar").fetchSemanticsNode().boundsInRoot.top
        assertTrue(location < tabs)
    }
}
