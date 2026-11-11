package com.example.planya.data.remote.api

import com.example.planya.data.remote.dto.ChatMessageResponseDto
import com.example.planya.data.remote.dto.ChatPageResponseDto
import com.example.planya.data.remote.dto.SendChatMessageDto
import com.example.planya.data.remote.dto.ConversationsResponseDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Rutas protegidas con el autorizador JWT de Cognito (ver backend/chat.py). */
interface ChatApi {
    @GET("conversaciones")
    suspend fun conversations(@Header("Authorization") token: String, @Query("nextKey") nextKey: String?): ConversationsResponseDto

    @GET("planes/{id}/mensajes")
    suspend fun messages(
        @Header("Authorization") token: String,
        @Path("id") planId: String,
        @Query("nextKey") nextKey: String?,
        @Query("after") after: String?
    ): ChatPageResponseDto

    @POST("planes/{id}/mensajes")
    suspend fun send(
        @Header("Authorization") token: String,
        @Path("id") planId: String,
        @Body body: SendChatMessageDto
    ): ChatMessageResponseDto
}
