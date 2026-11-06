package com.example.planya.data.profile

import com.example.planya.data.auth.AuthRepository
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.photos.PlanPhotoRepository
import com.example.planya.data.remote.api.PhotoUploadResponse
import com.google.gson.Gson
import com.google.gson.JsonObject
import retrofit2.HttpException
import retrofit2.http.*

data class UserProfile(val userId: String, val name: String, val email: String = "", val avatarKey: String? = null,
    val avatarUrl: String? = null, val revision: Long = 0, val interests: List<String> = emptyList(), val saved: Boolean = false)
data class ProfileResponse(val success: Boolean, val data: UserProfile?)
interface ProfileApi {
    @GET("perfil") suspend fun get(@Header("Authorization") token: String): ProfileResponse
    @PUT("perfil") suspend fun update(@Header("Authorization") token: String, @Body data: Map<String, @JvmSuppressWildcards Any>): ProfileResponse
    @POST("perfil/foto") suspend fun upload(@Header("Authorization") token: String, @Body data: Map<String, @JvmSuppressWildcards Any>): PhotoUploadResponse
}
class ProfileConflictException(message: String) : IllegalStateException(message)
interface ProfileGateway {
    suspend fun get(): UserProfile
    suspend fun save(name: String, revision: Long, avatarKey: String?, removeAvatar: Boolean, interests: Set<String>): UserProfile
    suspend fun upload(name: String): String
}
class ProfileRepository(private val api: ProfileApi, private val auth: AuthRepository, private val photos: PlanPhotoRepository,
    private val expectedUserId: String? = null) : ProfileGateway {
    override suspend fun get() = request { api.get("Bearer ${auth.token(expectedUserId)}") }
    override suspend fun save(name: String, revision: Long, avatarKey: String?, removeAvatar: Boolean, interests: Set<String>) = request {
        val body = mutableMapOf<String, Any>("name" to name.trim(), "expectedRevision" to revision, "removeAvatar" to removeAvatar, "interests" to interests.toList())
        if (avatarKey != null && !removeAvatar) body["avatarKey"] = avatarKey
        api.update("Bearer ${auth.token(expectedUserId)}", body)
    }
    override suspend fun upload(name: String) = photos.uploadAvatar(name, api, expectedUserId)
    private suspend fun request(block: suspend () -> ProfileResponse): UserProfile {
        try {
            val response = block()
            check(response.success && response.data != null) { "El servidor no devolvió tu perfil." }
            return response.data
        } catch (failure: HttpException) {
            if (failure.code() == 401) throw AuthSessionRequiredException()
            val error = runCatching { Gson().fromJson(failure.response()?.errorBody()?.string(), JsonObject::class.java).get("error")?.asString }.getOrNull()
            if (failure.code() == 409) throw ProfileConflictException(error ?: "Tu perfil cambió. Recarga antes de guardar.")
            throw IllegalStateException(error ?: when (failure.code()) {
                404 -> "Falta habilitar las rutas de Perfil en AWS. Tus cambios no se enviaron."
                403 -> "AWS no autorizó este acceso. Revisa el autorizador de Perfil."
                else -> "No pudimos guardar o cargar tu perfil. Revisa tu conexión e intenta de nuevo."
            })
        }
    }
}
