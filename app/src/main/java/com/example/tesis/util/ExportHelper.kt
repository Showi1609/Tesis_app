package com.example.tesis.util

import android.content.Context
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exportación de la base de datos de salida, en Excel y en CSV.
 *
 * Las columnas se definen UNA vez en [columns] y los dos formatos se derivan de
 * ahí. Antes el encabezado y la fila se escribían por separado, que es como se
 * desalinean sin que nadie lo note hasta abrir el archivo.
 *
 * Dos decisiones que conviene poder sustentar:
 *
 * 1. El formato recomendado es .xlsx. Los números viajan como números y las
 *    fechas como fechas, así que no hay que pelear con punto o coma decimal
 *    según la configuración regional de quien lo abra, y las fechas se pueden
 *    agrupar por mes o semana en una tabla dinámica sin convertirlas antes.
 *    El CSV se mantiene porque es lo que consumen R y Python sin ceremonia.
 *
 * 2. Las columnas de error se llenan SOLO si hay conteo manual del experto. La
 *    app no tiene ground truth propio: un "% de acierto" calculado contra sí
 *    misma sería un número fabricado. Sin conteo manual la celda queda vacía,
 *    que es la representación honesta de "no medido".
 */

private const val SEP = ";"
private const val BOM = "﻿"

/** Todo lo que hace falta para calcular una fila. */
private class RowContext(
    val e: DetectionEntity,
    val trap: TrapRecord?,
    val status: String?,
    val session: SamplingSession?,
    val trapAggregate: TrapAggregate?,
    val trapStatus: String?
)

private class Column(
    val header: String,
    val width: Int = 14,
    val value: (RowContext) -> XlsxValue
)

private fun text(v: String?): XlsxValue =
    if (v.isNullOrBlank()) XlsxValue.Blank else XlsxValue.Text(v)

private fun num(v: Double?, decimals: Int = 2): XlsxValue =
    if (v == null) XlsxValue.Blank else XlsxValue.Num(v, decimals)

private fun num(v: Float?, decimals: Int = 2): XlsxValue =
    if (v == null) XlsxValue.Blank else XlsxValue.Num(v.toDouble(), decimals)

private fun whole(v: Int?): XlsxValue =
    if (v == null) XlsxValue.Blank else XlsxValue.Whole(v.toLong())

private fun date(millis: Long?, withTime: Boolean = true): XlsxValue =
    if (millis == null || millis <= 0L) XlsxValue.Blank else XlsxValue.DateTime(millis, withTime)

