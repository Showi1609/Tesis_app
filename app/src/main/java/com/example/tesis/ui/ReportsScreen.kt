package com.example.tesis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tesis.util.HistoryManager
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.entryModelOf
import java.util.*

@Composable
fun ReportsScreen() {
    val context = LocalContext.current
    val historyManager = remember { HistoryManager(context) }
    val detections by historyManager.detections.collectAsState()
    
    // Filtrar últimos 15 días para un reporte más completo
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.DAY_OF_YEAR, -15)
    val recentDetections = detections.filter { it.timestamp >= calendar.timeInMillis }.sortedBy { it.timestamp }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Text("ANÁLISIS POBLACIONAL MIP", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
        Text("Reporte de incidencia de Mosca Blanca", fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        if (recentDetections.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("Sin datos suficientes para el análisis", color = MaterialTheme.colorScheme.secondary)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text("Tendencia Temporal (15 días)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val trendEntries = recentDetections.mapIndexed { index, det ->
                        com.patrykandpatrick.vico.core.entry.FloatEntry(index.toFloat(), det.count.toFloat())
                    }
                    Chart(
                        chart = lineChart(),
                        model = entryModelOf(trendEntries),
                        startAxis = rememberStartAxis(),
                        bottomAxis = rememberBottomAxis(),
                        modifier = Modifier.fillMaxWidth().height(200.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                }
                
                item {
                    Text("Incidencia por Lote / Invernadero", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val lotData = recentDetections.groupBy { it.lot }.mapValues { entry ->
                        entry.value.map { it.count }.average()
                    }
                    
                    lotData.forEach { (lot, avg) ->
                        LotIncidenceRow(lot, avg)
                    }
                    
                    Spacer(modifier = Modifier.height(32.dp))
                }
                
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Resumen Estadístico", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            val avgTotal = recentDetections.map { it.count }.average()
                            val maxLot = recentDetections.groupBy { it.lot }.maxByOrNull { it.value.map { v -> v.count }.average() }?.key ?: "N/A"
                            
                            StatisticItem("Promedio de Finca", "%.2f moscas/trampa".format(avgTotal))
                            StatisticItem("Lote Crítico", maxLot)
                            StatisticItem("Total Muestreos", recentDetections.size.toString())
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LotIncidenceRow(lot: String, avg: Double) {
    val color = when {
        avg < 10 -> Color.Green
        avg < 25 -> Color.Yellow
        else -> Color.Red
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(lot, modifier = Modifier.width(100.dp), fontWeight = FontWeight.Medium)
        LinearProgressIndicator(
            progress = (avg.toFloat() / 50f).coerceIn(0f, 1f),
            modifier = Modifier.weight(1f).height(8.dp),
            color = color,
            trackColor = color.copy(alpha = 0.2f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("%.1f".format(avg), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StatisticItem(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
