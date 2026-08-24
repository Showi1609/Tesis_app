package com.example.tesis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import android.widget.Toast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tesis.util.DEFAULT_REPLACEMENT_DAYS
import com.example.tesis.util.FarmProfile
import com.example.tesis.util.FarmMapStore
import com.example.tesis.util.FramingMode
import com.example.tesis.util.GeoPoint
import com.example.tesis.util.HistoryManager
import com.example.tesis.util.LocationHelper
import com.example.tesis.util.TrapRecord
import com.example.tesis.util.TrapRegistry
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Malla de trampas del estudio.
 *
 * Cada trampa lleva su propia fecha de instalación porque el ciclo de reposición
 * es individual: reponer la T-03 hoy no reinicia el reloj de la T-07.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrapsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val registry = remember { TrapRegistry(context) }
    val historyManager = remember { HistoryManager(context) }
    val state by registry.state.collectAsState()
    val detections by historyManager.detections.collectAsState()
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    // Alta automática de las trampas que ya aparecen en el historial.
    LaunchedEffect(detections.size) { registry.seedFrom(detections) }

    var editing by remember { mutableStateOf<TrapRecord?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editingFarm by remember { mutableStateOf<FarmProfile?>(null) }
    var confirmDelete by remember { mutableStateOf<TrapRecord?>(null) }

    val farms = registry.farmNames()

    Scaffold(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        topBar = {
            TopAppBar(
                title = { Text("Trampas", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Nueva trampa") }
            )
        }
    ) { padding ->
        if (state.trapList.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Aún no hay trampas registradas.\n\nDa de alta la malla de tu invernadero " +
                        "para que cada trampa lleve su propia fecha de instalación y la app " +
                        "pueda calcular capturas por trampa por día.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        ) {
            farms.forEach { farm ->
                val profile = registry.farmProfile(farm)
                val trapsOfFarm = state.trapList.filter { it.farm == farm && it.isActive }

                item(key = "farm-$farm") {
                    FarmCard(
                        profile = profile,
                        trapCount = trapsOfFarm.size,
                        onEdit = { editingFarm = profile }
                    )
                }

                trapsOfFarm.groupBy { it.greenhouse }.toSortedMap().forEach { (greenhouse, traps) ->
                    item(key = "gh-$farm-$greenhouse") {
                        Text(
                            greenhouse,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(traps.sortedBy { it.code }, key = { it.id }) { trap ->
                        TrapRow(
                            trap = trap,
                            days = registry.exposureDays(trap),
                            overdue = registry.isOverdue(trap),
                            cycleDays = profile.cycleDays,
                            sizeLabel = registry.effectiveSize(trap).let { (w, h) ->
                                "${trimCm(w)} × ${trimCm(h)} cm"
                            },
                            installedLabel = trap.installedAt?.let { dateFormat.format(Date(it)) },
                            onReplace = { registry.replaceTrap(trap.id) },
                            onEdit = { editing = trap },
                            onDelete = { confirmDelete = trap }
                        )
                    }
                }

                item(key = "sp-$farm") { Spacer(Modifier.height(20.dp)) }
            }

            item { Spacer(Modifier.height(90.dp)) }
        }
    }

    if (creating || editing != null) {
        TrapEditorDialog(
            initial = editing,
            knownFarms = farms,
            knownGreenhouses = editing?.farm?.let { registry.greenhouseNames(it) } ?: emptyList(),
            registry = registry,
            onDismiss = { creating = false; editing = null },
            onSave = { trap ->
                registry.upsertTrap(trap)
                creating = false
                editing = null
            }
        )
    }

    editingFarm?.let { profile ->
        FarmEditorDialog(
            profile = profile,
            onDismiss = { editingFarm = null },
            onSave = {
                registry.upsertFarm(it)
                editingFarm = null
            }
        )
    }

    confirmDelete?.let { trap ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Eliminar trampa") },
            text = {
                Text(
                    "Se elimina la ficha de ${trap.code} en ${trap.greenhouse}. " +
                        "Los muestreos ya guardados no se borran ni se modifican."
                )
            },
            confirmButton = {
                Button(onClick = {
                    registry.deleteTrap(trap.id)
                    confirmDelete = null
                }) { Text("Eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun FarmCard(profile: FarmProfile, trapCount: Int, onEdit: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    "Trampa ${trimCm(profile.widthCm)} × ${trimCm(profile.heightCm)} cm · " +
                        "reposición cada ${profile.cycleDays} d · $trapCount trampa(s)",
                    fontSize = 12.sp
                )
                Text(
                    "Encuadre por defecto: ${profile.framingMode.label}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Editar finca")
            }
        }
    }
}

@Composable
private fun TrapRow(
    trap: TrapRecord,
    days: Double?,
    overdue: Boolean,
    cycleDays: Int,
    sizeLabel: String,
    installedLabel: String?,
    onReplace: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (overdue) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(trap.code, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    when {
                        installedLabel == null -> "Sin fecha de instalación"
                        days == null -> "Instalada el $installedLabel"
                        else -> "Instalada el $installedLabel · ${"%.0f".format(days)} día(s)"
                    },
                    fontSize = 12.sp
                )
                Text(sizeLabel, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                trap.positionLabel?.let {
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (trap.point == null) {
                    Text(
                        "Sin coordenada: no aparece en el mapa",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (overdue) {
                    Text(
                        "Supera el ciclo de $cycleDays d: puede estar saturada y subestimar",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (installedLabel == null) {
                    Text(
                        "Sin fecha no hay capturas/trampa/día",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            IconButton(onClick = onReplace) {
                Icon(
                    Icons.Default.Autorenew,
                    contentDescription = "Reponer adhesivo",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Editar")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrapEditorDialog(
    initial: TrapRecord?,
    knownFarms: List<String>,
    knownGreenhouses: List<String>,
    registry: TrapRegistry,
    onDismiss: () -> Unit,
    onSave: (TrapRecord) -> Unit
) {
    var farm by remember { mutableStateOf(initial?.farm ?: knownFarms.firstOrNull() ?: "Finca 1") }
    var greenhouse by remember {
        mutableStateOf(initial?.greenhouse ?: knownGreenhouses.firstOrNull() ?: "Invernadero 1")
    }
    var code by remember { mutableStateOf(initial?.code ?: "") }
    var installedAt by remember { mutableStateOf(initial?.installedAt) }
    var overrideSize by remember { mutableStateOf(initial?.widthCm != null) }
    var widthText by remember { mutableStateOf(initial?.widthCm?.let { trimCm(it) } ?: "") }
    var heightText by remember { mutableStateOf(initial?.heightCm?.let { trimCm(it) } ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var showDatePicker by remember { mutableStateOf(false) }

    var latitude by remember { mutableStateOf(initial?.latitude) }
    var longitude by remember { mutableStateOf(initial?.longitude) }
    var capturing by remember { mutableStateOf(false) }
    var showMapPicker by remember { mutableStateOf(false) }
    var rowText by remember { mutableStateOf(initial?.row?.toString() ?: "") }
    var columnText by remember { mutableStateOf(initial?.column?.toString() ?: "") }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locationHelper = remember { LocationHelper(context) }
    val farmMapStore = remember { FarmMapStore(context) }
    val farmMap by farmMapStore.map.collectAsState()

    // La pertenencia al invernadero es geométrica: al fijar la posición se
    // resuelve por el polígono que la contiene, en vez de fiarse del nombre
    // escrito a mano.
    val assign: (Double, Double) -> Unit = { la, lo ->
        latitude = la
        longitude = lo
        farmMap.areaAt(GeoPoint(la, lo))?.let { area ->
            greenhouse = area.name
            farmMap.documentName?.takeIf { it.isNotBlank() }?.let { farm = it }
        }
    }

    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val greenhouseOptions = remember(farm) { registry.greenhouseNames(farm) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nueva trampa" else "Editar trampa") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                EditablePicker("Finca", knownFarms, farm) { farm = it }
                Spacer(Modifier.height(8.dp))
                EditablePicker("Invernadero", greenhouseOptions, greenhouse) { greenhouse = it }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Código de la trampa") },
                    placeholder = { Text("Ej: T-01") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(14.dp))
                Text("Instalación del adhesivo actual", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    installedAt?.let { dateFormat.format(Date(it)) } ?: "Sin fecha",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    OutlinedButton(onClick = { showDatePicker = true }) { Text("Elegir") }
                    OutlinedButton(onClick = { installedAt = System.currentTimeMillis() }) {
                        Text("Hoy")
                    }
                    if (installedAt != null) {
                        TextButton(onClick = { installedAt = null }) { Text("Quitar") }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text("Posición dentro del invernadero", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "Cama y surco. No depende del GPS y es lo que permite el mapa de calor: " +
                        "a esta escala el GPS no distingue trampas vecinas, pero la cama y el " +
                        "surco son exactos por construcción.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    OutlinedTextField(
                        value = rowText,
                        onValueChange = { new -> rowText = new.filter { it.isDigit() } },
                        label = { Text("Cama / fila") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = columnText,
                        onValueChange = { new -> columnText = new.filter { it.isDigit() } },
                        label = { Text("Surco / columna") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text("Coordenada de la trampa", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    text = if (latitude != null && longitude != null)
                        "%.6f, %.6f".format(latitude, longitude)
                    else "Sin coordenada",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    "Al fijarla, el invernadero se asigna solo según el polígono que la " +
                        "contenga. Sirve además para dibujarla en el plano y para sugerirla " +
                        "al guardar un muestreo.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (latitude != null && longitude != null) {
                    val area = farmMap.areaAt(GeoPoint(latitude!!, longitude!!))
                    Text(
                        text = area?.let { "Dentro de ${it.name}" }
                            ?: "Fuera de todos los polígonos importados",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (area != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    OutlinedButton(
                        enabled = !capturing,
                        onClick = {
                            capturing = true
                            scope.launch {
                                val loc = locationHelper.getCurrentLocation()
                                if (loc != null) {
                                    assign(loc.latitude, loc.longitude)
                                    Toast.makeText(
                                        context,
                                        "Coordenada capturada (±%.0f m)".format(loc.accuracy),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "No se pudo obtener la ubicación",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                capturing = false
                            }
                        }
                    ) { Text(if (capturing) "Capturando..." else "Capturar aquí") }
                    OutlinedButton(onClick = { showMapPicker = true }) { Text("Elegir en el mapa") }
                    if (latitude != null) {
                        TextButton(onClick = { latitude = null; longitude = null }) { Text("Quitar") }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Tamaño distinto al de la finca", fontSize = 13.sp)
                        Text(
                            "Solo si esta trampa no usa el formato estándar de la finca.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = overrideSize, onCheckedChange = { overrideSize = it })
                }
                if (overrideSize) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        OutlinedTextField(
                            value = widthText,
                            onValueChange = { new -> widthText = new.filter { it.isDigit() || it == '.' || it == ',' } },
                            label = { Text("Ancho (cm)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = heightText,
                            onValueChange = { new -> heightText = new.filter { it.isDigit() || it == '.' || it == ',' } },
                            label = { Text("Alto (cm)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Observaciones") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = code.isNotBlank() && farm.isNotBlank() && greenhouse.isNotBlank(),
                onClick = {
                    onSave(
                        (initial ?: TrapRecord(farm = farm, greenhouse = greenhouse, code = code)).copy(
                            farm = farm.trim(),
                            greenhouse = greenhouse.trim(),
                            code = code.trim(),
                            installedAt = installedAt,
                            widthCm = if (overrideSize) parseCmValue(widthText) else null,
                            heightCm = if (overrideSize) parseCmValue(heightText) else null,
                            latitude = latitude,
                            longitude = longitude,
                            row = rowText.toIntOrNull(),
                            column = columnText.toIntOrNull(),
                            notes = notes.ifBlank { null }
                        )
                    )
                }
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )

    if (showMapPicker) {
        MapLocationPickerDialog(
            title = "Ubicación de ${code.ifBlank { "la trampa" }}",
            initial = latitude?.let { la -> longitude?.let { lo -> GeoPoint(la, lo) } },
            onDismiss = { showMapPicker = false },
            onConfirm = { point ->
                assign(point.lat, point.lon)
                showMapPicker = false
            }
        )
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = installedAt ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    installedAt = pickerState.selectedDateMillis
                    showDatePicker = false
                }) { Text("Aceptar") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancelar") }
            }
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun FarmEditorDialog(
    profile: FarmProfile,
    onDismiss: () -> Unit,
    onSave: (FarmProfile) -> Unit
) {
    var width by remember { mutableStateOf(trimCm(profile.widthCm)) }
    var height by remember { mutableStateOf(trimCm(profile.heightCm)) }
    var windowWidth by remember { mutableStateOf(trimCm(profile.windowWidth)) }
    var windowHeight by remember { mutableStateOf(trimCm(profile.windowHeight)) }
    var cycle by remember { mutableStateOf(profile.cycleDays.toString()) }
    var framing by remember { mutableStateOf(profile.framingMode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profile.name) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Formato de trampa que usa esta finca. Las trampas nuevas lo heredan; " +
                        "cada muestreo guarda su propia copia, así que cambiarlo no altera lo ya medido.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))
                Text("Cara de la trampa", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                CmPairFields(width, height, { width = it }, { height = it })

                Spacer(Modifier.height(12.dp))
                Text("Ventana de muestreo", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "Solo se usa en los muestreos registrados con encuadre de ventana.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CmPairFields(windowWidth, windowHeight, { windowWidth = it }, { windowHeight = it })

                Spacer(Modifier.height(12.dp))
                Text("Encuadre por defecto", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    FramingMode.entries.forEach { mode ->
                        FilterChip(
                            selected = framing == mode,
                            onClick = { framing = mode },
                            label = { Text(mode.label, fontSize = 12.sp) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = cycle,
                    onValueChange = { new -> cycle = new.filter { it.isDigit() } },
                    label = { Text("Ciclo de reposición (días)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "La app avisa cuando una trampa supera este ciclo, porque un adhesivo " +
                        "saturado deja de capturar de forma proporcional y subestima la población.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    profile.copy(
                        trapWidthCm = parseCmValue(width),
                        trapHeightCm = parseCmValue(height),
                        windowWidthCm = parseCmValue(windowWidth),
                        windowHeightCm = parseCmValue(windowHeight),
                        defaultFramingMode = framing.code,
                        replacementDays = cycle.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_REPLACEMENT_DAYS
                    )
                )
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun CmPairFields(
    width: String,
    height: String,
    onWidth: (String) -> Unit,
    onHeight: (String) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        OutlinedTextField(
            value = width,
            onValueChange = { new -> onWidth(new.filter { it.isDigit() || it == '.' || it == ',' }) },
            label = { Text("Ancho (cm)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = height,
            onValueChange = { new -> onHeight(new.filter { it.isDigit() || it == '.' || it == ',' }) },
            label = { Text("Alto (cm)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Campo que combina lista desplegable y texto libre.
 *
 * Escribir el identificador a mano en cada muestreo es lo que produce que "T-01"
 * y "T-1" acaben siendo dos trampas distintas en los reportes. Con la lista se
 * elige lo ya existente; el texto libre queda para dar de alta algo nuevo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditablePicker(
    label: String,
    options: List<String>,
    value: String,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = {
                if (options.isNotEmpty()) {
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Elegir $label")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

internal fun trimCm(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString()
    else String.format(Locale.getDefault(), "%.1f", value)

internal fun parseCmValue(text: String): Float? =
    text.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
