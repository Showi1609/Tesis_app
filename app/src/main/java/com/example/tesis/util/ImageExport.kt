package com.example.tesis.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exportación de la evidencia fotográfica de un muestreo.
 *
 * La imagen se entrega con un pie rotulado (trampa, cara, fecha, conteo,
 * densidad y umbrales del modelo). Una foto suelta en un anexo no dice de qué
 * trampa es ni con qué parámetros se contó; con el pie, cada imagen se sostiene
 * sola como evidencia.
 */

private const val ALBUM = "BioCount MIPE"

/**
 * Reconstruye la imagen del muestreo.
 *
 * @param withBoxes dibuja las cajas de detección sobre la foto.
 * @param withCaption añade el pie con los metadatos del muestreo.
 */
fun renderEvidenceBitmap(
    entity: DetectionEntity,
    withBoxes: Boolean,
    withCaption: Boolean = true
): Bitmap? {
    val path = entity.imagePath ?: return null
    val file = File(path)
    if (!file.exists()) return null

    val source = try {
        BitmapFactory.decodeFile(path)
    } catch (_: Exception) {
        null
    } ?: return null

    val captionHeight = if (withCaption) (source.height * 0.10f).coerceIn(90f, 260f) else 0f
    val output = Bitmap.createBitmap(
        source.width,
        source.height + captionHeight.toInt(),
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(output)
    canvas.drawColor(Color.BLACK)
    canvas.drawBitmap(source, 0f, 0f, null)

    if (withBoxes) {
        // Las cajas están en el espacio de píxeles que registró la inferencia; se
        // reescalan por si la imagen guardada en disco tiene otra resolución.
        val scaleX = if (entity.imageWidth > 0) source.width.toFloat() / entity.imageWidth else 1f
        val scaleY = if (entity.imageHeight > 0) source.height.toFloat() / entity.imageHeight else 1f

        val stroke = (source.width / 400f).coerceIn(1.5f, 6f)
        val boxPaint = Paint().apply {
            color = Color.CYAN
            style = Paint.Style.STROKE
            strokeWidth = stroke
            isAntiAlias = true
        }
        entity.detections?.forEach { d ->
            canvas.drawRect(
                d.rect.left * scaleX,
                d.rect.top * scaleY,
                d.rect.right * scaleX,
                d.rect.bottom * scaleY,
                boxPaint
            )
        }
    }

    if (withCaption) {
        drawCaption(canvas, entity, source.height.toFloat(), source.width.toFloat(), captionHeight, withBoxes)
    }

    if (source != output) source.recycle()
    return output
}

private fun drawCaption(
    canvas: Canvas,
    entity: DetectionEntity,
    top: Float,
    width: Float,
    height: Float,
    withBoxes: Boolean
) {
    val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    val base = height / 5.2f

    val titlePaint = Paint().apply {
        color = Color.WHITE
        textSize = base
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    val linePaint = Paint().apply {
        color = Color.LTGRAY
        textSize = base * 0.78f
        isAntiAlias = true
    }

    val margin = width * 0.025f
    var y = top + base * 1.25f

    val density = Metrics.densityPer100Cm2(entity)
    val densityText = density?.let { " · %.2f ind/100 cm²".format(it) } ?: ""

    canvas.drawText(
        "${entity.trapIdOrDefault} · cara ${entity.faceEnum.code} · ${entity.count} moscas$densityText",
        margin, y, titlePaint
    )

    y += base * 0.95f
    canvas.drawText(
        "${entity.farmOrDefault} · ${entity.greenhouseOrDefault} · ${entity.lotOrDefault} · " +
            dateFmt.format(Date(entity.timestamp)),
        margin, y, linePaint
    )

    y += base * 0.85f
    val detail = buildString {
        append(if (withBoxes) "Detecciones del modelo" else "Foto original sin marcar")
        append(" · ")
        append(entity.modelVersion ?: ModelInfo.VERSION)
        append(" · conf ")
        append(entity.confThreshold ?: ModelInfo.CONF_THRESHOLD)
        append(" · NMS ")
        append(entity.iouThreshold ?: ModelInfo.IOU_NMS)
    }
    canvas.drawText(detail, margin, y, linePaint)

    entity.manualCount?.let { manual ->
        y += base * 0.85f
        val err = Metrics.relativeErrorPct(entity)
        canvas.drawText(
            "Conteo manual: $manual" + (err?.let { " · error %.1f %%".format(it) } ?: ""),
            margin, y, linePaint
        )
    }
}

/** Nombre de archivo estable y descriptivo para la evidencia. */
fun evidenceFileName(entity: DetectionEntity, withBoxes: Boolean): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(entity.timestamp))
    val safe = { s: String -> s.replace(Regex("[^A-Za-z0-9._-]"), "-") }
    val suffix = if (withBoxes) "detecciones" else "original"
    return "biocount_${safe(entity.greenhouseOrDefault)}_${safe(entity.trapIdOrDefault)}" +
        "_cara${entity.faceEnum.code}_${stamp}_$suffix.jpg"
}

/**
 * Guarda la imagen en la galería del teléfono, en un álbum propio.
 *
 * Usa MediaStore, que en Android 10 y superior no requiere permisos de
 * almacenamiento. En versiones anteriores no hay ruta sin pedir permiso de
 * escritura, así que devuelve null y la interfaz ofrece compartir en su lugar.
 */
fun saveImageToGallery(context: Context, bitmap: Bitmap, fileName: String): Uri? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        } ?: return null

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    } catch (_: Exception) {
        null
    }
}

/** Comparte la imagen por el selector del sistema (WhatsApp, correo, Drive...). */
fun shareImage(context: Context, bitmap: Bitmap, fileName: String): Boolean = try {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, fileName)
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Compartir evidencia"))
    true
} catch (_: Exception) {
    false
}

/** Nombre del álbum donde quedan las imágenes, para poder decírselo al usuario. */
fun galleryAlbumName(): String = ALBUM
