package com.example.tesis.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.tesis.util.FarmMapStore
import com.example.tesis.util.GeoPoint
import com.example.tesis.util.LocationHelper
import com.example.tesis.util.TrapRegistry
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.launch

/**
 * Caché de etiquetas de texto para el mapa.
 *
 * Los descriptores se construyen DENTRO del contenido del mapa a propósito:
 * BitmapDescriptorFactory solo funciona una vez que Google Maps está
 * inicializado, y eso ocurre justo antes de que ese contenido se componga.
 */
class MarkerLabelCache {
    private val cache = mutableMapOf<String, BitmapDescriptor?>()

    fun get(text: String, small: Boolean, density: Float): BitmapDescriptor? =
        cache.getOrPut("$text|$small") {
            runCatching {
                BitmapDescriptorFactory.fromBitmap(buildTextBitmap(text, small, density))
            }.getOrNull()
        }
}

@Composable
fun rememberMarkerLabelCache(): MarkerLabelCache = remember { MarkerLabelCache() }

/**
 * Selector de coordenada sobre el mapa.
 *
 * Existe porque el GPS no siempre alcanza: dentro de un invernadero el error
 * puede ser de decenas de metros, y muchas veces la malla se registra en oficina
 * y no parado junto a cada trampa. Tocando el plano, con los invernaderos
 * importados de fondo, la posición se fija con la precisión que dé el satélite.
 */
@Composable
fun MapLocationPickerDialog(
    title: String,
    initial: GeoPoint?,
    showTraps: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (GeoPoint) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val farmMapStore = remember { FarmMapStore(context) }
    val trapRegistry = remember { TrapRegistry(context) }
    val locationHelper = remember { LocationHelper(context) }
    val scope = rememberCoroutineScope()
    val labels = rememberMarkerLabelCache()

    val farmMap by farmMapStore.map.collectAsState()
    val registryState by trapRegistry.state.collectAsState()

    var selected by remember { mutableStateOf(initial) }
    var locating by remember { mutableStateOf(false) }

    val start = initial
        ?: farmMap.areaList.firstNotNullOfOrNull { it.centroid() }
        ?: GeoPoint(4.711, -74.0721)

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(start.lat, start.lon), 19f)
    }

    val containingArea = selected?.let { farmMap.areaAt(it) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(mapType = MapType.SATELLITE),
                    uiSettings = MapUiSettings(zoomControlsEnabled = true),
                    onMapClick = { latLng -> selected = GeoPoint(latLng.latitude, latLng.longitude) }
                ) {
                    farmMap.areaList.forEach { area ->
                        if (area.points.size >= 3) {
                            Polygon(
                                points = area.points.map { LatLng(it.lat, it.lon) },
                                fillColor = Color(0x2248C7E0),
                                strokeColor = Color(0xFF48C7E0),
                                strokeWidth = 4f
                            )
                            area.centroid()?.let { c ->
                                Marker(
                                    state = MarkerState(position = LatLng(c.lat, c.lon)),
                                    icon = labels.get(area.name, false, density),
                                    anchor = Offset(0.5f, 0.5f),
                                    zIndex = 0.5f
                                )
                            }
                        }
                    }

                    // Trampas ya ubicadas, para no colocar dos en el mismo sitio
                    if (showTraps) {
                        registryState.trapList.forEach { trap ->
                            trap.point?.let { p ->
                                Marker(
                                    state = MarkerState(position = LatLng(p.lat, p.lon)),
                                    icon = labels.get(trap.code, true, density),
                                    anchor = Offset(0.5f, 0.5f),
                                    alpha = 0.8f,
                                    zIndex = 1f
                                )
                            }
                        }
                    }

                    selected?.let { p ->
                        Marker(
                            state = MarkerState(position = LatLng(p.lat, p.lon)),
                            icon = BitmapDescriptorFactory.defaultMarker(
                                BitmapDescriptorFactory.HUE_VIOLET
                            ),
                            zIndex = 2f
                        )
                    }
                }

                Card(
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.75f),
                        contentColor = Color.White
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                        Text(
                            "Toca el mapa para fijar la posición.",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                        selected?.let {
                            Text(
                                "%.6f, %.6f".format(it.lat, it.lon),
                                fontSize = 12.sp,
                                color = Color.White,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        containingArea?.let {
                            Text(
                                "Dentro de ${it.name}",
                                fontSize = 11.sp,
                                color = Color(0xFF8BE28B)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    enabled = !locating,
                    onClick = {
                        locating = true
                        scope.launch {
                            locationHelper.getCurrentLocation()?.let { loc ->
                                selected = GeoPoint(loc.latitude, loc.longitude)
                                cameraPositionState.position = CameraPosition.fromLatLngZoom(
                                    LatLng(loc.latitude, loc.longitude),
                                    19f
                                )
                            }
                            locating = false
                        }
                    }
                ) { Text(if (locating) "Ubicando..." else "Mi ubicación") }

                Spacer(Modifier.weight(1f))

                TextButton(onClick = onDismiss) { Text("Cancelar") }
                Button(
                    enabled = selected != null,
                    onClick = { selected?.let(onConfirm) }
                ) { Text("Usar esta posición") }
            }
        }
    }
}

/**
 * Rasteriza un rótulo para usarlo como icono de marcador.
 *
 * Google Maps solo dibuja chinchetas; para que el nombre del invernadero quede
 * centrado sobre su polígono hay que convertir el texto en imagen y anclar el
 * marcador a su propio centro.
 */
private fun buildTextBitmap(text: String, small: Boolean, density: Float): Bitmap {
    val textSize = (if (small) 11f else 14f) * density
    val padH = (if (small) 6f else 9f) * density
    val padV = (if (small) 3f else 5f) * density
    val radius = 6f * density

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        color = android.graphics.Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val metrics = textPaint.fontMetrics
    val textWidth = textPaint.measureText(text)
    val textHeight = metrics.descent - metrics.ascent

    val width = (textWidth + padH * 2).toInt().coerceAtLeast(1)
    val height = (textHeight + padV * 2).toInt().coerceAtLeast(1)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(if (small) 190 else 205, 12, 16, 24)
    }
    canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, bgPaint)

    val stroke = 1.2f * density
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = android.graphics.Color.argb(160, 72, 199, 224)
    }
    canvas.drawRoundRect(
        RectF(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2),
        radius,
        radius,
        borderPaint
    )

    canvas.drawText(text, padH, padV - metrics.ascent, textPaint)
    return bitmap
}
