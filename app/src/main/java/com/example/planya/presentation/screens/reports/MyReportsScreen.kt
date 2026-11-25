package com.example.planya.presentation.screens.reports

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.planya.domain.model.*
import com.example.planya.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ResolvedGreen = Color(0xFF1B6E35)
private val ResolvedBg = Color(0xFFE3F3E7)
private val ReviewBg = Color(0xFFFFEFD9)
private val NeutralBg = Color(0xFFEDEDF1)

/** "21 nov 2026": fecha corta en español, sin el punto de la abreviatura. */
internal fun formatReportDate(seconds: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale("es")).format(Date(seconds * 1000)).replace(".", "")

private fun statusColors(status: ReportStatus): Pair<Color, Color> = when (status) {
    ReportStatus.RECIBIDO -> ChipBg to IndigoDark
    ReportStatus.EN_REVISION -> ReviewBg to BadgeBrown
    ReportStatus.RESUELTO -> ResolvedBg to ResolvedGreen
    ReportStatus.DESCARTADO -> NeutralBg to TextMuted
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyReportsScreen(
    state: MyReportsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onFilter: (ReportFilter) -> Unit,
    onToggle: (String) -> Unit,
    onRetryDetail: (String) -> Unit,
    onLoadMore: () -> Unit
) {
    val visible = if (state.requiresLogin) emptyList() else state.items.filter { state.filter.accepts(it.status) }
    Scaffold(containerColor = ScreenBg, topBar = {
        TopAppBar(title = { Text("Mis reportes", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            actions = {
                IconButton(onClick = onRefresh, enabled = !state.busy && !state.requiresLogin,
                    modifier = Modifier.testTag("reports-refresh")) {
                    Icon(Icons.Outlined.Refresh, "Actualizar reportes", tint = Indigo)
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = ScreenBg))
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("reports-list"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(20.dp), tint = TextMuted)
                        Text("Solo se muestran los reportes de tu cuenta", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("reports-filters")) {
                        items(ReportFilter.entries) { filter ->
                            val selected = filter == state.filter
                            FilterChip(selected = selected, onClick = { onFilter(filter) },
                                enabled = !state.requiresLogin,
                                label = { Text(filter.label, style = MaterialTheme.typography.labelLarge) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("reports-filter-${filter.name}"),
                                shape = RoundedCornerShape(50),
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Indigo,
                                    selectedLabelColor = Color.White, containerColor = Color.White, labelColor = TextPrimary),
                                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected,
                                    borderColor = OutlineSoft, selectedBorderColor = Indigo))
                        }
                    }
                }
                if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("reports-loading"), color = Indigo) }
                if (state.requiresLogin) item {
                    Text("Tu sesión venció. Vuelve a ingresar para ver tus reportes.", color = IndigoDark,
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (state.error != null) item { ErrorCard(state.error, stale = state.showingStaleData, enabled = !state.busy, onRetry = onRetry) }
                if (state.loaded && visible.isEmpty() && state.error == null && !state.loading) item {
                    EmptyCard(state.filter, hasMore = state.nextKey != null)
                }
                items(visible, key = { it.id }) { report ->
                    ReportCard(report, expanded = report.id == state.expandedId, detail = state.details[report.id],
                        onToggle = { onToggle(report.id) }, onRetryDetail = { onRetryDetail(report.id) })
                }
                if (state.nextKey != null && !state.requiresLogin) item {
                    OutlinedButton(onClick = onLoadMore, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("reports-more"),
                        shape = RoundedCornerShape(14.dp), border = BorderStroke(1.5.dp, Indigo)) {
                        if (state.loadingMore) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Indigo)
                        else Text("Cargar más", style = MaterialTheme.typography.titleSmall, color = Indigo)
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, stale: Boolean, enabled: Boolean, onRetry: () -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, OutlineSoft),
        modifier = Modifier.fillMaxWidth().testTag("reports-error")) {
        Column(Modifier.padding(16.dp)) {
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            if (stale) Text("Ves los reportes cargados antes; pueden no estar actualizados.",
                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = TextMuted)
            TextButton(onClick = onRetry, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reintentar") }
        }
    }
}

@Composable
private fun EmptyCard(filter: ReportFilter, hasMore: Boolean) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().testTag("reports-empty")) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Flag, null, tint = Indigo, modifier = Modifier.size(32.dp))
            Text(when {
                hasMore -> "Continúa la búsqueda"
                filter == ReportFilter.ABIERTOS -> "No tienes reportes abiertos"
                filter == ReportFilter.CERRADOS -> "No tienes reportes cerrados"
                else -> "Aún no has enviado reportes"
            }, style = MaterialTheme.typography.titleMedium, color = IndigoDark)
            Text(if (hasMore) "Esta página no tiene reportes con este filtro. Puedes cargar la siguiente."
                else "Cuando reportes un plan o un mensaje, podrás seguir aquí su estado y la respuesta de moderación.",
                style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        }
    }
}

