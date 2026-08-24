package com.example.tesis.ui

import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.tesis.BoxedDeteccion
import com.example.tesis.util.DetectionEntity
import com.example.tesis.util.GeoPoint
import com.example.tesis.util.HistoryManager
import com.example.tesis.util.LocationHelper
import com.example.tesis.util.Metrics
import com.example.tesis.util.SessionManager
import com.example.tesis.util.SettingsManager
import com.example.tesis.util.TrapRecord
import com.example.tesis.util.TrapRegistry
import com.example.tesis.util.evidenceFileName
import com.example.tesis.util.exportToCsv
import com.example.tesis.util.exportToXlsx
import com.example.tesis.util.galleryAlbumName
import com.example.tesis.util.renderEvidenceBitmap
import com.example.tesis.util.saveImageToGallery
import com.example.tesis.util.shareImage
import com.example.tesis.util.generatePdfReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(settingsManager: SettingsManager) {
    val context = LocalContext.current
    val historyManager = remember { HistoryManager(context) }
    val trapRegistry = remember { TrapRegistry(context) }
    val sessionManager = remember { SessionManager(context) }
    val detections by historyManager.detections.collectAsState()
    val sdf = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    var selectedItem by remember { mutableStateOf<DetectionEntity?>(null) }
    var editingItem by remember { mutableStateOf<DetectionEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, start = 16.dp, end = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            AppLogo(height = 65.dp)
        }
        
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Historial de Muestreos",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
                val validated = detections.count { it.manualCount != null }
                Text(
                    "${detections.size} registros ($validated validados)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

        // Botones rotulados en vez de iconos sueltos: la exportación es la salida
        // principal del trabajo de campo y antes quedaba escondida tras un icono.
        //
        // Excel va primero y resaltado porque es el formato con el que se trabaja
        // la base de datos: los números llegan como números y las fechas como
        // fechas, así que se puede hacer una tabla dinámica sin preparar nada.
        // El CSV se conserva para R y Python.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { exportToXlsx(context, detections, settingsManager, trapRegistry, sessionManager) },
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
                onClick = { exportToCsv(context, detections, settingsManager, trapRegistry, sessionManager) },
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
                onClick = { generatePdfReport(context, detections) },
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

        Spacer(modifier = Modifier.height(16.dp))

        if (detections.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No hay registros aún", color = MaterialTheme.colorScheme.secondary)
            }
        } else {
            LazyColumn {
                items(detections, key = { it.id }) { item ->
                    DetectionItem(
                        item = item,
                        sdf = sdf,
                        onDelete = { historyManager.delete(item) },
                        onEdit = { editingItem = item },
                        onViewMap = {
                            val uri = "geo:${item.latitude},${item.longitude}?q=${item.latitude},${item.longitude}(Muestreo)"
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
                        }
                    ) { selectedItem = it }
                }
            }
        }

        selectedItem?.let { item ->
            ImageViewerDialog(item = item, onDismiss = { selectedItem = null })
        }

        editingItem?.let { item ->
            EditSampleDialog(
                item = item,
                onDismiss = { editingItem = null },
                onSave = { updated ->
                    historyManager.update(updated)
                    editingItem = null
                }
            )
        }
    }
}
}

