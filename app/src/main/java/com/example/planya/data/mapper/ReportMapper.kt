package com.example.planya.data.mapper

import com.example.planya.data.remote.dto.ReportDto
import com.example.planya.domain.model.*
import java.util.UUID

private fun String?.isCanonicalUuid() = this != null && runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)

/** Descarta filas mal formadas en lugar de inventar estados, fechas o motivos. */
fun ReportDto.toSummary(): ReportSummary? {
    val reportId = id?.takeIf { it.isCanonicalUuid() } ?: return null
    val state = ReportStatus.from(status) ?: return null
    val target = ReportTargetType.from(targetType) ?: return null
    val created = createdAt?.takeIf { it > 0 } ?: return null
    val updated = updatedAt?.takeIf { it >= created } ?: created
    val code = shortCode?.takeIf { it.matches(Regex("[0-9A-F]{4}")) }
        ?: reportId.replace("-", "").take(4).uppercase()
    return ReportSummary(reportId, code, target, planTitle?.trim()?.takeIf { it.isNotEmpty() },
        ReportReason.from(reason), state, created, updated, hasResponse == true && !state.isOpen)
}

fun ReportDto.toDetail(): ReportDetail? {
    val summary = toSummary() ?: return null
    // Una respuesta solo se muestra si el servidor cerró el reporte.
    val answer = publicResponse?.trim()?.takeIf { it.isNotEmpty() && !summary.status.isOpen }
    return ReportDetail(summary.copy(hasResponse = answer != null), description?.trim()?.takeIf { it.isNotEmpty() },
        answer, targetAvailable == true)
}
