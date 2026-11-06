package com.example.planya.data.remote.api

import com.example.planya.data.remote.dto.PlanResponseDto
import retrofit2.http.*

interface PlanActionsApi {
    @POST("planes") suspend fun create(@Header("Authorization") token: String, @Body data: Map<String, @JvmSuppressWildcards Any>): PlanResponseDto
    @PUT("planes/{id}") suspend fun edit(@Header("Authorization") token: String, @Path("id") id: String, @Body data: Map<String, @JvmSuppressWildcards Any>): PlanResponseDto
    @DELETE("planes/{id}") suspend fun cancel(@Header("Authorization") token: String, @Path("id") id: String): PlanResponseDto
    @POST("planes/{id}/participantes") suspend fun join(@Header("Authorization") token: String, @Path("id") id: String): PlanResponseDto
    @DELETE("planes/{id}/participantes/me") suspend fun leave(@Header("Authorization") token: String, @Path("id") id: String): PlanResponseDto
}
