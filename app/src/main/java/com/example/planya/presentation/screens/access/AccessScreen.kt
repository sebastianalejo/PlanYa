package com.example.planya.presentation.screens.access

import android.util.Patterns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import com.example.planya.core.network.AuthConfig
import com.example.planya.R
import com.example.planya.ui.theme.*

@Composable
fun AccessScreen(state: AccountState, onMode: (AccessMode) -> Unit,
    onSubmit: (String, String, String, String) -> Unit, onResend: (String) -> Unit, onExplore: () -> Unit,
    onGoogle: () -> Unit = {}, onCancelGoogle: () -> Unit = {}, onRestore: () -> Unit = {}) {
    val busy = state.busy || state.restoring
    var email by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    // Las contraseñas no se guardan en el estado persistido de Android.
    var password by remember { mutableStateOf("") }
    var repeatPassword by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<String?>(null) }
    var emailOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.mode) { password = ""; repeatPassword = ""; code = ""; visible = false; validation = null }
    var resendSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.resendAvailableAt) {
        do {
            resendSeconds = ((state.resendAvailableAt - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0).toInt()
            if (resendSeconds > 0) kotlinx.coroutines.delay(1000)
        } while (resendSeconds > 0)
    }
    val mode = state.mode
    val showForm = mode != AccessMode.LOGIN || emailOpen
    BackHandler(enabled = mode != AccessMode.LOGIN || emailOpen || busy) {
        if (!busy) {
            if (mode == AccessMode.LOGIN) { emailOpen = false; password = ""; validation = null }
            else onMode(AccessMode.LOGIN)
        }
    }
    val title = when (mode) {
        AccessMode.LOGIN -> if (emailOpen) "Ingresa con tu correo" else "Conéctate al instante"
        AccessMode.REGISTER -> "Crea tu cuenta"
        AccessMode.VERIFY -> "Confirma tu correo"
        AccessMode.RECOVER -> "Recupera tu acceso"
        AccessMode.RESET -> "Nueva contraseña"
    }
    val submit = when (mode) {
        AccessMode.LOGIN -> "Ingresar"
        AccessMode.REGISTER -> "Crear cuenta"
        AccessMode.VERIFY -> "Confirmar correo"
        AccessMode.RECOVER -> "Enviar código"
        AccessMode.RESET -> "Guardar contraseña"
    }
    Surface(color = ScreenBg, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Image(painterResource(R.drawable.planya_wordmark), "Logo de PlanYa",
                    Modifier.width(160.dp).height(80.dp))
                Surface(shape = RoundedCornerShape(28.dp), color = Color.White,
                    modifier = Modifier.fillMaxWidth(), shadowElevation = 1.dp) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = IndigoDark,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text(when (mode) {
                    AccessMode.LOGIN -> if (emailOpen) "Vuelve a tus planes y a los encuentros que compartes."
                        else "Tu próximo plan espontáneo está a un toque de distancia."
                    AccessMode.REGISTER -> "Crea tu cuenta y encuentra personas con tus mismos intereses."
                    AccessMode.VERIFY -> "Revisa tu correo e ingresa el código que recibiste."
                    AccessMode.RECOVER -> "Te enviaremos un código para recuperar tu cuenta."
                    AccessMode.RESET -> "Elige una contraseña nueva para volver a tus planes."
                }, style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (state.restoreError != null) {
                    Text(state.restoreError, color = BadgeBrown, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onRestore, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Reintentar sesión guardada") }
                }
                if (!AuthConfig.configured) Text("El acceso por cuenta aún no está habilitado. Por ahora puedes explorar los planes.",
                    style = MaterialTheme.typography.bodySmall, color = BadgeBrown)
                if ((mode == AccessMode.LOGIN && !emailOpen) || mode == AccessMode.REGISTER) {
                    OutlinedButton(onClick = onGoogle, enabled = !busy && AuthConfig.googleConfigured,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, Color(0xFF747775)),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White,
                            contentColor = Color(0xFF1F1F1F))) {
                        Image(painterResource(R.drawable.google_g), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Continuar con Google", style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium))
                    }
                    if (mode == AccessMode.REGISTER)
                    Text("o continúa con tu correo", style = MaterialTheme.typography.bodySmall, color = TextMuted,
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                if (mode == AccessMode.LOGIN && !emailOpen) {
                    OutlinedButton(onClick = { emailOpen = true; validation = null }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        border = BorderStroke(1.dp, Indigo), shape = RoundedCornerShape(50)) {
                        Icon(Icons.Outlined.Email, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp)); Text("Correo electrónico")
                    }
                }
                if (showForm) {
                if (mode == AccessMode.REGISTER) OutlinedTextField(name, { name = it.take(80) },
                    label = { Text("Tu nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                OutlinedTextField(email, { email = it.trim() }, label = { Text("Correo electrónico") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true,
                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
                if (mode in listOf(AccessMode.VERIFY, AccessMode.RESET)) OutlinedTextField(code, { code = it.take(12) },
                    label = { Text("Código del correo") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                if (mode in listOf(AccessMode.LOGIN, AccessMode.REGISTER, AccessMode.RESET)) {
                    OutlinedTextField(password, { password = it }, label = { Text("Contraseña") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                if (visible) "Ocultar contraseña" else "Mostrar contraseña")
                        } }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    if (mode != AccessMode.LOGIN) {
                        OutlinedTextField(repeatPassword, { repeatPassword = it }, label = { Text("Repite la contraseña") },
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            singleLine = true, enabled = !busy)
                        Text("Usa al menos 8 caracteres, mayúscula, minúscula, número y símbolo.",
                            style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                }
                (validation ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                state.message?.let { Text(it, color = Indigo, style = MaterialTheme.typography.bodyMedium) }
                if (mode in listOf(AccessMode.RECOVER, AccessMode.RESET)) Text("Si ingresaste con Google, vuelve con Continuar con Google. Este formulario restablece la contraseña de una cuenta creada con correo en PlanYa.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                Button(enabled = !busy && AuthConfig.configured,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange), onClick = {
                        validation = when {
                            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> "Escribe un correo válido."
                            mode == AccessMode.REGISTER && name.trim().length < 2 -> "Escribe tu nombre."
                            mode in listOf(AccessMode.LOGIN, AccessMode.REGISTER, AccessMode.RESET) && password.isEmpty() -> "Escribe tu contraseña."
                            mode in listOf(AccessMode.REGISTER, AccessMode.RESET) &&
                                (password.length < 8 || password.none(Char::isUpperCase) || password.none(Char::isLowerCase) ||
                                    password.none(Char::isDigit) || password.all(Char::isLetterOrDigit)) -> "La contraseña debe cumplir los requisitos indicados."
                            mode in listOf(AccessMode.REGISTER, AccessMode.RESET) && password != repeatPassword -> "Las contraseñas no coinciden."
                            mode in listOf(AccessMode.VERIFY, AccessMode.RESET) && code.isBlank() -> "Escribe el código recibido."
                            else -> null
                        }
                        if (validation == null) onSubmit(name, email, password, code)
                    }) { Text(if (state.restoring) "Recuperando sesión…" else if (state.busy) "Espera un momento…" else submit) }
                if (mode == AccessMode.LOGIN) {
                    TextButton(onClick = { onMode(AccessMode.RECOVER) }, enabled = !busy) { Text("Olvidé mi contraseña") }
                } else {
                    if (mode in listOf(AccessMode.VERIFY, AccessMode.RESET)) TextButton(onClick = {
                        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) validation = "Escribe un correo válido." else { validation = null; onResend(email) }
                    }, enabled = !busy && resendSeconds == 0 && AuthConfig.configured) { Text(if (resendSeconds > 0) "Reenviar en ${resendSeconds}s" else "Reenviar código") }
                    TextButton(onClick = { onMode(AccessMode.LOGIN) }, enabled = !busy) { Text("Volver a ingresar") }
                }
                }
                if (!showForm) {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    state.message?.let { Text(it, color = Indigo, style = MaterialTheme.typography.bodySmall) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.googleInProgress) TextButton(onClick = onCancelGoogle) { Text("Cancelar acceso con Google") }
                if (mode == AccessMode.LOGIN) {
                    if (emailOpen) TextButton(onClick = { emailOpen = false; password = ""; validation = null }, enabled = !busy,
                        modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Otras formas de ingresar") }
                    TextButton(onClick = { onMode(AccessMode.REGISTER) }, enabled = !busy,
                        modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Quiero crear una cuenta") }
                }
                }
                }
                TextButton(onClick = onExplore, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Explorar planes sin cuenta") }
            }
        }
    }
}
