package com.example.tesis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tesis.util.CropSettings
import com.example.tesis.util.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsManager: SettingsManager) {
    val currentSettings by settingsManager.settings.collectAsState()
    
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        topBar = {
            TopAppBar(title = { Text("Configuración MIP", fontWeight = FontWeight.Bold) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Añadir Cultivo")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            // Sección de Disparo Automático
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Disparo Automático", fontWeight = FontWeight.Bold)
                        Text("Captura la foto cuando el enfoque es óptimo.", fontSize = 12.sp)
                    }
                    Switch(
                        checked = currentSettings.autoShotEnabled,
                        onCheckedChange = { settingsManager.save(currentSettings.copy(autoShotEnabled = it)) }
                    )
                }
            }

            Text("Umbrales por Cultivo", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Define cuándo el nivel de plaga es bajo, medio o alto.", fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
            
            Spacer(modifier = Modifier.height(16.dp))
            
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(currentSettings.crops) { crop ->
                    CropSettingItem(crop)
                }
            }
        }
    }

    if (showAddDialog) {
        AddCropDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { newCrop ->
                val newList = currentSettings.crops.toMutableList()
                newList.add(newCrop)
                settingsManager.save(currentSettings.copy(crops = newList))
                showAddDialog = false
            }
        )
    }
}

@Composable
fun CropSettingItem(crop: CropSettings) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(crop.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Bajo: < ${crop.lowThreshold} | Medio: < ${crop.mediumThreshold}", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun AddCropDialog(onDismiss: () -> Unit, onAdd: (CropSettings) -> Unit) {
    var name by remember { mutableStateOf("") }
    var low by remember { mutableStateOf("5") }
    var medium by remember { mutableStateOf("15") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo Cultivo") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre del Cultivo") })
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = low, onValueChange = { low = it }, label = { Text("Umbral Bajo (Verde)") })
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = medium, onValueChange = { medium = it }, label = { Text("Umbral Medio (Amarillo)") })
            }
        },
        confirmButton = {
            Button(onClick = { 
                if (name.isNotEmpty()) {
                    onAdd(CropSettings(name, low.toIntOrNull() ?: 5, medium.toIntOrNull() ?: 15))
                }
            }) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
