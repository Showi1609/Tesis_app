package com.example.tesis.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tesis.util.CropSettings
import com.example.tesis.util.FarmMapStore
import com.example.tesis.util.SessionManager
import com.example.tesis.util.exportConfig
import com.example.tesis.util.importConfig
import com.example.tesis.util.ModelInfo
import com.example.tesis.util.confThresholdOrDefault
import com.example.tesis.util.iouNmsOrDefault
import com.example.tesis.util.tileGridOrDefault
import com.example.tesis.util.tiledDetectionOrDefault
import com.example.tesis.util.readFarmMap
import com.example.tesis.util.SettingsManager
import com.example.tesis.util.TrapRegistry
import com.example.tesis.util.usesDefaultThresholds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    onOpenTraps: () -> Unit,
    onOpenSessions: () -> Unit
) {
    val context = LocalContext.current
    val currentSettings by settingsManager.settings.collectAsState()
    val trapRegistry = remember { TrapRegistry(context) }
    val registryState by trapRegistry.state.collectAsState()
    val sessionManager = remember { SessionManager(context) }
    val sessionState by sessionManager.state.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Añadir Cultivo")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(
                    "Configuración MIP",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
                AppLogoIcon(modifier = Modifier.align(Alignment.TopEnd), size = 40.dp)
            }

            // ---------------- Sesiones ----------------
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpenSessions() },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sesiones", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        val active = sessionState.active
                        Text(
                            active?.label ?: "Sin sesión activa",
                            fontSize = 12.sp
                        )
                        Text(
                            "${sessionState.sessionList.size} sesión(es) · agrupan el trabajo " +
                                "por jornada y operario.",
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        if (active?.needsOperator == true) {
                            Text(
                                "La sesión activa no tiene operario",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = "Abrir sesiones")
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---------------- Trampas ----------------
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpenTraps() },
                // primaryContainer quedaba casi blanco con texto blanco encima.
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Trampas", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        val traps = registryState.trapList.filter { it.isActive }
                        val overdue = traps.count { trapRegistry.isOverdue(it) }
                        val undated = traps.count { it.installedAt == null }
                        Text(
                            "${traps.size} registrada(s) en ${traps.map { it.farm }.distinct().size} finca(s)",
                            fontSize = 12.sp
                        )
                        Text(
                            "Tamaño de trampa, ciclo de reposición y fecha de instalación de cada una.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        if (overdue > 0) {
                            Text(
                                "$overdue trampa(s) superan su ciclo de reposición",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        if (undated > 0) {
                            Text(
                                "$undated sin fecha de instalación: sin ella no hay capturas/trampa/día",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = "Abrir trampas")
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---------------- Mapa de la finca ----------------
            FarmMapCard()

            Spacer(Modifier.height(16.dp))

            // ---------------- Disparo automático ----------------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Disparo automático", fontWeight = FontWeight.Bold)
                        Text(
                            "Captura la foto cuando el encuadre y el enfoque son óptimos.",
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = currentSettings.autoShotEnabled,
                        onCheckedChange = {
                            settingsManager.save(currentSettings.copy(autoShotEnabled = it))
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---------------- Traslado de configuración ----------------
            ConfigTransferCard()

            Spacer(Modifier.height(16.dp))

            // ---------------- Umbrales del detector ----------------
            DetectionThresholdsCard(settingsManager)

            Spacer(Modifier.height(24.dp))

            // ---------------- Umbrales ----------------
            Text("Umbrales por cultivo", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Definen el semáforo MIP sobre el conteo por cara de trampa.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(12.dp))

            currentSettings.crops.forEach { crop ->
                CropSettingItem(crop)
            }

            Spacer(Modifier.height(80.dp))
        }
    }

    if (showAddDialog) {
        AddCropDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { newCrop ->
                settingsManager.save(currentSettings.copy(crops = currentSettings.crops + newCrop))
                showAddDialog = false
            }
        )
    }
}

/**
 * Llevar la configuración a otro teléfono.
 *
 * Exporta e importa el mapa de la finca, el registro de trampas y los ajustes.
 * NO viajan los muestreos ni las fotos: el segundo teléfono arranca a tomar
 * fotos desde cero, que es justo lo que se busca, y así no hay forma de duplicar
 * registros al importar.
 */
@Composable
private fun ConfigTransferCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var lastMessage by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { importConfig(context, uri) }
            busy = false
            lastMessage = result.message
            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Configuración", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "Lleva el mapa, las trampas y los umbrales a otro teléfono. Los muestreos " +
                    "y las fotos no viajan: el otro equipo empieza a tomar fotos desde cero.",
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                "Vale la pena porque si en un teléfono la trampa se llama T-01 y en el otro " +
                    "T-1, los reportes salen partidos y el error solo se ve al final.",
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { exportConfig(context) }
                            busy = false
                            val message = if (saved == null) {
                                "No se pudo guardar el archivo"
                            } else {
                                "Guardado en ${saved.folderLabel}\n${saved.fileName}"
                            }
                            lastMessage = message
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Exportar", fontSize = 13.sp) }

                OutlinedButton(
                    enabled = !busy,
                    onClick = { importLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f)
                ) { Text("Importar", fontSize = 13.sp) }
            }

            if (busy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            lastMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 11.sp)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Importar REEMPLAZA trampas, mapa y ajustes de este teléfono. El historial " +
                    "de muestreos no se toca.",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * Umbrales de inferencia del detector.
 *
 * Se exponen porque son la palanca directa contra el subconteo, pero con la
 * advertencia a la vista: cambiarlos cambia el conteo sobre la MISMA foto, así
 * que los muestreos tomados antes y después dejan de ser comparables salvo que
 * se reporte el umbral de cada uno. Por eso cada registro guarda el suyo.
 */
@Composable
private fun DetectionThresholdsCard(settingsManager: SettingsManager) {
    val settings by settingsManager.settings.collectAsState()

    // Estado local mientras se arrastra: guardar en cada píxel del deslizador
    // escribiría el archivo decenas de veces por gesto.
    var conf by remember(settings.confThreshold) { mutableFloatStateOf(settings.confThresholdOrDefault) }
    var iou by remember(settings.iouNms) { mutableFloatStateOf(settings.iouNmsOrDefault) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Detector",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                if (!settings.usesDefaultThresholds) {
                    TextButton(onClick = { settingsManager.resetDetectionThresholds() }) {
                        Text("Restablecer", fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Confianza mínima: ${"%.2f".format(conf)}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Slider(
                value = conf,
                onValueChange = { conf = it },
                onValueChangeFinished = { settingsManager.updateDetectionThresholds(conf, iou) },
                valueRange = ModelInfo.CONF_MIN..ModelInfo.CONF_MAX
            )
            Text(
                "Más baja recupera moscas dudosas y deja entrar polvo y restos. " +
                    "De fábrica: ${"%.2f".format(ModelInfo.CONF_THRESHOLD)}.",
                fontSize = 11.sp
            )

            Spacer(Modifier.height(10.dp))
            Text(
                "Solape del NMS: ${"%.2f".format(iou)}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Slider(
                value = iou,
                onValueChange = { iou = it },
                onValueChangeFinished = { settingsManager.updateDetectionThresholds(conf, iou) },
                valueRange = ModelInfo.IOU_MIN..ModelInfo.IOU_MAX
            )
            Text(
                "En la trampa las moscas se pegan unas a otras. Si dos cajas vecinas se " +
                    "solapan más que este valor, una se borra por duplicada — y ese " +
                    "subconteo empeora justo en alta densidad. Subirlo conserva más. " +
                    "De fábrica: ${"%.2f".format(ModelInfo.IOU_NMS)}.",
                fontSize = 11.sp
            )

            if (!settings.usesDefaultThresholds) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Con estos valores el conteo sobre la MISMA foto cambia. Los " +
                        "muestreos anteriores no son comparables con los nuevos salvo que " +
                        "se reporte el umbral de cada uno; va en el .xlsx, columnas " +
                        "conf_umbral e iou_nms.",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

            // ---- Mosaico ----
            val tiled = settings.tiledDetectionOrDefault
            val grid = settings.tileGridOrDefault

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Detección por mosaico", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "Parte la foto en trozos y pasa cada uno completo por el modelo.",
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = tiled,
                    onCheckedChange = { settingsManager.updateTiling(it, grid) }
                )
            }

            if (tiled) {
                Spacer(Modifier.height(8.dp))
                Text("Rejilla", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (ModelInfo.TILE_GRID_MIN..ModelInfo.TILE_GRID_MAX).forEach { option ->
                        FilterChip(
                            selected = grid == option,
                            onClick = { settingsManager.updateTiling(true, option) },
                            label = { Text("${option}×$option", fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "El modelo entra siempre al mismo tamaño; lo que cambia es cuánta " +
                        "trampa cabe dentro. Con ${grid}×$grid cada trozo cubre un " +
                        "${"%.0f".format(100f / grid)} % del ancho, así que una mosca ocupa " +
                        "unas $grid veces más píxeles. El precio son ${grid * grid} " +
                        "inferencias por foto: tarda varios segundos.",
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Solo aplica a la foto fija, no al visor en vivo. Cada muestreo " +
                        "registra con qué modo se contó (columna modo_deteccion), así que " +
                        "puedes analizar la misma foto en los dos modos y comparar.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
                Text(
                    "Bajo: < ${crop.lowThreshold} | Medio: < ${crop.mediumThreshold}",
                    fontSize = 12.sp
                )
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
        title = { Text("Nuevo cultivo") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre del cultivo") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = low,
                    onValueChange = { new -> low = new.filter { it.isDigit() } },
                    label = { Text("Umbral bajo (verde)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = medium,
                    onValueChange = { new -> medium = new.filter { it.isDigit() } },
                    label = { Text("Umbral medio (amarillo)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isNotEmpty()) {
                    onAdd(CropSettings(name, low.toIntOrNull() ?: 5, medium.toIntOrNull() ?: 15))
                }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/**
 * Importación del mapa de la finca exportado desde Google My Maps.
 *
 * Los polígonos de invernadero hacen dos cosas: dan contexto real al mapa, y
 * permiten saber en qué invernadero está parado el operario. Esto último es
 * fiable donde la cercanía entre trampas no lo es, porque un invernadero mide
 * decenas de metros y el error del GPS cabe dentro con holgura.
 */
@Composable
private fun FarmMapCard() {
    val context = LocalContext.current
    val store = remember { FarmMapStore(context) }
    val farmMap by store.map.collectAsState()
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val name = queryDisplayName(context, uri)
            val imported = withContext(Dispatchers.IO) { readFarmMap(context, uri, name) }
            if (imported == null || imported.isEmpty) {
                Toast.makeText(
                    context,
                    "No se encontraron invernaderos en ese archivo. Debe ser el KML o KMZ que exporta My Maps.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                store.save(imported)
                Toast.makeText(
                    context,
                    "Importados ${imported.areaList.size} recinto(s)",
                    Toast.LENGTH_LONG
                ).show()
            }
            importing = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Mapa de la finca", fontWeight = FontWeight.Bold, fontSize = 16.sp)

            if (farmMap.isEmpty) {
                Text(
                    "Importa el KML o KMZ que exportas desde Google My Maps (en computador: " +
                        "menú del mapa › Exportar a KML/KMZ). Los invernaderos delimitados " +
                        "aparecerán en el mapa y la app podrá saber en cuál estás parado.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
            } else {
                Text(
                    (farmMap.documentName ?: farmMap.sourceName ?: "Mapa importado"),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    "${farmMap.areaList.size} invernadero(s) delimitado(s) · " +
                        "${farmMap.placeList.size} punto(s)",
                    fontSize = 12.sp
                )
                val layers = farmMap.areaList.mapNotNull { it.layer }.distinct()
                if (layers.isNotEmpty()) {
                    Text(
                        "Bloques: ${layers.joinToString(", ")}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !importing,
                    onClick = { picker.launch(arrayOf("*/*")) }
                ) {
                    Text(if (importing) "Importando..." else if (farmMap.isEmpty) "Importar KML" else "Reemplazar")
                }
                if (!farmMap.isEmpty) {
                    TextButton(onClick = { store.clear() }) { Text("Quitar") }
                }
            }

            // Los puntos del KML son referencia del mapa, no trampas de monitoreo:
            // las de monitoreo se dan de alta a mano y se posicionan sobre el
            // polígono. Aquí solo queda cómo retirar las que se importaron antes.
            if (farmMap.placeList.isNotEmpty()) {
                val registry = remember { TrapRegistry(context) }
                val registryState by registry.state.collectAsState()
                val imported = remember(registryState, farmMap) {
                    registry.countMapImported(farmMap)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    "El mapa trae ${farmMap.placeList.size} punto(s) de referencia. No son " +
                        "trampas de monitoreo: esas se registran en Ajustes › Trampas y se " +
                        "posicionan sobre el polígono de su invernadero.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (imported > 0) {
                    Text(
                        "Hay $imported ficha(s) de trampa creadas desde esos puntos.",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            val removed = registry.removeMapImportedTraps(farmMap)
                            Toast.makeText(
                                context,
                                "Eliminadas $removed ficha(s) creadas desde el mapa",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text("Eliminar esas fichas") }
                }
            }
        }
    }
}

/** Nombre visible del documento elegido, para mostrarlo como origen del mapa. */
private fun queryDisplayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
} catch (_: Exception) {
    null
}
