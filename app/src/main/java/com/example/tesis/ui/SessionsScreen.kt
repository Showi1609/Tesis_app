package com.example.tesis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tesis.util.HistoryManager
import com.example.tesis.util.SamplingSession
import com.example.tesis.util.SessionManager
import com.example.tesis.util.TrapRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sesiones de muestreo.
 *
 * Una sesión agrupa un lote de trabajo con su operario. Sirve para separar
 * jornadas y, sobre todo, para poder juntar después lo que midieron dos
 * teléfonos distintos sin que las filas se mezclen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val sessionManager = remember { SessionManager(context) }
    val historyManager = remember { HistoryManager(context) }
    val trapRegistry = remember { TrapRegistry(context) }

    val state by sessionManager.state.collectAsState()
    val detections by historyManager.detections.collectAsState()
    val registryState by trapRegistry.state.collectAsState()

    val counts = remember(detections) {
        detections.groupingBy { it.sessionId ?: "" }.eachCount()
    }
    val farmOptions = remember(registryState) { trapRegistry.farmNames() }

    var editing by remember { mutableStateOf<SamplingSession?>(null) }
    var creating by remember { mutableStateOf(false) }

    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    Scaffold(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        topBar = {
            TopAppBar(
                title = { Text("Sesiones de muestreo") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, contentDescription = "Nueva sesión")
            }
        }
    ) { padding ->
        val sessions = state.sessionList.sortedByDescending { it.startedAt ?: 0L }

        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Aún no hay sesiones. Crea una antes de salir a muestrear: es lo que " +
                        "permite saber después quién tomó cada dato y de qué teléfono salió.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    val count = counts[session.id] ?: 0
                    SessionCard(
                        session = session,
                        sampleCount = count,
                        isActive = state.activeId == session.id,
                        dateText = session.startedAt?.let { dateFmt.format(Date(it)) },
                        onActivate = { sessionManager.setActive(session.id) },
                        onEdit = { editing = session },
                        onClose = { sessionManager.close(session.id) },
                        onReopen = { sessionManager.reopen(session.id) },
                        onDelete = { sessionManager.delete(session.id) }
                    )
                }
            }
        }
    }

    if (creating) {
        SessionDialog(
            session = null,
            farmOptions = farmOptions,
            onDismiss = { creating = false },
            onSave = { name, operator, farm, notes ->
                sessionManager.create(name, operator, farm, notes)
                creating = false
            }
        )
    }

    editing?.let { session ->
        SessionDialog(
            session = session,
            farmOptions = farmOptions,
            onDismiss = { editing = null },
            onSave = { name, operator, farm, notes ->
                sessionManager.update(
                    session.copy(
                        name = name.trim().ifBlank { null },
                        operator = operator.trim().ifBlank { null },
                        farm = farm.trim().ifBlank { null },
                        notes = notes.trim().ifBlank { null }
                    )
                )
                editing = null
            }
        )
    }
}

@Composable
private fun SessionCard(
    session: SamplingSession,
    sampleCount: Int,
    isActive: Boolean,
    dateText: String?,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onClose: () -> Unit,
    onReopen: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.nameOrDefault,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                if (isActive) {
                    AssistChip(onClick = {}, label = { Text("Activa", fontSize = 11.sp) })
                } else if (!session.isOpen) {
                    AssistChip(onClick = {}, label = { Text("Cerrada", fontSize = 11.sp) })
                }
            }

            Text(session.operatorOrDefault, fontSize = 13.sp)
            session.farm?.let { Text(it, fontSize = 12.sp) }
            dateText?.let { Text("Iniciada $it", fontSize = 11.sp) }
            Text(
                "$sampleCount muestreo(s)" + (session.device?.let { " · $it" } ?: ""),
                fontSize = 11.sp
            )
            session.notes?.let {
                Text(it, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }

            if (session.needsOperator) {
                Text(
                    "Sin operario: complétalo para poder decir quién tomó estos datos.",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!isActive && session.isOpen) {
                    TextButton(onClick = onActivate) { Text("Activar", fontSize = 12.sp) }
                }
                TextButton(onClick = onEdit) { Text("Editar", fontSize = 12.sp) }
                if (session.isOpen) {
                    TextButton(onClick = onClose) { Text("Cerrar", fontSize = 12.sp) }
                } else {
                    TextButton(onClick = onReopen) { Text("Reabrir", fontSize = 12.sp) }
                }
                // Borrar solo si está vacía: una sesión con muestreos dejaría filas
                // apuntando a un nombre que ya no existe, justo en el .xlsx.
                if (sampleCount == 0) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(
                            "Borrar",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Borrar sesión") },
            text = { Text("\"${session.nameOrDefault}\" no tiene muestreos. ¿La elimino?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Borrar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun SessionDialog(
    session: SamplingSession?,
    farmOptions: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(session?.name ?: SessionManager.defaultName()) }
    var operator by remember { mutableStateOf(session?.operator ?: "") }
    var farm by remember { mutableStateOf(session?.farm ?: farmOptions.firstOrNull() ?: "") }
    var notes by remember { mutableStateOf(session?.notes ?: "") }
    var farmExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (session == null) "Nueva sesión" else "Editar sesión") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = operator,
                    onValueChange = { operator = it },
                    label = { Text("Operario") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedTextField(
                        value = farm,
                        onValueChange = { farm = it },
                        label = { Text("Finca (opcional)") },
                        singleLine = true,
                        trailingIcon = {
                            if (farmOptions.isNotEmpty()) {
                                TextButton(onClick = { farmExpanded = true }) {
                                    Text("Elegir", fontSize = 12.sp)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = farmExpanded,
                        onDismissRequest = { farmExpanded = false }
                    ) {
                        farmOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    farm = option
                                    farmExpanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Observaciones (opcional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (operator.isBlank()) {
                    Text(
                        "Sin operario los datos quedan sin responsable, que es el dato que " +
                            "hace falta cuando dos personas muestrean el mismo día.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, operator, farm, notes) }) {
                Text(if (session == null) "Crear y activar" else "Guardar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
