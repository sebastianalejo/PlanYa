package com.example.planya.domain.repository

import com.example.planya.domain.model.ReportReason
import com.example.planya.domain.model.ReportReceipt
import com.example.planya.domain.model.ReportTarget

/** US-12: envío de reportes. */
interface ReportProblemRepository {
    /**
     * Envía un reporte. Reintentar con el mismo [requestId] y el mismo contenido devuelve el mismo
     * reporte sin duplicarlo; con otro contenido el servidor responde conflicto.
     */
    suspend fun create(target: ReportTarget, reason: ReportReason, description: String, requestId: String): ReportReceipt
}
