package com.example.planya.data.remote.dto

// Contrato PROPUESTO (docs/equipo/US-09-BENEFICIOS.md): el backend todavía debe implementarlo.
data class BenefitResponseDto<T>(val success: Boolean?, val data: T?)

data class BenefitDto(
    val id: String?, val userId: String?, val merchantName: String?, val title: String?,
    val discount: String?, val category: String?, val conditions: String?, val limits: String?,
    val logoUrl: String?, val validUntil: Long?, val status: String?, val usedAt: Long?
)

data class BenefitListDto(val items: List<BenefitDto>?, val nextKey: String?)

data class BenefitCodeDto(val code: String?, val expiresAt: Long?)
