package com.example.planya.presentation.screens.operator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.planya.presentation.components.QrCameraPreview
import com.example.planya.ui.theme.*

/**
 * US-14: lector de QR del operador. Leer NO canjea: solo muestra el código. El canje se envía
 * cuando el operador pulsa «Confirmar canje», y solo se anuncia con el recibo del servidor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperatorScanScreen(state: OperatorUiState, onBack: () -> Unit, onManual: () -> Unit, onScanned: (String) -> Unit,
    onConfirm: () -> Unit, onRescan: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    fun hasCamera() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(hasCamera()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; if (it) cameraFailed = false }
    LaunchedEffect(Unit) { if (!granted && !asked) { asked = true; launcher.launch(Manifest.permission.CAMERA) } }
    // Al volver de Ajustes se comprueba de nuevo el permiso.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = hasCamera() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = state.writing) { /* Esperar la respuesta del servidor. */ }
    val reading = state.pending != null && state.pending.code != null
    val paused = reading || state.writing || state.receipt != null

    Scaffold(containerColor = ScreenBg, topBar = {
        TopAppBar(title = { Text("Escanear cupón", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = onBack, enabled = !state.writing) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ScreenBg))
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(20.dp), tint = Indigo)
                    Text("Solo operadores autorizados", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                }
                when {
                    state.receipt != null -> ReceiptCard(state.receipt.benefitId, onRescan, onBack)
                    !granted || cameraFailed -> CameraUnavailable(granted, onAllow = { launcher.launch(Manifest.permission.CAMERA) },
                        onSettings = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)))
                        }, onManual = onManual)
                    else -> {
                        CameraWindow(paused) { QrCameraPreview(paused, onScanned, { cameraFailed = true }, Modifier.fillMaxSize()) }
                        if (state.scanError != null) Text(state.scanError, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("scan-error"))
                        if (!reading) Text("Apunta la cámara al QR del cliente. Solo se leen cupones de PlanYa.",
                            style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        if (reading) ReviewCard(state, onConfirm, onRescan)
                        OutlinedButton(onClick = { onRescan(); onManual() }, enabled = !state.writing,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(50)) {
                            Text("Ingresar código manualmente")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraWindow(paused: Boolean, preview: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(Color(0xFF1B1D22))
        .semantics { contentDescription = "Vista de la cámara para leer el código QR del cupón" }) {
        preview()
        Canvas(Modifier.fillMaxSize().padding(48.dp)) {
            val arm = size.minDimension * .18f
            val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            val corner = Orange
            val w = size.width; val h = size.height
            fun l(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(corner, Offset(x, y), Offset(x + dx * arm, y), stroke.width, StrokeCap.Round)
                drawLine(corner, Offset(x, y), Offset(x, y + dy * arm), stroke.width, StrokeCap.Round)
            }
            l(0f, 0f, 1f, 1f); l(w, 0f, -1f, 1f); l(0f, h, 1f, -1f); l(w, h, -1f, -1f)
        }
        if (paused) Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp), color = Color(0xCC2F3132), shape = RoundedCornerShape(50)) {
            Text("Lectura pausada", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White,
                style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ReviewCard(state: OperatorUiState, onConfirm: () -> Unit, onRescan: () -> Unit) {
    val code = state.pending?.code.orEmpty()
    Surface(color = Color.White, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Código leído", style = MaterialTheme.typography.labelLarge, color = TextMuted)
            Surface(color = ScreenBg, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, OutlineSoft)) {
                Text(code, Modifier.fillMaxWidth().padding(14.dp).testTag("scan-code"),
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold), color = TextPrimary)
            }
            Text("Leer el QR no confirma el canje.", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text("El servidor comprobará vigencia y permisos al confirmar.", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            if (state.operationError != null) {
                Text(state.operationError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Text("Reintentar usa la misma solicitud: no se consume dos veces.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            if (state.writing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Indigo)
            Button(onClick = onConfirm, enabled = !state.writing, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = Orange)) {
                Text(if (state.operationError != null) "Reintentar misma solicitud" else "Confirmar canje")
            }
            TextButton(onClick = onRescan, enabled = !state.writing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Volver a escanear")
            }
        }
    }
}

@Composable
private fun ReceiptCard(benefitId: String, onRescan: () -> Unit, onBack: () -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(44.dp), tint = Indigo)
            Text("Cupón USADO", style = MaterialTheme.typography.titleLarge, color = IndigoDark)
            Text("Canje confirmado por el servidor.", style = MaterialTheme.typography.bodyMedium, color = TextMuted, textAlign = TextAlign.Center)
            Text("Recibo: $benefitId", Modifier.testTag("scan-receipt"), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = TextPrimary, textAlign = TextAlign.Center)
            Button(onClick = onRescan, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = Orange)) { Text("Escanear otro cupón") }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(50)) {
                Text("Volver al panel")
            }
        }
    }
}

@Composable
private fun CameraUnavailable(permissionGranted: Boolean, onAllow: () -> Unit, onSettings: () -> Unit, onManual: () -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (permissionGranted) "No se pudo iniciar la cámara" else "Necesitamos la cámara para leer el QR",
                style = MaterialTheme.typography.titleMedium, color = IndigoDark)
            Text(if (permissionGranted) "Cierra otras aplicaciones que usen la cámara e inténtalo de nuevo, o escribe el código."
            else "La imagen se analiza en tu teléfono y no se guarda ni se envía. También puedes escribir el código a mano.",
                style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            if (!permissionGranted) {
                Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange)) { Text("Permitir cámara") }
                TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Abrir ajustes de la app") }
            }
            OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(50)) {
                Text("Ingresar código manualmente")
            }
        }
    }
}
