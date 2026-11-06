package com.example.planya

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.planya.presentation.screens.access.*
import com.example.planya.ui.theme.PlanesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AccessCompletionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun registerValidatesConfirmationAndOnlySendsAfterValidInputs() {
        var calls = 0
        compose.setContent { PlanesTheme { AccessScreen(AccountState(ready = true, mode = AccessMode.REGISTER), {}, { _, _, _, _ -> calls++ }, {}, {}) } }
        compose.onNodeWithText("Crear cuenta").performScrollTo().performClick()
        compose.onNodeWithText("Escribe un correo válido.").assertExists()
        compose.onNode(hasSetTextAction() and hasText("Tu nombre")).performScrollTo().performTextInput("Andrea")
        compose.onNode(hasSetTextAction() and hasText("Correo electrónico")).performScrollTo().performTextInput("fake@example.com")
        compose.onNode(hasSetTextAction() and hasText("Contraseña", substring = false)).performScrollTo().performTextInput("Synthetic-pass-123")
        compose.onNode(hasSetTextAction() and hasText("Repite la contraseña")).performScrollTo().performTextInput("Different-pass-123")
        compose.onNodeWithText("Crear cuenta").performScrollTo().performClick()
        compose.onNodeWithText("Las contraseñas no coinciden.").assertExists()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNode(hasSetTextAction() and hasText("Repite la contraseña")).performScrollTo().performTextReplacement("Synthetic-pass-123")
        compose.onNodeWithText("Crear cuenta").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun resendRequiresEmailAndCooldownDisablesDuplicateRequests() {
        var calls = 0
        var deadline by mutableLongStateOf(0)
        compose.setContent { PlanesTheme { AccessScreen(AccountState(ready = true, mode = AccessMode.VERIFY, resendAvailableAt = deadline), {}, { _, _, _, _ -> },
            { calls++; deadline = System.currentTimeMillis() + 60_000 }, {}) } }
        compose.onNodeWithText("Reenviar código").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNode(hasSetTextAction() and hasText("Correo electrónico")).performScrollTo().performTextInput("fake@example.com")
        compose.onNodeWithText("Reenviar código").performScrollTo().performClick()
        compose.onNode(hasText("Reenviar en", substring = true)).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun resetCanResendCodeAndRequiresMatchingNewPassword() {
        var resets = 0
        var resends = 0
        compose.setContent { PlanesTheme { AccessScreen(AccountState(ready = true, mode = AccessMode.RESET), {}, { _, _, _, _ -> resets++ }, { resends++ }, {}) } }
        compose.onNode(hasSetTextAction() and hasText("Correo electrónico")).performScrollTo().performTextInput("fake@example.com")
        compose.onNodeWithText("Reenviar código").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, resends) }
        compose.onNode(hasSetTextAction() and hasText("Código del correo")).performScrollTo().performTextInput("123456")
        compose.onNode(hasSetTextAction() and hasText("Contraseña", substring = false)).performScrollTo().performTextInput("Synthetic-pass-123")
        compose.onNode(hasSetTextAction() and hasText("Repite la contraseña")).performScrollTo().performTextInput("Synthetic-pass-123")
        compose.onNodeWithText("Guardar contraseña").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, resets) }
    }
    @Test fun retryStoredSessionIsSeparateFromStartingANewLogin() {
        var retries = 0
        var logins = 0
        compose.setContent { PlanesTheme { AccessScreen(AccountState(ready = true, restoreError = "Sin conexión"), {}, { _, _, _, _ -> logins++ }, {}, {}, onRestore = { retries++ }) } }
        compose.onNodeWithText("Reintentar sesión guardada").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(0, logins) }
    }
    @Test fun passwordsAreNotRestoredAfterAndroidRecreatesTheScreen() {
        val restore = StateRestorationTester(compose)
        restore.setContent { PlanesTheme { AccessScreen(AccountState(ready = true), {}, { _, _, _, _ -> }, {}, {}) } }
        compose.onNodeWithText("Correo electrónico").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Correo electrónico")).performScrollTo().performTextInput("fake@example.com")
        compose.onNode(hasSetTextAction() and hasText("Contraseña", substring = false)).performScrollTo().performTextInput("Synthetic-pass-123")
        restore.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction() and hasText("Correo electrónico")).assertTextContains("fake@example.com")
        compose.onNodeWithText("Ingresar").performScrollTo().performClick()
        compose.onNodeWithText("Escribe tu contraseña.").assertExists()
    }
}
