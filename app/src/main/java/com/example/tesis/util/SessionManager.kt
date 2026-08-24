package com.example.tesis.util

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Sesiones de muestreo.
 *
 * Una sesión es un lote de trabajo: quién lo hizo, en qué finca y cuándo. Cada
 * foto que se guarda queda adscrita a la sesión activa.
 *
 * Para qué sirve, más allá de ordenar: la app no tiene nube, así que dos
 * celulares trabajando el mismo día producen dos archivos separados. Sin una
 * etiqueta que diga de dónde salió cada fila, al juntarlos en Excel no hay forma
 * de saber qué midió cada quién ni de rehacer un lote si algo salió mal. El [id]
 * es un UUID, así que dos sesiones llamadas igual en dos celulares distintos no
 * se confunden al unir los datos.
 *
 * El operario queda además copiado en cada muestreo: es un hecho de la lectura,
 * y renombrar la sesión después no debería reescribir la historia.
 */
data class SamplingSession(
    val id: String = UUID.randomUUID().toString(),
    val name: String? = null,
    val operator: String? = null,
    val farm: String? = null,
    /** Modelo del teléfono, para rastrear de qué equipo salió el lote. */
    val device: String? = null,
    val startedAt: Long? = null,
    val closedAt: Long? = null,
    val notes: String? = null
) {
    val nameOrDefault: String get() = name?.takeIf { it.isNotBlank() } ?: "Sesión sin nombre"
    val operatorOrDefault: String get() = operator?.takeIf { it.isNotBlank() } ?: "Sin operario"
    val isOpen: Boolean get() = closedAt == null
    val needsOperator: Boolean get() = operator.isNullOrBlank()
    val label: String get() = "$nameOrDefault · $operatorOrDefault"
}

data class SessionState(
    val sessions: List<SamplingSession>? = null,
    val activeId: String? = null
) {
    val sessionList: List<SamplingSession> get() = sessions.orEmpty()
    val active: SamplingSession? get() = sessionList.firstOrNull { it.id == activeId }
    val openSessions: List<SamplingSession> get() = sessionList.filter { it.isOpen }
}

class SessionManager private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, "sessions.json")
    private val gson = Gson()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SessionState> = _state

    private fun load(): SessionState = try {
        if (file.exists()) {
            val type = object : TypeToken<SessionState>() {}.type
            gson.fromJson<SessionState>(file.readText(), type) ?: SessionState()
        } else SessionState()
    } catch (_: Exception) {
        SessionState()
    }

    private fun persist(next: SessionState) {
        try {
            file.writeText(gson.toJson(next))
        } catch (_: Exception) {
            // Mejor esfuerzo: el estado sigue vivo en memoria.
        }
        _state.value = next
    }

    /**
     * Crea una sesión y la deja activa.
     *
     * @return la sesión creada, para poder mostrarla enseguida.
     */
    fun create(
        name: String,
        operator: String,
        farm: String? = null,
        notes: String? = null,
        now: Long = System.currentTimeMillis()
    ): SamplingSession {
        val session = SamplingSession(
            name = name.trim().ifBlank { defaultName(now) },
            operator = operator.trim().ifBlank { null },
            farm = farm?.trim()?.ifBlank { null },
            device = deviceLabel(),
            startedAt = now,
            notes = notes?.trim()?.ifBlank { null }
        )
        val current = _state.value
        persist(
            current.copy(
                sessions = current.sessionList + session,
                activeId = session.id
            )
        )
        return session
    }

    /**
     * Garantiza que haya una sesión activa.
     *
     * Se llama antes de guardar un muestreo. Crear una por defecto es preferible
     * a bloquear el guardado en pleno invernadero: el dato queda etiquetado, y el
     * operario se puede completar después desde la pantalla de sesiones.
     */
    fun ensureActive(now: Long = System.currentTimeMillis()): SamplingSession {
        _state.value.active?.takeIf { it.isOpen }?.let { return it }
        // Si la activa quedó cerrada, se retoma la última abierta antes de crear.
        _state.value.openSessions.maxByOrNull { it.startedAt ?: 0L }?.let { open ->
            persist(_state.value.copy(activeId = open.id))
            return open
        }
        return create(name = defaultName(now), operator = "", now = now)
    }

    fun setActive(id: String) {
        if (_state.value.sessionList.none { it.id == id }) return
        persist(_state.value.copy(activeId = id))
    }

    fun update(session: SamplingSession) {
        val current = _state.value
        persist(
            current.copy(
                sessions = current.sessionList.map { if (it.id == session.id) session else it }
            )
        )
    }

    fun close(id: String, now: Long = System.currentTimeMillis()) {
        val current = _state.value
        val updated = current.sessionList.map {
            if (it.id == id && it.closedAt == null) it.copy(closedAt = now) else it
        }
        // Cerrar la activa la deja sin activa: la siguiente foto forzaría a elegir
        // o a crear una nueva, que es justo lo que se quiere al cerrar un lote.
        persist(
            current.copy(
                sessions = updated,
                activeId = if (current.activeId == id) null else current.activeId
            )
        )
    }

    fun reopen(id: String) {
        val current = _state.value
        persist(
            current.copy(
                sessions = current.sessionList.map {
                    if (it.id == id) it.copy(closedAt = null) else it
                },
                activeId = id
            )
        )
    }

    /**
     * Borra una sesión.
     *
     * La pantalla solo lo ofrece cuando la sesión no tiene muestreos: borrar una
     * con datos dejaría filas apuntando a una sesión que ya no existe, y en el
     * .xlsx aparecerían sin nombre ni operario justo cuando más falta hacen.
     */
    fun delete(id: String) {
        val current = _state.value
        persist(
            current.copy(
                sessions = current.sessionList.filterNot { it.id == id },
                activeId = if (current.activeId == id) null else current.activeId
            )
        )
    }

    fun find(id: String?): SamplingSession? =
        id?.let { key -> _state.value.sessionList.firstOrNull { it.id == key } }

    companion object {
        @Volatile
        private var instance: SessionManager? = null

        operator fun invoke(context: Context): SessionManager =
            instance ?: synchronized(this) {
                instance ?: SessionManager(context).also { instance = it }
            }

        fun defaultName(now: Long = System.currentTimeMillis()): String =
            "Muestreo " + SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(now))

        fun deviceLabel(): String =
            listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { !it.isNullOrBlank() }
                .joinToString(" ")
                .ifBlank { "Desconocido" }
    }
}
