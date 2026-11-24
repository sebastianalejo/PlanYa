package com.example.planya.domain.model

/*
 * Modelo REPORT compartido por US-12 (Reportar un problema) y US-13 (Mis reportes).
 * Contrato completo en docs/equipo/CONTRATO-REPORTES.md. Los códigos coinciden con el backend.
 */

enum class ReportStatus(val label: String, val isOpen: Boolean) {
    RECIBIDO("Recibido", true),
    EN_REVISION("En revisión", true),
    RESUELTO("Resuelto", false),
    DESCARTADO("Descartado", false);

    companion object { fun from(code: String?) = entries.firstOrNull { it.name == code } }
}

enum class ReportReason(val label: String) {
    INAPPROPRIATE("Contenido inapropiado"),
    SPAM("Spam o publicidad"),
    SAFETY("Riesgo de seguridad"),
    OTHER("Otro motivo");

    companion object { fun from(code: String?) = entries.firstOrNull { it.name == code } ?: OTHER }
}

enum class ReportTargetType(val label: String) {
    PLAN("Plan"), MESSAGE("Mensaje del chat");

    companion object { fun from(code: String?) = entries.firstOrNull { it.name == code } }
}

/** Filtros de US-13. `query` es el valor que entiende GET /reportes?estado=. */
enum class ReportFilter(val label: String, val query: String) {
    TODOS("Todos", "TODOS"), ABIERTOS("Abiertos", "ABIERTOS"), CERRADOS("Cerrados", "CERRADOS");

    fun accepts(status: ReportStatus) = when (this) {
        TODOS -> true
        ABIERTOS -> status.isOpen
        CERRADOS -> !status.isOpen
    }
}

/** Tarjeta del listado: sin descripción ni respuesta (llegan al expandir). */
data class ReportSummary(
    val id: String,
    val shortCode: String,
    val targetType: ReportTargetType,
    val planTitle: String?,
    val reason: ReportReason,
    val status: ReportStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val hasResponse: Boolean
)

/** Detalle expandido de un reporte propio. Nunca contiene datos de moderación internos. */
data class ReportDetail(
    val summary: ReportSummary,
    val description: String?,
    val publicResponse: String?,
    val targetAvailable: Boolean
)

data class ReportsPage(val items: List<ReportSummary>, val nextKey: String?)

class ReportsCursorExpiredException : IllegalStateException("La lista cambió. Actualiza para continuar.")
class ReportNotFoundException : IllegalStateException("No encontramos este reporte. Actualiza la lista.")

/** Une páginas sin duplicar y conserva el orden del servidor (más reciente primero). */
fun mergeReports(current: List<ReportSummary>, incoming: List<ReportSummary>): List<ReportSummary> {
    val byId = LinkedHashMap<String, ReportSummary>()
    (current + incoming).forEach { byId[it.id] = it }
    return byId.values.sortedWith(compareByDescending<ReportSummary> { it.createdAt }.thenByDescending { it.id })
}
