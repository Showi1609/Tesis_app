package com.example.tesis.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

fun exportToCsv(context: Context, detections: List<DetectionEntity>) {
    val fileName = "reporte_moscas_blancas_${System.currentTimeMillis()}.csv"
    val file = File(context.cacheDir, fileName)
    val header = "ID,Fecha,Conteo,Latitud,Longitud\n"
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    
    val csvContent = StringBuilder(header)
    detections.forEach {
        val date = sdf.format(Date(it.timestamp))
        csvContent.append("${it.id},$date,${it.count},${it.latitude ?: ""},${it.longitude ?: ""}\n")
    }
    
    file.writeText(csvContent.toString())
    
    val uri = FileProvider.getUriForFile(context, "com.example.tesis.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Exportar reporte"))
}
