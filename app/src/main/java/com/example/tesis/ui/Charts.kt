package com.example.tesis.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Gráficas dibujadas a mano sobre Canvas de Compose.
 *
 * Se prescindió de la librería de gráficas externa por tres razones: una
 * dependencia menos que romper entre versiones, control total sobre ejes y
 * etiquetas en español, y la posibilidad de reutilizar exactamente el mismo
 * dibujo dentro del reporte PDF.
 */

data class ChartPoint(val x: Double, val y: Double)

data class ChartSeries(
    val name: String,
    val points: List<ChartPoint>,
    val color: Color
)

data class BarEntry(
    val label: String,
    val value: Double,
    val color: Color,
    val caption: String? = null
)

/** Paleta categórica, estable por índice para que una serie no cambie de color. */
object ChartPalette {
    private val colors = listOf(
        Color(0xFF4C9AFF),
        Color(0xFFFF8B5E),
        Color(0xFF57C785),
        Color(0xFFD98BFF),
        Color(0xFFFFD166),
        Color(0xFF5EC8D8),
        Color(0xFFFF6B8A),
        Color(0xFFA0A8B8)
    )

    operator fun get(index: Int): Color = colors[((index % colors.size) + colors.size) % colors.size]

    val faceA = Color(0xFF4C9AFF)
    val faceB = Color(0xFFFF8B5E)

    fun forLevel(value: Double, low: Double, high: Double): Color = when {
        value < low -> Color(0xFF57C785)
        value < high -> Color(0xFFFFD166)
        else -> Color(0xFFFF6B6B)
    }
}

/**
 * Serie temporal con eje X real (milisegundos), no índice de registro.
 *
 * Usar el índice como eje X hace que dos muestreos separados por dos semanas se
 * dibujen a la misma distancia que dos del mismo día, lo que invalida cualquier
 * lectura de tendencia.
 */