private val columns: List<Column> = listOf(
    // Identificación
    Column("id_registro", 16) { XlsxValue.Whole(it.e.id) },

    // Sesión: es lo que permite juntar el trabajo de dos teléfonos sin mezclarlo.
    // El operario sale del registro, no de la sesión, porque es un hecho de la
    // lectura y no debe cambiar si después se renombra la sesión.
    Column("sesion", 22) { text(it.session?.nameOrDefault) },
    Column("operario", 18) { text(it.e.operator ?: it.session?.operator) },
    Column("dispositivo", 20) { text(it.session?.device) },
    Column("sesion_id", 34) { text(it.e.sessionId) },
    Column("finca", 16) { text(it.e.farmOrDefault) },
    Column("invernadero", 16) { text(it.e.greenhouseOrDefault) },
    Column("cultivo", 20) { text(it.e.cropOrDefault) },
    Column("trampa_id", 12) { text(it.e.trapIdOrDefault) },
    Column("cara", 8) { text(it.e.faceEnum.code) },
    Column("pos_fila", 10) { whole(it.trap?.row) },
    Column("pos_columna", 12) { whole(it.trap?.column) },
    Column("trampa_lat", 14) { num(it.trap?.latitude, 6) },
    Column("trampa_lon", 14) { num(it.trap?.longitude, 6) },
    Column("trampa_asignada_por", 18) { text(it.e.trapSelectionMode) },

    // Tiempo
    Column("fecha", 12) { date(it.e.timestamp, withTime = false) },
    Column("fecha_hora", 18) { date(it.e.timestamp) },
    Column("fecha_instalacion", 16) { date(it.e.installTimestamp, withTime = false) },
    Column("dias_exposicion", 14) { num(Metrics.exposureDays(it.e)) },
    Column("ciclo_reposicion_dias", 18) { whole(it.e.replacementCycleDays) },
    Column("excede_ciclo", 12) {
        Metrics.exceedsCycle(it.e)?.let { x -> XlsxValue.Text(if (x) "SI" else "NO") } ?: XlsxValue.Blank
    },

    // Geometría del muestreo
    Column("modo_encuadre", 16) { text(it.e.framingModeEnum.code) },
    Column("ancho_trampa_cm", 14) { num(it.e.trapWidthCm) },
    Column("alto_trampa_cm", 14) { num(it.e.trapHeightCm) },
    Column("area_cara_cm2", 14) { num(Metrics.trapFaceAreaCm2(it.e)) },
    Column("ancho_ventana_cm", 14) { num(it.e.windowWidthCm) },
    Column("alto_ventana_cm", 14) { num(it.e.windowHeightCm) },
    Column("area_muestreada_cm2", 16) { num(Metrics.sampledAreaCm2(it.e)) },

    // Resultado
    Column("conteo_app", 12) { XlsxValue.Whole(it.e.count.toLong()) },
    Column("conteo_cara_estimado", 18) { num(Metrics.estimatedFaceCount(it.e), 1) },
    Column("densidad_ind_cm2", 16) { num(Metrics.densityPerCm2(it.e), 4) },
    Column("densidad_ind_100cm2", 18) { num(Metrics.densityPer100Cm2(it.e)) },
    Column("capturas_cara_dia", 16) { num(Metrics.catchPerFacePerDay(it.e)) },
    Column("estado_semaforo", 14) { text(it.status) },
    Column("conteo_trampa", 14) { whole(it.trapAggregate?.totalCount) },
    Column("estado_trampa", 16) { text(it.trapStatus) },
    Column("adultos_trampa_semana", 22) { num(it.trapAggregate?.catchPerTrapPerWeek, 1) },

    // Confianza del modelo (no es acierto: solo lo seguro que estaba)
    Column("confianza_media", 14) { num(Metrics.meanScore(it.e), 4) },
    Column("confianza_minima", 14) { num(Metrics.minScore(it.e), 4) },
    Column("detecciones_baja_confianza", 22) { whole(Metrics.lowConfidenceCount(it.e)) },

    // Validación contra el experto
    Column("conteo_manual", 14) { whole(it.e.manualCount) },
    Column("error_absoluto", 14) { whole(Metrics.absoluteError(it.e)) },
    Column("error_relativo_pct", 16) { num(Metrics.relativeErrorPct(it.e)) },
    Column("acierto_pct", 12) { num(Metrics.accuracyPct(it.e)) },

    // Precisión y sensibilidad: separan los dos errores que el total confunde
    Column("verdaderos_positivos", 20) { whole(Metrics.truePositives(it.e)) },
    Column("falsos_positivos", 16) { whole(Metrics.falsePositives(it.e)) },
    Column("falsos_negativos", 16) { whole(Metrics.falseNegatives(it.e)) },
    Column("precision_pct", 14) { num(Metrics.precisionPct(it.e)) },
    Column("sensibilidad_pct", 16) { num(Metrics.recallPct(it.e)) },
    Column("f1", 10) { num(Metrics.f1(it.e), 4) },

    // Trazabilidad
    Column("modelo_version", 28) { text(it.e.modelVersion ?: ModelInfo.VERSION) },
    Column("conf_umbral", 12) { num(it.e.confThreshold ?: ModelInfo.CONF_THRESHOLD) },
    Column("iou_nms", 10) { num(it.e.iouThreshold ?: ModelInfo.IOU_NMS) },
    Column("modo_deteccion", 16) { text(it.e.detectionMode ?: "COMPLETA") },
    Column("origen_imagen", 14) { text(it.e.imageSource) },
    Column("imagen_archivo", 26) { text(it.e.imagePath?.substringAfterLast('/')) },
    Column("ancho_img_px", 12) { XlsxValue.Whole(it.e.imageWidth.toLong()) },
    Column("alto_img_px", 12) { XlsxValue.Whole(it.e.imageHeight.toLong()) },

    // Geolocalización del muestreo
    Column("latitud", 14) { num(it.e.latitude, 6) },
    Column("longitud", 14) { num(it.e.longitude, 6) },
    Column("precision_gps_m", 14) { num(it.e.gpsAccuracyM, 1) },

    // Libre
    Column("observaciones", 28) { text(it.e.notes) }
)

