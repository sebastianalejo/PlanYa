package com.example.planya

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.planya.domain.model.*
import com.example.planya.presentation.screens.benefits.*
import com.example.planya.presentation.screens.profile.ProfileScreen
import com.example.planya.ui.theme.PlanesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BenefitsIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val coupon = Benefit("test", "u1", "Local de prueba", "Cupon de prueba", "10%",
        validUntil = 2000, status = BenefitStatus.DISPONIBLE)
    @Test fun filteredEmptyPageCanStillLoadNextPage() {
        var more = 0
        compose.setContent { PlanesTheme {
            BenefitsScreen(BenefitsUiState(loaded = true, nextKey = "next"), 100,
                {}, {}, {}, {}, { more++ })
        } }
        compose.onNodeWithText("Ver más").performClick()
        compose.runOnIdle { assertEquals(1, more) }
    }
    @Test fun showingCodeRequiresExplicitActionAndDoesNotAnnounceRedemption() {
        var requests = 0
        compose.setContent { PlanesTheme {
            BenefitDetailScreen(BenefitsUiState(selected = coupon), 100, {}, { requests++ }, {}, {}, {})
        } }
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithText("Mostrar código").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("Beneficio canjeado").assertDoesNotExist()
    }
    @Test fun expiredCouponDoesNotOfferCode() {
        compose.setContent { PlanesTheme {
            BenefitDetailScreen(BenefitsUiState(selected = coupon), 2000, {}, { fail("No generar") }, {}, {}, {})
        } }
        compose.onNodeWithText("Beneficio vencido").performScrollTo().assertExists()
        compose.onNodeWithText("Mostrar código").assertDoesNotExist()
    }
    @Test fun profileHasOnlyOneBenefitsOptionAndKeepsItsCallback() {
        var opens = 0
        compose.setContent { PlanesTheme {
            ProfileScreen("Ana", "fake@example.com", emptySet(), null, false, false, null,
                {}, {}, {}, {}, {}, {}, {}, {}, onBenefits = { opens++ })
        } }
        compose.onNodeWithTag("perfil-lista").performScrollToNode(hasText("Mis beneficios"))
        compose.onAllNodesWithText("Mis beneficios").assertCountEquals(1)
        compose.onNodeWithText("Mis beneficios").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }
}
