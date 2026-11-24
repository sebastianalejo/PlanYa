package com.example.planya.data.remote.api

import com.example.planya.data.remote.dto.ReportDetailResponseDto
import com.example.planya.data.remote.dto.ReportsResponseDto
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * US-13 Mis reportes. Rutas protegidas con el autorizador JWT (ver backend/report_queries.py).
 * La cuenta sale del token: Android nunca envía reporterId.
 */
interface ReportsApi {
    @GET("reportes")
    suspend fun reports(
        @Header("Authorization") token: String,
        @Query("estado") filter: String,
        @Query("nextKey") nextKey: String?
    ): ReportsResponseDto

    @GET("reportes/{id}")
    suspend fun report(@Header("Authorization") token: String, @Path("id") id: String): ReportDetailResponseDto
}
