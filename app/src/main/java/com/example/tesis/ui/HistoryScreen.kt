package com.example.tesis.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LocationOn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.compose.material.icons.filled.PictureAsPdf
import com.example.tesis.BoxedDeteccion
import com.example.tesis.util.DetectionEntity
import com.example.tesis.util.HistoryManager
import com.example.tesis.util.exportToCsv
import com.example.tesis.util.generatePdfReport
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val historyManager = remember { HistoryManager(context) }
    val detections by historyManager.detections.collectAsState()
    val sdf = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    var selectedItem by remember { mutableStateOf<DetectionEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Historial de Muestreos", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = { generatePdfReport(context, detections) }) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = "Exportar PDF", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { exportToCsv(context, detections) }) {
                    Icon(Icons.Default.FileDownload, contentDescription = "Exportar CSV")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (detections.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No hay registros aún", color = MaterialTheme.colorScheme.secondary)
            }
        } else {
            LazyColumn {
                items(detections) { item ->
                    DetectionItem(
                        item = item, 
                        sdf = sdf, 
                        onDelete = { historyManager.delete(item) },
                        onViewMap = {
                            val uri = "geo:${item.latitude},${item.longitude}?q=${item.latitude},${item.longitude}(Muestreo)"
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                            context.startActivity(intent)
                        }
                    ) { selectedItem = it }
                }
            }
        }
    }

    // Visor de imagen detallado con Zoom y Hold-to-Compare
    selectedItem?.let { item ->
        ImageViewerDialog(
            item = item, 
            onDismiss = { selectedItem = null }
        )
    }
}

@Composable
fun DetectionItem(
    item: DetectionEntity, 
    sdf: SimpleDateFormat, 
    onDelete: () -> Unit,
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
                    val cropName = item.crop.let { if (it == "null" || it.isNullOrEmpty()) "Rosa bajo invernadero" else it }
                    val lotName = item.lot.let { if (it == "null" || it.isNullOrEmpty()) "Lote 1" else it }
                    
                    Text(text = "Moscas: ${item.count}", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(text = "$cropName - $lotName", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    Text(text = sdf.format(Date(item.timestamp)), fontSize = 12.sp)
                    if (item.latitude != null && item.longitude != null) {
                        Text(text = "Ubicación: ${"%.4f".format(item.latitude)}, ${"%.4f".format(item.longitude)}", fontSize = 11.sp)
                    }
                }
                
                if (item.latitude != null) {
                    IconButton(onClick = onViewMap) {
                        Icon(Icons.Default.LocationOn, contentDescription = "Ver mapa", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun ImageViewerDialog(item: DetectionEntity, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showBoxes by remember { mutableStateOf(value = true) }

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

            // Botón cerrar
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(40.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
            }
            
            // Texto indicativo
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
}

@Composable
fun ThumbnailDetectionOverlay(boxes: List<BoxedDeteccion>, imgW: Int, imgH: Int) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (imgW == 0 || imgH == 0) return@Canvas
        
        val viewW = size.width
        val viewH = size.height
        
        // Calcular escala de Crop: max(viewW/imgW, viewH/imgH)
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
