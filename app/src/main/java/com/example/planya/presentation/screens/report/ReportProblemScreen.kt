package com.example.planya.presentation.screens.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.planya.domain.model.REPORT_MAX_TEXT
import com.example.planya.domain.model.REPORT_MIN_TEXT
import com.example.planya.domain.model.ReportReceipt
import com.example.planya.domain.model.ReportReason
import com.example.planya.domain.model.ReportTargetType
import com.example.planya.domain.model.reportTextLength
import com.example.planya.ui.theme.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * US-12 «Reportar un problema». Pantalla compartida por «Reportar plan» (Detalle) y
 * «Reportar mensaje» (Chat). Solo muestra éxito con el recibo confirmado por el servidor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportProblemScreen(
    state: ReportProblemUiState,
    onReason: (ReportReason) -> Unit,
    onDescription: (String) -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
    onViewReport: (() -> Unit)? = null
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    fun requestLeave() {
        if (state.sending) return
        if (state.hasDraft) confirmDiscard = true else onClose()
    }
    BackHandler(enabled = state.hasDraft || state.sending) { requestLeave() }
    val receipt = state.receipt

    Scaffold(
        containerColor = ScreenBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Reportar un problema", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Lo revisa el equipo de moderación", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (receipt != null) onClose() else requestLeave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ScreenBg)
            )
        },
        bottomBar = {
            if (receipt == null) Surface(color = Color.White, shadowElevation = 8.dp) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = onSend,
                        enabled = !state.sending && !state.rejected,
                        modifier = Modifier.widthIn(max = 648.dp).fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White)
                    ) {
                        if (state.sending) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(10.dp))
                            Text("Enviando…", style = MaterialTheme.typography.titleSmall)
                        } else {
                            Text(if (state.uncertain) "Reintentar envío" else "Enviar reporte", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    TextButton(onClick = { requestLeave() }, enabled = !state.sending, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Cancelar", color = Indigo, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (receipt != null) ReceiptContent(receipt, state.target.type, onClose, onViewReport)
            else FormContent(state, onReason, onDescription)
        }
    }

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("¿Descartar el reporte?") },
        text = { Text("Se perderán el motivo y la descripción que escribiste.") },
        confirmButton = {
            TextButton(onClick = { confirmDiscard = false; onClose() }) {
                Text("Descartar", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Seguir editando", color = Indigo) } }
    )
}

@Composable
private fun FormContent(state: ReportProblemUiState, onReason: (ReportReason) -> Unit, onDescription: (String) -> Unit) {
    val target = state.target
    Column(
        Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Referencia del contenido reportado (recibida desde la navegación y validada en el servidor).
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, OutlineSoft)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(color = ChipBg, shape = RoundedCornerShape(14.dp)) {
                    Icon(if (target.type == ReportTargetType.PLAN) Icons.Outlined.Event else Icons.Outlined.ChatBubbleOutline,
                        null, tint = Indigo, modifier = Modifier.padding(12.dp).size(26.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (target.type == ReportTargetType.PLAN) "Encuentro" else "Mensaje en el chat",
                        style = MaterialTheme.typography.labelLarge, color = TextMuted)
                    Text(target.planTitle ?: "Plan", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = TextPrimary)
                    if (target.type == ReportTargetType.MESSAGE) target.messageAuthor?.let {
                        Text("Escrito por $it", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                }
            }
        }

        Text("¿Qué ocurrió?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, if (state.showErrors && state.reasonError != null) MaterialTheme.colorScheme.error else OutlineSoft)) {
            Column(Modifier.selectableGroup()) {
                ReportReason.entries.forEachIndexed { index, reason ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected = state.reason == reason, enabled = !state.sending, role = Role.RadioButton,
                                onClick = { onReason(reason) })
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = state.reason == reason, onClick = null, enabled = !state.sending,
                            colors = RadioButtonDefaults.colors(selectedColor = Indigo))
                        Spacer(Modifier.width(14.dp))
                        Text(reason.label, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                    }
                    if (index < ReportReason.entries.lastIndex)
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = OutlineSoft.copy(alpha = .7f))
                }
            }
        }
        if (state.showErrors) state.reasonError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Text("Describe lo ocurrido", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
        val descriptionError = if (state.showErrors) state.descriptionError else null
        OutlinedTextField(
            value = state.description,
            onValueChange = onDescription,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.sending,
            minLines = 4,
            maxLines = 8,
            placeholder = { Text("Cuéntanos qué pasó para que el equipo pueda revisarlo.") },
            isError = descriptionError != null,
            supportingText = {
                Text(descriptionError ?: "${reportTextLength(state.description)}/$REPORT_MAX_TEXT · mínimo $REPORT_MIN_TEXT caracteres",
                    color = if (descriptionError != null) MaterialTheme.colorScheme.error else TextMuted)
            },
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Indigo, unfocusedBorderColor = OutlineSoft,
                focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, disabledContainerColor = Color.White)
        )

        Notice(Icons.Outlined.Shield, "Visible solo para ti y el equipo de moderación. Las demás personas del plan no sabrán que lo reportaste.",
            tint = Indigo, background = Color.Transparent)
        Notice(Icons.Outlined.Info, "Enviar un reporte no cancela el plan, no borra mensajes ni bloquea cuentas: el equipo lo revisa.",
            tint = Indigo, background = ChipBg)
        if (state.reason == ReportReason.SAFETY) Notice(Icons.Outlined.WarningAmber,
            "Este formulario no atiende emergencias. Si estás en peligro ahora, llama a la Policía (105) o a los servicios de emergencia de tu zona.",
            tint = Orange, background = Orange.copy(alpha = .08f))
        state.error?.let { message ->
            Surface(color = Color.White, shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = .5f))) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(message, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        if (state.uncertain) Text("Reintentar es seguro: no se creará un reporte duplicado.",
                            style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String, tint: Color, background: Color) {
    Surface(color = background, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = if (background == Color.Transparent) 2.dp else 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
        }
    }
}

@Composable
private fun ReceiptContent(receipt: ReportReceipt, type: ReportTargetType, onClose: () -> Unit, onViewReport: (() -> Unit)?) {
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag("es-PE")) }
    Column(
        Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(color = ChipBg, shape = CircleShape) {
            Icon(Icons.Outlined.CheckCircle, null, tint = Indigo, modifier = Modifier.padding(16.dp).size(40.dp))
        }
        Text("Reporte enviado", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = IndigoDark)
        Text("El equipo de moderación lo revisará. Enviarlo no cancela el plan ni avisa a las demás personas.",
            style = MaterialTheme.typography.bodyMedium, color = TextMuted, textAlign = TextAlign.Center)
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, OutlineSoft)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ReceiptRow("Número", "#${receipt.shortCode}")
                ReceiptRow("Estado", receipt.status.label)
                ReceiptRow("Enviado", format.format(Date(receipt.createdAt * 1000)))
                ReceiptRow("Motivo", receipt.reason.label)
                ReceiptRow("Contenido", (if (type == ReportTargetType.MESSAGE) "Mensaje en " else "") + (receipt.planTitle ?: "Plan"))
            }
        }
        Text("Guarda este número para hacer seguimiento de tu reporte.", style = MaterialTheme.typography.bodySmall,
            color = TextMuted, textAlign = TextAlign.Center)
        if (onViewReport != null) {
            Button(onClick = onViewReport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange)) { Text("Ver mi reporte") }
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, OutlineSoft)) { Text("Listo", color = Indigo) }
    }
}

@Composable
private fun ReceiptRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextMuted, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}
