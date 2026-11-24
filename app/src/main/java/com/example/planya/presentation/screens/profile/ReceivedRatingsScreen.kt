package com.example.planya.presentation.screens.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.planya.data.profile.ReceivedRating
import com.example.planya.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun formatReceivedDate(epochSeconds: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("es-PE"))
        .format(Date(epochSeconds * 1000)).replace(".", "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivedRatingsScreen(state: ReceivedRatingsState, onBack: () -> Unit, onRefresh: () -> Unit,
    onLoadMore: () -> Unit, onRetry: () -> Unit) {
    val busy = state.loading || state.refreshing || state.loadingMore
    Scaffold(containerColor = ScreenBg, topBar = {
        TopAppBar(title = { Text("Opiniones recibidas") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
        }, actions = {
            IconButton(onClick = onRefresh, enabled = !busy) {
                if (state.loading || state.refreshing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else Icon(Icons.Filled.Refresh, "Actualizar opiniones")
            }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = ScreenBg))
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxSize().testTag("opiniones-lista"),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.loading && !state.loaded) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Consultando tus opiniones…", style = MaterialTheme.typography.bodySmall, color = Indigo)
                    }
                }
                state.error?.let { message -> item { ErrorCard(message, onRetry, enabled = !busy) } }
                if (state.loaded) {
                    item { ReputationCard(state.count, state.average) }
                    item { PrivacyNote() }
                    if (state.error != null) item {
                        Text("Mostramos la última información recibida.", style = MaterialTheme.typography.bodySmall,
                            color = TextMuted)
                    }
                }
                state.pageError?.let { message ->
                    item { ErrorCard(message, onRetry, enabled = !busy, compact = true) }
                }
                if (state.loaded && state.error == null && state.count == 0 && state.items.isEmpty()) {
                    item { EmptyOpinions() }
                }
                if (state.loaded && state.error == null && state.count > 0 && state.items.isEmpty()) {
                    item { ProvisionalOpinions(state.count, onRefresh, enabled = !busy) }
                }
                items(state.items) { rating -> OpinionCard(rating) }
                if (state.nextKey != null) {
                    item { LoadMore(enabled = !busy, loading = state.loadingMore, onClick = onLoadMore) }
                }
                if (state.items.isNotEmpty()) item {
                    Text("Mostrando ${state.items.size} de ${state.count} " +
                        (if (state.count == 1) "opinión." else "opiniones."),
                        style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
        }
    }
}

@Composable
private fun ReputationCard(count: Int, average: Double?) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(20.dp)) {
            val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.2f
            if (count > 0 && average != null) {
                val value: @Composable () -> Unit = {
                    Text(String.format(Locale.US, "%.1f", average),
                        style = MaterialTheme.typography.displaySmall, color = TextPrimary)
                }
                val stars: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RatingStars(average)
                        Text("$count ${if (count == 1) "valoración" else "valoraciones"}",
                            style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    }
                }
                if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { value(); stars() }
                else Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) { value(); stars() }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RatingStars(null)
                    Text("0 valoraciones · Sin nota todavía", style = MaterialTheme.typography.labelLarge,
                        color = TextMuted)
                }
            }
        }
    }
}

@Composable
private fun PrivacyNote() {
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp), tint = TextMuted)
        Text("Solo tú puedes consultar este historial", style = MaterialTheme.typography.bodySmall, color = TextMuted)
    }
}

@Composable
private fun EmptyOpinions() {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sin opiniones recibidas", style = MaterialTheme.typography.titleMedium, color = IndigoDark)
            Text("Cuando participantes de encuentros terminados te valoren, verás aquí la nota, la fecha y la " +
                "referencia del encuentro. No tener opiniones todavía no significa una mala calificación.",
                style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        }
    }
}

@Composable
private fun ProvisionalOpinions(count: Int, onRefresh: () -> Unit, enabled: Boolean) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("El historial todavía no está disponible", style = MaterialTheme.typography.titleMedium, color = IndigoDark)
            Text("El resumen indica $count ${if (count == 1) "valoración" else "valoraciones"}, pero las tarjetas " +
                "aún no llegaron. Actualiza en unos segundos; no mostraremos una lista vacía como si no hubiera notas.",
                style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            TextButton(onClick = onRefresh, enabled = enabled) { Text("Actualizar") }
        }
    }
}

@Composable
private fun OpinionCard(rating: ReceivedRating) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Participante del encuentro", style = MaterialTheme.typography.titleSmall, color = TextPrimary,
                    modifier = Modifier.weight(1f))
                Text(formatReceivedDate(rating.createdAt), style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            RatingStars(rating.score.toDouble())
            Text(rating.planTitle, style = MaterialTheme.typography.titleMedium, color = IndigoDark)
            Text(ratingDescription(rating.score), style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            if (!rating.planAvailable) Text("El encuentro ya no está disponible; la nota se conserva.",
                style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, enabled: Boolean, compact: Boolean = false) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (compact) "No pudimos cargar más opiniones" else "No pudimos cargar tus opiniones",
                style = MaterialTheme.typography.titleSmall, color = IndigoDark)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry, enabled = enabled) { Text("Reintentar") }
        }
    }
}

@Composable
private fun LoadMore(enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Orange),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange)) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Orange)
            Spacer(Modifier.width(8.dp))
        }
        Text(if (loading) "Cargando…" else "Cargar más")
    }
}
