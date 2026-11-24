package com.example.planya.domain.usecase.report

import com.example.planya.domain.model.ReportReason
import com.example.planya.domain.model.ReportReceipt
import com.example.planya.domain.model.ReportTarget
import com.example.planya.domain.repository.ReportProblemRepository

class SendReportUseCase(private val repository: ReportProblemRepository) {
    suspend operator fun invoke(target: ReportTarget, reason: ReportReason, description: String, requestId: String): ReportReceipt =
        repository.create(target, reason, description.trim(), requestId)
}
