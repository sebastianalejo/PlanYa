package com.example.planya.presentation.screens.plandetail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.planya.domain.model.*
import com.example.planya.presentation.components.*
import com.example.planya.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanDetailScreen(
    plan: Plan?, isLoading: Boolean, error: String?, onRetry: () -> Unit, area: LocatedArea?, nowSeconds: Long,
    onLocation: () -> Unit, onBack: () -> Unit,
    onNavigate: (BottomTab) -> Unit, onCreate: () -> Unit,
    userId: String?, actionBusy: Boolean, onJoin: () -> Unit, onLeave: () -> Unit,
    onEdit: () -> Unit, onCancel: () -> Unit,
    onDirections: (Plan) -> Unit = {}, onShare: (Plan) -> Unit = {}
) {
    var confirmation by remember(plan?.id) { mutableStateOf<String?>(null) }
    val expired = plan?.isExpired(nowSeconds) == true
    val owner = userId != null && plan?.ownerId == userId
    val joined = userId != null && plan?.attendeeIds?.contains(userId) == true
    val full = plan?.let { it.capacity != null && it.attendees != null && it.attendees.size >= it.capacity } == true
    Scaffold(containerColor = ScreenBg,
        topBar = {
            TopAppBar(title = { Text("Detalle del plan", style = MaterialTheme.typography.titleSmall) },
                navigationIcon = { IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver al inicio")
                } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = ScreenBg))
        },
        bottomBar = {
            Column {
                if (plan != null) Surface(color = Color.White) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center) {
                        if (expired || plan.expiresAt == null || (full && !joined && !owner)) {
                            Surface(color = ScreenBg, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 648.dp).fillMaxWidth()) {
                                Text(when { expired -> "Este plan ya finalizó"; plan.expiresAt == null -> "El anfitrión debe confirmar el horario"; else -> "Por ahora no quedan cupos" },
                                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                            }
                        } else Column(Modifier.widthIn(max = 648.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (owner) Text("Eres el anfitrión · Gestiona tu encuentro", style = MaterialTheme.typography.labelMedium, color = Indigo)
                        Button(onClick = { if (owner) onEdit() else if (joined) confirmation = "leave" else onJoin() },
                            enabled = !actionBusy,
                            modifier = Modifier.widthIn(max = 648.dp).fillMaxWidth().heightIn(min = 50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (owner || joined) Indigo else Orange),
                            shape = RoundedCornerShape(50)) {
                            Text(when {
                                actionBusy -> "Guardando…"
                                owner -> "Editar mi plan"
                                joined -> "Salir del plan"
                                else -> "Unirme ahora"
                            })
                            if (owner) { Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)) }
                            if (!expired && !full && !owner && !joined) {
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                            }
                        }
                        }
                    }
                }
                AppBottomBar(onNavigate = onNavigate, onCreate = onCreate)
            }
        }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (plan == null) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isLoading) CircularProgressIndicator() else {
                        Text("Este plan no está disponible", style = MaterialTheme.typography.titleMedium)
                        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        TextButton(onClick = onRetry) { Text("Reintentar") }
                        TextButton(onClick = onBack) { Text("Volver al inicio") }
                    }
                }
            } else {
                Column(Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (error != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Datos sin actualizar", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            TextButton(onClick = onRetry) { Text("Reintentar") }
                        }
                    }
                    Box(Modifier.fillMaxWidth().heightIn(min = 200.dp).aspectRatio(1.65f)
                        .clip(RoundedCornerShape(22.dp))) {
                        PlanImage(if (expired) plan.copy(imageUrl = null, imageKey = null, photos = emptyList()) else plan, Modifier.fillMaxSize())
                        Card(modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(16.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ProfileAvatar(plan.authorAvatarUrl)
                                Column(Modifier.weight(1f)) {
                                    Text(plan.authorName, style = MaterialTheme.typography.titleSmall)
                                    Text("Organizador del plan", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                }
                            }
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, OutlineSoft)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Surface(color = ChipBg, shape = RoundedCornerShape(50)) {
                                Text(plan.category?.label ?: "Plan espontáneo", Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    color = Indigo, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(plan.title, style = MaterialTheme.typography.titleLarge, color = IndigoDark)
                            Text(plan.description ?: "El organizador aún no agregó una descripción.",
                                style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                DetailMetric("DISTANCIA", distanceLabel(area?.point, plan.coordinates) ?: if (area == null) "Elegir ubicación" else "Sin coordenadas",
                                    Modifier.weight(1f).then(if (area == null) Modifier.clickable(onClick = onLocation) else Modifier), false)
                                DetailMetric(if (expired) "ESTADO" else "EXPIRA EN", plan.expirationLabel(nowSeconds),
                                    Modifier.weight(1f), true)
                            }
                            Text(if (area == null) "Elige tu ubicación para calcular la distancia al encuentro." else "Distancia aproximada en línea recta.",
                                style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            if (area?.googlePlaceId != null) GoogleMapsAttribution(area.googleAttributions)
                            HorizontalDivider(color = OutlineSoft.copy(alpha = .6f))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.LocationOn, null, tint = Indigo, modifier = Modifier.size(20.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Punto de encuentro", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                                    Text(plan.meetingPlace ?: "Por definir", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (plan.coordinates != null || !plan.meetingPlace.isNullOrBlank())
                                    OutlinedButton(onClick = { onDirections(plan) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                        Icon(Icons.Outlined.NearMe, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Cómo llegar")
                                    }
                                TextButton(onClick = { onShare(plan) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Icon(Icons.Outlined.Share, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Compartir plan")
                                }
                            }
                            plan.startsAt?.let {
                                Text("Empieza: " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
                                    java.text.DateFormat.SHORT, java.util.Locale.forLanguageTag("es-PE")).format(java.util.Date(it * 1000)),
                                    style = MaterialTheme.typography.bodyMedium, color = Indigo)
                            }
                            if (owner && !expired) {
                                HorizontalDivider(color = OutlineSoft.copy(alpha = .6f))
                                TextButton(onClick = { confirmation = "cancel" }, enabled = !actionBusy) { Text("Cancelar este plan", color = MaterialTheme.colorScheme.error) }
                            }
                            if (joined && !owner) Text("Tu inscripción está confirmada.", color = Indigo, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(plan.attendees?.let { "Asistentes (${it.size} ${if (it.size == 1) "confirmado" else "confirmados"})" } ?: "Asistentes",
                                style = MaterialTheme.typography.titleSmall, color = IndigoDark)
                            Text(plan.capacity?.let { "Máximo: $it personas" } ?: "Cupo por confirmar",
                                style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            if (plan.attendees == null) Text("Aún no hay información de participantes.",
                                style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                            else {
                                if (plan.attendees.isEmpty()) Text("Sé la primera persona en unirte.",
                                    style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                                plan.attendees.forEach { name ->
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        UserAvatar(name)
                                        Text(name, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                plan.capacity?.let {
                                    Text("${(it - plan.attendees.size).coerceAtLeast(0)} cupos libres",
                                        style = MaterialTheme.typography.labelMedium, color = Indigo)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(expired, plan?.id, owner, joined) {
        if (expired || plan == null || (confirmation == "cancel" && !owner) || (confirmation == "leave" && !joined)) confirmation = null
    }
    if (confirmation != null && !expired && plan != null) AlertDialog(onDismissRequest = { confirmation = null }, containerColor = Color.White,
        title = { Text(if (confirmation == "cancel") "¿Cancelar este plan?" else "¿Salir del plan?") },
        text = { Text(if (confirmation == "cancel") "Dejará de aparecer para los participantes. Esta acción cancela el encuentro."
            else "Tu cupo quedará disponible para otra persona.") },
        confirmButton = { Button(enabled = !actionBusy && !isLoading &&
            (if (confirmation == "cancel") owner else joined),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = {
            val action = confirmation; confirmation = null
            if (action == "cancel") onCancel() else onLeave()
        }) { Text(if (confirmation == "cancel") "Cancelar plan" else "Salir del plan") } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Volver") } })
}

@Composable
private fun DetailMetric(label: String, value: String, modifier: Modifier, expiry: Boolean) {
    Surface(modifier = modifier, color = if (expiry) Orange.copy(alpha = .06f) else ScreenBg,
        shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(if (expiry) Icons.Outlined.Timer else Icons.Outlined.LocationOn, null,
                tint = if (expiry) Orange else Indigo, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = if (expiry) Orange else Indigo)
            Text(value, style = MaterialTheme.typography.labelLarge, color = TextPrimary)
            }
        }
    }
}