@Composable
fun TimeSeriesChart(
    series: List<ChartSeries>,
    yAxisLabel: String,
    xLabelFormatter: (Double) -> String,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 220.dp
) {
    val allPoints = series.flatMap { it.points }
    if (allPoints.isEmpty()) {
        EmptyChartPlaceholder(modifier, height)
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    Column(modifier = modifier.fillMaxWidth()) {
        ChartLegend(series.map { it.name to it.color })
        Spacer(Modifier.height(8.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
        ) {
            val leftPad = 52.dp.toPx()
            val rightPad = 12.dp.toPx()
            val topPad = 12.dp.toPx()
            val bottomPad = 30.dp.toPx()

            val plotW = size.width - leftPad - rightPad
            val plotH = size.height - topPad - bottomPad
            if (plotW <= 0f || plotH <= 0f) return@Canvas

            val minX = allPoints.minOf { it.x }
            val maxX = allPoints.maxOf { it.x }
            val rawMaxY = allPoints.maxOf { it.y }
            val maxY = niceCeil(if (rawMaxY <= 0.0) 1.0 else rawMaxY)
            val spanX = (maxX - minX).takeIf { it > 0.0 } ?: 1.0

            fun px(x: Double) = leftPad + ((x - minX) / spanX * plotW).toFloat()
            fun py(y: Double) = topPad + plotH - (y / maxY * plotH).toFloat()

            // Rejilla horizontal y etiquetas del eje Y
            val ticks = 4
            for (i in 0..ticks) {
                val value = maxY * i / ticks
                val y = py(value)
                drawLine(gridColor, Offset(leftPad, y), Offset(leftPad + plotW, y), strokeWidth = 1f)
                val text = formatAxisValue(value)
                val layout = textMeasurer.measure(text, TextStyle(fontSize = 10.sp, color = labelColor))
                drawText(
                    layout,
                    topLeft = Offset(leftPad - layout.size.width - 6.dp.toPx(), y - layout.size.height / 2f)
                )
            }

            // Ejes
            drawLine(axisColor, Offset(leftPad, topPad), Offset(leftPad, topPad + plotH), strokeWidth = 2f)
            drawLine(
                axisColor,
                Offset(leftPad, topPad + plotH),
                Offset(leftPad + plotW, topPad + plotH),
                strokeWidth = 2f
            )

            // Etiquetas del eje X: inicio, medio y fin
            val xTicks = if (spanX <= 1.0) listOf(minX) else listOf(minX, (minX + maxX) / 2.0, maxX)
            xTicks.forEachIndexed { index, value ->
                val text = xLabelFormatter(value)
                val layout = textMeasurer.measure(text, TextStyle(fontSize = 10.sp, color = labelColor))
                val cx = px(value)
                val left = when {
                    xTicks.size == 1 -> cx - layout.size.width / 2f
                    index == 0 -> cx
                    index == xTicks.lastIndex -> cx - layout.size.width
                    else -> cx - layout.size.width / 2f
                }
                drawText(
                    layout,
                    topLeft = Offset(
                        left.coerceIn(0f, size.width - layout.size.width),
                        topPad + plotH + 8.dp.toPx()
                    )
                )
            }

            // Series
            series.forEach { s ->
                val sorted = s.points.sortedBy { it.x }
                if (sorted.isEmpty()) return@forEach

                if (sorted.size > 1) {
                    val path = Path()
                    sorted.forEachIndexed { index, p ->
                        val x = px(p.x)
                        val y = py(p.y)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color = s.color, style = Stroke(width = 2.5.dp.toPx()))
                }

                sorted.forEach { p ->
                    drawCircle(s.color, radius = 3.5.dp.toPx(), center = Offset(px(p.x), py(p.y)))
                }
            }
        }

        Text(
            text = yAxisLabel,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
        )
    }
}

/**
 * Serie temporal con banda de dispersión (mínimo-máximo o media ± desviación).
 * Sirve para el nivel de invernadero, donde la media sin dispersión oculta que
 * una trampa puede estar disparada mientras las demás están limpias.
 */
@Composable
fun TimeSeriesWithBandChart(
    mean: ChartSeries,
    lower: List<ChartPoint>,
    upper: List<ChartPoint>,
    yAxisLabel: String,
    xLabelFormatter: (Double) -> String,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 220.dp
) {
    val allPoints = mean.points + lower + upper
    if (allPoints.isEmpty()) {
        EmptyChartPlaceholder(modifier, height)
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    Column(modifier = modifier.fillMaxWidth()) {
        ChartLegend(listOf(mean.name to mean.color, "Rango entre trampas" to mean.color.copy(alpha = 0.25f)))
        Spacer(Modifier.height(8.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
        ) {
            val leftPad = 52.dp.toPx()
            val rightPad = 12.dp.toPx()
            val topPad = 12.dp.toPx()
            val bottomPad = 30.dp.toPx()

            val plotW = size.width - leftPad - rightPad
            val plotH = size.height - topPad - bottomPad
            if (plotW <= 0f || plotH <= 0f) return@Canvas

            val minX = allPoints.minOf { it.x }
            val maxX = allPoints.maxOf { it.x }
            val rawMaxY = allPoints.maxOf { it.y }
            val maxY = niceCeil(if (rawMaxY <= 0.0) 1.0 else rawMaxY)
            val spanX = (maxX - minX).takeIf { it > 0.0 } ?: 1.0

            fun px(x: Double) = leftPad + ((x - minX) / spanX * plotW).toFloat()
            fun py(y: Double) = topPad + plotH - (y / maxY * plotH).toFloat()

            val ticks = 4
            for (i in 0..ticks) {
                val value = maxY * i / ticks
                val y = py(value)
                drawLine(gridColor, Offset(leftPad, y), Offset(leftPad + plotW, y), strokeWidth = 1f)
                val layout = textMeasurer.measure(
                    formatAxisValue(value),
                    TextStyle(fontSize = 10.sp, color = labelColor)
                )
                drawText(
                    layout,
                    topLeft = Offset(leftPad - layout.size.width - 6.dp.toPx(), y - layout.size.height / 2f)
                )
            }

            drawLine(axisColor, Offset(leftPad, topPad), Offset(leftPad, topPad + plotH), strokeWidth = 2f)
            drawLine(
                axisColor,
                Offset(leftPad, topPad + plotH),
                Offset(leftPad + plotW, topPad + plotH),
                strokeWidth = 2f
            )

            val xTicks = if (spanX <= 1.0) listOf(minX) else listOf(minX, (minX + maxX) / 2.0, maxX)
            xTicks.forEachIndexed { index, value ->
                val layout = textMeasurer.measure(
                    xLabelFormatter(value),
                    TextStyle(fontSize = 10.sp, color = labelColor)
                )
                val cx = px(value)
                val left = when {
                    xTicks.size == 1 -> cx - layout.size.width / 2f
                    index == 0 -> cx
                    index == xTicks.lastIndex -> cx - layout.size.width
                    else -> cx - layout.size.width / 2f
                }
                drawText(
                    layout,
                    topLeft = Offset(
                        left.coerceIn(0f, size.width - layout.size.width),
                        topPad + plotH + 8.dp.toPx()
                    )
                )
            }

            // Banda de dispersión
            val lo = lower.sortedBy { it.x }
            val hi = upper.sortedBy { it.x }
            if (lo.size == hi.size && lo.size > 1) {
                val band = Path()
                hi.forEachIndexed { index, p ->
                    val x = px(p.x); val y = py(p.y)
                    if (index == 0) band.moveTo(x, y) else band.lineTo(x, y)
                }
                lo.reversed().forEach { p -> band.lineTo(px(p.x), py(p.y)) }
                band.close()
                drawPath(band, color = mean.color.copy(alpha = 0.18f))
            }

            val sorted = mean.points.sortedBy { it.x }
            if (sorted.size > 1) {
                val path = Path()
                sorted.forEachIndexed { index, p ->
                    val x = px(p.x); val y = py(p.y)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = mean.color, style = Stroke(width = 2.5.dp.toPx()))
            }
            sorted.forEach { p ->
                drawCircle(mean.color, radius = 3.5.dp.toPx(), center = Offset(px(p.x), py(p.y)))
            }
        }

        Text(
            text = yAxisLabel,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
        )
    }
}

/**
 * Barras horizontales para comparación y ranking. Horizontales a propósito:
 * los nombres reales ("Invernadero 3", "T-14") no caben rotados bajo una barra
 * vertical en pantalla de teléfono.
 */
@Composable
fun HorizontalBarChart(
    entries: List<BarEntry>,
    valueFormatter: (Double) -> String,
    modifier: Modifier = Modifier,
    referenceLine: Pair<Double, String>? = null
) {
    if (entries.isEmpty()) {
        EmptyChartPlaceholder(modifier, 120.dp)
        return
    }

    val maxValue = maxOf(
        entries.maxOf { it.value },
        referenceLine?.first ?: 0.0
    ).takeIf { it > 0.0 } ?: 1.0

    Column(modifier = modifier.fillMaxWidth()) {
        entries.forEach { entry ->
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(entry.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        valueFormatter(entry.value),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = entry.color
                    )
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(entry.color.copy(alpha = 0.15f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((entry.value / maxValue).toFloat().coerceIn(0f, 1f))
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(entry.color)
                    )
                }
                entry.caption?.let {
                    Text(
                        it,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        referenceLine?.let { (value, label) ->
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.width(24.dp).height(2.dp)) {
                    drawLine(
                        color = Color(0xFFFF6B6B),
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width, size.height / 2),
                        strokeWidth = size.height,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "$label: ${valueFormatter(value)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun ChartLegend(items: List<Pair<String, Color>>) {
    if (items.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.take(6).forEach { (name, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(5.dp))
                Text(
                    name,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun EmptyChartPlaceholder(modifier: Modifier, height: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Sin datos suficientes para esta vista",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

/** Redondea el tope del eje Y a un valor "bonito" (1, 2, 5 x 10^n). */
private fun niceCeil(value: Double): Double {
    if (value <= 0.0) return 1.0
    val exponent = floor(log10(value))
    val fraction = value / 10.0.pow(exponent)
    val niceFraction = when {
        fraction <= 1.0 -> 1.0
        fraction <= 2.0 -> 2.0
        fraction <= 5.0 -> 5.0
        else -> 10.0
    }
    return niceFraction * 10.0.pow(exponent)
}

private fun formatAxisValue(value: Double): String = when {
    value == 0.0 -> "0"
    abs(value) >= 100 -> value.toInt().toString()
    abs(value) >= 10 -> String.format(java.util.Locale.getDefault(), "%.0f", value)
    abs(value) >= 1 -> String.format(java.util.Locale.getDefault(), "%.1f", value)
    else -> String.format(java.util.Locale.getDefault(), "%.2f", value)
}

/** Utilidad de formato para etiquetas de valor en las barras. */
fun formatDecimal(value: Double, decimals: Int = 1): String =
    String.format(java.util.Locale.getDefault(), "%.${decimals}f", value)

/** Una celda del mapa de calor del invernadero. */
data class HeatCell(
    val row: Int,
    val column: Int,
    val value: Double?,
    val label: String
)

/**
 * Mapa de calor del invernadero por cama y surco.
 *
 * Es la vista que un ranking en lista no puede dar: un foco de mosca blanca se
 * reconoce porque las trampas altas están juntas, y eso solo se ve en el plano.
 * No usa GPS — se dibuja con la posición relativa de cada ficha de trampa.
 */
@Composable
fun GreenhouseHeatmap(
    cells: List<HeatCell>,
    valueFormatter: (Double) -> String,
    lowCut: Double,
    highCut: Double,
    modifier: Modifier = Modifier
) {
    if (cells.isEmpty()) {
        EmptyChartPlaceholder(modifier, 140.dp)
        return
    }

    val rows = cells.map { it.row }.distinct().sorted()
    val columns = cells.map { it.column }.distinct().sorted()
    val byPosition = cells.associateBy { it.row to it.column }

    Column(modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        // Encabezado de surcos
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(46.dp))
            columns.forEach { column ->
                Box(
                    modifier = Modifier.width(62.dp).padding(2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "S$column",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }

        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.width(46.dp).padding(2.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        "Cama $row",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                columns.forEach { column ->
                    val cell = byPosition[row to column]
                    HeatCellBox(cell, valueFormatter, lowCut, highCut)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeatLegendChip(ChartPalette.forLevel(0.0, lowCut, highCut), "Baja")
            HeatLegendChip(ChartPalette.forLevel(lowCut, lowCut, highCut), "Media")
            HeatLegendChip(ChartPalette.forLevel(highCut, lowCut, highCut), "Alta")
            HeatLegendChip(Color(0xFF3A3F4B), "Sin dato")
        }
    }
}

@Composable
private fun HeatCellBox(
    cell: HeatCell?,
    valueFormatter: (Double) -> String,
    lowCut: Double,
    highCut: Double
) {
    val value = cell?.value
    val background = when {
        cell == null -> Color.Transparent
        value == null -> Color(0xFF3A3F4B)
        else -> ChartPalette.forLevel(value, lowCut, highCut)
    }

    Box(
        modifier = Modifier
            .width(62.dp)
            .height(52.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        if (cell != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    cell.label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10131A)
                )
                Text(
                    value?.let { valueFormatter(it) } ?: "—",
                    fontSize = 10.sp,
                    color = Color(0xFF10131A).copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun HeatLegendChip(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
    }
}

/** Una trampa situada sobre el plano del invernadero. */
data class TrapPlot(
    val code: String,
    val lat: Double,
    val lon: Double,
    val value: Double?
)

/**
 * Plano del invernadero con sus trampas en la posición real.
 *
 * Deliberadamente NO interpola una superficie de calor. Con trampas dispuestas
 * sobre el perímetro interior, rellenar el centro con valores calculados
 * pintaría densidades donde nadie midió; la pregunta "¿cómo sabe la densidad en
 * el centro si no puso trampas ahí?" no tendría respuesta. Se dibuja el contorno
 * real y un disco por trampa, y el foco se reconoce igual porque las trampas
 * altas aparecen agrupadas.
 *
 * La longitud se corrige por el coseno de la latitud para que la forma del
 * invernadero no salga estirada en horizontal.
 */
@Composable
fun GreenhousePlan(
    outline: List<Pair<Double, Double>>,
    traps: List<TrapPlot>,
    lowCut: Double,
    highCut: Double,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 300.dp
) {
    if (outline.size < 3) {
        EmptyChartPlaceholder(modifier, height)
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val outlineColor = Color(0xFF48C7E0)
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    val emptyColor = Color(0xFF3A3F4B)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            val pad = 26.dp.toPx()
            val plotW = size.width - pad * 2
            val plotH = size.height - pad * 2
            if (plotW <= 0f || plotH <= 0f) return@Canvas

            val meanLat = outline.sumOf { it.first } / outline.size
            val lonScale = cos(meanLat * PI / 180.0)

            // Coordenadas planas locales: x hacia el este, y hacia el norte.
            fun flat(lat: Double, lon: Double) = (lon * lonScale) to lat

            val flatOutline = outline.map { flat(it.first, it.second) }
            val minX = flatOutline.minOf { it.first }
            val maxX = flatOutline.maxOf { it.first }
            val minY = flatOutline.minOf { it.second }
            val maxY = flatOutline.maxOf { it.second }

            val spanX = (maxX - minX).takeIf { it > 0.0 } ?: 1.0
            val spanY = (maxY - minY).takeIf { it > 0.0 } ?: 1.0

            // Una sola escala para los dos ejes: si no, el invernadero se deforma.
            val scale = minOf(plotW / spanX, plotH / spanY)
            val drawW = (spanX * scale).toFloat()
            val drawH = (spanY * scale).toFloat()
            val originX = pad + (plotW - drawW) / 2f
            val originY = pad + (plotH - drawH) / 2f

            fun project(lat: Double, lon: Double): Offset {
                val (fx, fy) = flat(lat, lon)
                return Offset(
                    originX + ((fx - minX) * scale).toFloat(),
                    // La latitud crece hacia el norte y la pantalla hacia abajo.
                    originY + drawH - ((fy - minY) * scale).toFloat()
                )
            }

            val path = Path()
            flatOutline.forEachIndexed { index, _ ->
                val p = project(outline[index].first, outline[index].second)
                if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path.close()

            drawPath(path, color = outlineColor.copy(alpha = 0.12f))
            drawPath(path, color = outlineColor, style = Stroke(width = 2.5.dp.toPx()))

            traps.forEach { trap ->
                val center = project(trap.lat, trap.lon)
                val color = trap.value?.let { ChartPalette.forLevel(it, lowCut, highCut) } ?: emptyColor

                drawCircle(color = color, radius = 9.dp.toPx(), center = center)
                drawCircle(
                    color = Color(0xFF10131A),
                    radius = 9.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )

                val layout = textMeasurer.measure(
                    trap.code,
                    TextStyle(fontSize = 10.sp, color = labelColor, fontWeight = FontWeight.Bold)
                )
                drawText(
                    layout,
                    topLeft = Offset(
                        (center.x - layout.size.width / 2f)
                            .coerceIn(0f, size.width - layout.size.width),
                        (center.y + 11.dp.toPx()).coerceAtMost(size.height - layout.size.height)
                    )
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeatLegendChip(ChartPalette.forLevel(0.0, lowCut, highCut), "Baja")
            HeatLegendChip(ChartPalette.forLevel(lowCut, lowCut, highCut), "Media")
            HeatLegendChip(ChartPalette.forLevel(highCut, lowCut, highCut), "Alta")
            HeatLegendChip(emptyColor, "Sin muestreos")
        }
        Text(
            "Solo se colorea donde hay trampa: el interior no se rellena con " +
                "valores calculados porque ahí no se midió.",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
