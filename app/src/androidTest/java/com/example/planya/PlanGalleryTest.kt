package com.example.planya

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.data.photos.*
import com.example.planya.domain.model.*
import com.example.planya.domain.repository.*
import com.example.planya.domain.usecase.plan.GetPlanUseCase
import com.example.planya.presentation.components.*
import com.example.planya.presentation.screens.createplan.*
import com.example.planya.ui.theme.PlanesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PlanGalleryTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun fourPhotosLimitCanRemoveIndividuallyAndChooseCover() {
        val store = PlanPhotoStore(context)
        val files = (0..3).map { index ->
            val (name, _) = store.newCameraFile()
            val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(listOf(0xff8eaa98, 0xffd6ab7c, 0xff91a3c2, 0xffc5b4cb)[index].toInt())
            store.file(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }; bitmap.recycle()
            name
        }
        var draft by mutableStateOf(PlanDraft().withPhotos(files.map { DraftPhoto(localName = it) }))
        try {
            compose.setContent { PlanesTheme {
                PlanPhotoPicker(draft, true, {}, {}, {},
                    onRemoveAt = { index -> draft = draft.withPhotos(draft.gallery().filterIndexed { i, _ -> i != index }) },
                    onCover = { index -> val items = draft.gallery(); draft = draft.withPhotos(listOf(items[index]) + items.filterIndexed { i, _ -> i != index }) })
            } }
            compose.onNodeWithText("4/4").assertExists()
            compose.onNodeWithText("Tomar foto").assertIsNotEnabled()
            compose.onNodeWithText("Elegir foto").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Seleccionar foto 2").performClick()
            compose.onNodeWithText("Usar de portada").performClick()
            compose.runOnIdle { assertEquals(files[1], draft.localPhotoName) }
            val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
            File(context.cacheDir, "gallery-editor-layout.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithText("Quitar foto").performClick()
            compose.onNodeWithText("3/4").assertExists()
            compose.onNodeWithText("Elegir foto").assertIsEnabled()
            compose.runOnIdle { assertFalse(draft.gallery().any { it.localName == files[1] }) }
            repeat(3) { compose.onNodeWithText("Quitar foto").performClick() }
            compose.onNodeWithText("0/4").assertExists()
            compose.onNodeWithText("Quitar foto").assertDoesNotExist()
            compose.onNodeWithText("Tomar foto").assertIsEnabled()
            compose.onNodeWithText("Elegir foto").assertIsEnabled()
            compose.runOnIdle {
                assertTrue(draft.gallery().isEmpty())
                assertNull(draft.localPhotoName)
                assertNull(draft.imageKey)
                assertTrue(draft.imageUrl.isNullOrBlank())
            }
        } finally { files.forEach(store::delete) }
    }

    @Test fun detailGalleryCanSwipeAndKeepsVisiblePageCount() {
        compose.setContent { PlanesTheme {
            PhotoGallery(listOf(null, null, null, null), "Encuentro", androidx.compose.ui.Modifier.fillMaxSize())
        } }
        compose.onNodeWithText("1 / 4").assertExists()
        compose.onNodeWithContentDescription("Galería de fotos del plan").performTouchInput { swipeLeft() }
        compose.onNodeWithText("2 / 4").assertExists()
    }

    @Test fun interruptedGalleryUploadRetainsFilesAndReusesSuccessfulUploads() {
        val store = PlanPhotoStore(context)
        val (rawName, _) = store.newCameraFile()
        val uri = android.net.Uri.fromFile(store.file(rawName))
        val bitmap = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888)
        store.file(rawName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }; bitmap.recycle()
        var uploads = 0
        var publications = 0
        val photos = object : PlanPhotoGateway {
            override val store = store
            override suspend fun upload(name: String, requestId: String, planId: String?): String {
                uploads++; if (uploads == 2) error("Interrupción de prueba")
                return "key-$name"
            }
        }
        val actions = object : PlanActions {
            override suspend fun create(data: Map<String, Any>): Plan {
                publications++; assertEquals(4, (data["photos"] as List<*>).size)
                return Plan("new", "Ana", timeAgo = "", title = "Galería")
            }
            override suspend fun edit(id: String, data: Map<String, Any>): Plan = error("Sin red")
            override suspend fun join(id: String): Plan = error("Sin red")
            override suspend fun leave(id: String): Plan = error("Sin red")
            override suspend fun cancel(id: String): Plan = error("Sin red")
        }
        val source = object : PlanRepository {
            override suspend fun getPlans(nextKey: String?) = PlanPage(emptyList(), null)
            override suspend fun getPlan(id: String): Plan = error("Sin red")
        }
        lateinit var editor: PlanEditorViewModel
        val prepared = mutableListOf<String>()
        try {
            compose.runOnIdle {
                editor = PlanEditorViewModel(null, GetPlanUseCase(source), actions, SavedStateHandle(), photos)
                editor.change(PlanDraft(title = "Galería", location = LocatedArea(GeoPoint(-12.0, -77.0), "Parque", false)))
            }
            repeat(4) {
                compose.runOnIdle { editor.selectPhoto(uri) }
                compose.waitUntil(5000) { !editor.state.value.photoBusy }
            }
            compose.runOnIdle {
                prepared.addAll(editor.state.value.draft.gallery().mapNotNull { it.localName })
                assertEquals(4, prepared.size)
                editor.selectPhoto(uri)
                assertEquals(4, editor.state.value.draft.gallery().size)
                editor.save()
            }
            compose.waitUntil(5000) { !editor.state.value.busy }
            compose.runOnIdle {
                assertEquals(0, publications)
                assertEquals(2, uploads)
                assertTrue(prepared.all { store.file(it).isFile })
                assertNotNull(editor.state.value.draft.gallery().first().imageKey)
                editor.save()
            }
            compose.waitUntil(5000) { editor.state.value.saved != null }
            compose.runOnIdle {
                assertEquals(1, publications)
                assertEquals(5, uploads)
                assertTrue(prepared.none { store.file(it).exists() })
            }
        } finally { store.delete(rawName); prepared.forEach(store::delete) }
    }
}