@Composable
private fun StatusBadge(status: ReportStatus) {
    val (bg, fg) = statusColors(status)
    Surface(color = bg, shape = RoundedCornerShape(50), modifier = Modifier.semantics { contentDescription = "Estado: ${status.label}" }) {
        Text(status.label, Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ReportCard(report: ReportSummary, expanded: Boolean, detail: ReportDetailState?,
    onToggle: () -> Unit, onRetryDetail: () -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, OutlineSoft),
        modifier = Modifier.fillMaxWidth().testTag("report-${report.id}")) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(report.planTitle ?: report.targetType.label, Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge, color = TextPrimary,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                StatusBadge(report.status)
            }
            Text("#${report.shortCode}", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            if (report.targetType == ReportTargetType.MESSAGE)
                Text("Mensaje del chat", style = MaterialTheme.typography.labelMedium, color = Indigo)
            Text("Enviado el ${formatReportDate(report.createdAt)}", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            Text(buildLabel("Motivo: ", report.reason.label), style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            if (expanded) ExpandedDetail(report, detail, onRetryDetail)
            TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag("report-toggle-${report.id}")
                    .semantics { contentDescription = if (expanded) "Ocultar detalle del reporte ${report.shortCode}"
                        else "Ver detalle del reporte ${report.shortCode}" }) {
                Text(if (expanded) "Ocultar detalle" else "Ver detalle", style = MaterialTheme.typography.titleSmall, color = Indigo)
                Spacer(Modifier.width(6.dp))
                Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, tint = Indigo)
            }
        }
    }
}

private fun buildLabel(label: String, value: String) = androidx.compose.ui.text.buildAnnotatedString {
    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)); append(label); pop(); append(value)
}

@Composable
private fun ExpandedDetail(report: ReportSummary, state: ReportDetailState?, onRetry: () -> Unit) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = OutlineSoft)
    val detail = state?.detail
    when {
        state?.loading == true && detail == null -> Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("report-detail-loading")) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Indigo)
            Text("Cargando detalle…", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        }
        detail == null -> {
            Text(state?.error ?: "No se pudo cargar el detalle.", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reintentar detalle") }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("report-detail-${report.id}")) {
            state?.error?.let { Text("No se pudo actualizar el detalle: $it",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (!detail.targetAvailable) Text("Contenido no disponible",
                Modifier.testTag("report-target-unavailable"), style = MaterialTheme.typography.labelLarge, color = BadgeBrown)
            Text("Tu descripción", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text(detail.description ?: "Sin descripción registrada.", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            val answer = detail.publicResponse
            Text("Respuesta de moderación", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text(answer ?: if (detail.summary.status.isOpen)
                    "Tu reporte está pendiente de revisión. Actualiza más tarde para ver si hay respuesta."
                else "El reporte se cerró sin una respuesta pública.",
                style = MaterialTheme.typography.bodyMedium, color = if (answer != null) TextPrimary else TextMuted)
            Text("Actualizado el ${formatReportDate(detail.summary.updatedAt)}",
                style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
}
