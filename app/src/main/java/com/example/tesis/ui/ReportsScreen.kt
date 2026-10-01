package com.example.tesis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tesis.util.DetectionEntity
import com.example.tesis.util.FarmMapStore
import com.example.tesis.util.HistoryManager
import com.example.tesis.util.Metrics
import com.example.tesis.util.SessionManager
import com.example.tesis.util.SettingsManager
import com.example.tesis.util.TrapFace
import com.example.tesis.util.TrapRecord
import com.example.tesis.util.TrapRegistry
import com.example.tesis.util.aggregateByTrap
import com.example.tesis.util.exportToCsv
import com.example.tesis.util.exportToXlsx
import com.example.tesis.util.generatePdfReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Nivel de agregación del reporte. */
enum class ReportLevel(val label: String) {
    FINCA("Finca"),
    INVERNADERO("Invernadero"),
    TRAMPA("Trampa")
}

/** Métrica graficada. */
enum class ReportMetric(val label: String, val unit: String, val decimals: Int) {
    DENSIDAD("Densidad", "ind/100 cm²", 2),
    CONTEO("Conteo", "moscas/cara", 1)
}

private data class PeriodOption(val days: Int, val label: String)

private val PERIODS = listOf(
    PeriodOption(7, "7 d"),
    PeriodOption(15, "15 d"),
    PeriodOption(30, "30 d"),
    PeriodOption(90, "90 d"),
    PeriodOption(0, "Todo")
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(settingsManager: SettingsManager) {
    val context = LocalContext.current
    val historyManager = remember { HistoryManager(context) }
    val trapRegistry = remember { TrapRegistry(context) }
    val sessionManager = remember { SessionManager(context) }
    val detections by historyManager.detections.collectAsState()

    var level by remember { mutableStateOf(ReportLevel.INVERNADERO) }
    var metric by remember { mutableStateOf(ReportMetric.DENSIDAD) }
    var periodDays by remember { mutableStateOf(15) }

    val cutoff = remember(periodDays) {
        if (periodDays <= 0) 0L
        else System.currentTimeMillis() - periodDays * 86_400_000L
    }
    val data = remember(detections, cutoff) {
        detections.filter { it.timestamp >= cutoff }.sortedBy { it.timestamp }
    }

    val greenhouses = remember(data) { data.map { it.greenhouseOrDefault }.distinct().sorted() }
    var selectedGreenhouse by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(greenhouses) {
        if (selectedGreenhouse == null || selectedGreenhouse !in greenhouses) {
            selectedGreenhouse = greenhouses.firstOrNull()
        }
    }

    val traps = remember(data, selectedGreenhouse) {
        data.filter { it.greenhouseOrDefault == selectedGreenhouse }
            .map { it.trapIdOrDefault }.distinct().sorted()
    }
    var selectedTrap by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(traps) {
        if (selectedTrap == null || selectedTrap !in traps) selectedTrap = traps.firstOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(modifier = Modifier.align(Alignment.CenterStart).padding(end = 56.dp)) {
                Text(
                    "ANÁLISIS POBLACIONAL MIP",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Mosca blanca · T. vaporariorum",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            AppLogoIcon(modifier = Modifier.align(Alignment.TopEnd), size = 40.dp)
        }

        Spacer(Modifier.height(14.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ReportLevel.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = level == item,
                    onClick = { level = item },
                    shape = SegmentedButtonDefaults.itemShape(index, ReportLevel.entries.size)
                ) { Text(item.label, fontSize = 13.sp) }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PERIODS.forEach { option ->
                FilterChip(
                    selected = periodDays == option.days,
                    onClick = { periodDays = option.days },
                    label = { Text(option.label, fontSize = 12.sp) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportMetric.entries.forEach { item ->
                FilterChip(
                    selected = metric == item,
                    onClick = { metric = item },
                    label = { Text(item.label, fontSize = 12.sp) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // La exportación también vive aquí: es donde se mira el análisis y por
        // tanto donde se busca sacarlo. Exporta el periodo seleccionado arriba.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { exportToXlsx(context, data, settingsManager, trapRegistry, sessionManager) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.TableChart,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text("Excel", fontSize = 12.sp, maxLines = 1)
            }
            OutlinedButton(
                onClick = { exportToCsv(context, data, settingsManager, trapRegistry, sessionManager) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text("CSV", fontSize = 12.sp, maxLines = 1)
            }
            OutlinedButton(
                onClick = { generatePdfReport(context, data) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text("PDF", fontSize = 12.sp, maxLines = 1)
            }
        }

        Spacer(Modifier.height(12.dp))

        if (data.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Sin muestreos en el periodo seleccionado",
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            when (level) {
                ReportLevel.FINCA -> farmSection(data, metric)
                ReportLevel.INVERNADERO -> greenhouseSection(
                    data = data,
                    metric = metric,
                    greenhouses = greenhouses,
                    selected = selectedGreenhouse,
                    onSelect = { selectedGreenhouse = it },
                    settingsManager = settingsManager
                )
                ReportLevel.TRAMPA -> trapSection(
                    data = data,
                    metric = metric,
                    greenhouses = greenhouses,
                    selectedGreenhouse = selectedGreenhouse,
                    onSelectGreenhouse = { selectedGreenhouse = it },
                    traps = traps,
                    selectedTrap = selectedTrap,
                    onSelectTrap = { selectedTrap = it }
                )
            }

            item { ValidationCard(data) }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Nivel finca: comparación entre invernaderos
// ---------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.farmSection(
    data: List<DetectionEntity>,
    metric: ReportMetric
) {
    item {
        SectionTitle("Tendencia por finca")
        val byFarm = data.groupBy { it.farmOrDefault }.toSortedMap()
        val series = byFarm.entries.mapIndexed { index, (name, records) ->
            ChartSeries(
                name = name,
                points = dailyMeans(records, metric),
                color = ChartPalette[index]
            )
        }
        TimeSeriesChart(
            series = series,
            yAxisLabel = "Media diaria (${metric.unit})",
            xLabelFormatter = ::formatDayLabel
        )
        Spacer(Modifier.height(24.dp))
    }

    item {
        SectionTitle("Comparación de fincas")
        val entries = data.groupBy { it.farmOrDefault }
            .map { (name, records) ->
                val values = records.mapNotNull { metricValue(it, metric) }
                val mean = if (values.isEmpty()) 0.0 else values.average()
                val trapCount = records.map { it.trapLabel }.distinct().size
                val houseCount = records.map { it.greenhouseOrDefault }.distinct().size
                BarEntry(
                    label = name,
                    value = mean,
                    color = ChartPalette.forLevel(mean, rankLow(metric), rankHigh(metric)),
                    caption = "$houseCount invernadero(s) · $trapCount trampa(s)"
                )
            }
            .sortedByDescending { it.value }
        HorizontalBarChart(
            entries = entries,
            valueFormatter = { "${formatDecimal(it, metric.decimals)} ${metric.unit}" }
        )
        Spacer(Modifier.height(24.dp))
    }

    item {
        SectionTitle("Invernaderos más cargados")
        Text(
            "Los ocho con mayor ${metric.label.lowercase()} media del periodo, de toda la finca.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        val top = data.groupBy { it.greenhouseOrDefault }
            .map { (name, records) ->
                val values = records.mapNotNull { metricValue(it, metric) }
                val mean = if (values.isEmpty()) 0.0 else values.average()
                BarEntry(
                    label = name,
                    value = mean,
                    color = ChartPalette.forLevel(mean, rankLow(metric), rankHigh(metric)),
                    caption = "${records.map { it.trapIdOrDefault }.distinct().size} trampa(s)"
                )
            }
            .sortedByDescending { it.value }
            .take(8)
        HorizontalBarChart(
            entries = top,
            valueFormatter = { "${formatDecimal(it, metric.decimals)} ${metric.unit}" }
        )
        Spacer(Modifier.height(24.dp))
    }

    item {
        val values = data.mapNotNull { metricValue(it, metric) }
        val critical = data.groupBy { it.greenhouseOrDefault }
            .mapValues { (_, records) -> records.mapNotNull { metricValue(it, metric) }.averageOrZero() }
            .maxByOrNull { it.value }
        StatsCard(
            "Resumen de finca",
            listOf(
                "Fincas" to data.map { it.farmOrDefault }.distinct().size.toString(),
                "Invernaderos monitoreados" to data.map { it.greenhouseOrDefault }.distinct().size.toString(),
                "Trampas monitoreadas" to data.map { it.trapLabel }.distinct().size.toString(),
                "Muestreos (caras)" to data.size.toString(),
                "Media general" to "${formatDecimal(values.averageOrZero(), metric.decimals)} ${metric.unit}",
                "Invernadero crítico" to (critical?.key ?: "N/D")
            )
        )
        Spacer(Modifier.height(20.dp))
    }
}

// ---------------------------------------------------------------------------
// Nivel invernadero: unidad de decisión MIP
// ---------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.greenhouseSection(
    data: List<DetectionEntity>,
    metric: ReportMetric,
    greenhouses: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    settingsManager: SettingsManager
) {
    item {
        SelectorRow("Invernadero", greenhouses, selected, onSelect)
        Spacer(Modifier.height(16.dp))
    }

    val records = data.filter { it.greenhouseOrDefault == selected }

    item {
        SectionTitle("Tendencia del invernadero")
        Text(
            "Media de las trampas con el rango observado entre ellas. Una media sin " +
                "dispersión oculta que una sola trampa esté disparada.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        val grouped = records.groupBy { it.dayStartMillis() }.toSortedMap()
        val mean = mutableListOf<ChartPoint>()
        val lower = mutableListOf<ChartPoint>()
        val upper = mutableListOf<ChartPoint>()
        grouped.forEach { (day, dayRecords) ->
            val values = dayRecords.mapNotNull { metricValue(it, metric) }
            if (values.isNotEmpty()) {
                mean += ChartPoint(day.toDouble(), values.average())
                lower += ChartPoint(day.toDouble(), values.min())
                upper += ChartPoint(day.toDouble(), values.max())
            }
        }

        TimeSeriesWithBandChart(
            mean = ChartSeries("Media del invernadero", mean, ChartPalette[0]),
            lower = lower,
            upper = upper,
            yAxisLabel = "Media diaria (${metric.unit})",
            xLabelFormatter = ::formatDayLabel
        )
        Spacer(Modifier.height(8.dp))

        val trapCount = records.map { it.trapIdOrDefault }.distinct().size
        if (trapCount < 3) {
            WarningNote(
                "Solo $trapCount trampa(s) en este invernadero. Para reportar media ± " +
                    "dispersión con sentido estadístico se recomiendan al menos 3."
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    item {
        SectionTitle("Plano del invernadero")

        val context = LocalContext.current
        val registry = remember { TrapRegistry(context) }
        val farmMapStore = remember { FarmMapStore(context) }
        val farmMap by farmMapStore.map.collectAsState()

        // La pertenencia es geométrica: el invernadero es el polígono, y sus
        // trampas son las que caen dentro. El nombre escrito no decide nada.
        val area = farmMap.areaList.firstOrNull { it.name == selected }
        // El emparejamiento va por el id de la ficha, no por el texto del código:
        // ahí estaba el desajuste que dejaba el plano vacío. Para los muestreos
        // guardados antes de existir ese vínculo se cae al código.
        val valueById = records.filter { it.trapRecordId != null }
            .groupBy { it.trapRecordId!! }
            .mapValues { (_, rs) ->
                rs.mapNotNull { metricValue(it, metric) }.takeIf { it.isNotEmpty() }?.average()
            }
        val valueByCode = records.groupBy { it.trapIdOrDefault }
            .mapValues { (_, rs) ->
                rs.mapNotNull { metricValue(it, metric) }.takeIf { it.isNotEmpty() }?.average()
            }
        fun valueOf(trap: TrapRecord): Double? = valueById[trap.id] ?: valueByCode[trap.code]
        fun isPlotted(e: DetectionEntity, plotted: List<TrapRecord>): Boolean =
            plotted.any { it.id == e.trapRecordId || it.code == e.trapIdOrDefault }

        when {
            area != null -> {
                val contained = registry.trapsInArea(area)
                Text(
                    "Posición real de las ${contained.size} trampa(s) contenidas en el " +
                        "polígono de $selected, coloreadas por ${metric.label.lowercase()}.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (contained.isEmpty()) {
                    WarningNote(
                        "El polígono de $selected no contiene ninguna trampa con coordenada. " +
                            "Dales posición en Ajustes › Trampas, con el selector de mapa o " +
                            "capturando el GPS junto a cada una."
                    )
                } else {
                    GreenhousePlan(
                        outline = area.points.map { it.lat to it.lon },
                        traps = contained.map { trap ->
                            TrapPlot(
                                code = trap.code,
                                lat = trap.latitude ?: 0.0,
                                lon = trap.longitude ?: 0.0,
                                value = valueOf(trap)
                            )
                        },
                        lowCut = rankLow(metric),
                        highCut = rankHigh(metric)
                    )

                    // Nada desaparece en silencio: si un muestreo no tiene trampa
                    // ubicada dentro del polígono, se dice cuál.
                    val missing = records.filterNot { isPlotted(it, contained) }
                        .map { it.trapIdOrDefault }
                        .distinct()
                        .sorted()
                    if (missing.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        WarningNote(
                            "Muestreos cuya trampa no está registrada dentro de este " +
                                "polígono: ${missing.joinToString(", ")}. Revisa que esa " +
                                "trampa exista en Ajustes › Trampas y tenga coordenada."
                        )
                    }
                }
            }

            else -> {
                // Respaldo para invernaderos sin polígono importado: cuadrícula
                // por cama y surco, si esas posiciones están registradas.
                val cells = records.groupBy { it.trapIdOrDefault }.mapNotNull { (code, rs) ->
                    val trap = registry.findTrap(rs.first().farmOrDefault, selected.orEmpty(), code)
                    val row = trap?.row
                    val column = trap?.column
                    if (row == null || column == null) null
                    else HeatCell(
                        row = row,
                        column = column,
                        value = valueByCode[code],
                        label = code
                    )
                }
                if (cells.isEmpty()) {
                    WarningNote(
                        "No hay polígono importado para $selected ni trampas con cama y surco. " +
                            "Importa el mapa en Ajustes › Mapa de la finca, o registra la " +
                            "posición de las trampas."
                    )
                } else {
                    GreenhouseHeatmap(
                        cells = cells,
                        valueFormatter = { formatDecimal(it, metric.decimals) },
                        lowCut = rankLow(metric),
                        highCut = rankHigh(metric)
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    item {
        SectionTitle("Focos: ranking de trampas")
        val entries = records.groupBy { it.trapIdOrDefault }
            .map { (trap, trapRecords) ->
                val values = trapRecords.mapNotNull { metricValue(it, metric) }
                val mean = values.averageOrZero()
                val faces = trapRecords.map { it.faceEnum }.distinct().size
                BarEntry(
                    label = trap,
                    value = mean,
                    color = ChartPalette.forLevel(mean, rankLow(metric), rankHigh(metric)),
                    caption = if (faces < 2) "Solo una cara registrada" else "Caras A y B"
                )
            }
            .sortedByDescending { it.value }
        HorizontalBarChart(
            entries = entries,
            valueFormatter = { "${formatDecimal(it, metric.decimals)} ${metric.unit}" }
        )
        Spacer(Modifier.height(24.dp))
    }

    item {
        val crop = records.firstOrNull()?.cropOrDefault ?: ""
        val (low, medium) = settingsManager.getThresholdsFor(crop)
        val meanCount = records.map { it.count.toDouble() }.averageOrZero()
        val status = Metrics.mipStatus(meanCount.toInt(), low, medium)
        val aggregates = records.aggregateByTrap()
        val perTrapDay = aggregates.mapNotNull { it.catchPerTrapPerDay }

        StatsCard(
            "Resumen del invernadero",
            listOf(
                "Cultivo" to crop,
                "Trampas" to records.map { it.trapIdOrDefault }.distinct().size.toString(),
                "Trampas completas (A+B)" to "${aggregates.count { it.isComplete }} de ${aggregates.size}",
                "Media" to "${formatDecimal(records.mapNotNull { metricValue(it, metric) }.averageOrZero(), metric.decimals)} ${metric.unit}",
                "Capturas/trampa/día" to
                    if (perTrapDay.isEmpty()) "N/D (falta fecha de instalación)"
                    else formatDecimal(perTrapDay.average(), 2),
                "Semáforo MIP (conteo medio)" to status.label
            )
        )
        Spacer(Modifier.height(20.dp))
    }
}

// ---------------------------------------------------------------------------
// Nivel trampa: diagnóstico y sesgo entre caras
// ---------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.trapSection(
    data: List<DetectionEntity>,
    metric: ReportMetric,
    greenhouses: List<String>,
    selectedGreenhouse: String?,
    onSelectGreenhouse: (String) -> Unit,
    traps: List<String>,
    selectedTrap: String?,
    onSelectTrap: (String) -> Unit
) {
    item {
        SelectorRow("Invernadero", greenhouses, selectedGreenhouse, onSelectGreenhouse)
        Spacer(Modifier.height(8.dp))
        SelectorRow("Trampa", traps, selectedTrap, onSelectTrap)
        Spacer(Modifier.height(16.dp))
    }

    val records = data.filter {
        it.greenhouseOrDefault == selectedGreenhouse && it.trapIdOrDefault == selectedTrap
    }

    item {
        SectionTitle("Cara A frente a cara B")
        Text(
            "La orientación de cada cara sesga la captura. Separarlas permite ver si " +
                "una está saturada o si la trampa está mal orientada respecto al surco.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        val series = listOf(
            ChartSeries(
                "Cara A",
                dailyMeans(records.filter { it.faceEnum == TrapFace.A }, metric),
                ChartPalette.faceA
            ),
            ChartSeries(
                "Cara B",
                dailyMeans(records.filter { it.faceEnum == TrapFace.B }, metric),
                ChartPalette.faceB
            )
        ).filter { it.points.isNotEmpty() }

        TimeSeriesChart(
            series = series,
            yAxisLabel = "Por cara (${metric.unit})",
            xLabelFormatter = ::formatDayLabel
        )
        Spacer(Modifier.height(24.dp))
    }

    item {
        SectionTitle("Acumulado por cara")
        val entries = TrapFace.entries.mapNotNull { face ->
            val faceRecords = records.filter { it.faceEnum == face }
            if (faceRecords.isEmpty()) return@mapNotNull null
            val values = faceRecords.mapNotNull { metricValue(it, metric) }
            BarEntry(
                label = face.label,
                value = values.averageOrZero(),
                color = if (face == TrapFace.A) ChartPalette.faceA else ChartPalette.faceB,
                caption = "${faceRecords.size} muestreo(s) · ${faceRecords.sumOf { it.count }} moscas en total"
            )
        }
        HorizontalBarChart(
            entries = entries,
            valueFormatter = { "${formatDecimal(it, metric.decimals)} ${metric.unit}" }
        )
        Spacer(Modifier.height(24.dp))
    }

    item {
        val aggregates = records.aggregateByTrap()
        val exposures = records.mapNotNull { Metrics.exposureDays(it) }
        val area = records.firstOrNull()?.let { Metrics.trapFaceAreaCm2(it) }
        val perTrapDay = aggregates.mapNotNull { it.catchPerTrapPerDay }

        StatsCard(
            "Resumen de la trampa",
            listOf(
                "Muestreos (caras)" to records.size.toString(),
                "Fechas completas (A+B)" to "${aggregates.count { it.isComplete }} de ${aggregates.size}",
                "Área de cara" to (area?.let { "${formatDecimal(it.toDouble(), 0)} cm²" } ?: "N/D"),
                "Encuadre" to (records.firstOrNull()?.framingModeEnum?.label ?: "N/D"),
                "Días de exposición (media)" to
                    if (exposures.isEmpty()) "N/D" else formatDecimal(exposures.average(), 1),
                "Capturas/trampa/día" to
                    if (perTrapDay.isEmpty()) "N/D" else formatDecimal(perTrapDay.average(), 2),
                "Total acumulado" to "${records.sumOf { it.count }} moscas"
            )
        )
        Spacer(Modifier.height(20.dp))
    }
}

// ---------------------------------------------------------------------------
// Validación contra conteo manual
// ---------------------------------------------------------------------------

@Composable
private fun ValidationCard(data: List<DetectionEntity>) {
    val validated = data.filter { it.manualCount != null }
    if (validated.isEmpty()) return

    val errors = validated.mapNotNull { Metrics.absoluteError(it)?.toDouble() }
    val relatives = validated.mapNotNull { Metrics.relativeErrorPct(it) }
    val accuracies = validated.mapNotNull { Metrics.accuracyPct(it) }
    val bias = errors.averageOrZero()
    val sd = if (errors.size > 1) {
        val mean = errors.average()
        kotlin.math.sqrt(errors.sumOf { (it - mean) * (it - mean) } / (errors.size - 1))
    } else 0.0

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Validación contra conteo manual", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "Comparación de conteos agregados, no precisión ni recall del modelo: " +
                    "esas métricas exigen emparejar cajas una a una contra anotaciones.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
            )
            StatRow("Muestreos validados", "${validated.size} de ${data.size}")
            StatRow("Sesgo medio (app − experto)", formatDecimal(bias, 2) + " moscas")
            StatRow("DE de las diferencias", formatDecimal(sd, 2))
            StatRow(
                "Límites de acuerdo (95 %)",
                "${formatDecimal(bias - 1.96 * sd, 1)} a ${formatDecimal(bias + 1.96 * sd, 1)}"
            )
            StatRow("Error relativo medio", "${formatDecimal(relatives.averageOrZero(), 1)} %")
            StatRow(
                "Error absoluto medio",
                "${formatDecimal(relatives.map { abs(it) }.averageOrZero(), 1)} %"
            )
            StatRow("Acierto medio", "${formatDecimal(accuracies.averageOrZero(), 1)} %")
        }
    }
}

// ---------------------------------------------------------------------------
// Piezas compartidas
// ---------------------------------------------------------------------------

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun SelectorRow(
    label: String,
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
        Box {
            OutlinedButton(
                onClick = { if (options.isNotEmpty()) expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selected ?: "Sin datos")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsCard(title: String, rows: List<Pair<String, String>>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            rows.forEach { (label, value) -> StatRow(label, value) }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WarningNote(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF441B1B), // Rojo oscuro sólido para mejor contraste
            contentColor = Color(0xFFFFDADA)   // Texto claro
        )
    ) {
        Text(
            text,
            fontSize = 12.sp,
            modifier = Modifier.padding(12.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Utilidades de cálculo
// ---------------------------------------------------------------------------

private fun metricValue(e: DetectionEntity, metric: ReportMetric): Double? = when (metric) {
    ReportMetric.DENSIDAD -> Metrics.densityPer100Cm2(e)
    ReportMetric.CONTEO -> e.count.toDouble()
}

/** Media diaria de la métrica, con el eje X en milisegundos del inicio del día. */
private fun dailyMeans(records: List<DetectionEntity>, metric: ReportMetric): List<ChartPoint> =
    records.groupBy { it.dayStartMillis() }
        .toSortedMap()
        .mapNotNull { (day, dayRecords) ->
            val values = dayRecords.mapNotNull { metricValue(it, metric) }
            if (values.isEmpty()) null else ChartPoint(day.toDouble(), values.average())
        }

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

/** Cortes indicativos para colorear rankings, según la métrica mostrada. */
private fun rankLow(metric: ReportMetric): Double =
    if (metric == ReportMetric.DENSIDAD) 4.0 else 10.0

private fun rankHigh(metric: ReportMetric): Double =
    if (metric == ReportMetric.DENSIDAD) 10.0 else 25.0

private val dayFormat = SimpleDateFormat("dd/MM", Locale.getDefault())

private fun formatDayLabel(millis: Double): String = dayFormat.format(Date(millis.toLong()))
