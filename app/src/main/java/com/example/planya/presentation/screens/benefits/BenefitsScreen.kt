package com.example.planya.presentation.screens.benefits

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.planya.domain.model.Benefit
import com.example.planya.domain.model.BenefitStatus
import com.example.planya.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun formatBenefitDate(seconds: Long): String = SimpleDateFormat("d 'de' MMMM yyyy", Locale("es")).format(Date(seconds * 1000))
internal fun formatBenefitTime(seconds: Long): String = SimpleDateFormat("HH:mm", Locale("es")).format(Date(seconds * 1000))

/** Texto + icono del estado: la información nunca depende solo del color. */
internal fun statusLine(benefit: Benefit, status: BenefitStatus): Pair<ImageVector, String> = when (status) {
    BenefitStatus.DISPONIBLE -> Icons.Outlined.Schedule to (benefit.validUntil?.let { "Vence el ${formatBenefitDate(it)}" } ?: "Disponible")
    BenefitStatus.USADO -> Icons.Outlined.CheckCircle to (benefit.usedAt?.let { "Canjeado el ${formatBenefitDate(it)}" } ?: "Ya fue canjeado")
    BenefitStatus.VENCIDO -> Icons.Outlined.Timer to (benefit.validUntil?.let { "Venció el ${formatBenefitDate(it)}" } ?: "Venció")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenefitsScreen(state: BenefitsUiState, nowSeconds: Long, onBack: () -> Unit, onFilter: (BenefitStatus) -> Unit,
    onOpen: (Benefit) -> Unit, onRetry: () -> Unit, onLoadMore: () -> Unit) {
    val visible = state.benefits.filter { it.effectiveStatus(nowSeconds) == state.filter }
    Scaffold(containerColor = ScreenBg,
        topBar = { TopAppBar(title = { Text("Mis beneficios", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ScreenBg)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max = 680.dp).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BenefitStatus.entries.forEach { status ->
                    FilterChip(selected = state.filter == status, onClick = { onFilter(status) }, label = { Text(status.plural) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ChipBg, selectedLabelColor = IndigoDark))
                }
            }
            Box(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
                when {
                    state.isLoading && visible.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.errorMessage != null && visible.isEmpty() -> BenefitsMessage(
                        Icons.Outlined.CardGiftcard, "No pudimos cargar tus beneficios", state.errorMessage, "Reintentar", onRetry)
                    state.loaded && visible.isEmpty() -> BenefitsMessage(Icons.Outlined.CardGiftcard, emptyTitle(state.filter),
                        "Los beneficios aparecerán aquí cuando un local aliado los asigne a tu cuenta.")
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(visible, key = { it.id }) { BenefitCard(it, nowSeconds) { onOpen(it) } }
                        if (state.errorMessage != null) item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(state.errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = if (state.nextKey != null) onLoadMore else onRetry) { Text("Reintentar") }
                            }
                        }
                        if (state.nextKey != null && state.errorMessage == null) item {
                            OutlinedButton(onClick = onLoadMore, enabled = !state.isLoadingMore,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(50)) {
                                if (state.isLoadingMore) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Ver más")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun emptyTitle(filter: BenefitStatus) = when (filter) {
    BenefitStatus.DISPONIBLE -> "Aún no tienes beneficios disponibles"
    BenefitStatus.USADO -> "Todavía no has canjeado beneficios"
    BenefitStatus.VENCIDO -> "No tienes beneficios vencidos"
}

@Composable
private fun BenefitsMessage(icon: ImageVector, title: String, body: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(color = ChipBg, shape = RoundedCornerShape(50)) { Icon(icon, null, Modifier.padding(16.dp).size(28.dp), tint = Indigo) }
        Text(title, style = MaterialTheme.typography.titleMedium, color = IndigoDark, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = TextMuted, textAlign = TextAlign.Center)
        if (actionLabel != null) Button(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = Orange)) { Text(actionLabel) }
    }
}

@Composable
private fun BenefitCard(benefit: Benefit, nowSeconds: Long, onOpen: () -> Unit) {
    val status = benefit.effectiveStatus(nowSeconds)
    val (icon, line) = statusLine(benefit, status)
    Surface(color = Color.White, shape = RoundedCornerShape(24.dp), shadowElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                MerchantLogo(benefit.logoUrl, Modifier.size(56.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    benefit.category?.let {
                        Surface(color = ChipBg, shape = RoundedCornerShape(8.dp)) {
                            Text(it.uppercase(), Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = IndigoDark)
                        }
                    }
                    Text(benefit.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(benefit.merchantName, style = MaterialTheme.typography.bodyMedium, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(20.dp), tint = Indigo)
                Text("${status.label} · $line", style = MaterialTheme.typography.labelLarge, color = Indigo)
            }
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(50),
                colors = if (status == BenefitStatus.DISPONIBLE) ButtonDefaults.buttonColors(containerColor = Orange)
                else ButtonDefaults.buttonColors(containerColor = ChipBg, contentColor = IndigoDark)) {
                Text(if (status == BenefitStatus.DISPONIBLE) "Ver y canjear" else "Ver detalle")
            }
        }
    }
}

@Composable
internal fun MerchantLogo(url: String?, modifier: Modifier) {
    Surface(modifier = modifier, color = ChipBg, shape = RoundedCornerShape(16.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Storefront, null, Modifier.size(28.dp), tint = Indigo)
            if (url != null) coil3.compose.AsyncImage(url, null, Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
        }
    }
}
