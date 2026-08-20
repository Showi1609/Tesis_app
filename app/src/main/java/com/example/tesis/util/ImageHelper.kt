package com.example.tesis.util

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

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
