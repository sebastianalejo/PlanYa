package com.example.planya.data.remote.dto

// US-12: JSON de POST /reportes (backend/reports.py). Los nombres siguen docs/equipo/CONTRATO-REPORTES.md.
data class CreateReportDto(
    val requestId: String,
    val targetType: String,
    val planId: String,
    val reason: String,
    val description: String,
    /** Solo MESSAGE: cursor del chat «<ms>#<uuid>». */
    val messageId: String? = null
)

data class ReportReceiptDto(
    val id: String? = null,
    val status: String? = null,
    val createdAt: Long? = null,
    val targetType: String? = null,
    val planId: String? = null,
    val planTitle: String? = null,
    val reason: String? = null
)

data class CreateReportResponseDto(
    val success: Boolean? = null,
    val data: ReportReceiptDto? = null,
    val duplicate: Boolean? = null,
    val error: String? = null,
    val code: String? = null
)
