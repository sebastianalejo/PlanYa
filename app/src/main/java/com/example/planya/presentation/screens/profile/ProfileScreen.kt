package com.example.planya.presentation.screens.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.planya.data.profile.ProfileActivity
import com.example.planya.data.profile.ProfileConfidence
import com.example.planya.data.profile.ProfileVerification
import com.example.planya.domain.model.*
import com.example.planya.presentation.components.*
import com.example.planya.ui.theme.*
import java.util.Locale

// Perfil real: la actividad, la confianza y la verificación las decide el servidor.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(name: String?, email: String?, interests: Set<String>, area: LocatedArea?,
    hasSavedGoogleLocation: Boolean, busy: Boolean, error: String?, onBack: () -> Unit,
    onInterests: () -> Unit, onLocation: () -> Unit, onFeed: (PlanFeedScope) -> Unit,
    onLogin: () -> Unit, onLogout: () -> Unit, onNavigate: (BottomTab) -> Unit, onCreate: () -> Unit,
    avatarUrl: String? = null, onEdit: (() -> Unit)? = null, profileLoading: Boolean = false,
    profileError: String? = null, onRetryProfile: () -> Unit = {}, interestsSyncing: Boolean = false,
    interestsError: String? = null, onRetryInterests: () -> Unit = {},
    verification: ProfileVerification? = null,
    activity: ProfileActivity? = null, activityLoading: Boolean = false, activityError: String? = null,
    onRetryActivity: () -> Unit = {},
    confidence: ProfileConfidence? = null, confidenceLoading: Boolean = false, confidenceError: String? = null,
    onRetryConfidence: () -> Unit = {},
    onChat: (() -> Unit)? = null, onBenefits: (() -> Unit)? = null, onOperator: (() -> Unit)? = null) {
    val signedIn = name != null
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = busy) { /* Esperar a que termine el cierre de sesión. */ }
    val interestLabels = PlanCategory.entries.filter { it.name in interests }.joinToString(" · ") { it.label }
    Scaffold(containerColor = ScreenBg,
        topBar = { TopAppBar(title = { Text("Mi perfil", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ScreenBg)) },
        bottomBar = { AppBottomBar({ if (!busy) onNavigate(it) }, { if (!busy) onCreate() }, selectedTab = BottomTab.PERFIL) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxSize().testTag("perfil-lista"), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item {
                    Surface(color = Color.White, shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(color = ChipBg, shape = CircleShape) {
                                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.PersonOutline, null, Modifier.size(36.dp), tint = Indigo)
                                    if (avatarUrl != null) coil3.compose.AsyncImage(
                                        coil3.request.ImageRequest.Builder(LocalContext.current).data(avatarUrl)
                                            .diskCachePolicy(coil3.request.CachePolicy.DISABLED).memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build(),
                                        "Tu foto de perfil", Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                                }
                            }
                            Text(name ?: "Tu próximo plan empieza aquí", style = MaterialTheme.typography.headlineSmall, color = IndigoDark)
                            if (signedIn) {
                                if (!email.isNullOrBlank()) Text(email, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp), tint = Indigo)
                                    Text("Sesión de PlanYa iniciada", style = MaterialTheme.typography.labelMedium, color = Indigo)
                                }
                                if (!profileLoading) VerificationStatus(verification)
                                if (profileLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                                if (onEdit != null) OutlinedButton(onClick = onEdit, enabled = !busy && !profileLoading,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Editar perfil") }
                                if (profileError != null) {
                                    Text(profileError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    TextButton(onClick = onRetryProfile, enabled = !busy && !profileLoading) { Text("Reintentar perfil") }
                                }
                            } else {
                                Text("Ingresa para organizar encuentros y unirte a otros planes.", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                                Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.buttonColors(containerColor = Orange)) { Text("Ingresar o crear cuenta") }
                            }
                        }
                    }
                }
                item {
                    ProfileGroup("Tus encuentros") {
                        ProfileOption(Icons.AutoMirrored.Outlined.EventNote, "Mis planes", "Encuentros que organizas", !busy) {
                            if (signedIn) onFeed(PlanFeedScope.OWNED) else onLogin()
                        }
                        HorizontalDivider(Modifier.padding(start = 64.dp), color = OutlineSoft.copy(alpha = .6f))
                        ProfileOption(Icons.Outlined.Groups, "Me uní", "Encuentros en los que participas", !busy) {
                            if (signedIn) onFeed(PlanFeedScope.JOINED) else onLogin()
                        }
                    }
                }
                if (signedIn) item {
                    ProfileGroup("Tu actividad") { ActivitySummary(activity, activityLoading, activityError, onRetryActivity) }
                }
                if (signedIn) item {
                    ProfileGroup("Confianza") { ReputationSummary(confidence, confidenceLoading, confidenceError, onRetryConfidence) }
                }
                item {
                    ProfileGroup("Preferencias") {
                        ProfileOption(Icons.Outlined.FavoriteBorder, "Mis intereses", interestLabels.ifBlank { "Elige tus actividades favoritas" }, !busy, onInterests)
                        HorizontalDivider(Modifier.padding(start = 64.dp), color = OutlineSoft.copy(alpha = .6f))
                        ProfileOption(Icons.Outlined.LocationOn, "Mi ubicación", area?.displayAddress
                            ?: if (hasSavedGoogleLocation) "Recupera tu dirección elegida" else "Elige desde el mapa, GPS o buscador", !busy, onLocation)
                    }
                    if (interestsSyncing) Text("Sincronizando intereses…", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = Indigo)
                    if (interestsError != null) {
                        Text(interestsError, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetryInterests, enabled = !busy && !interestsSyncing) { Text("Reintentar intereses") }
                    }
                }
                if (signedIn && (onChat != null || onBenefits != null)) item {
                    ProfileGroup("Comunidad") {
                        onChat?.let {
                            ProfileOption(Icons.AutoMirrored.Outlined.Chat, "Mis conversaciones", "Chats de tus encuentros", !busy, it)
                            if (onBenefits != null) HorizontalDivider(Modifier.padding(start = 64.dp), color = OutlineSoft.copy(alpha = .6f))
                        }
                        onBenefits?.let {
                            ProfileOption(Icons.Outlined.CardGiftcard, "Mis beneficios", "Cupones y descuentos disponibles", !busy, it)
                        }
                    }
                }
                if (signedIn && onOperator != null) item {
                    ProfileGroup("Personal autorizado") {
                        ProfileOption(Icons.Outlined.Lock, "Acceso de operadores", "El servidor comprueba los permisos de esta cuenta", !busy, onOperator)
                    }
                }
                if (error != null) item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (signedIn) item {
                    OutlinedButton(onClick = { confirmLogout = true }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp)); Text(if (busy) "Cerrando sesión…" else "Cerrar sesión")
                    }
                }
                item {
                    Text("Tu ubicación de búsqueda no se publica en el perfil. La dirección que elijas en cada plan sí será visible en ese encuentro.",
                        style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
        }
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, containerColor = Color.White,
        title = { Text("¿Cerrar sesión?") },
        text = { Text("Cerrarás tu sesión de PlanYa en este dispositivo. Tus planes publicados no se borrarán.") },
        confirmButton = { Button(onClick = { confirmLogout = false; onLogout() },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Sí, cerrar sesión") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Quedarme") } })
}

