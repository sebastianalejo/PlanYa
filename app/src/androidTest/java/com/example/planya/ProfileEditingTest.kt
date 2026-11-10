package com.example.planya

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.data.photos.PlanPhotoStore
import com.example.planya.data.profile.*
import com.example.planya.presentation.screens.profile.*
import com.example.planya.ui.theme.PlanesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ProfileEditingTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = PlanPhotoStore(context)
    private class Gateway : ProfileGateway {
        var profile = UserProfile("user", "Andrea", "andrea@example.com", avatarKey = "old", revision = 2, saved = true,
            interests = listOf("CAFE"), verification = ProfileVerification("NONE"))
        var activityData = ProfileActivity("user", 2, 1, 0)
        var confidenceData = ProfileConfidence("user", 0, null)
        var loadFailure = false
        var activityFailure = false
        var confidenceFailure = false
        var saveFailure: Exception? = null
        var saves = 0
        var uploads = 0
        var removed = false
        var savedInterests: Set<String>? = null
        override suspend fun get(): UserProfile { if (loadFailure) error("Sin red"); return profile }
        override suspend fun upload(name: String): String { uploads++; return "pending" }
        override suspend fun activity(): ProfileActivity { if (activityFailure) error("Sin actividad"); return activityData }
        override suspend fun confidence(): ProfileConfidence { if (confidenceFailure) error("Sin confianza"); return confidenceData }
        override suspend fun save(name: String, revision: Long, avatarKey: String?, removeAvatar: Boolean, interests: Set<String>?): UserProfile {
            saves++; saveFailure?.let { throw it }; removed = removeAvatar; savedInterests = interests
            profile = profile.copy(name = name, revision = revision + 1,
                avatarKey = if (removeAvatar) null else avatarKey ?: profile.avatarKey,
                interests = interests?.toList() ?: profile.interests)
            return profile
        }
    }
    private fun model(gateway: Gateway, saved: SavedStateHandle = SavedStateHandle()): ProfileViewModel {
        lateinit var model: ProfileViewModel
        compose.runOnIdle { model = ProfileViewModel(gateway, store, "user", saved) }
        compose.waitUntil(5000) { !model.state.value.loading }
        return model
    }
    @Test fun nameIsValidatedSavedAndLoadedAgainFromRemoteGateway() {
        val gateway = Gateway(); val editor = model(gateway)
        compose.runOnIdle {
            editor.name(" "); editor.save(emptySet()); assertEquals(0, gateway.saves)
            editor.name("a".repeat(81)); editor.save(emptySet()); assertEquals(0, gateway.saves)
            editor.name("  Andrea PlanYa  "); editor.save(setOf("CAFE"))
        }
        compose.waitUntil { editor.state.value.savedEvent > 0 }
        val reopened = model(gateway)
        compose.runOnIdle {
            assertEquals("Andrea PlanYa", reopened.state.value.name)
            assertEquals("andrea@example.com", reopened.state.value.profile?.email)
            assertEquals(listOf("CAFE"), reopened.state.value.profile?.interests)
            assertFalse(reopened.state.value.dirty)
        }
    }
    @Test fun profileSaveFailureKeepsPhotoAndRetryDoesNotUploadAgain() {
        val gateway = Gateway().apply { saveFailure = IllegalStateException("Sin red") }
        val editor = model(gateway)
        val (rawName, _) = store.newCameraFile()
        val image = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        store.file(rawName).outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 85, it) }; image.recycle()
        var prepared: String? = null
        try {
            compose.runOnIdle { editor.select(Uri.fromFile(store.file(rawName))) }
            compose.waitUntil(5000) { !editor.state.value.photoBusy }
            compose.runOnIdle { prepared = editor.state.value.localPhoto; assertNotNull(prepared); editor.save(emptySet()) }
            compose.waitUntil { !editor.state.value.busy }
            compose.runOnIdle {
                assertTrue(store.file(prepared!!).isFile); assertTrue(editor.state.value.dirty)
                assertEquals(1, gateway.uploads)
                gateway.saveFailure = null; editor.save(emptySet())
            }
            compose.waitUntil { editor.state.value.savedEvent > 0 }
            compose.runOnIdle { assertEquals(1, gateway.uploads); assertFalse(store.file(prepared!!).exists()); assertNull(editor.state.value.localPhoto) }
        } finally { store.delete(rawName); store.delete(prepared) }
    }
    @Test fun conflictPreservesDraftAndFailedReloadDoesNotDiscardIt() {
        val gateway = Gateway().apply { saveFailure = ProfileConflictException("Cambió") }
        val editor = model(gateway)
        compose.runOnIdle { editor.name("Mi borrador"); editor.save(emptySet()) }
        compose.waitUntil { editor.state.value.conflict }
        compose.runOnIdle { gateway.loadFailure = true; editor.load() }
        compose.waitUntil { !editor.state.value.loading }
        compose.runOnIdle {
            assertEquals("Mi borrador", editor.state.value.name)
            assertTrue(editor.state.value.conflict)
            gateway.loadFailure = false; editor.load()
        }
        compose.waitUntil { !editor.state.value.loading }
        compose.runOnIdle { assertEquals("Andrea", editor.state.value.name); assertFalse(editor.state.value.dirty) }
    }
    @Test fun restoredDirtyDraftDoesNotSilentlyOverwriteANewerRevision() {
        val gateway = Gateway(); val saved = SavedStateHandle(); val editor = model(gateway, saved)
        compose.runOnIdle { editor.name("Pendiente"); gateway.profile = gateway.profile.copy(revision = 5) }
        val restored = model(gateway, saved)
        compose.runOnIdle { assertEquals("Pendiente", restored.state.value.name); assertTrue(restored.state.value.conflict) }
    }
    @Test fun removeAvatarIsExplicitAndDoesNotClearOtherFields() {
        val gateway = Gateway(); val editor = model(gateway)
        compose.runOnIdle { editor.removePhoto(); editor.save(setOf("CAFE")) }
        compose.waitUntil { editor.state.value.savedEvent > 0 }
        compose.runOnIdle { assertTrue(gateway.removed); assertNull(editor.state.value.profile?.avatarKey); assertEquals("Andrea", editor.state.value.name) }
    }
    @Test fun editorIsARealScreenWithDiscardConfirmationAndSinglePhotoControls() {
        var back = 0
        val state = ProfileState(profile = UserProfile("user", "Andrea", "andrea@example.com"), name = "Andrea nueva", loaded = true, dirty = true)
        compose.setContent { PlanesTheme { EditProfileScreen(state, {}, {}, {}, {}, {}, {}, { back++ }) } }
        compose.onNodeWithText("Editar perfil").assertIsDisplayed()
        compose.onNodeWithText("Guardar perfil").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Foto de perfil (opcional)").assertExists()
        compose.onNodeWithText("0/4").assertDoesNotExist()
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, "edit-profile-layout.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithContentDescription("Volver al perfil").performClick()
        compose.onNodeWithText("Seguir editando").performClick()
        compose.runOnIdle { assertEquals(0, back) }
        compose.onNodeWithContentDescription("Volver al perfil").performClick()
        compose.onNodeWithText("Descartar").performClick()
        compose.runOnIdle { assertEquals(1, back) }
    }

    @Test fun profileLoadsActivityAndEmptyConfidenceFromServer() {
        val gateway = Gateway()
        val editor = model(gateway)
        compose.waitUntil(5000) { editor.state.value.activity != null && editor.state.value.confidence != null }
        compose.runOnIdle {
            assertEquals(2, editor.state.value.activity?.organized)
            assertEquals(1, editor.state.value.activity?.joined)
            assertEquals(0, editor.state.value.activity?.attended)
            assertEquals(0, editor.state.value.confidence?.count)
            assertNull(editor.state.value.confidence?.average)
            assertEquals("Andrea", editor.state.value.profile?.name)
        }
    }

    @Test fun trustFailureDoesNotRemoveProfileAndRetryRecovers() {
        val gateway = Gateway().apply { activityFailure = true; confidenceFailure = true }
        val editor = model(gateway)
        compose.waitUntil(5000) { !editor.state.value.activityLoading && !editor.state.value.confidenceLoading }
        compose.runOnIdle {
            assertNull(editor.state.value.activity)
            assertNull(editor.state.value.confidence)
            assertTrue(editor.state.value.activityError != null)
            assertTrue(editor.state.value.confidenceError != null)
            assertEquals("Andrea", editor.state.value.profile?.name)
            gateway.activityFailure = false; gateway.confidenceFailure = false
            gateway.confidenceData = ProfileConfidence("user", 4, 4.25)
            editor.retryActivity(); editor.retryConfidence()
        }
        compose.waitUntil(5000) { editor.state.value.activity != null && editor.state.value.confidence != null }
        compose.runOnIdle {
            assertEquals(2, editor.state.value.activity?.organized)
            assertEquals(4, editor.state.value.confidence?.count)
            assertEquals(4.25, editor.state.value.confidence?.average ?: 0.0, 0.001)
            assertNull(editor.state.value.activityError)
            assertNull(editor.state.value.confidenceError)
        }
    }

    @Test fun savingNameOrPhotoWithoutInterestsPreservesTheServerSelection() {
        val gateway = Gateway()
        val editor = model(gateway)
        compose.runOnIdle { editor.name("Andrea actualizada"); editor.save() }
        compose.waitUntil(5000) { editor.state.value.savedEvent > 0 }
        compose.runOnIdle {
            assertNull(gateway.savedInterests)
            assertEquals(listOf("CAFE"), editor.state.value.profile?.interests)
            assertEquals(listOf("CAFE"), gateway.profile.interests)
        }
    }
}
