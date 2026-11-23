package com.example.planya.data.mapper

import com.example.planya.data.remote.dto.ReportReceiptDto
import com.example.planya.domain.model.ReportReason
import com.example.planya.domain.model.ReportReceipt
import com.example.planya.domain.model.ReportStatus
import com.example.planya.domain.model.ReportTargetType

/** Null si falta un dato obligatorio o llega un valor desconocido: no se completa con datos inventados. */
fun ReportReceiptDto.toReceipt(): ReportReceipt? {
    return ReportReceipt(
        id = id?.takeIf { it.isNotBlank() } ?: return null,
        status = ReportStatus.from(status) ?: return null,
        createdAt = createdAt ?: return null,
        targetType = ReportTargetType.from(targetType) ?: return null,
        planId = planId ?: return null,
        planTitle = planTitle,
        reason = ReportReason.entries.firstOrNull { it.name == reason } ?: return null
    )
}