@Composable
private fun VerificationStatus(verification: ProfileVerification?) {
    val verified = verification?.verified == true
    val pending = verification?.pending == true
    val tint = if (verified) Indigo else TextMuted
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(when {
            verified -> Icons.Filled.CheckCircle
            pending -> Icons.Outlined.Schedule
            else -> Icons.Outlined.Lock
        }, contentDescription = if (verified) "Cuenta verificada" else null, modifier = Modifier.size(16.dp), tint = tint)
        Text(when {
            verified -> "Verificación aprobada"
            pending -> "Verificación en revisión"
            else -> "Sin verificación aprobada"
        }, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun ActivitySummary(activity: ProfileActivity?, loading: Boolean, error: String?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry, enabled = !loading) { Text("Reintentar actividad") }
        }
        if (activity != null) {
            Row(Modifier.fillMaxWidth()) {
                ActivityMetric("Organizados", activity.organized, Modifier.weight(1f))
                ActivityMetric("Me uní", activity.joined, Modifier.weight(1f))
                ActivityMetric("Asistencias", activity.attended, Modifier.weight(1f))
            }
            Text("Las asistencias solo cuentan cuando están acreditadas por el servidor.",
                style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
}

@Composable
private fun ActivityMetric(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall, color = IndigoDark)
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextMuted)
    }
}

@Composable
private fun ReputationSummary(confidence: ProfileConfidence?, loading: Boolean, error: String?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry, enabled = !loading) { Text("Reintentar confianza") }
        }
        when {
            confidence != null && confidence.count <= 0 -> {
                Text("Sin valoraciones", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Text("Cuando recibas valoraciones válidas de otros participantes, aparecerán aquí.",
                    style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            confidence != null -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Star, null, modifier = Modifier.size(24.dp), tint = Orange)
                    Text(String.format(Locale.US, "%.1f", confidence.average ?: 0.0),
                        style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                    Text("· ${confidence.count} ${if (confidence.count == 1) "valoración" else "valoraciones"}",
                        style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                }
                Text("Promedio y cantidad calculados por el servidor.",
                    style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }
    }
}

@Composable
private fun ProfileGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = IndigoDark)
        Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) { Column(content = content) }
    }
}

@Composable
private fun ProfileOption(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = Color.White) {
        Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = ChipBg, shape = RoundedCornerShape(12.dp)) {
                Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = Indigo)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = TextMuted)
        }
    }
}
