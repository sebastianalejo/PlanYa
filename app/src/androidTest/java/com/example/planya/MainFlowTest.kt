package com.example.planya

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.domain.model.*
import com.example.planya.domain.repository.PlanRepository
import com.example.planya.domain.usecase.plan.*
import com.example.planya.presentation.navigation.AppNavigation
import com.example.planya.presentation.screens.access.AccountViewModel
import com.example.planya.presentation.screens.home.HomeViewModel
import com.example.planya.ui.theme.PlanesTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainFlowTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private class IsolatedApplication(context: Context) : Application() {
        init { attachBaseContext(context) }
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("flow_test_$name", mode)
    }
    @After fun finish() {
        store.clear()
        context.getSharedPreferences("flow_test_user_preferences", 0).edit().clear().commit()
    }
    @Test fun guestCanChooseInterestsBrowseDraftAndReturnWithoutPublishing() {
        // Preferencias aisladas y datos en memoria: no altera cuentas ni DynamoDB.
        val plan = Plan("p1", "Organizador", timeAgo = "Hoy", title = "Café de prueba",
            category = PlanCategory.CAFE, meetingPlace = "Av. Larco 123, Miraflores",
            expiresAt = System.currentTimeMillis() / 1000 + 3600, capacity = 5)
        val source = object : PlanRepository {
            override suspend fun getPlans(nextKey: String?) = PlanPage(listOf(plan), null)
            override suspend fun getPlan(id: String) = plan
        }
        val home = HomeViewModel(GetPlansUseCase(source), GetPlanUseCase(source))
        val account = AccountViewModel(IsolatedApplication(context))
        store.put("home", home); store.put("account", account)
        compose.setContent { PlanesTheme { AppNavigation(home, account) } }
        compose.waitUntil(8_000) { compose.onAllNodesWithText("Explorar planes sin cuenta").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Explorar planes sin cuenta").performScrollTo().performClick()
        compose.onNodeWithText("Café").performClick()
        compose.onNodeWithText("Continuar").performScrollTo().performClick()
        compose.onNodeWithText("Planes disponibles").assertExists()
        compose.onNodeWithText("Perfil").performClick()
        compose.onNodeWithText("Mi perfil").assertIsDisplayed()
        compose.onNodeWithText("Mis intereses").performScrollTo().performClick()
        compose.onNodeWithText("Continuar").performScrollTo().performClick()
        compose.onNodeWithText("Mi perfil").assertIsDisplayed()
        compose.onNodeWithText("Inicio").performClick()
        compose.onNodeWithText("Planes disponibles").assertExists()
        compose.onNodeWithText("Crear").performClick()
        compose.onNodeWithText("Crear un plan").assertExists()
        compose.onNodeWithText("Revisar plan").performClick()
        compose.onNodeWithText("Escribe un título de hasta 120 caracteres.").assertExists()
        compose.onNode(hasSetTextAction() and hasText("Título del plan")).performTextInput("Mi plan")
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithText("¿Descartar cambios?").assertExists()
        compose.onNodeWithText("Seguir editando").performClick()
        compose.onNodeWithText("Crear un plan").assertExists()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithText("Descartar").performClick()
        compose.onNodeWithText("Planes disponibles").assertExists()
        compose.onNodeWithText("Ver detalles").performScrollTo().performClick()
        compose.onNodeWithText("Detalle del plan").assertExists()
        compose.onNodeWithText("Unirme ahora").performClick()
        compose.onNodeWithText("Conéctate al instante").assertExists()
        compose.onNodeWithText("Quiero crear una cuenta").performScrollTo().performClick()
        compose.onNodeWithText("Crea tu cuenta").assertExists()
        compose.onNodeWithText("Volver a ingresar").performScrollTo().performClick()
        compose.onNodeWithText("Conéctate al instante").assertExists()
    }
}
