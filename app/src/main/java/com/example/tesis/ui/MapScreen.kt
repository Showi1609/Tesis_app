package com.example.tesis.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import com.example.tesis.util.SettingsManager
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val historyManager = remember { HistoryManager(context) }
    val settingsManager = remember { SettingsManager(context) }
    val detections by historyManager.detections.collectAsState()
    
    val hasLocationPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    
    val initialPos = if (detections.isNotEmpty()) {
        val last = detections.first()
        LatLng(last.latitude ?: 4.711, last.longitude ?: -74.0721)
    } else {
        LatLng(4.711, -74.0721) // Bogotá por defecto
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(initialPos, 15f)
    }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            uiSettings = MapUiSettings(myLocationButtonEnabled = hasLocationPermission),
            properties = MapProperties(isMyLocationEnabled = hasLocationPermission)
        ) {
            detections.forEach { detection ->
                if (detection.latitude != null && detection.longitude != null) {
                    val (low, medium) = settingsManager.getThresholdsFor(detection.crop)
                    val color = when {
                        detection.count < low -> Color.Green
                        detection.count < medium -> Color.Yellow
                        else -> Color.Red
                    }
                    
                    Marker(
                        state = MarkerState(position = LatLng(detection.latitude, detection.longitude)),
                        title = "${detection.crop}: ${detection.count}",
                        snippet = "Lote: ${detection.lot}",
                        icon = BitmapDescriptorFactory.defaultMarker(
                            when(color) {
                                Color.Green -> BitmapDescriptorFactory.HUE_GREEN
                                Color.Yellow -> BitmapDescriptorFactory.HUE_YELLOW
                                else -> BitmapDescriptorFactory.HUE_RED
                            }
                        )
                    )
                }
            }
        }

        // Leyenda
        Card(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .padding(bottom = 80.dp), // Evitar bottom bar
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.85f),
                contentColor = Color.White
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Intensidad MIP", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                LegendItem(Color.Green, "Bajo")
                LegendItem(Color.Yellow, "Medio")
                LegendItem(Color.Red, "Alto")
            }
        }
    }
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Box(modifier = Modifier.size(12.dp).background(color, CircleShape))
        Spacer(modifier = Modifier.width(10.dp))
        Text(label, fontSize = 11.sp, color = Color.White)
    }
}
