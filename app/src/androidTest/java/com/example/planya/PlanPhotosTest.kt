package com.example.planya

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.planya.data.photos.*
import com.example.planya.domain.model.*
import com.example.planya.domain.repository.PlanActions
import com.example.planya.domain.repository.PlanRepository
import com.example.planya.domain.usecase.plan.GetPlanUseCase
import com.example.planya.presentation.components.PlanPhotoPicker
import com.example.planya.presentation.screens.createplan.*
import com.example.planya.ui.theme.PlanesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class PlanPhotosTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = PlanPhotoStore(context)
    private val area = LocatedArea(GeoPoint(-12.0, -77.0), "Parque", false)
    private class ResultOwner(var gallery: Uri? = null, val cameraResult: Boolean = false) : ActivityResultRegistryOwner {
        var capture: Uri? = null
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                val value: Any? = if (contract is ActivityResultContracts.TakePicture) {
                    capture = input as Uri; cameraResult
                } else gallery
                @Suppress("UNCHECKED_CAST") dispatchResult(requestCode, value as O)
            }
        }
    }
    private fun source() = object : PlanRepository {
        override suspend fun getPlans(nextKey: String?): PlanPage = error("Sin red")
        override suspend fun getPlan(id: String): Plan = error("Sin red")
    }
    private fun rawPhoto(): Pair<String, android.net.Uri> {
        val capture = store.newCameraFile()
        val bitmap = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        store.file(capture.first).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        return capture
    }

    @Test fun preparationCorrectsRotationRemovesGpsAndLimitsSize() = runBlocking {
        val (name, uri) = rawPhoto()
        var prepared: String? = null
        try {
            ExifInterface(store.file(name).path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "12/1,6/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "S")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "77/1,1/1,48/1")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
                saveAttributes()
            }
            prepared = store.prepare(uri)
            val file = store.file(prepared!!)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            assertTrue(bounds.outHeight > bounds.outWidth)
            assertTrue(maxOf(bounds.outHeight, bounds.outWidth) <= 1600)
            assertTrue(file.length() in 1..PlanPhotoStore.MAX_BYTES)
            val exif = ExifInterface(file.path)
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
            assertTrue(store.file(name).exists())
        } finally { store.delete(name); store.delete(prepared) }
    }

    @Test fun invalidImageDoesNotDeleteSourceAndPathsStayPrivate() = runBlocking {
        val (name, uri) = store.newCameraFile()
        try {
            store.file(name).writeText("not-an-image")
            val failure = runCatching { store.prepare(uri) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(store.file(name).exists())
            assertThrows(IllegalArgumentException::class.java) { store.file("../../other.txt") }
            assertEquals("com.example.planya.photos", uri.authority)
        } finally { store.delete(name) }
    }

    @Test fun photoControlsReplaceUrlFieldAndRemoveOnlyOnExplicitClick() {
        var removed = 0
        compose.setContent { PlanesTheme {
            PlanPhotoPicker(PlanDraft(imageKey = "stored-key"), true, {}, { removed++ }, {})
        } }
        compose.onNodeWithText("Tomar foto").assertExists()
        compose.onNodeWithText("Elegir foto").assertExists()
        compose.onNodeWithText("Enlace HTTPS de la foto (opcional)").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, removed) }
        compose.onNodeWithText("Quitar foto").performClick()
        compose.runOnIdle { assertEquals(1, removed) }
    }

    @Test fun uploadFailureKeepsDraftAndNeverPublishesWithoutTheSelectedPhoto() {
        val (name, _) = rawPhoto()
        var calls = 0
        val actions = object : PlanActions {
            override suspend fun create(data: Map<String, Any>): Plan { calls++; error("No se debe publicar") }
            override suspend fun edit(id: String, data: Map<String, Any>): Plan = error("Sin red")
            override suspend fun join(id: String): Plan = error("Sin red")
            override suspend fun leave(id: String): Plan = error("Sin red")
            override suspend fun cancel(id: String): Plan = error("Sin red")
        }
        val photos = object : PlanPhotoGateway {
            override val store = this@PlanPhotosTest.store
            override suspend fun upload(name: String, requestId: String, planId: String?): String = error("Fallo de subida de prueba")
        }
        lateinit var editor: PlanEditorViewModel
        try {
            compose.runOnIdle {
                editor = PlanEditorViewModel(null, GetPlanUseCase(source()), actions, SavedStateHandle(), photos)
                editor.change(PlanDraft(title = "Mi plan con foto", location = area, localPhotoName = name))
                editor.save()
            }
            compose.waitUntil(5_000) { editor.state.value.error != null }
            compose.runOnIdle {
                assertEquals(0, calls)
                assertEquals(name, editor.state.value.draft.localPhotoName)
                assertEquals("Mi plan con foto", editor.state.value.draft.title)
                assertTrue(store.file(name).exists())
                assertNull(editor.state.value.saved)
            }
        } finally { store.delete(name) }
    }

    @Test fun cancellingGalleryAndCameraDoesNotRemoveExistingPhoto() {
        val owner = ResultOwner()
        var selections = 0
        var removals = 0
        compose.setContent { PlanesTheme {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                PlanPhotoPicker(PlanDraft(imageKey = "previous"), true, { selections++ }, { removals++ }, { error(it) })
            }
        } }
        compose.onNodeWithText("Elegir foto").performClick()
        compose.onNodeWithText("Tomar foto").performClick()
        compose.runOnIdle {
            assertEquals(0, selections)
            assertEquals(0, removals)
            assertNotNull(owner.capture)
            assertFalse(store.file(owner.capture!!.lastPathSegment!!).exists())
        }
    }

    @Test fun galleryReturnsOnlySelectedPhotoAndCameraUsesPrivateProvider() {
        val (name, uri) = rawPhoto()
        val owner = ResultOwner(uri, true)
        var selected: Uri? = null
        try {
            compose.setContent { PlanesTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    PlanPhotoPicker(PlanDraft(), true, { selected = it }, {}, { error(it) })
                }
            } }
            compose.onNodeWithText("Elegir foto").performClick()
            compose.runOnIdle { assertEquals(uri, selected) }
            compose.onNodeWithText("Tomar foto").performClick()
            compose.runOnIdle {
                assertEquals(owner.capture, selected)
                assertEquals("com.example.planya.photos", selected?.authority)
            }
        } finally { store.delete(name); store.delete(owner.capture?.lastPathSegment) }
    }

    @Test fun retryReusesUploadedPhotoAndSuccessfulSaveRemovesLocalCopy() {
        val (name, _) = rawPhoto()
        var uploads = 0
        var writes = 0
        var payload: Map<String, Any>? = null
        val key = "fotos/00000000-0000-0000-0000-000000000000/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.jpg"
        val actions = object : PlanActions {
            override suspend fun create(data: Map<String, Any>): Plan {
                writes++; payload = data
                if (writes == 1) error("Fallo temporal de prueba")
                return Plan("saved", "Yo", timeAgo = "", title = "Mi plan", imageKey = key)
            }
            override suspend fun edit(id: String, data: Map<String, Any>): Plan = error("Sin red")
            override suspend fun join(id: String): Plan = error("Sin red")
            override suspend fun leave(id: String): Plan = error("Sin red")
            override suspend fun cancel(id: String): Plan = error("Sin red")
        }
        val photos = object : PlanPhotoGateway {
            override val store = this@PlanPhotosTest.store
            override suspend fun upload(name: String, requestId: String, planId: String?): String { uploads++; return key }
        }
        lateinit var editor: PlanEditorViewModel
        try {
            compose.runOnIdle {
                editor = PlanEditorViewModel(null, GetPlanUseCase(source()), actions, SavedStateHandle(), photos)
                editor.change(PlanDraft(title = "Mi plan", location = area, localPhotoName = name))
                editor.save()
            }
            compose.waitUntil(5_000) { editor.state.value.error != null }
            compose.runOnIdle { editor.save() }
            compose.waitUntil(5_000) { editor.state.value.saved != null }
            compose.runOnIdle {
                assertEquals(1, uploads)
                assertEquals(2, writes)
                assertEquals(key, payload?.get("imageKey"))
                assertFalse(payload!!.containsKey("imageUrl"))
                assertFalse(store.file(name).exists())
            }
        } finally { store.delete(name) }
    }
}
