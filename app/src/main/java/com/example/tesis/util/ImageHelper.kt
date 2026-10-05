package com.example.tesis.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

const val GALLERY_FOLDER_NAME = "BioCount MIP"

/**
 * Aplica el mismo preprocesamiento unificado para fotos de cámara (bytes) o galería (Uri).
 * El orden estricto de v1.1 es: decodificar SIN aplicar EXIF, redimensionar, y rotar después.
 */
fun decodeAndScaleImage(context: Context, bytes: ByteArray?, uri: Uri?, maxDim: Int): Bitmap? {
    try {
        // 1. Decodificar SIN aplicar EXIF
        val options = BitmapFactory.Options().apply { inMutable = true }
        val bitmap = if (bytes != null) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } else if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } else null
        
        if (bitmap == null) return null

        // 2. Redimensionar con scale()
        val scale = maxDim.toFloat() / max(bitmap.width, bitmap.height)
        val smallBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        
        if (smallBitmap != bitmap) bitmap.recycle()

        // 3. Rotar según EXIF
        var rotationDegrees = 0
        if (bytes != null) {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            rotationDegrees = exif.rotationDegrees
        } else if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                rotationDegrees = exif.rotationDegrees
            }
        }

        return if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(smallBitmap, 0, 0, smallBitmap.width, smallBitmap.height, matrix, true)
            smallBitmap.recycle()
            rotated
        } else smallBitmap
        
    } catch (e: Exception) {
        Log.e("ImageHelper", "Error decodificando imagen unificada", e)
        return null
    }
}

/** Limpia nombres de finca o trampa para usarlos en archivos */
fun sanitizeForFileName(input: String?): String {
    if (input.isNullOrBlank()) return "unknown"
    return input.replace(Regex("[^a-zA-Z0-9_\\-]"), "")
}

/** Devuelve true si la ruta pertenece a Pictures/BioCount MIP o el nombre inicia con BioCount_ */
fun isBioCountOriginal(uriString: String?, fileName: String?): Boolean {
    if (fileName != null && fileName.startsWith("BioCount_")) return true
    if (uriString != null && uriString.contains("Pictures/") && uriString.contains(GALLERY_FOLDER_NAME)) return true
    return false
}

/** Calcula SHA-256 de un flujo de bytes */
fun calculateSha256(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

fun calculateSha256FromUri(context: Context, uri: Uri): String? {
    return try {
        val md = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }
}

/** Guarda los bytes de la foto capturada en Pictures/BioCount MIP y devuelve la URI y metadatos */
data class GallerySaveResult(val uri: String?, val fileName: String, val sha256: String?)

fun saveOriginalToGallery(
    context: Context,
    jpegBytes: ByteArray,
    farm: String,
    greenhouse: String,
    trapId: String,
    face: String
): GallerySaveResult {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val shortId = UUID.randomUUID().toString().take(6)
    val fileName = "BioCount_${sanitizeForFileName(farm)}_${sanitizeForFileName(greenhouse)}_${sanitizeForFileName(trapId)}_${sanitizeForFileName(face)}_${timestamp}_${shortId}.jpg"
    val sha256 = calculateSha256(jpegBytes)
    
    val resolver = context.contentResolver
    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$GALLERY_FOLDER_NAME")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    if (uri != null) {
        try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(jpegBytes)
            }
            
            // Verificar que se haya copiado la orientación si venía en los bytes (ExifInterface en Q+)
            try {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val sourceExif = ExifInterface(ByteArrayInputStream(jpegBytes))
                    val destExif = ExifInterface(pfd.fileDescriptor)
                    val rotation = sourceExif.rotationDegrees
                    if (rotation != 0 && destExif.rotationDegrees == 0) {
                        Log.w("ImageHelper", "Restaurando orientacion EXIF $rotation en galeria")
                        val orientationValue = when(rotation) {
                            90 -> ExifInterface.ORIENTATION_ROTATE_90
                            180 -> ExifInterface.ORIENTATION_ROTATE_180
                            270 -> ExifInterface.ORIENTATION_ROTATE_270
                            else -> ExifInterface.ORIENTATION_NORMAL
                        }
                        destExif.setAttribute(ExifInterface.TAG_ORIENTATION, orientationValue.toString())
                        destExif.saveAttributes()
                    }
                }
            } catch(e: Exception) {
                Log.e("ImageHelper", "No se pudo actualizar EXIF en destino", e)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
        } catch (e: Exception) {
            Log.e("ImageHelper", "Error guardando en galeria", e)
            return GallerySaveResult(null, fileName, sha256)
        }
    }
    
    return GallerySaveResult(uri?.toString(), fileName, sha256)
}

fun saveOriginalImage(context: Context, bitmap: Bitmap): String? {
    return try {
        val fileName = "raw_${UUID.randomUUID()}.jpg"
        val file = File(context.filesDir, fileName)
        val out = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        out.flush()
        out.close()
        file.absolutePath
    } catch (e: Exception) {
        null
    }
}
