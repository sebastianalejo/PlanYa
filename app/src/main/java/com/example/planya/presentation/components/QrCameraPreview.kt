package com.example.planya.presentation.components

import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Vista previa de la cámara trasera que lee códigos QR con ML Kit (modelo incorporado).
 *
 * - La cámara se vincula al ciclo de vida y se libera al salir.
 * - Cada fotograma se cierra siempre (`ImageProxy.close`), también si falla la lectura.
 * - No se guarda ni se sube vídeo: el análisis ocurre en el teléfono.
 * - Con [paused] el analizador se retira; la vista previa sigue visible.
 * - [onCode] recibe el texto crudo del QR en el hilo principal. Quien lo use debe validarlo.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun QrCameraPreview(paused: Boolean, onCode: (String) -> Unit, onCameraError: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCode by rememberUpdatedState(onCode)
    val latestOnError by rememberUpdatedState(onCameraError)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    val analysis = remember {
        ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
    }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) { latestOnError() }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            provider?.unbindAll()
        }
    }

    DisposableEffect(paused) {
        if (paused) analysis.clearAnalyzer()
        else analysis.setAnalyzer(executor) { proxy ->
            val image = proxy.image
            if (image == null) { proxy.close(); return@setAnalyzer }
            scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let { latestOnCode(it) } }
                .addOnCompleteListener { proxy.close() }
        }
        onDispose { }
    }

    DisposableEffect(Unit) {
        onDispose { analysis.clearAnalyzer(); scanner.close(); executor.shutdown() }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
