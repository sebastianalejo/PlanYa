package com.example.planya

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.domain.model.*
import com.example.planya.presentation.screens.home.HomeScreen
import com.example.planya.presentation.screens.interests.InterestsScreen
import com.example.planya.presentation.screens.plandetail.PlanDetailScreen
import com.example.planya.presentation.screens.createplan.*
import com.example.planya.presentation.screens.chat.*
import com.example.planya.presentation.screens.benefits.*
import com.example.planya.presentation.screens.map.*
import com.example.planya.ui.theme.PlanesTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Capturas del codigo real ejecutado en Android, con datos de prueba aislados.
 * No inicia sesion, publica planes, envia mensajes ni modifica AWS. */
class T3EvidenceCaptureTest {
    @get:Rule val compose = createComposeRule()
    private val now = System.currentTimeMillis() / 1000
    private val point = GeoPoint(-12.1211, -77.0297)
    private val area = LocatedArea(point, "Parque Kennedy, Miraflores", false)
    private val plan = Plan("t3-evidence", "Andrea de prueba", timeAgo = "", title = "Un café por Miraflores",
        description = "Conversemos y compartamos un café cerca del parque.", category = PlanCategory.CAFE,
        coordinates = point, meetingPlace = "Parque Kennedy, Miraflores", expiresAt = now + 3600,
        startsAt = now + 900, capacity = 5, ownerId = "host", attendees = listOf("Andrea de prueba"), attendeeIds = listOf("host"))
    private fun capture(name: String) {
        compose.waitForIdle()
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        File(app.cacheDir, "t3-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun interests() {
        compose.setContent { PlanesTheme { InterestsScreen(setOf("CAFE", "CAMINATA")) {} } }
        capture("interests")
    }
    @Test fun homeAndNavigation() {
        compose.setContent { PlanesTheme {
            HomeScreen(listOf(plan, plan.copy(id = "walk", title = "Caminata por el parque", category = PlanCategory.CAMINATA)),
                false, false, null, false, {}, area, now, {}, {}, {}, {}, {}, {}, userId = "viewer")
        } }
        capture("home")
    }
    @Test fun detail() {
        compose.setContent { PlanesTheme {
            PlanDetailScreen(plan, false, null, {}, area, now, {}, {}, {}, {}, "viewer", false, {}, {}, {}, {})
        } }
        capture("detail")
    }
    @Test fun editor() {
        compose.setContent { PlanesTheme {
            PlanEditorScreen(EditorState(), false, area, {}, {}, {}, {})
        } }
        capture("create")
    }
    @Test fun chat() {
        compose.setContent { PlanesTheme {
            ChatScreen(ChatUiState(planTitle = plan.title, loaded = true, loading = false, chatExpiresAt = now + 7200,
                messages = listOf(
                    ChatMessage("m1", plan.id, "host", "Andrea de prueba", "Nos vemos en la entrada del parque.", now * 1000 - 60000, "1"),
                    ChatMessage("m2", plan.id, "viewer", "Luis de prueba", "¡Perfecto! Ya estoy llegando.", now * 1000 - 30000, "2"))),
                "viewer", "host", plan.title, {}, {}, {}, {}, {}, {}, {}, nowMillis = { now * 1000 })
        } }
        capture("chat")
    }
    @Test fun benefits() {
        val coupon = Benefit("t3", "viewer", "Local de prueba PlanYa", "PRUEBA de beneficios", "Sin descuento real",
            conditions = "Solo prueba técnica. Sin valor comercial.", validUntil = now + 3600, status = BenefitStatus.DISPONIBLE)
        compose.setContent { PlanesTheme {
            BenefitsScreen(BenefitsUiState(benefits = listOf(coupon), loaded = true), now, {}, {}, {}, {}, {})
        } }
        capture("benefits")
    }
    @Test fun map() {
        compose.setContent { PlanesTheme {
            MapScreen(MapUiState(plans = listOf(plan), hasLoaded = true), area, now,
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.waitForIdle()
        Thread.sleep(4500)
        capture("map")
    }
}
