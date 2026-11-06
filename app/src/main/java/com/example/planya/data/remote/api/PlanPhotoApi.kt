package com.example.planya.data.remote.api

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

data class PhotoUploadRequest(val size: Long, val contentType: String = "image/jpeg", val requestId: String, val planId: String?)
data class PhotoUploadData(val uploadUrl: String, val fields: Map<String, String>, val imageKey: String, val maxBytes: Long)
data class PhotoUploadResponse(val success: Boolean, val data: PhotoUploadData?)

interface PlanPhotoApi {
    @POST("fotos/subida") suspend fun requestUpload(@Header("Authorization") token: String,
        @Body request: PhotoUploadRequest): PhotoUploadResponse
}
