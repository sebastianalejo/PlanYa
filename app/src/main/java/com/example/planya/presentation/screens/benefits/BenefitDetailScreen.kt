package com.example.planya.presentation.screens.benefits

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.planya.domain.model.Benefit
import com.example.planya.domain.model.BenefitCode
import com.example.planya.domain.model.BenefitStatus
import com.example.planya.ui.theme.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

private const val STATUS_POLL_MILLIS = 15_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenefitDetailScreen(state: BenefitsUiState, nowSeconds: Long, onBack: () -> Unit, onShowCode: () -> Unit,
    onRetry: () -> Unit, onRefresh: () -> Unit, onCodeExpired: () -> Unit) {
    val benefit = state.selected
    val code = state.code
    // Mientras el código esté visible se consulta al servidor para confirmar el canje.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val refresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(code?.code, lifecycle) {
        if (code != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(STATUS_POLL_MILLIS); refresh() }
        }
    }
    LaunchedEffect(code?.code, code?.expiresAt, nowSeconds) { if (code != null && nowSeconds >= code.expiresAt) onCodeExpired() }
    Scaffold(containerColor = ScreenBg,
        topBar = { TopAppBar(title = { Text("Beneficio", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ScreenBg)) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                benefit == null && state.detailLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                benefit == null -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.detailError ?: "No encontramos este beneficio.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = Orange)) { Text("Reintentar") }
                }
                else -> Column(Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.detailError != null) Text(state.detailError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    DetailCard(benefit, nowSeconds)
                    ActionCard(benefit, nowSeconds, code, state.codeLoading, state.codeError, onShowCode)
                }
            }
        }
    }
}

@Composable
private fun DetailCard(benefit: Benefit, nowSeconds: Long) {
    val status = benefit.effectiveStatus(nowSeconds)
    val (icon, line) = statusLine(benefit, status)
    Surface(color = Color.White, shape = RoundedCornerShape(24.dp), shadowElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                MerchantLogo(benefit.logoUrl, Modifier.size(64.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    benefit.category?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = Indigo) }
                    Text(benefit.merchantName, style = MaterialTheme.typography.titleSmall, color = TextMuted)
                }
            }
            Text(benefit.title, style = MaterialTheme.typography.headlineSmall, color = IndigoDark)
            if (benefit.discount.isNotBlank()) Text(benefit.discount, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            HorizontalDivider(color = OutlineSoft.copy(alpha = .6f))
            DetailRow("Estado", "${status.label} · $line", icon = icon)
            DetailRow("Condiciones", benefit.conditions.ifBlank { "El local no publicó condiciones adicionales." })
            benefit.limits?.let { DetailRow("Límites", it) }
            DetailRow("Vigencia", benefit.validUntil?.let { "Hasta el ${formatBenefitDate(it)}" } ?: "Sin fecha de vencimiento informada")
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextMuted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = Indigo)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        }
    }
}

@Composable
private fun ActionCard(benefit: Benefit, nowSeconds: Long, code: BenefitCode?, loading: Boolean, error: String?, onShowCode: () -> Unit) {
    val status = benefit.effectiveStatus(nowSeconds)
    Surface(color = Color.White, shape = RoundedCornerShape(24.dp), shadowElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            when (status) {
                BenefitStatus.USADO -> {
                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(40.dp), tint = Indigo)
                    Text("Beneficio canjeado", style = MaterialTheme.typography.titleMedium, color = IndigoDark)
                    Text(benefit.usedAt?.let { "El servidor confirmó el canje el ${formatBenefitDate(it)} a las ${formatBenefitTime(it)}." }
                        ?: "El servidor confirmó que este beneficio ya fue utilizado.", style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted, textAlign = TextAlign.Center)
                    Text("No se puede canjear otra vez.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
                BenefitStatus.VENCIDO -> {
                    Text("Beneficio vencido", style = MaterialTheme.typography.titleMedium, color = IndigoDark)
                    Text("Este beneficio ya no se puede canjear.", style = MaterialTheme.typography.bodyMedium, color = TextMuted, textAlign = TextAlign.Center)
                }
                BenefitStatus.DISPONIBLE -> if (code == null) {
                    Text("Revisa las condiciones y muestra el código al personal autorizado del local. Solo el personal puede confirmar el canje.",
                        style = MaterialTheme.typography.bodyMedium, color = TextMuted, textAlign = TextAlign.Center)
                    if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Button(onClick = onShowCode, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = Orange)) {
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                        else {
                            Icon(Icons.Outlined.QrCode2, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                            Text(if (error != null) "Reintentar código" else "Mostrar código")
                        }
                    }
                } else {
                    QrCode(code.code, Modifier.fillMaxWidth(.8f))
                    Text(code.code, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                        color = TextPrimary, textAlign = TextAlign.Center)
                    Text("Código válido hasta las ${formatBenefitTime(code.expiresAt)}. Si no se lee el QR, el personal puede escribir este código.",
                        style = MaterialTheme.typography.bodySmall, color = TextMuted, textAlign = TextAlign.Center)
                    Text("El canje solo se confirma cuando el servidor lo registra.", style = MaterialTheme.typography.bodySmall, color = TextMuted, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** QR real generado con ZXing a partir del código opaco del servidor; negro sobre blanco para máximo contraste. */
@Composable
private fun QrCode(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) {
        runCatching {
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 1, 1,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        }.getOrNull()
    }
    if (matrix == null) {
        Text("No se pudo dibujar el QR. Usa el código escrito.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        return
    }
    Box(modifier.aspectRatio(1f).background(Color.White, RoundedCornerShape(12.dp)).padding(16.dp)
        .semantics { contentDescription = "Código QR del beneficio. Código escrito: $content" }) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.width / matrix.width
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + .5f, cell + .5f))
            }
        }
    }
}