@Composable
fun DetectionItem(
    item: DetectionEntity,
    sdf: SimpleDateFormat,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onViewMap: () -> Unit,
    onItemClick: (DetectionEntity) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onItemClick(item) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp).clip(RectangleShape)) {
                item.imagePath?.let { path ->
                    AsyncImage(
                        model = File(path),
                        contentDescription = "Detección",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    item.detections?.let { boxes ->
                        ThumbnailDetectionOverlay(
                            boxes = boxes,
                            imgW = item.imageWidth,
                            imgH = item.imageHeight
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${item.count} moscas · cara ${item.faceEnum.code}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )

                    ReliabilityRow(item)

                    Metrics.densityPer100Cm2(item)?.let { density ->
                        Text(
                            text = "%.2f ind/100 cm²".format(density),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Text(
                        text = "${item.greenhouseOrDefault} · ${item.trapIdOrDefault} · ${item.lotOrDefault}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(text = "${item.cropOrDefault} · ${item.farmOrDefault}", fontSize = 12.sp)
                    Text(text = sdf.format(Date(item.timestamp)), fontSize = 12.sp)

                    Metrics.catchPerFacePerDay(item)?.let {
                        Text(text = "%.2f capturas/cara/día".format(it), fontSize = 11.sp)
                    }

                    item.manualCount?.let { manual ->
                        val error = Metrics.relativeErrorPct(item)
                        Text(
                            text = "Manual: $manual" + (error?.let { " · error %.1f %%".format(it) } ?: ""),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        val recall = Metrics.recallPct(item)
                        val precision = Metrics.precisionPct(item)
                        if (recall != null || precision != null) {
                            Text(
                                text = buildString {
                                    recall?.let { append("Sensibilidad %.1f %%".format(it)) }
                                    precision?.let {
                                        if (isNotEmpty()) append(" · ")
                                        append("precisión %.1f %%".format(it))
                                    }
                                    Metrics.falseNegatives(item)?.let { append(" · $it perdidas") }
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        } else if (Metrics.needsTruePositives(item)) {
                            Text(
                                text = "Falta cuántas detecciones eran correctas",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    } ?: Text(
                        text = "Sin conteo manual",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )

                    if (item.latitude != null && item.longitude != null) {
                        Text(
                            text = "Ubicación: ${"%.4f".format(item.latitude)}, ${"%.4f".format(item.longitude)}",
                            fontSize = 11.sp
                        )
                    }
                }

                Column {
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Editar conteo manual",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (item.latitude != null) {
                        IconButton(onClick = onViewMap) {
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = "Ver mapa",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Eliminar",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/**
 * Edición posterior de un muestreo ya guardado.
 *
 * Existe sobre todo para el conteo manual del experto: en campo casi nunca está
 * disponible en el momento de la captura, y sin él las métricas de error no se
 * pueden calcular. También permite corregir la identificación de la trampa.
 */
@Composable
fun EditSampleDialog(
    item: DetectionEntity,
    onDismiss: () -> Unit,
    onSave: (DetectionEntity) -> Unit
) {
    val context = LocalContext.current
    val trapRegistry = remember { TrapRegistry(context) }
    val locationHelper = remember { LocationHelper(context) }
    val registryState by trapRegistry.state.collectAsState()
    val scope = rememberCoroutineScope()

    var farm by remember { mutableStateOf(item.farmOrDefault) }
    var greenhouse by remember { mutableStateOf(item.greenhouseOrDefault) }
    var trapId by remember { mutableStateOf(item.trapIdOrDefault) }
    var manualCountText by remember { mutableStateOf(item.manualCount?.toString() ?: "") }
    var truePositivesText by remember { mutableStateOf(item.truePositives?.toString() ?: "") }
    var notes by remember { mutableStateOf(item.notes ?: "") }
    val boundTrap = remember(registryState, farm, greenhouse, trapId) {
        trapRegistry.findTrap(farm, greenhouse, trapId)
    }
    var latitude by remember(boundTrap?.id) {
        mutableStateOf(boundTrap?.latitude ?: item.latitude)
    }
    var longitude by remember(boundTrap?.id) {
        mutableStateOf(boundTrap?.longitude ?: item.longitude)
    }
    var showMapPicker by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }

    val farmOptions = remember(registryState) { trapRegistry.farmNames() }
    val greenhouseOptions = remember(registryState, farm) { trapRegistry.greenhouseNames(farm) }
    val trapOptions = remember(registryState, farm, greenhouse) {
        trapRegistry.trapsIn(farm, greenhouse).map { it.code }
    }

    val manual = manualCountText.toIntOrNull()
    val previewError = if (manual != null && manual > 0) {
        (item.count - manual).toDouble() / manual * 100.0
    } else null

    // Se acota aquí y no solo al guardar, para que la vista previa no muestre
    // una precisión mayor que 100 % mientras se teclea.
    val previewVp = manual?.let { m ->
        truePositivesText.toIntOrNull()?.coerceIn(0, minOf(item.count, m))
    }

    val trapChanged = farm != item.farmOrDefault ||
        greenhouse != item.greenhouseOrDefault ||
        trapId != item.trapIdOrDefault

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar muestreo") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Conteo de la app: ${item.count} moscas · cara ${item.faceEnum.code}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Metrics.densityPer100Cm2(item)?.let {
                    Text("Densidad: %.2f ind/100 cm²".format(it), fontSize = 13.sp)
                }

                Spacer(Modifier.height(12.dp))
                Text("Asignación de trampa", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "Corrige aquí un muestreo que quedó apuntando a la trampa vecina. " +
                        "Al cambiarlo queda registrado como asignación manual.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                EditablePicker("Finca", farmOptions, farm) { farm = it }
                Spacer(Modifier.height(8.dp))
                EditablePicker("Invernadero", greenhouseOptions, greenhouse) { greenhouse = it }
                Spacer(Modifier.height(8.dp))
                EditablePicker("Trampa", trapOptions, trapId) { trapId = it }

                item.trapSelectionMode?.let {
                    Text(
                        "Asignada originalmente por: $it" +
                            (item.gpsAccuracyM?.let { a -> " · GPS ±%.0f m".format(a) } ?: ""),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text("Ubicación de la trampa", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "El mapa y el plano del invernadero dibujan la TRAMPA, no cada foto. " +
                        "Por eso lo que se fija aquí es la coordenada de la ficha de " +
                        "$trapId, que es la que se ve reflejada.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (latitude != null && longitude != null)
                        "%.6f, %.6f".format(latitude, longitude)
                    else "Sin coordenada",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    OutlinedButton(onClick = { showMapPicker = true }) { Text("Elegir en el mapa") }
                    OutlinedButton(
                        enabled = !locating,
                        onClick = {
                            locating = true
                            scope.launch {
                                val loc = locationHelper.getCurrentLocation()
                                if (loc != null) {
                                    latitude = loc.latitude
                                    longitude = loc.longitude
                                } else {
                                    Toast.makeText(
                                        context,
                                        "No se pudo obtener la ubicación",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                locating = false
                            }
                        }
                    ) { Text(if (locating) "Ubicando..." else "Aquí") }
                }

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = manualCountText,
                    onValueChange = { new -> manualCountText = new.filter { it.isDigit() } },
                    label = { Text("Conteo manual del experto") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                previewError?.let {
                    Text(
                        "Error relativo: %.1f %% · acierto %.1f %%".format(
                            it,
                            (100 - kotlin.math.abs(it)).coerceIn(0.0, 100.0)
                        ),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (manual != null) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = truePositivesText,
                        onValueChange = { new -> truePositivesText = new.filter { it.isDigit() } },
                        label = { Text("De las ${item.count} detecciones, cuántas eran mosca") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (previewVp != null) {
                        val recall = if (manual > 0) previewVp.toDouble() / manual * 100.0 else null
                        val precision = if (item.count > 0) {
                            previewVp.toDouble() / item.count * 100.0
                        } else null
                        Text(
                            "Perdidas: ${manual - previewVp} · falsos positivos: ${item.count - previewVp}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Text(
                            buildString {
                                recall?.let { append("Sensibilidad %.1f %%".format(it)) }
                                precision?.let {
                                    if (isNotEmpty()) append(" · ")
                                    append("precisión %.1f %%".format(it))
                                }
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Sin este dato solo se comparan totales: un muestreo que se " +
                                "salta moscas y marca otras cosas cuadra en el total y " +
                                "parecería perfecto.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Observaciones") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                // La coordenada se escribe en la ficha de la trampa, que es lo que
                // dibujan el mapa y el plano. Antes solo se guardaba en el muestreo
                // y por eso parecía que no se guardaba nada.
                val la = latitude
                val lo = longitude
                if (la != null && lo != null) {
                    val target = trapRegistry.findTrap(farm, greenhouse, trapId)
                    if (target != null) {
                        trapRegistry.upsertTrap(target.copy(latitude = la, longitude = lo))
                    } else {
                        trapRegistry.upsertTrap(
                            TrapRecord(
                                farm = farm.ifBlank { item.farmOrDefault },
                                greenhouse = greenhouse.ifBlank { item.greenhouseOrDefault },
                                code = trapId.ifBlank { item.trapIdOrDefault },
                                latitude = la,
                                longitude = lo
                            )
                        )
                    }
                    Toast.makeText(
                        context,
                        "Coordenada guardada en la trampa $trapId",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                onSave(
                    item.copy(
                        farm = farm.ifBlank { null },
                        greenhouse = greenhouse.ifBlank { null },
                        trapId = trapId.ifBlank { null },
                        manualCount = manualCountText.toIntOrNull(),
                        truePositives = previewVp,
                        latitude = latitude,
                        longitude = longitude,
                        notes = notes.ifBlank { null },
                        trapRecordId = trapRegistry.findTrap(farm, greenhouse, trapId)?.id
                            ?: item.trapRecordId,
                        trapSelectionMode = if (trapChanged) "MANUAL" else item.trapSelectionMode
                    )
                )
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )

    if (showMapPicker) {
        MapLocationPickerDialog(
            title = "Ubicación de la trampa $trapId",
            initial = latitude?.let { la -> longitude?.let { lo -> GeoPoint(la, lo) } },
            onDismiss = { showMapPicker = false },
            onConfirm = { point ->
                latitude = point.lat
                longitude = point.lon
                showMapPicker = false
            }
        )
    }
}

@Composable
fun ImageViewerDialog(item: DetectionEntity, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showBoxes by remember { mutableStateOf(value = true) }
    var showExport by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                showBoxes = false
                                tryAwaitRelease()
                                showBoxes = true
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val oldScale = scale
                            scale = (scale * zoom).coerceIn(1f, 10f)
                            val zoomChange = scale / oldScale
                            offset = ((offset + pan) * zoomChange) - (centroid * (zoomChange - 1f))
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                        transformOrigin = TransformOrigin(0f, 0f)
                    )
            ) {
                item.imagePath?.let { path ->
                    AsyncImage(
                        model = File(path),
                        contentDescription = "Visor detallado",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    if (showBoxes) {
                        item.detections?.let { boxes ->
                            DynamicDetectionOverlay(
                                boxes = boxes,
                                imgW = item.imageWidth,
                                imgH = item.imageHeight
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 40.dp, end = 24.dp)
            ) {
                if (item.imagePath != null) {
                    IconButton(
                        onClick = { showExport = true },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Guardar o compartir la foto",
                            tint = Color.White
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
                }
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Mantén un dedo para ocultar cajas",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "Usa dos dedos para zoom",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }
    }

    if (showExport) {
        ExportImageDialog(item = item, onDismiss = { showExport = false })
    }
}

/**
 * Guardado y compartido de la evidencia fotográfica.
 *
 * Se ofrecen las dos versiones a propósito: la marcada sirve para el anexo del
 * documento, y la original sin cajas es la que hace falta para que un experto
 * cuente a ciegas sin verse influido por lo que ya detectó el modelo.
 */
@Composable
fun ExportImageDialog(item: DetectionEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }

    fun saveToGallery(withBoxes: Boolean) {
        if (working) return
        working = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                val bitmap = renderEvidenceBitmap(item, withBoxes = withBoxes)
                if (bitmap == null) null
                else {
                    val uri = saveImageToGallery(context, bitmap, evidenceFileName(item, withBoxes))
                    bitmap.recycle()
                    uri
                }
            }
            Toast.makeText(
                context,
                if (saved != null) "Guardada en la galería, álbum ${galleryAlbumName()}"
                else "No se pudo guardar en la galería. Prueba con Compartir.",
                Toast.LENGTH_LONG
            ).show()
            working = false
            onDismiss()
        }
    }

    fun share(withBoxes: Boolean) {
        if (working) return
        working = true
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                renderEvidenceBitmap(item, withBoxes = withBoxes)
            }
            if (bitmap == null) {
                Toast.makeText(
                    context,
                    "No se encontró la foto de este muestreo.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                shareImage(context, bitmap, evidenceFileName(item, withBoxes))
                bitmap.recycle()
            }
            working = false
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Descargar evidencia") },
        text = {
            Column {
                Text(
                    "La imagen se descargará con un pie que indica trampa, cara, fecha, conteo, " +
                        "densidad y los umbrales del modelo, para que sirva como evidencia por sí sola.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                TextButton(
                    enabled = !working,
                    onClick = { saveToGallery(true) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Descargar con detecciones marcadas") }

                TextButton(
                    enabled = !working,
                    onClick = { saveToGallery(false) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Descargar foto original (limpia)") }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                TextButton(
                    enabled = !working,
                    onClick = { share(true) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Compartir con detecciones") }

                if (working) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(enabled = !working, onClick = onDismiss) { Text("Cerrar") }
        }
    )
}

@Composable
fun ThumbnailDetectionOverlay(boxes: List<BoxedDeteccion>, imgW: Int, imgH: Int) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (imgW == 0 || imgH == 0) return@Canvas

        val viewW = size.width
        val viewH = size.height

        val scale = maxOf(viewW / imgW.toFloat(), viewH / imgH.toFloat())
        val drawW = imgW * scale
        val drawH = imgH * scale
        val offsetX = (viewW - drawW) / 2f
        val offsetY = (viewH - drawH) / 2f

        boxes.forEach { d ->
            drawRect(
                color = Color.Cyan,
                topLeft = Offset((d.rect.left * scale) + offsetX, (d.rect.top * scale) + offsetY),
                size = Size(d.rect.width() * scale, d.rect.height() * scale),
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

@Composable
fun DynamicDetectionOverlay(boxes: List<BoxedDeteccion>, imgW: Int, imgH: Int) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (imgW == 0 || imgH == 0) return@Canvas

        val screenWidth = size.width
        val screenHeight = size.height

        val scale = minOf(screenWidth / imgW.toFloat(), screenHeight / imgH.toFloat())
        val drawW = imgW * scale
        val drawH = imgH * scale
        val offsetX = (screenWidth - drawW) / 2f
        val offsetY = (screenHeight - drawH) / 2f

        boxes.forEach { d ->
            drawRect(
                color = Color.Cyan,
                topLeft = Offset((d.rect.left * scale) + offsetX, (d.rect.top * scale) + offsetY),
                size = Size(d.rect.width() * scale, d.rect.height() * scale),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

/**
 * Fiabilidad del conteo, junto a la foto.
 *
 * Muestra dos cosas que no son lo mismo y conviene no confundir:
 *
 *  - La confianza del modelo, calculada sobre las puntuaciones que dio a cada
 *    caja. Indica lo seguro que estaba de lo que marcó, no si acertó. Sirve para
 *    decidir si un conteo merece revisión manual.
 *  - El acierto, que solo existe si hay conteo del experto con qué comparar. Ese
 *    sí mide si el número es correcto.
 */
@Composable
fun ReliabilityRow(item: DetectionEntity) {
    val mean = Metrics.meanScore(item)
    val label = Metrics.confidenceLabel(item)

    if (mean != null && label != null) {
        val color = when (label) {
            "Alta" -> Color(0xFF57C785)
            "Media" -> Color(0xFFFFD166)
            else -> Color(0xFFFF6B6B)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 3.dp)
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Confianza del modelo: ${label.lowercase()}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = color
            )
            Spacer(Modifier.width(6.dp))
            // El decimal se muestra aparte, chico y gris: la etiqueta cualitativa
            // es la que se lee de un vistazo, el numero es solo detalle para quien
            // lo busca (antes iban pegados y del mismo color, y un numero cercano
            // al umbral en amarillo/rojo se leia como "algo anda mal").
            Text(
                text = "(media %.2f)".format(mean),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        Metrics.lowConfidenceCount(item)?.takeIf { it > 0 }?.let { low ->
            Text(
                "$low de ${item.count} detección(es) apenas sobre el umbral",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }

    val accuracy = Metrics.accuracyPct(item)
    val relative = Metrics.relativeErrorPct(item)
    if (accuracy != null && relative != null) {
        Text(
            "Acierto contra el experto: " + "%.1f".format(accuracy) +
                " % (error " + "%+.1f".format(relative) + " %)",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 2.dp)
        )
    } else {
        Text(
            "Sin conteo del experto: el acierto no se puede medir",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}
