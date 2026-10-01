package com.example.tesis.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Guardado de exportaciones en la carpeta pública de Descargas.
 *
 * Antes los reportes se entregaban por el menú de compartir. Eso obliga a elegir
 * una app destino cada vez y, con un CSV, muchas veces no hay ninguna que lo
 * acepte y el archivo se queda sin salir del teléfono. Escribirlo en Descargas
 * lo deja como un archivo normal, visible desde el gestor de archivos y abrible
 * con Sheets, Excel o Drive.
 */

/** Subcarpeta dentro de Descargas donde quedan todas las exportaciones. */
const val DOWNLOAD_FOLDER = "BioCount MIP"

/**
 * Escribe un archivo en Descargas/BioCount MIP.
 *
 * En Android 10 y superior usa MediaStore, que no pide permisos. En versiones
 * anteriores no hay ruta pública sin permiso de escritura, así que cae en el
 * almacenamiento externo propio de la app, que tampoco lo requiere.
 *
 * @return dónde quedó, o null si no se pudo escribir.
 */
fun saveToDownloads(
    context: Context,
    fileName: String,
    mimeType: String,
    write: (OutputStream) -> Unit
): SavedFile? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) null
        else {
            resolver.openOutputStream(uri)?.use(write)
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            SavedFile(uri, "Descargas/$DOWNLOAD_FOLDER", fileName, mimeType)
        }
    } else {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            DOWNLOAD_FOLDER
        ).apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use(write)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        SavedFile(uri, dir.absolutePath, fileName, mimeType)
    }
} catch (_: Exception) {
    null
}

data class SavedFile(
    val uri: Uri,
    val folderLabel: String,
    val fileName: String,
    val mimeType: String
)

/**
 * Intenta abrir el archivo con la app que el sistema tenga asociada.
 *
 * @return false si no hay ninguna capaz de abrir ese tipo, que con los CSV pasa
 * en teléfonos sin hoja de cálculo instalada.
 */
fun openSavedFile(context: Context, saved: SavedFile): Boolean = try {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(saved.uri, saved.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    true
} catch (_: Exception) {
    false
}

/** Compartir un archivo ya guardado, para cuando sí se quiere mandar a alguien. */
fun shareSavedFile(context: Context, saved: SavedFile) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = saved.mimeType
            putExtra(Intent.EXTRA_STREAM, saved.uri)
            putExtra(Intent.EXTRA_SUBJECT, saved.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir ${saved.fileName}"))
    } catch (_: Exception) {
        // Si no hay a quién compartir, el archivo ya está guardado igualmente.
    }
}
