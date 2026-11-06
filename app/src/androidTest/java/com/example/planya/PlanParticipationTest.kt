package com.example.planya

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.planya.domain.model.Plan
import com.example.planya.presentation.screens.plandetail.PlanDetailScreen
import com.example.planya.ui.theme.PlanesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlanParticipationTest {
    @get:Rule val compose = createComposeRule()
    private val plan = Plan("p1", "Ana", timeAgo = "", title = "Encuentro", ownerId = "host",
        capacity = 2, attendees = listOf("Ana"), attendeeIds = listOf("host"), expiresAt = 1000)
    @Test fun joinUpdatesActionAndLeavingNeedsConfirmation() {
        var current by mutableStateOf(plan)
        var joins = 0
        var leaves = 0
        compose.setContent { PlanesTheme {
            PlanDetailScreen(current, false, null, {}, null, 100, {}, {}, {}, {}, "guest", false,
                { joins++; current = current.copy(attendeeIds = listOf("host", "guest"), attendees = listOf("Ana", "Luis")) },
                { leaves++; current = plan }, {}, {})
        } }
        compose.onNodeWithText("Unirme ahora").performClick()
        compose.onNodeWithText("Unirme ahora").assertDoesNotExist()
        compose.onNodeWithText("Salir del plan").performClick()
        compose.onNodeWithText("Volver").performClick()
        compose.runOnIdle { assertEquals(1, joins); assertEquals(0, leaves) }
        compose.onNodeWithText("Salir del plan").performClick()
        compose.onNode(hasText("Salir del plan") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Unirme ahora").assertExists()
        compose.runOnIdle { assertEquals(1, leaves) }
    }
    @Test fun fullAndExpiredPlansCannotBeJoined() {
        var expired by mutableStateOf(false)
        compose.setContent { PlanesTheme {
            PlanDetailScreen(plan.copy(attendees = listOf("Ana", "Luis"), attendeeIds = listOf("host", "u2")),
                false, null, {}, null, if (expired) 1001 else 100, {}, {}, {}, {}, "guest", false,
                { fail("No puede inscribirse") }, {}, {}, {})
        } }
        compose.onNodeWithText("Por ahora no quedan cupos").assertExists()
        compose.onNodeWithText("Unirme ahora").assertDoesNotExist()
        compose.runOnIdle { expired = true }
        compose.onNodeWithText("Este plan ya finalizó").assertExists()
        compose.onNodeWithText("Unirme ahora").assertDoesNotExist()
    }
    @Test fun hostCancellationIsExplicitAndDoesNotOfferJoinOrLeave() {
        var cancellations = 0
        compose.setContent { PlanesTheme {
            PlanDetailScreen(plan, false, null, {}, null, 100, {}, {}, {}, {}, "host", false,
                { fail("El anfitrión no se inscribe") }, { fail("El anfitrión no abandona") }, {}, { cancellations++ })
        } }
        compose.onNodeWithText("Unirme ahora").assertDoesNotExist()
        compose.onNodeWithText("Salir del plan").assertDoesNotExist()
        compose.onNodeWithText("Cancelar este plan").performScrollTo().performClick()
        compose.onNodeWithText("Volver").performClick()
        compose.runOnIdle { assertEquals(0, cancellations) }
        compose.onNodeWithText("Cancelar este plan").performClick()
        compose.onNodeWithText("Cancelar plan").performClick()
        compose.runOnIdle { assertEquals(1, cancellations) }
    }
}
