package com.example.planya.presentation.screens.profile

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.planya.presentation.components.PlanPhotoPicker
import com.example.planya.presentation.screens.createplan.PlanDraft
import com.example.planya.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(state: ProfileState, onName: (String) -> Unit, onPhoto: (Uri) -> Unit,
    onRemovePhoto: () -> Unit, onError: (String) -> Unit, onSave: () -> Unit, onReload: () -> Unit, onBack: () -> Unit) {
    var confirm by remember { mutableStateOf<String?>(null) }
    val busy = state.busy || state.photoBusy
    val enabled = state.loaded && !busy && !state.loading
    fun back() { if (!busy) { if (state.dirty) confirm = "discard" else onBack() } }
    BackHandler { back() }
    Scaffold(containerColor = ScreenBg,
        topBar = { TopAppBar(title = { Text("Editar perfil") }, navigationIcon = {
            IconButton(onClick = { back() }, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver al perfil") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = ScreenBg)) },
        bottomBar = { Surface(color = Color.White) {
            Button(onClick = onSave, enabled = enabled && state.dirty && !state.conflict,
                modifier = Modifier.navigationBarsPadding().imePadding().padding(16.dp).fillMaxWidth().heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Indigo)) {
                Text(if (state.busy) "Guardando perfil…" else if (state.photoBusy) "Preparando foto…" else "Guardar perfil")
            }
        } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (state.loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Así te verán en PlanYa", style = MaterialTheme.typography.titleLarge, color = IndigoDark)
                Text("Tu nombre de PlanYa es independiente de Google. Tu correo no se cambia desde esta pantalla.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!state.loaded || state.conflict) OutlinedButton(onClick = {
                    if (state.dirty) confirm = "reload" else onReload()
                }, enabled = !busy && !state.loading) { Text(if (state.conflict) "Recargar perfil" else "Reintentar carga") }
                OutlinedTextField(state.name, onName, label = { Text("Nombre visible") }, singleLine = true,
                    supportingText = { Text("${state.name.length}/80") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(state.profile?.email.orEmpty(), {}, label = { Text("Correo de tu cuenta") }, readOnly = true,
                    enabled = false, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(disabledTextColor = TextMuted, disabledLabelColor = TextMuted))
                val draft = PlanDraft(imageUrl = if (state.removeAvatar) "" else state.profile?.avatarUrl.orEmpty(),
                    imageKey = if (state.removeAvatar) null else state.profile?.avatarKey, localPhotoName = state.localPhoto)
                PlanPhotoPicker(draft, enabled, onPhoto, onRemovePhoto, onError, profilePhoto = true)
                Text("La foto de perfil no vence cuando termina un plan. Puedes cambiarla o quitarla aquí.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }
    }
    if (confirm != null) AlertDialog(onDismissRequest = { confirm = null }, containerColor = Color.White,
        title = { Text(if (confirm == "reload") "¿Recargar tu perfil?" else "¿Descartar cambios?") },
        text = { Text(if (confirm == "reload") "Una carga exitosa reemplazará tu borrador por el perfil guardado. Si falla, tus cambios se conservan."
            else "El nombre y la foto que cambiaste todavía no se guardaron.") },
        confirmButton = { TextButton(onClick = { val action = confirm; confirm = null; if (action == "reload") onReload() else onBack() }) {
            Text(if (confirm == "reload") "Sí, recargar" else "Descartar") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Seguir editando") } })
}
