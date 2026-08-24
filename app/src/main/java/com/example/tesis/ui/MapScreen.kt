package com.example.tesis.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.tesis.util.FarmMapStore
import com.example.tesis.util.HeatSample
import com.example.tesis.util.buildHeatBitmap
import com.example.tesis.util.heatBounds
import com.example.tesis.util.samplesOutsideAreas
import com.example.tesis.util.HistoryManager
import com.example.tesis.util.Metrics
import com.example.tesis.util.SettingsManager
import com.example.tesis.util.TrapRecord
import com.example.tesis.util.TrapRegistry
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Mapa de la finca.
 *
 * Muestra dos capas: los invernaderos importados desde My Maps, y una marca por
 * TRAMPA, no por muestreo. Antes se dibujaba un marcador por cada muestreo con
 * el GPS del momento de la foto, así que una misma trampa aparecía como una nube
 * de puntos dispersos por el error del GPS entre visitas.
 *
 * Los paneles sobrepuestos comparten un mismo estilo y se apilan sin solaparse:
 * el mapa es el contenido, y todo lo demás debe estorbarlo lo menos posible.
 */
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val historyManager = remember { HistoryManager(context) }
    val settingsManager = remember { SettingsManager(context) }
    val trapRegistry = remember { TrapRegistry(context) }
    val farmMapStore = remember { FarmMapStore(context) }

    val detections by historyManager.detections.collectAsState()
    val registryState by trapRegistry.state.collectAsState()
    val farmMap by farmMapStore.map.collectAsState()

    val density = LocalDensity.current.density
    val labels = rememberMarkerLabelCache()

    var showHeat by remember { mutableStateOf(true) }
    var heatRadiusM by remember { mutableStateOf(40.0) }
    var legendExpanded by remember { mutableStateOf(false) }

    val hasLocationPermission = remember {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    val locatedTraps = remember(registryState) {
        registryState.trapList.filter { it.isActive && it.point != null }
    }

    // Densidad media por trampa, para colorear los marcadores.
    val densityByTrap = remember(detections) {
        detections.groupBy { it.farmOrDefault to (it.greenhouseOrDefault to it.trapIdOrDefault) }
            .mapValues { (_, records) ->
                records.mapNotNull { Metrics.densityPer100Cm2(it) }
                    .takeIf { it.isNotEmpty() }?.average()
            }
    }

    val initialPos = remember(farmMap, locatedTraps, detections) {
        farmMap.areaList.firstNotNullOfOrNull { it.centroid() }
            ?.let { LatLng(it.lat, it.lon) }
            ?: locatedTraps.firstOrNull()?.point?.let { LatLng(it.lat, it.lon) }
            ?: detections.firstOrNull { it.latitude != null && it.longitude != null }
                ?.let { LatLng(it.latitude!!, it.longitude!!) }
            ?: LatLng(4.711, -74.0721) // Bogotá por defecto
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(initialPos, 17f)
    }

    // Superficie de calor sobre el mapa. Se calcula fuera del hilo principal
    // porque recorre cada píxel contra cada trampa.
    val heatSamples = remember(locatedTraps, densityByTrap) {
        locatedTraps.mapNotNull { trap ->
            val point = trap.point ?: return@mapNotNull null
            val value = densityByTrap[trap.farm to (trap.greenhouse to trap.code)]
                ?: return@mapNotNull null
            HeatSample(point.lat, point.lon, value)
        }
    }
    val heatArea = remember(heatSamples, heatRadiusM) { heatBounds(heatSamples, heatRadiusM) }
    val heatMax = remember(heatSamples) { heatSamples.maxOfOrNull { it.value } ?: 0.0 }
    val areas = farmMap.areaList

    // Trampas cuya coordenada no cae en ningún invernadero: no entran a la
    // interpolación, y conviene avisarlo en vez de dejarlas desaparecer.
    val strayTraps = remember(heatSamples, areas) { samplesOutsideAreas(heatSamples, areas) }

    val heatOverlay by produceState<BitmapDescriptor?>(
        initialValue = null,
        heatSamples, heatArea, heatMax, showHeat, heatRadiusM, areas
    ) {
        value = if (!showHeat || heatArea == null || heatSamples.isEmpty()) null
        else withContext(Dispatchers.Default) {
            buildHeatBitmap(
                samples = heatSamples,
                bounds = heatArea,
                areas = areas,
                maxDistanceM = heatRadiusM,
                maxValue = heatMax
            )?.let { bitmap ->
                runCatching { BitmapDescriptorFactory.fromBitmap(bitmap) }.getOrNull()
            }
        }
    }

    // Avisos de configuración incompleta. Se muestran unos segundos y se van
    // solos: son un recordatorio al entrar, no algo que deba tapar el mapa
    // mientras se trabaja.
    val notices = buildList {
        if (farmMap.isEmpty) add("Sin mapa de finca: impórtalo en Ajustes › Mapa de la finca")
        val missingGps = registryState.trapList.count { it.isActive && it.point == null }
        if (missingGps > 0) add("$missingGps trampa(s) sin coordenada: captúrala en su ficha")
        if (strayTraps > 0) {
            add("$strayTraps trampa(s) con coordenada fuera de todo invernadero: revisa su ficha, no entran al mapa de calor")
        }
    }
    val noticeKey = notices.joinToString("|")
    var noticesVisible by remember(noticeKey) { mutableStateOf(notices.isNotEmpty()) }
    LaunchedEffect(noticeKey) {
        if (notices.isNotEmpty()) {
            noticesVisible = true
            delay(7000)
            noticesVisible = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            uiSettings = MapUiSettings(myLocationButtonEnabled = hasLocationPermission),
            properties = MapProperties(
                isMyLocationEnabled = hasLocationPermission,
                mapType = MapType.SATELLITE
            )
        ) {
            // La superficie va primero, para quedar bajo polígonos y marcadores.
            val overlay = heatOverlay
            val hb = heatArea
            if (showHeat && overlay != null && hb != null) {
                GroundOverlay(
                    position = GroundOverlayPosition.create(
                        LatLngBounds(
                            LatLng(hb.minLat, hb.minLon),
                            LatLng(hb.maxLat, hb.maxLon)
                        )
                    ),
                    image = overlay,
                    transparency = 0f,
                    zIndex = 0f
                )
            }

            // Invernaderos importados desde My Maps
            farmMap.areaList.forEach { area ->
                if (area.points.size >= 3) {
                    Polygon(
                        points = area.points.map { LatLng(it.lat, it.lon) },
                        // El relleno va casi transparente: ahora el interior lo
                        // ocupa el mapa de calor y un tinte sólido lo falsearía.
                        fillColor = Color(0x1448C7E0),
                        strokeColor = Color(0xFF48C7E0),
                        strokeWidth = 4f
                    )
                    // El nombre va como rótulo centrado sobre el polígono, no como
                    // chincheta: una chincheta apunta a un punto, y el invernadero
                    // es un área.
                    area.centroid()?.let { c ->
                        Marker(
                            state = MarkerState(position = LatLng(c.lat, c.lon)),
                            title = area.name,
                            snippet = area.layer ?: "Invernadero",
                            icon = labels.get(area.name, false, density),
                            anchor = Offset(0.5f, 0.5f),
                            zIndex = 0.5f
                        )
                    }
                }
            }

            // Una marca por trampa
            locatedTraps.forEach { trap ->
                val point = trap.point ?: return@forEach
                val trapDensity = densityByTrap[trap.farm to (trap.greenhouse to trap.code)]
                Marker(
                    state = MarkerState(position = LatLng(point.lat, point.lon)),
                    title = "${trap.code} · ${trap.greenhouse}",
                    snippet = buildSnippet(trap, trapDensity, trapRegistry.isOverdue(trap)),
                    icon = BitmapDescriptorFactory.defaultMarker(hueForDensity(trapDensity))
                )
            }

            // Respaldo: sin trampas ubicadas, se muestran los muestreos como antes
            if (locatedTraps.isEmpty()) {
                detections.forEach { detection ->
                    val lat = detection.latitude
                    val lon = detection.longitude
                    if (lat != null && lon != null) {
                        val (low, medium) = settingsManager.getThresholdsFor(detection.cropOrDefault)
                        Marker(
                            state = MarkerState(position = LatLng(lat, lon)),
                            title = "${detection.trapIdOrDefault} cara ${detection.faceEnum.code}",
                            snippet = "${detection.count} moscas · ${detection.greenhouseOrDefault}",
                            icon = BitmapDescriptorFactory.defaultMarker(
                                when {
                                    detection.count < low -> BitmapDescriptorFactory.HUE_GREEN
                                    detection.count < medium -> BitmapDescriptorFactory.HUE_YELLOW
                                    else -> BitmapDescriptorFactory.HUE_RED
                                }
                            )
                        )
                    }
                }
            }
        }

        // Los avisos y el control del calor van en la misma columna, así que no
        // pueden solaparse por ancho de pantalla ni por longitud del texto.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (noticesVisible && notices.isNotEmpty()) {
                MapOverlayCard(modifier = Modifier.fillMaxWidth()) {
                    notices.forEachIndexed { index, notice ->
                        if (index > 0) Spacer(Modifier.height(4.dp))
                        Text(notice, fontSize = 11.sp, color = OverlayText)
                    }
                }
            }

            MapOverlayCard(modifier = Modifier.width(178.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Mapa de calor",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = OverlayText,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = showHeat,
                        onCheckedChange = { showHeat = it },
                        modifier = Modifier.scale(0.7f)
                    )
                }
                if (showHeat) {
                    Text(
                        "Alcance ${heatRadiusM.toInt()} m",
                        fontSize = 11.sp,
                        color = OverlayTextDim
                    )
                    Slider(
                        value = heatRadiusM.toFloat(),
                        onValueChange = { heatRadiusM = it.toDouble() },
                        valueRange = 10f..120f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        if (areas.isEmpty()) {
                            "Solo se pinta hasta esa distancia de una trampa."
                        } else {
                            "Confinado a cada invernadero: no se interpola a través de la pared."
                        },
                        fontSize = 9.sp,
                        color = OverlayTextFaint
                    )
                }
            }
        }

        // Leyenda, plegada por omisión: los colores se entienden solos y el
        // detalle estorba cuando ya se sabe leer el mapa.
        MapOverlayCard(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .padding(bottom = 80.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { legendExpanded = !legendExpanded }
            ) {
                Text(
                    "Densidad por trampa",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = OverlayText
                )
                Spacer(Modifier.width(6.dp))
                if (!legendExpanded) {
                    LegendDots()
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    imageVector = if (legendExpanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (legendExpanded) "Plegar leyenda" else "Desplegar leyenda",
                    tint = OverlayTextDim,
                    modifier = Modifier.size(16.dp)
                )
            }
            if (legendExpanded) {
                Spacer(modifier = Modifier.height(6.dp))
                LegendItem(LegendLow, "Baja (< 4 ind/100 cm²)")
                LegendItem(LegendMedium, "Media (4 a 10)")
                LegendItem(LegendHigh, "Alta (> 10)")
                LegendItem(LegendNone, "Sin muestreos aún")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Estilo común de los paneles sobrepuestos
// ---------------------------------------------------------------------------

/**
 * Un solo color de fondo para todos los paneles del mapa.
 *
 * Antes cada tarjeta llevaba su propio alfa (0.8 y 0.85), y esa diferencia
 * mínima se lee como si una estuviera más "encendida" que la otra.
 */
private val OverlayBackground = Color(0xCC101A1E)
private val OverlayText = Color(0xFFF2F6F7)
private val OverlayTextDim = Color(0xB3F2F6F7)
private val OverlayTextFaint = Color(0x8AF2F6F7)

private val LegendLow = Color(0xFF4CAF50)
private val LegendMedium = Color(0xFFFFC107)
private val LegendHigh = Color(0xFFE53935)
private val LegendNone = Color(0xFF48C7E0)

@Composable
private fun MapOverlayCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = OverlayBackground,
            contentColor = OverlayText
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            content = content
        )
    }
}

@Composable
private fun LegendDots() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf(LegendLow, LegendMedium, LegendHigh, LegendNone).forEachIndexed { index, color ->
            if (index > 0) Spacer(Modifier.width(3.dp))
            Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        }
    }
}

private fun buildSnippet(trap: TrapRecord, density: Double?, overdue: Boolean): String =
    buildString {
        append(density?.let { "%.2f ind/100 cm²".format(it) } ?: "Sin muestreos")
        trap.positionLabel?.let { append(" · ").append(it) }
        if (overdue) append(" · ciclo vencido")
    }

/**
 * Colorea el marcador por densidad media. Los cortes son los mismos que usan los
 * rankings de la pantalla de Reportes, para que las dos vistas se lean igual.
 */
private fun hueForDensity(density: Double?): Float = when {
    density == null -> BitmapDescriptorFactory.HUE_AZURE
    density < 4.0 -> BitmapDescriptorFactory.HUE_GREEN
    density < 10.0 -> BitmapDescriptorFactory.HUE_YELLOW
    else -> BitmapDescriptorFactory.HUE_RED
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, fontSize = 10.sp, color = OverlayTextDim)
    }
}
