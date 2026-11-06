package com.example.planya.data

import com.example.planya.data.photos.PlanPhotoRepository
import com.example.planya.data.remote.api.PhotoUploadData
import com.example.planya.domain.model.*
import com.example.planya.presentation.screens.createplan.PlanDraft
import org.junit.Assert.*
import org.junit.Test

class PhotoUploadTest {
    private val key = "fotos/00000000-0000-0000-0000-000000000000/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.jpg"
    private val valid = PhotoUploadData("https://planya-fotos-test.s3.us-east-1.amazonaws.com/",
        mapOf("key" to key, "Content-Type" to "image/jpeg"), key, 1_500_000)
    @Test fun acceptsOnlyExpectedS3HttpsDestinations() {
        PlanPhotoRepository.validate(valid)
        PlanPhotoRepository.validate(valid.copy(uploadUrl = "https://planya-fotos-test.s3.amazonaws.com/"))
        for (url in listOf("http://planya.s3.amazonaws.com/", "https://example.com/", "https://planya.s3.amazonaws.com.evil.test/",
            "https://user:pass@planya.s3.amazonaws.com/", "https://planya.s3.amazonaws.com:8443/")) {
            assertThrows(IllegalStateException::class.java) { PlanPhotoRepository.validate(valid.copy(uploadUrl = url)) }
        }
    }
    @Test fun avatarUploadUsesOnlyThePrivateStagingNamespace() {
        val avatarKey = "fotos/perfiles/00000000-0000-0000-0000-000000000000/22222222-2222-2222-2222-222222222222.jpg"
        val avatar = valid.copy(imageKey = avatarKey, fields = valid.fields + ("key" to avatarKey))
        PlanPhotoRepository.validate(avatar, avatar = true)
        assertThrows(IllegalStateException::class.java) { PlanPhotoRepository.validate(avatar) }
        assertThrows(IllegalStateException::class.java) { PlanPhotoRepository.validate(valid, avatar = true) }
        val persistent = avatarKey.removePrefix("fotos/")
        assertThrows(IllegalStateException::class.java) { PlanPhotoRepository.validate(avatar.copy(imageKey = persistent, fields = avatar.fields + ("key" to persistent)), avatar = true) }
    }
    @Test fun rejectsMismatchingObjectAndUnexpectedFormatOrSize() {
        assertThrows(IllegalStateException::class.java) { PlanPhotoRepository.validate(valid.copy(imageKey = "../photo.jpg")) }
        assertThrows(IllegalStateException::class.java) { PlanPhotoRepository.validate(valid.copy(maxBytes = 50_000_000)) }
        assertThrows(IllegalStateException::class.java) { PlanPhotoRepository.validate(valid.copy(fields = valid.fields + ("Content-Type" to "text/html"))) }
    }
    @Test fun publishedPayloadNeverContainsLocalPathOrSignedDisplayUrlWhenKeyExists() {
        val draft = PlanDraft(title = "Foto", location = LocatedArea(GeoPoint(-12.0, -77.0), "Parque", false),
            imageUrl = "https://example.com/temporary", imageKey = key, localPhotoName = "local.jpg")
        val body = draft.payload("request")
        assertEquals(key, body["imageKey"])
        assertFalse(body.containsKey("imageUrl"))
        assertFalse(body.containsKey("localPhotoName"))
        // Ahora una galería pendiente no se puede publicar omitiendo la foto silenciosamente.
        assertThrows(IllegalStateException::class.java) { draft.copy(imageKey = null).payload("request") }
    }
}
