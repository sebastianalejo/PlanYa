package com.example.planya.data.photos

import android.content.Context
import com.example.planya.data.auth.AuthRepository
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.remote.api.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface PlanPhotoGateway {
    val store: PlanPhotoStore
    suspend fun upload(name: String, requestId: String, planId: String?): String
}

class PlanPhotoRepository(context: Context, private val api: PlanPhotoApi, private val auth: AuthRepository) : PlanPhotoGateway {
    override val store = PlanPhotoStore(context.applicationContext)
    private val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    override suspend fun upload(name: String, requestId: String, planId: String?): String {
        val file = store.file(name)
        check(file.isFile && file.length() in 1..PlanPhotoStore.MAX_BYTES) { "La foto del borrador ya no está disponible. Elígela de nuevo." }
        val response = try { api.requestUpload("Bearer ${auth.token()}", PhotoUploadRequest(file.length(), requestId = requestId, planId = planId)) }
        catch (failure: HttpException) {
            if (failure.code() == 401) throw AuthSessionRequiredException()
            error(when (failure.code()) {
                404 -> "Falta desplegar la ruta POST /fotos/subida en AWS. Tu borrador se conserva."
                403 -> "AWS no autorizó la subida. Revisa el autorizador y los permisos del laboratorio."
                else -> "No se pudo preparar la subida. Revisa AWS y vuelve a intentar."
            })
        }
        return transfer(name, response, false)
    }

    suspend fun uploadAvatar(name: String, api: com.example.planya.data.profile.ProfileApi, expectedUserId: String? = null): String {
        val file = store.file(name)
        check(file.isFile && file.length() in 1..PlanPhotoStore.MAX_BYTES) { "La foto ya no está disponible. Elígela de nuevo." }
        val response = try { api.upload("Bearer ${auth.token(expectedUserId)}", mapOf("size" to file.length(), "contentType" to "image/jpeg")) }
        catch (failure: HttpException) {
            if (failure.code() == 401) throw AuthSessionRequiredException()
            error("No se pudo preparar la foto de perfil. Revisa la ruta POST /perfil/foto y sus permisos en AWS.")
        }
        return transfer(name, response, true)
    }

    private suspend fun transfer(name: String, response: PhotoUploadResponse, avatar: Boolean): String {
        val file = store.file(name)
        val data = response.data
        check(response.success && data != null) { "El servidor no preparó la subida de la foto." }
        validate(data, avatar)
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            data.fields.forEach { (key, value) -> addFormDataPart(key, value) }
            // S3 exige el archivo como último campo del formulario.
            addFormDataPart("file", "foto.jpg", file.asRequestBody("image/jpeg".toMediaType()))
        }.build()
        val request = Request.Builder().url(data.uploadUrl).post(form).build()
        // Este cliente no tiene Authorization: el token Cognito nunca se envía a S3.
        suspendCancellableCoroutine<Unit> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("No se pudo subir la foto. Tu borrador sigue guardado.", e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        if (it.isSuccessful) continuation.resume(Unit)
                        else continuation.resumeWithException(IOException("S3 rechazó la foto (${it.code}). Revisa los permisos o reintenta."))
                    }
                }
            })
        }
        return data.imageKey
    }

    companion object {
        fun validate(data: PhotoUploadData, avatar: Boolean = false) {
            val url = data.uploadUrl.toHttpUrlOrNull()
            check(url != null && url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
                url.host.matches(Regex("[a-z0-9.-]+\\.s3(?:\\.[a-z0-9-]+)?\\.amazonaws\\.com"))) { "El servidor devolvió un destino de subida no válido." }
            val keyPattern = if (avatar) "fotos/perfiles/[a-f0-9-]{36}/[a-f0-9-]{36}\\.jpg" else "fotos/[a-f0-9-]{36}/[a-f0-9-]{36}/[a-f0-9-]{36}\\.jpg"
            check(data.fields["key"] == data.imageKey && data.imageKey.matches(Regex(keyPattern))) { "La clave de la foto no es válida." }
            check(data.fields["Content-Type"] == "image/jpeg" && data.maxBytes == PlanPhotoStore.MAX_BYTES) { "El formato de subida no es válido." }
        }
    }
}
