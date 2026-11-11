package com.example.planya.presentation.screens.operator

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.planya.domain.model.OperatorMember
import com.example.planya.domain.model.OperatorOffer
import com.example.planya.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperatorScreen(state: OperatorUiState, onBack: () -> Unit, onRefresh: () -> Unit,
    onMore: () -> Unit, onTab: (Boolean) -> Unit, onSelect: (OperatorOffer, String) -> Unit,
    onCatalog: () -> Unit, onMember: (OperatorMember) -> Unit, onCode: (String) -> Unit,
    onReviewCode: () -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    BackHandler(enabled = state.writing || state.pending != null) { if (!state.writing) onDismiss() }
    val enabled = !state.loading && !state.writing && state.pending == null
    Scaffold(containerColor = ScreenBg, topBar = {
        TopAppBar(title = { Text("Panel del operador", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = onBack, enabled = !state.writing) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ScreenBg))
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("operator-list"),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text("Gestiona encuentros y cupones", style = MaterialTheme.typography.titleLarge, color = IndigoDark)
                    Text("Solo personal autorizado. Confirmar asistencia requiere comprobar que la persona estuvo presente.",
                        Modifier.padding(top = 8.dp), color = TextMuted)
                }
                if (state.denied) item {
                    OperatorCard {
                        Text("Acceso restringido", style = MaterialTheme.typography.titleMedium, color = Indigo)
                        Text("Tu cuenta no tiene permiso de operador. Puedes seguir usando tus planes y beneficios personales.")
                        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Volver a mi perfil") }
                    }
                } else if (!state.requiresLogin) {
                    item {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            if (maxWidth < 360.dp || LocalDensity.current.fontScale > 1.2f) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    OperatorMode(false, state.redeemTab, enabled, onTab, Modifier.fillMaxWidth())
                                    OperatorMode(true, state.redeemTab, enabled, onTab, Modifier.fillMaxWidth())
                                }
                            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OperatorMode(false, state.redeemTab, enabled, onTab, Modifier.weight(1f))
                                OperatorMode(true, state.redeemTab, enabled, onTab, Modifier.weight(1f))
                            }
                        }
                    }
                    if (state.loading || state.writing) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Indigo) }
                    if (state.error != null) item {
                        OperatorCard {
                            Text(state.error, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRefresh, enabled = enabled) { Text("Actualizar ofertas") }
                        }
                    }
                    if (state.success != null) item {
                        OperatorCard {
                            Text("Operación confirmada", color = Indigo, style = MaterialTheme.typography.titleMedium)
                            Text(state.success)
                        }
                    }
                    if (state.redeemTab && state.loaded) item {
                        OperatorCard {
                            Text("Canjear un cupón", style = MaterialTheme.typography.titleMedium, color = Indigo)
                            Text("Pide a la persona su código vigente. No uses su contraseña ni un código de inicio de sesión.", color = TextMuted)
                            OutlinedTextField(value = state.code, onValueChange = onCode, enabled = enabled,
                                label = { Text("Código del cupón") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("operator-code"))
                            Button(onClick = onReviewCode, enabled = enabled && state.code.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Orange)) { Text("Revisar canje") }
                            Text("Solo se consume tras confirmar. Sin conexión no se valida localmente.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                    } else if (!state.redeemTab) {
                        val plan = state.plan
                        if (plan != null) {
                            item {
                                TextButton(onClick = onCatalog, enabled = enabled) { Text("Cambiar encuentro") }
                                Text(plan.title, style = MaterialTheme.typography.titleLarge, color = IndigoDark)
                                Text(state.offer?.merchantName.orEmpty(), color = TextMuted)
                                Text("Selecciona a quien estuvo presente. Tu propia cuenta no aparece.", Modifier.padding(top = 8.dp))
                            }
                            if (plan.members.isEmpty()) item { Text("No hay participantes elegibles para confirmar.", color = TextMuted) }
                            items(plan.members, key = { it.userId }) { member ->
                                OperatorCard {
                                    Text(member.name, style = MaterialTheme.typography.titleMedium)
                                    if (member.isHost) Text("Anfitrión", style = MaterialTheme.typography.labelMedium, color = Indigo)
                                    OutlinedButton(onClick = { onMember(member) }, enabled = enabled,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Revisar asistencia") }
                                }
                            }
                        } else {
                            if (state.loaded && state.offers.isEmpty() && state.error == null) item {
                                OperatorCard { Text(if (state.nextKey != null) "No hay ofertas en esta página. Puedes seguir buscando." else "No hay ofertas vigentes asignadas a tus locales.") }
                            }
                            items(state.offers, key = { it.id }) { offer ->
                                OperatorCard {
                                    Text(offer.merchantName, color = Indigo, style = MaterialTheme.typography.labelLarge)
                                    Text(offer.title, style = MaterialTheme.typography.titleMedium)
                                    Text(offer.discount, color = Orange)
                                    Text(offer.conditions, color = TextMuted)
                                    offer.planIds.forEachIndexed { index, id ->
                                        OutlinedButton(onClick = { onSelect(offer, id) }, enabled = enabled,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Ver encuentro ${index + 1}") }
                                    }
                                    if (offer.planIds.isEmpty()) Text("Sin encuentros autorizados.", color = TextMuted)
                                }
                            }
                            item {
                                if (state.nextKey != null) OutlinedButton(onClick = onMore, enabled = enabled,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cargar más ofertas") }
                                TextButton(onClick = onRefresh, enabled = enabled) { Text("Actualizar ofertas") }
                            }
                        }
                    }
                }
            }
        }
    }
    state.pending?.let { command ->
        AlertDialog(onDismissRequest = { if (!state.writing) onDismiss() }, containerColor = Color.White,
            title = { Text(if (command.code != null) "Confirmar canje" else "Confirmar asistencia", color = IndigoDark) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (command.code != null) {
                        Text("Código: ${command.code}")
                        Text("El servidor consumirá este cupón una sola vez si es válido para tu local.")
                    } else {
                        Text(command.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        Text(state.plan?.title.orEmpty())
                        Text(state.offer?.discount.orEmpty(), color = Orange)
                        Text(state.offer?.conditions.orEmpty(), color = TextMuted)
                        Text("Confirma solo si comprobaste su asistencia. Se registrará y emitirá un cupón con las condiciones de esta oferta.")
                    }
                    if (state.operationError != null) {
                        Text(state.operationError, color = MaterialTheme.colorScheme.error)
                        Text("Cerrar esta revisión no revierte una solicitud ya enviada.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.writing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Indigo)
                }
            }, confirmButton = {
                Button(onClick = onConfirm, enabled = !state.writing, colors = ButtonDefaults.buttonColors(containerColor = Orange),
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.operationError != null) "Reintentar misma solicitud" else "Confirmar") }
            }, dismissButton = { TextButton(onClick = onDismiss, enabled = !state.writing) {
                Text(if (state.operationError != null) "Cerrar revisión" else "Volver sin enviar")
            } })
    }
}

@Composable
private fun OperatorMode(redeem: Boolean, selected: Boolean, enabled: Boolean, onTab: (Boolean) -> Unit, modifier: Modifier) {
    FilterChip(selected = selected == redeem, onClick = { onTab(redeem) }, enabled = enabled,
        label = { Text(if (redeem) "Canjear código" else "Asistencias", maxLines = 1) }, modifier = modifier.heightIn(min = 48.dp))
}

@Composable
private fun OperatorCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
