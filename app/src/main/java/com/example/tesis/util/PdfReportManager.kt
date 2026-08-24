package com.example.tesis.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reporte PDF de monitoreo.
 *
 * El archivo se escribe en la caché de la app y se comparte por FileProvider, no
 * en la carpeta pública de Descargas: desde Android 10 el almacenamiento con
 * ámbito impide escribir ahí directamente y la exportación fallaba en silencio
 * en teléfonos modernos.
 */
fun generatePdfReport(context: Context, detections: List<DetectionEntity>) {
    if (detections.isEmpty()) {
        Toast.makeText(context, "No hay datos para exportar", Toast.LENGTH_SHORT).show()
        return
    }

    val pageWidth = 595
    val pageHeight = 842
    val marginLeft = 40f
    val marginRight = pageWidth - 40f

    val titlePaint = Paint().apply {
        textSize = 17f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    val headingPaint = Paint().apply {
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    val textPaint = Paint().apply {
        textSize = 9.5f
        isAntiAlias = true
    }
    val mutedPaint = Paint().apply {
        textSize = 8.5f
        color = Color.DKGRAY
        isAntiAlias = true
    }
    val linePaint = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 0.8f
    }

    val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    val shortFmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    val stampFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    val document = PdfDocument()
    var pageNumber = 1
    var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
    var canvas: Canvas = page.canvas
    var y = 50f

    fun newPageIfNeeded(needed: Float) {
        if (y + needed <= pageHeight - 50f) return
        canvas.drawText("Página $pageNumber", marginRight - 50f, pageHeight - 30f, mutedPaint)
        document.finishPage(page)
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        canvas = page.canvas
        y = 50f
    }

    // ---------------- Encabezado ----------------
    canvas.drawText("REPORTE DE MONITOREO MIP — MOSCA BLANCA", marginLeft, y, titlePaint)
    y += 18f
    canvas.drawText("BioCount MIPE · Trialeurodes vaporariorum", marginLeft, y, mutedPaint)
    y += 12f
    canvas.drawText("Generado el ${dateFmt.format(Date())}", marginLeft, y, mutedPaint)
    y += 10f
    canvas.drawText(
        "Modelo: ${ModelInfo.VERSION} · conf ${ModelInfo.CONF_THRESHOLD} · NMS IoU ${ModelInfo.IOU_NMS}",
        marginLeft, y, mutedPaint
    )
    y += 14f
    canvas.drawLine(marginLeft, y, marginRight, y, linePaint)
    y += 20f

    // ---------------- Resumen ----------------
    val densities = detections.mapNotNull { Metrics.densityPer100Cm2(it) }
    val validated = detections.filter { it.manualCount != null }

    canvas.drawText("Resumen general", marginLeft, y, headingPaint)
    y += 15f

    val summary = listOfNotNull(
        "Muestreos (caras de trampa): ${detections.size}",
        "Trampas monitoreadas: ${detections.map { it.trapLabel }.distinct().size}",
        "Invernaderos: ${detections.map { it.greenhouseOrDefault }.distinct().size}",
        "Conteo medio por cara: ${"%.1f".format(detections.map { it.count }.average())} moscas",
        densities.takeIf { it.isNotEmpty() }
            ?.let { "Densidad media: ${"%.2f".format(it.average())} ind/100 cm²" },
        "Periodo: ${shortFmt.format(Date(detections.minOf { it.timestamp }))} a " +
            shortFmt.format(Date(detections.maxOf { it.timestamp }))
    )
    summary.forEach {
        canvas.drawText("• $it", marginLeft + 6f, y, textPaint)
        y += 13f
    }
    y += 8f

    // ---------------- Incidencia por invernadero ----------------
    newPageIfNeeded(60f)
    canvas.drawText("Incidencia por invernadero", marginLeft, y, headingPaint)
    y += 15f

    detections.groupBy { it.greenhouseOrDefault }
        .toSortedMap()
        .forEach { (greenhouse, records) ->
            newPageIfNeeded(16f)
            val meanCount = records.map { it.count }.average()
            val meanDensity = records.mapNotNull { Metrics.densityPer100Cm2(it) }
                .takeIf { it.isNotEmpty() }?.average()
            val densityText = meanDensity?.let { " · ${"%.2f".format(it)} ind/100 cm²" } ?: ""
            val traps = records.map { it.trapIdOrDefault }.distinct().size
            canvas.drawText(
                "• $greenhouse — ${"%.1f".format(meanCount)} moscas/cara$densityText " +
                    "($traps trampa(s), ${records.size} muestreo(s))",
                marginLeft + 6f, y, textPaint
            )
            y += 13f
        }
    y += 10f

    // ---------------- Validación ----------------
    if (validated.isNotEmpty()) {
        newPageIfNeeded(80f)
        canvas.drawText("Validación contra conteo manual", marginLeft, y, headingPaint)
        y += 13f
        canvas.drawText(
            "Comparación de conteos agregados (tipo Bland-Altman); no equivale a precisión ni recall del modelo.",
            marginLeft, y, mutedPaint
        )
        y += 15f

        val diffs = validated.mapNotNull { Metrics.absoluteError(it)?.toDouble() }
        val bias = diffs.average()
        val sd = if (diffs.size > 1) {
            kotlin.math.sqrt(diffs.sumOf { (it - bias) * (it - bias) } / (diffs.size - 1))
        } else 0.0
        val accuracies = validated.mapNotNull { Metrics.accuracyPct(it) }

        listOf(
            "Muestreos validados: ${validated.size} de ${detections.size}",
            "Sesgo medio (app − experto): ${"%.2f".format(bias)} moscas",
            "Desviación estándar de las diferencias: ${"%.2f".format(sd)}",
            "Límites de acuerdo 95 %: ${"%.1f".format(bias - 1.96 * sd)} a ${"%.1f".format(bias + 1.96 * sd)}",
            "Acierto medio: ${"%.1f".format(accuracies.average())} %"
        ).forEach {
            canvas.drawText("• $it", marginLeft + 6f, y, textPaint)
            y += 13f
        }
        y += 10f
    }

    // ---------------- Detalle ----------------
    newPageIfNeeded(60f)
    canvas.drawText("Detalle de muestreos", marginLeft, y, headingPaint)
    y += 16f

    val columns = listOf(
        "Fecha" to marginLeft,
        "Invernadero" to marginLeft + 72f,
        "Trampa" to marginLeft + 165f,
        "Cara" to marginLeft + 215f,
        "Conteo" to marginLeft + 250f,
        "ind/100cm²" to marginLeft + 295f,
        "Manual" to marginLeft + 365f,
        "Error %" to marginLeft + 410f,
        "Acierto %" to marginLeft + 465f
    )
    columns.forEach { (label, x) -> canvas.drawText(label, x, y, mutedPaint) }
    y += 4f
    canvas.drawLine(marginLeft, y, marginRight, y, linePaint)
    y += 12f

    detections.sortedByDescending { it.timestamp }.forEach { e ->
        newPageIfNeeded(14f)
        val values = listOf(
            shortFmt.format(Date(e.timestamp)),
            e.greenhouseOrDefault.take(15),
            e.trapIdOrDefault.take(8),
            e.faceEnum.code,
            e.count.toString(),
            Metrics.densityPer100Cm2(e)?.let { "%.2f".format(it) } ?: "—",
            e.manualCount?.toString() ?: "—",
            Metrics.relativeErrorPct(e)?.let { "%.1f".format(it) } ?: "—",
            Metrics.accuracyPct(e)?.let { "%.1f".format(it) } ?: "—"
        )
        values.forEachIndexed { index, value ->
            canvas.drawText(value, columns[index].second, y, textPaint)
        }
        y += 13f
    }

    canvas.drawText("Página $pageNumber", marginRight - 50f, pageHeight - 30f, mutedPaint)
    document.finishPage(page)

    val fileName = "BioCount_Reporte_${stampFmt.format(Date())}.pdf"
    val saved = try {
        saveToDownloads(context, fileName, "application/pdf") { out -> document.writeTo(out) }
    } catch (_: Exception) {
        null
    } finally {
        document.close()
    }

    if (saved == null) {
        Toast.makeText(context, "No se pudo guardar el reporte", Toast.LENGTH_LONG).show()
    } else {
        Toast.makeText(
            context,
            "Guardado en ${saved.folderLabel}\n$fileName",
            Toast.LENGTH_LONG
        ).show()
    }
}
