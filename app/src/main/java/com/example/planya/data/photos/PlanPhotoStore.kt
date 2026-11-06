package com.example.planya.data.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

// Solo archivos privados propios, nunca elimina la foto original de la galería.
class PlanPhotoStore(private val context: Context) {
    private val directory get() = File(context.filesDir, "plan_photos").apply { mkdirs() }
    fun file(name: String): File {
        require(name.matches(Regex("[a-f0-9-]{36}\\.(jpg|raw)"))) { "Archivo de foto no válido" }
        return File(directory, name)
    }
    fun newCameraFile(): Pair<String, Uri> {
        // Extensión JPEG para que FileProvider anuncie image/jpeg a la cámara.
        val name = "${UUID.randomUUID()}.jpg"
        val photo = file(name).apply { createNewFile() }
        return name to FileProvider.getUriForFile(context, "${context.packageName}.photos", photo)
    }
    fun uri(name: String) = FileProvider.getUriForFile(context, "${context.packageName}.photos", file(name))
    fun delete(name: String?) { if (name != null) runCatching { file(name).delete() } }
    fun cleanOld() { directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000L }
        ?.forEach { if (it.name.matches(Regex("[a-f0-9-]{36}\\.(jpg|raw)"))) it.delete() } }

    suspend fun prepare(uri: Uri): String = withContext(Dispatchers.IO) {
        val raw = file("${UUID.randomUUID()}.raw")
        val target = file("${UUID.randomUUID()}.jpg")
        var bitmap: Bitmap? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                raw.outputStream().use { output ->
                    val buffer = ByteArray(16_384)
                    var total = 0L
                    while (true) {
                        val length = input.read(buffer)
                        if (length < 0) break
                        total += length
                        check(total <= 25_000_000) { "Elige una foto de menos de 25 MB." }
                        output.write(buffer, 0, length)
                    }
                }
            } ?: error("No se pudo abrir la foto. Elígela otra vez.")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(raw.path, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 120_000_000L) { "La imagen no es compatible o es demasiado grande." }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            bitmap = BitmapFactory.decodeFile(raw.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("No se pudo leer la imagen.")
            val orientation = runCatching { ExifInterface(raw.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(-90f); postScale(-1f, 1f) }
                    8 -> setRotate(270f)
                }
            }
            val original = bitmap!!
            val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            if (rotated !== original) original.recycle()
            bitmap = rotated
            // Recodifica los píxeles: no copia EXIF, GPS, dispositivo ni fecha originales.
            for (quality in listOf(85, 72, 58, 45)) {
                target.outputStream().use { check(rotated.compress(Bitmap.CompressFormat.JPEG, quality, it)) { "No se pudo preparar la foto." } }
                if (target.length() <= MAX_BYTES) break
            }
            check(target.length() in 1..MAX_BYTES) { "No pudimos reducir la foto a 1.5 MB. Elige otra." }
            target.name
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        } finally { raw.delete(); bitmap?.recycle() }
    }
    companion object { const val MAX_BYTES = 1_500_000L }
}
