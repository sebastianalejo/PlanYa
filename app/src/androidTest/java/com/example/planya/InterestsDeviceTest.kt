package com.example.planya

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.data.profile.DeviceInterestsStore
import com.example.planya.presentation.screens.profile.ProfileScreen
import com.example.planya.ui.theme.PlanesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class InterestsDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun pendingSelectionsPersistSeparatelyAndStaleSyncCannotClearNewChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("interests_device_test_only", 0)
        prefs.edit().clear().commit()
        try {
            val store = DeviceInterestsStore(prefs)
            store.select("account-a", setOf("CAFE")); store.select("account-b", setOf("FIESTA"))
            val before = store.read("account-a")
            assertEquals(setOf("CAFE"), before.values)
            assertEquals(setOf("FIESTA"), store.read("account-b").values)
            store.select("account-a", setOf("DEPORTES"))
            assertFalse(store.markSynced("account-a", before.version))
            assertTrue(store.read("account-a").dirty)
            assertFalse(store.pull("account-a", setOf("CAMINATA"), before.version))
            assertEquals(setOf("DEPORTES"), store.read("account-a").values)
            assertTrue(store.pull("new-account", setOf("ALMUERZO"), 0))
            assertTrue(store.read("new-account").complete)
            assertFalse(store.read("new-account").dirty)
        } finally { prefs.edit().clear().commit() }
    }
    @Test fun profileOffersRetryForUnsyncedInterestsWithoutLoggingOut() {
        var retries = 0
        var logouts = 0
        compose.setContent { PlanesTheme {
            ProfileScreen("Andrea", "fake@example.com", setOf("CAFE"), null, false, false, null,
                {}, {}, {}, {}, {}, { logouts++ }, {}, {}, interestsError = "Pendiente de sincronizar",
                onRetryInterests = { retries++ })
        } }
        compose.onNodeWithText("Reintentar intereses").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(0, logouts) }
    }
}