private fun buildContexts(
    detections: List<DetectionEntity>,
    settingsManager: SettingsManager?,
    trapRegistry: TrapRegistry?,
    sessionManager: SessionManager?
): List<RowContext> {
    val aggregates = detections.aggregateByTrap()
    return detections.sortedBy { it.timestamp }.map { e ->
        val thresholds = settingsManager?.getThresholdsFor(e.cropOrDefault)
        val aggregate = aggregates.find {
            it.farm == e.farmOrDefault &&
            it.greenhouse == e.greenhouseOrDefault &&
            it.trapId == e.trapIdOrDefault &&
            it.dayStart == e.dayStartMillis()
        }
        
        val trapStatus = if (aggregate != null) {
            val isCurrentFaceActive = (e == aggregate.faceA || e == aggregate.faceB)
            if (!isCurrentFaceActive) {
                "CAPTURA REEMPLAZADA"
            } else if (!aggregate.isComplete) {
                "TRAMPA INCOMPLETA"
            } else if (thresholds != null) {
                aggregate.mipStatusPerTrap(thresholds.first, thresholds.second)?.label
            } else {
                null
            }
        } else "TRAMPA INCOMPLETA"
        
        val statusLabel = thresholds?.let { (low, medium) -> Metrics.mipStatus(e.count, low, medium).label }
        
        RowContext(
            e = e,
            trap = trapRegistry?.findTrap(e.farmOrDefault, e.greenhouseOrDefault, e.trapIdOrDefault),
            status = statusLabel,
            session = sessionManager?.find(e.sessionId),
            trapAggregate = aggregate,
            trapStatus = trapStatus
        )
    }
}

// ---------------------------------------------------------------------------
// Excel
// ---------------------------------------------------------------------------

fun exportToXlsx(
    context: Context,
    detections: List<DetectionEntity>,
    settingsManager: SettingsManager? = null,
    trapRegistry: TrapRegistry? = null,
    sessionManager: SessionManager? = null
) {
    if (detections.isEmpty()) {
        Toast.makeText(context, "No hay registros para exportar", Toast.LENGTH_SHORT).show()
        return
    }

    val contexts = buildContexts(detections, settingsManager, trapRegistry, sessionManager)
    val rows = contexts.map { ctx -> columns.map { it.value(ctx) } }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val fileName = "biocount_muestreos_$stamp.xlsx"

    val saved = saveToDownloads(
        context = context,
        fileName = fileName,
        mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    ) { out ->
        writeXlsx(
            out = out,
            sheetName = "Muestreos",
            headers = columns.map { it.header },
            rows = rows,
            columnWidths = columns.map { it.width }
        )
    }

    Toast.makeText(
        context,
        if (saved == null) "No se pudo guardar el archivo"
        else "Guardado en ${saved.folderLabel}\n$fileName",
        Toast.LENGTH_LONG
    ).show()
}

// ---------------------------------------------------------------------------
// CSV
// ---------------------------------------------------------------------------

fun exportToCsv(
    context: Context,
    detections: List<DetectionEntity>,
    settingsManager: SettingsManager? = null,
    trapRegistry: TrapRegistry? = null,
    sessionManager: SessionManager? = null
) {
    if (detections.isEmpty()) {
        Toast.makeText(context, "No hay registros para exportar", Toast.LENGTH_SHORT).show()
        return
    }

    val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val dateTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    val sb = StringBuilder(BOM)
    sb.append(columns.joinToString(SEP) { it.header }).append("\r\n")

    buildContexts(detections, settingsManager, trapRegistry, sessionManager).forEach { ctx ->
        val cells = columns.map { column ->
            when (val v = column.value(ctx)) {
                is XlsxValue.Blank -> ""
                is XlsxValue.Text -> v.text
                is XlsxValue.Whole -> v.value.toString()
                // Coma decimal: es lo que espera Excel en configuración española,
                // y por eso el separador de columnas es ';'.
                is XlsxValue.Num -> String.format(Locale.GERMANY, "%.${v.decimals}f", v.value)
                is XlsxValue.DateTime ->
                    if (v.withTime) dateTimeFmt.format(Date(v.millis)) else dateFmt.format(Date(v.millis))
            }
        }
        sb.append(cells.joinToString(SEP) { escapeCsv(it) }).append("\r\n")
    }

    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val fileName = "biocount_muestreos_$stamp.csv"
    val saved = saveToDownloads(context, fileName, "text/csv") { out ->
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    Toast.makeText(
        context,
        if (saved == null) "No se pudo guardar el archivo"
        else "Guardado en ${saved.folderLabel}\n$fileName",
        Toast.LENGTH_LONG
    ).show()
}

/** Entrecomilla si el campo contiene el separador, comillas o saltos de línea. */
private fun escapeCsv(value: String): String =
    if (value.contains(SEP) || value.contains('"') || value.contains('\n') || value.contains('\r')) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else value
