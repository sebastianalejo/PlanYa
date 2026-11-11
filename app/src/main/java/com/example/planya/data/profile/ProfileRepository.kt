package com.example.planya.data.profile

import com.example.planya.data.auth.AuthRepository
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.photos.PlanPhotoRepository
import com.example.planya.data.remote.api.PhotoUploadResponse
import com.google.gson.Gson
import com.google.gson.JsonObject
import retrofit2.HttpException
import retrofit2.http.*

data class ProfileVerification(val status: String? = null, val method: String? = null, val verifiedAt: String? = null) {
    val verified: Boolean get() = status == "VERIFIED"
    val pending: Boolean get() = status == "PENDING"
}

data class UserProfile(val userId: String, val name: String, val email: String = "", val avatarKey: String? = null,
    val avatarUrl: String? = null, val revision: Long = 0, val interests: List<String> = emptyList(),
    val saved: Boolean = false, val verification: ProfileVerification? = null)

data class ProfileActivity(val userId: String = "", val organized: Int = 0, val joined: Int = 0, val attended: Int = 0)

data class ProfileConfidence(val userId: String = "", val count: Int = 0, val average: Double? = null)

data class ApiResponse<T>(val success: Boolean, val data: T? = null)

interface ProfileApi {
    @GET("perfil") suspend fun get(@Header("Authorization") token: String): ApiResponse<UserProfile>
    @PUT("perfil") suspend fun update(@Header("Authorization") token: String, @Body data: Map<String, @JvmSuppressWildcards Any>): ApiResponse<UserProfile>
    @POST("perfil/foto") suspend fun upload(@Header("Authorization") token: String, @Body data: Map<String, @JvmSuppressWildcards Any>): PhotoUploadResponse
    @GET("perfil/actividad") suspend fun activity(@Header("Authorization") token: String): ApiResponse<ProfileActivity>
    @GET("perfil/confianza") suspend fun confidence(@Header("Authorization") token: String): ApiResponse<ProfileConfidence>
}

class ProfileConflictException(message: String) : IllegalStateException(message)

interface ProfileGateway {
    suspend fun get(): UserProfile
    suspend fun save(name: String, revision: Long, avatarKey: String?, removeAvatar: Boolean, interests: Set<String>?): UserProfile
    suspend fun upload(name: String): String
    suspend fun activity(): ProfileActivity
    suspend fun confidence(): ProfileConfidence
}

class ProfileRepository(private val api: ProfileApi, private val auth: AuthRepository, private val photos: PlanPhotoRepository,
    private val expectedUserId: String? = null) : ProfileGateway {
    override suspend fun get() = request { api.get(token()) }

    override suspend fun save(name: String, revision: Long, avatarKey: String?, removeAvatar: Boolean, interests: Set<String>?) = request {
        val body = mutableMapOf<String, Any>(
            "name" to name.trim(), "expectedRevision" to revision, "removeAvatar" to removeAvatar
        )
        // Sin intereses explícitos el servidor conserva los guardados. Editar nombre o foto no los pisa.
        if (interests != null) body["interests"] = interests.toList()
        if (avatarKey != null && !removeAvatar) body["avatarKey"] = avatarKey
        api.update(token(), body)
    }

    override suspend fun upload(name: String) = photos.uploadAvatar(name, api, expectedUserId)

    override suspend fun activity() = request { api.activity(token()) }

    override suspend fun confidence() = request { api.confidence(token()) }

    private suspend fun token() = "Bearer ${auth.token(expectedUserId)}"

    private suspend fun <T> request(block: suspend () -> ApiResponse<T>): T {
        try {
            val response = block()
            check(response.success && response.data != null) { "El servidor no devolvió tus datos de perfil." }
            return response.data
        } catch (failure: HttpException) {
            if (failure.code() == 401) throw AuthSessionRequiredException()
            val error = runCatching { Gson().fromJson(failure.response()?.errorBody()?.string(), JsonObject::class.java).get("error")?.asString }.getOrNull()
            if (failure.code() == 409) throw ProfileConflictException(error ?: "Tu perfil cambió. Recarga antes de guardar.")
            throw IllegalStateException(error ?: when (failure.code()) {
                404 -> "Falta habilitar las rutas de Perfil en AWS. Tus cambios no se enviaron."
                403 -> "AWS no autorizó este acceso. Revisa el autorizador de Perfil."
                else -> "No pudimos cargar o guardar tu perfil. Revisa tu conexión e intenta de nuevo."
            })
        }
    }
}
