package com.example.planya.presentation.components

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import com.example.planya.data.photos.PlanPhotoStore
import com.example.planya.presentation.screens.createplan.PlanDraft
import com.example.planya.ui.theme.*

@Composable
fun PlanPhotoPicker(draft: PlanDraft, enabled: Boolean, onSelected: (Uri) -> Unit,
    onRemove: () -> Unit, onFailure: (String) -> Unit,
    onRemoveAt: (Int) -> Unit = { onRemove() }, onCover: (Int) -> Unit = {}, profilePhoto: Boolean = false) {
    val context = LocalContext.current
    val store = remember(context) { PlanPhotoStore(context.applicationContext) }
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }
    val photos = draft.gallery()
    var selected by remember(photos) { mutableIntStateOf(0) }
    val current = photos.getOrNull(selected)
    var imageError by remember(current) { mutableStateOf(false) }
    val cameraContract = remember {
        object : ActivityResultContracts.TakePicture() {
            override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                clipData = ClipData.newRawUri("Foto del plan", input)
            }
        }
    }
    val camera = rememberLauncherForActivityResult(cameraContract) { taken ->
        val name = cameraFile
        cameraFile = null
        if (name != null) {
            if (taken) onSelected(store.uri(name)) else store.delete(name)
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onSelected(uri) // Cancelar no elimina la foto anterior.
    }
    val file = current?.localName?.let { runCatching { store.file(it).takeIf { photo -> photo.isFile } }.getOrNull() }
    val model: Any? = file ?: current?.imageUrl?.takeIf(String::isNotBlank)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (profilePhoto) "Foto de perfil (opcional)" else "Fotos del plan (opcional)", style = MaterialTheme.typography.titleSmall, color = IndigoDark)
            if (!profilePhoto) Text("${photos.size}/4", style = MaterialTheme.typography.labelLarge, color = Indigo)
        }
        if (model != null) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                AsyncImage(model = ImageRequest.Builder(context).data(model)
                    .diskCachePolicy(CachePolicy.DISABLED).memoryCachePolicy(CachePolicy.DISABLED).build(),
                    contentDescription = if (profilePhoto) "Vista previa de tu foto de perfil" else "Vista previa de la foto del plan", contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(), onError = { imageError = true }, onSuccess = { imageError = false })
                if (imageError) Text("No pudimos mostrar esta foto. Puedes elegir otra.", Modifier.padding(16.dp), color = TextMuted)
            }
        } else if (current?.localName != null) Text("La foto local ya no está disponible. Elige otra antes de publicar.", color = MaterialTheme.colorScheme.error)
        else Text(if (profilePhoto) "Puedes usar PlanYa sin foto de perfil." else "Muestra el lugar o la actividad. Puedes publicar sin foto.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        if (photos.isNotEmpty()) {
            if (!profilePhoto) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                photos.forEachIndexed { index, photo ->
                    val thumbnail: Any? = photo.localName?.let { runCatching { store.file(it) }.getOrNull() }
                        ?: photo.imageUrl.takeIf(String::isNotBlank)
                    OutlinedCard(onClick = { selected = index }, modifier = Modifier.width(88.dp), enabled = enabled,
                        colors = CardDefaults.outlinedCardColors(containerColor = if (selected == index) ChipBg else ScreenBg)) {
                        AsyncImage(ImageRequest.Builder(context).data(thumbnail).diskCachePolicy(CachePolicy.DISABLED)
                            .memoryCachePolicy(CachePolicy.DISABLED).build(), "Seleccionar foto ${index + 1}",
                            Modifier.fillMaxWidth().height(60.dp), contentScale = ContentScale.Crop)
                        Text(if (index == 0) "Portada" else "Foto ${index + 1}", Modifier.padding(6.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Row {
                TextButton(onClick = { onRemoveAt(selected) }, enabled = enabled) { Text("Quitar foto", color = MaterialTheme.colorScheme.error) }
                if (selected != 0) TextButton(onClick = { onCover(selected) }, enabled = enabled) { Text("Usar de portada") }
            }
        }
        if (!profilePhoto) Text(if (photos.size == 4) "Ya tienes 4 fotos. Quita una para añadir otra." else "Añade hasta 4 fotos. Toca una miniatura para verla o quitarla.",
            style = MaterialTheme.typography.bodySmall, color = TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                try {
                    val (name, uri) = store.newCameraFile()
                    cameraFile = name
                    camera.launch(uri)
                } catch (_: Exception) {
                    store.delete(cameraFile); cameraFile = null
                    onFailure("No se pudo abrir la cámara. Puedes elegir una foto de la galería.")
                }
            }, enabled = enabled && (profilePhoto || photos.size < 4), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.AddAPhoto, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Tomar foto")
            }
            OutlinedButton(onClick = {
                try { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                catch (_: Exception) { onFailure("No se pudo abrir la galería. Intenta con la cámara.") }
            }, enabled = enabled && (profilePhoto || photos.size < 4), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Elegir foto")
            }
        }
        Text(if (profilePhoto) "Elige una foto sin documentos ni información privada. Se enviará al pulsar Guardar perfil."
            else "Se comparte al publicar y deja de mostrarse cuando termine el plan. No subas documentos ni información privada.",
            style = MaterialTheme.typography.bodySmall, color = TextMuted)
    }
}
