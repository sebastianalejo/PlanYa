package com.example.planya.data.remote.dto

/** JSON de GET /reportes y GET /reportes/{id} (backend/report_queries.py). */
data class ReportDto(
    val id: String? = null,
    val shortCode: String? = null,
    val targetType: String? = null,
    val planTitle: String? = null,
    val reason: String? = null,
    val status: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val hasResponse: Boolean? = null,
    val description: String? = null,
    val publicResponse: String? = null,
    val targetAvailable: Boolean? = null
)

data class ReportsPageDto(
    val userId: String? = null,
    val filter: String? = null,
    val items: List<ReportDto>? = null,
    val nextKey: String? = null
)

data class ReportsResponseDto(val success: Boolean? = null, val data: ReportsPageDto? = null)

data class ReportDetailDataDto(val userId: String? = null, val report: ReportDto? = null)

data class ReportDetailResponseDto(val success: Boolean? = null, val data: ReportDetailDataDto? = null)
