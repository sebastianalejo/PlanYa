package com.example.planya.domain.model

/*
 * US-12 «Reportar un problema». Los enums compartidos (ReportReason, ReportStatus, ReportTargetType)
 * están en ReportsModels.kt, el archivo acordado con US-13 (docs/equipo/CONTRATO-REPORTES.md).
 */

const val REPORT_MIN_TEXT = 10
const val REPORT_MAX_TEXT = 500

/**
 * Contenido que se reporta. Llega desde la navegación (Detalle o Chat) y el servidor lo vuelve
 * a validar: la app nunca busca identificadores privados arbitrarios.
 */
data class ReportTarget(
    val type: ReportTargetType,
    val planId: String,
    val planTitle: String?,
    /** Solo MESSAGE: cursor del chat «<milisegundos>#<uuid>», igual que en el contrato con US-13. */
    val messageId: String? = null,
    val messageAuthor: String? = null
)

/** Recibo que devuelve POST /reportes: lo único que confirma el envío. */
data class ReportReceipt(
    val id: String,
    val status: ReportStatus,
    val createdAt: Long,
    val targetType: ReportTargetType,
    val planId: String,
    val planTitle: String?,
    val reason: ReportReason
) {
    /** Mismo código corto que muestra «Mis reportes» (US-13). */
    val shortCode: String get() = id.replace("-", "").take(4).uppercase()
}

/** Cantidad de caracteres como los cuenta el servidor (sin espacios extremos, emoji = 1). */
fun reportTextLength(text: String): Int = text.trim().let { it.codePointCount(0, it.length) }

/** Validación local de la descripción; el servidor vuelve a validar. Null si es válida. */
fun reportDescriptionError(text: String): String? {
    val length = reportTextLength(text)
    return when {
        length == 0 -> "Describe lo ocurrido"
        length < REPORT_MIN_TEXT -> "Escribe al menos $REPORT_MIN_TEXT caracteres (llevas $length)"
        length > REPORT_MAX_TEXT -> "Máximo $REPORT_MAX_TEXT caracteres (llevas $length)"
        else -> null
    }
}

/** El servidor rechazó el contenido: ajeno, ya no accesible o chat cerrado. No sirve reintentar igual. */
class ReportRejectedException(val code: String?, message: String) : IllegalStateException(message)

/** Ese requestId ya se usó con otro contenido; el siguiente envío necesita un intento nuevo. */
class ReportConflictException(message: String) : IllegalStateException(message)

/** Sin respuesta del servidor: no se sabe si el reporte se guardó. */
class ReportNetworkException(message: String) : IllegalStateException(message)
