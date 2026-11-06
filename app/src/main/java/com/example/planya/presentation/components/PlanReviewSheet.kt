package com.example.planya.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.example.planya.data.photos.PlanPhotoStore
import com.example.planya.presentation.screens.createplan.PlanDraft
import com.example.planya.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanReviewSheet(draft: PlanDraft, editing: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { PlanPhotoStore(context.applicationContext) }
    val photoModels = draft.gallery().map { photo -> photo.localName?.let { runCatching { store.file(it).takeIf { file -> file.isFile } }.getOrNull() }
        ?: photo.imageUrl.takeIf(String::isNotBlank) }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .90f
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White, tonalElevation = 0.dp, scrimColor = Color.Black.copy(alpha = .42f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = OutlineSoft) }) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("plan-review")) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (editing) "Revisa los cambios" else "Revisa tu plan", style = MaterialTheme.typography.titleLarge, color = IndigoDark)
                    Text("Comprueba los datos antes de ${if (editing) "guardar" else "publicar"}.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Volver al formulario", tint = TextMuted) }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (photoModels.isNotEmpty()) PhotoGallery(photoModels, "Foto del plan para revisar",
                    Modifier.fillMaxWidth().aspectRatio(1.9f).clip(RoundedCornerShape(18.dp)))
                Surface(color = ChipBg, shape = RoundedCornerShape(50)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(categoryIcon(draft.category), null, Modifier.size(16.dp), tint = Indigo)
                        Text(draft.category.label, style = MaterialTheme.typography.labelMedium, color = IndigoDark)
                    }
                }
                Text(draft.title.trim(), style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                if (draft.description.isNotBlank()) Text(draft.description.trim(), style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                Surface(color = ScreenBg, shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ReviewLine(Icons.Outlined.LocationOn, "Punto de encuentro", listOfNotNull(draft.location?.displayAddress,
                            draft.location?.reference).joinToString(" · "))
                        ReviewLine(Icons.Outlined.Groups, "Cupo total", "${draft.capacity} personas, incluyéndote")
                        if (editing) {
                            ReviewLine(Icons.Outlined.Schedule, "Horario", "Se conservan el inicio y el vencimiento originales.")
                            val formatter = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
                            draft.originalStartsAt?.let { ReviewLine(Icons.Outlined.Schedule, "Inicio original", formatter.format(java.util.Date(it * 1000))) }
                            draft.originalExpiresAt?.let { ReviewLine(Icons.Outlined.Schedule, "Vencimiento original", formatter.format(java.util.Date(it * 1000))) }
                        }
                        else {
                            ReviewLine(Icons.Outlined.Schedule, "Empieza", if (draft.startsIn == 0) "Ahora" else "En ${draft.startsIn} minutos")
                            ReviewLine(Icons.Outlined.Schedule, "Deja de aparecer", "En ${draft.duration} minutos desde la publicación")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Visibility, null, Modifier.size(18.dp), tint = TextMuted)
                    Text(if (photoModels.isNotEmpty()) "La dirección y las fotos del encuentro serán visibles para quienes consulten el plan."
                        else "La dirección del encuentro será visible para quienes consulten el plan.",
                        style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
            HorizontalDivider(color = OutlineSoft.copy(alpha = .6f))
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White)) {
                    Text(if (editing) "Confirmar cambios" else "Confirmar publicación")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Seguir editando", color = Indigo) }
            }
        }
    }
}

@Composable
private fun ReviewLine(icon: ImageVector, label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = Indigo)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        }
    }
}
