package com.example.planya.presentation.screens.createplan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.planya.domain.model.*
import com.example.planya.presentation.components.LocationPicker
import com.example.planya.presentation.components.PlanPhotoPicker
import com.example.planya.presentation.components.PlanReviewSheet
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import android.net.Uri
import com.example.planya.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanEditorScreen(state: EditorState, editing: Boolean, currentArea: LocatedArea?,
    onChange: (PlanDraft) -> Unit, onSave: () -> Unit, onRetry: () -> Unit, onBack: () -> Unit,
    onPhotoSelected: (Uri) -> Unit = {}, onPhotoRemove: () -> Unit = {
        onChange(state.draft.copy(imageUrl = "", imageKey = null, localPhotoName = null))
    }, onPhotoError: (String) -> Unit = {}, onPhotoRemoveAt: (Int) -> Unit = { onPhotoRemove() }, onPhotoCover: (Int) -> Unit = {}) {
    var showLocation by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun back() { if (state.busy || state.photoBusy) return; if (state.dirty) discard = true else onBack() }
    BackHandler { back() }
    val draft = state.draft
    val editable = !state.busy && !state.photoBusy && !state.loading && state.loaded
    Scaffold(containerColor = ScreenBg,
        topBar = { TopAppBar(title = { Text(if (editing) "Editar mi plan" else "Crear un plan", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = { back() }, enabled = !state.busy && !state.photoBusy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ScreenBg)) },
        bottomBar = {
            Surface {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
                    Button(onClick = { keyboard?.hide(); if (draft.error() != null) onSave() else preview = true }, enabled = editable && !state.conflict,
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(min = 50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Orange), shape = RoundedCornerShape(50)) {
                        Text(if (state.photoBusy) "Preparando foto…" else if (state.busy) state.stage ?: "Guardando…" else if (editing) "Revisar cambios" else "Revisar plan")
                    }
                }
            }
        }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.loading || state.busy || state.photoBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (editing) "Actualiza los datos de tu encuentro." else "¿Qué hacemos hoy?", style = MaterialTheme.typography.titleLarge, color = IndigoDark)
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    if (editing && !state.loaded) TextButton(onClick = onRetry, enabled = !state.loading) { Text("Reintentar carga") }
                    if (editing && state.conflict) OutlinedButton(onClick = { reload = true }, enabled = editable) { Text("Recargar versión actual") }
                }
                OutlinedTextField(draft.title, { onChange(draft.copy(title = it.take(120))) }, label = { Text("Título del plan") },
                    modifier = Modifier.fillMaxWidth(), enabled = editable, maxLines = 2)
                OutlinedTextField(draft.description, { onChange(draft.copy(description = it.take(1000))) },
                    label = { Text("Cuéntanos qué harán") }, modifier = Modifier.fillMaxWidth(), enabled = editable, minLines = 3, maxLines = 6)
                EditorSelect("Categoría", draft.category.label, PlanCategory.entries.map { it.label }, editable) { selected ->
                    onChange(draft.copy(category = PlanCategory.entries.first { it.label == selected }))
                }
                OutlinedButton(onClick = { showLocation = true }, enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Icon(Icons.Outlined.LocationOn, null)
                    Spacer(Modifier.width(8.dp))
                    Text(draft.location?.fullAddress ?: draft.location?.label ?: "Elegir dirección del encuentro")
                }
                Text("El punto de encuentro aparecerá en el plan. Confirma la calle y el número antes de publicarlo.",
                    style = MaterialTheme.typography.bodySmall, color = TextMuted)
                OutlinedTextField(draft.capacity, { onChange(draft.copy(capacity = it.filter(Char::isDigit).take(3))) },
                    label = { Text("Cupo total, incluyéndote") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), enabled = editable, singleLine = true)
                if (editing && draft.minimumCapacity > 2) Text("Ya hay ${draft.minimumCapacity} asistentes confirmados.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                if (!editing) {
                    EditorSelect("¿Cuándo empieza?", if (draft.startsIn == 0) "Ahora" else "En ${draft.startsIn} min",
                        listOf("Ahora", "En 15 min", "En 30 min", "En 60 min"), !state.busy) {
                    val minutes = if (it == "Ahora") 0 else it.filter(Char::isDigit).toInt()
                    onChange(draft.copy(startsIn = minutes, duration = maxOf(draft.duration, minutes + 30)))
                    }
                    EditorSelect("El plan vence en", "${draft.duration} min", listOf("30 min", "60 min", "90 min", "120 min"), !state.busy) {
                        val minutes = it.filter(Char::isDigit).toInt()
                        onChange(draft.copy(duration = minutes, startsIn = minOf(draft.startsIn, minutes - 15)))
                    }
                    Text("El tiempo se cuenta desde la publicación. El plan dejará de aparecer cuando venza.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                } else Text("Editar conserva el horario y el vencimiento originales.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                PlanPhotoPicker(draft, editable, onPhotoSelected, onPhotoRemove, onPhotoError, onPhotoRemoveAt, onPhotoCover)
                TextButton(onClick = { back() }, enabled = !state.busy) { Text("Cancelar") }
            }
        }
    }
    if (showLocation) LocationPicker(draft.location ?: currentArea,
        onSelect = { onChange(draft.copy(location = it)) }, onDismiss = { showLocation = false }, allowGoogle = false)
    if (preview) PlanReviewSheet(draft, editing, onConfirm = { preview = false; onSave() }, onDismiss = { preview = false })
    if (reload) AlertDialog(onDismissRequest = { reload = false }, containerColor = androidx.compose.ui.graphics.Color.White,
        title = { Text("¿Recargar el plan?") }, text = { Text("Si la carga funciona, reemplazará tus cambios sin guardar y la foto seleccionada por los datos actuales del plan. Si falla, conservarás el borrador.") },
        confirmButton = { TextButton(onClick = { reload = false; onRetry() }) { Text("Sí, recargar") } },
        dismissButton = { TextButton(onClick = { reload = false }) { Text("Conservar borrador") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("¿Descartar cambios?") },
        text = { Text("Los cambios que hiciste aquí todavía no se guardaron.") },
        confirmButton = { TextButton(onClick = { discard = false; onBack() }) { Text("Descartar") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Seguir editando") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorSelect(label: String, selected: String, options: List<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = !expanded }) {
            OutlinedTextField(value = selected, onValueChange = {}, readOnly = true, enabled = enabled,
                label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                shape = RoundedCornerShape(14.dp), modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth())
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = androidx.compose.ui.graphics.Color.White) {
                options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelect(option) }) }
            }
    }
}
