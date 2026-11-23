package com.example.planya.data.remote.api

import com.example.planya.data.remote.dto.CreateReportDto
import com.example.planya.data.remote.dto.CreateReportResponseDto
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/** US-12. Ruta protegida con el autorizador JWT (ver backend/reports.py). */
interface ReportProblemApi {
    @POST("reportes")
    suspend fun create(@Header("Authorization") token: String, @Body body: CreateReportDto): CreateReportResponseDto
}
