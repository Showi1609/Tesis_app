package com.example.tesis.util

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

fun generatePdfReport(context: Context, detections: List<DetectionEntity>) {
    if (detections.isEmpty()) {
        Toast.makeText(context, "No hay datos para exportar", Toast.LENGTH_SHORT).show()
        return
    }

    val pdfDocument = PdfDocument()
    val paint = Paint()
    val titlePaint = Paint().apply {
        textSize = 20f
        isFakeBoldText = true
    }
    val textPaint = Paint().apply {
        textSize = 12f
    }

    val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4
    val page = pdfDocument.startPage(pageInfo)
    val canvas: Canvas = page.canvas

    val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    val now = sdf.format(Date())

    canvas.drawText("REPORTE DE MONITOREO MIP - TESIS", 50f, 50f, titlePaint)
    canvas.drawText("Fecha de generación: $now", 50f, 75f, textPaint)
    canvas.drawText("Total de muestreos: ${detections.size}", 50f, 95f, textPaint)
    
    canvas.drawLine(50f, 110f, 545f, 110f, paint)

    var y = 140f
    detections.take(15).forEach { detection ->
        canvas.drawText("${sdf.format(Date(detection.timestamp))} - ${detection.crop}", 50f, y, textPaint)
        canvas.drawText("Lote: ${detection.lot} | Moscas: ${detection.count}", 50f, y + 15f, textPaint)
        
        if (detection.latitude != null) {
            canvas.drawText("Ubicación: ${detection.latitude}, ${detection.longitude}", 300f, y + 15f, textPaint)
        }
        
        y += 45f
        if (y > 800f) return@forEach
    }

    pdfDocument.finishPage(page)

    val fileName = "Reporte_MIP_${System.currentTimeMillis()}.pdf"
    
    // Guardar en la carpeta pública de descargas
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val file = File(downloadsDir, fileName)

    try {
        pdfDocument.writeTo(FileOutputStream(file))
        Toast.makeText(context, "PDF guardado en Descargas", Toast.LENGTH_LONG).show()
        
        // Abrir el archivo automáticamente
        openPdfFile(context, file)
        
    } catch (e: Exception) {
        Toast.makeText(context, "Error al generar PDF: ${e.message}", Toast.LENGTH_SHORT).show()
    } finally {
        pdfDocument.close()
    }
}

private fun openPdfFile(context: Context, file: File) {
    try {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(Intent.createChooser(intent, "Abrir Reporte"))
    } catch (e: Exception) {
        Toast.makeText(context, "No se pudo abrir el PDF automáticamente", Toast.LENGTH_SHORT).show()
    }
}
