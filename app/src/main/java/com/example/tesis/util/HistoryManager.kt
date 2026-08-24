package com.example.tesis.util

import android.content.Context
import com.example.tesis.BoxedDeteccion
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Calendar

/**
 * Un muestreo: una foto de UNA cara de UNA trampa, en un momento dado.
 *
 * Casi todos los campos nuevos son nullable a propósito. Gson deserializa el
 * historial guardado por versiones anteriores de la app instanciando la clase
 * sin pasar por el constructor de Kotlin, así que los valores por defecto NO se
 * aplican y un campo ausente queda en null aunque el tipo sea no-nulo. Declararlos
 * nullable y exponer accesores con respaldo ([farmOrDefault], etc.) evita que un
 * registro viejo tumbe la app.
 */
data class DetectionEntity(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long,
    val count: Int,

    // Ubicación geográfica
    val latitude: Double? = null,
    val longitude: Double? = null,
    val gpsAccuracyM: Float? = null,

    // Identificación del muestreo
    val crop: String? = null,
    val lot: String? = null,
    val farm: String? = null,
    val greenhouse: String? = null,
    val trapId: String? = null,
    /**
     * Identificador de la ficha de trampa del registro.
     *
     * El código de trampa es texto y puede diferir entre lo que se escribió al
     * guardar y lo que hay en configuración; ese desajuste dejaba el plano del
     * invernadero vacío. Este vínculo no depende de cómo se haya escrito.
     */
    val trapRecordId: String? = null,
    val face: String? = null,

    // Geometría del muestreo
    val framingMode: String? = null,
    val trapWidthCm: Float? = null,
    val trapHeightCm: Float? = null,
    val windowWidthCm: Float? = null,
    val windowHeightCm: Float? = null,
    val installTimestamp: Long? = null,
    val replacementCycleDays: Int? = null,

    /** "MANUAL" o "GPS": cómo se eligió la trampa de este muestreo. */
    val trapSelectionMode: String? = null,

    // Sesión de muestreo
    /** Vínculo con la sesión; es un UUID, así que no choca entre teléfonos. */
    val sessionId: String? = null,
    /**
     * Quién hizo la lectura, copiado en el momento de guardar.
     *
     * Va aquí y no solo en la sesión porque es un hecho del muestreo: renombrar
     * o reasignar la sesión después no debería reescribir quién estuvo ahí.
     */
    val operator: String? = null,

    // Validación contra experto
    val manualCount: Int? = null,

    /**
     * De las detecciones de la app, cuántas eran realmente una mosca.
     *
     * Es lo que separa dos errores que el conteo agregado confunde: sin este
     * dato, un muestreo que se saltó nueve moscas y marcó nueve manchas de polvo
     * cuadra en el total y se reporta como acierto perfecto.
     */
    val truePositives: Int? = null,

    // Evidencia e inferencia
    val imagePath: String? = null,
    val imageSource: String? = null,
    val detections: List<BoxedDeteccion>? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val modelVersion: String? = null,
    val confThreshold: Float? = null,
    val iouThreshold: Float? = null,
    /**
     * "COMPLETA" o "MOSAICO_3x3". Sin esto no se pueden comparar los muestreos
     * de la prueba con los de referencia, que es justo para lo que se hizo.
     */
    val detectionMode: String? = null,

    val notes: String? = null
) {
    val farmOrDefault: String get() = farm.orFallback("Finca 1")
    val greenhouseOrDefault: String get() = greenhouse.orFallback("Invernadero 1")
    val trapIdOrDefault: String get() = trapId.orFallback("T-01")
    val cropOrDefault: String get() = crop.orFallback("Rosa bajo invernadero")
    val lotOrDefault: String get() = lot.orFallback("Lote 1")

    val faceEnum: TrapFace get() = TrapFace.from(face)
    val framingModeEnum: FramingMode get() = FramingMode.from(framingMode)

    /** Etiqueta legible de la trampa, útil como clave en gráficas y listados. */
    val trapLabel: String get() = "$greenhouseOrDefault / $trapIdOrDefault"

    /** Milisegundos del inicio del día local del muestreo, para agrupar por fecha. */
    fun dayStartMillis(): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }
}

private fun String?.orFallback(fallback: String): String =
    if (this.isNullOrBlank() || this == "null") fallback else this

/**
 * Historial persistido en JSON.
 *
 * Es un singleton por proceso: antes cada pantalla creaba su propia instancia y
 * por tanto su propio StateFlow sobre el mismo archivo, así que guardar un
 * muestreo en Detección no refrescaba Historial ni Reportes.
 */
class HistoryManager private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, "history.json")
    private val gson = Gson()
    private val _detections = MutableStateFlow(load())
    val detections: StateFlow<List<DetectionEntity>> = _detections

    private fun load(): List<DetectionEntity> = try {
        if (file.exists()) {
            val type = object : TypeToken<List<DetectionEntity>>() {}.type
            gson.fromJson<List<DetectionEntity>>(file.readText(), type) ?: emptyList()
        } else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun persist(list: List<DetectionEntity>) {
        try {
            file.writeText(gson.toJson(list))
        } catch (_: Exception) {
            // Si falla la escritura mantenemos el estado en memoria para no perder
            // el muestreo en curso; se reintentará en la siguiente operación.
        }
        _detections.value = list
    }

    fun add(detection: DetectionEntity) {
        persist(listOf(detection) + _detections.value)
    }

    fun update(detection: DetectionEntity) {
        persist(_detections.value.map { if (it.id == detection.id) detection else it })
    }

    fun delete(detection: DetectionEntity) {
        detection.imagePath?.let { path ->
            runCatching {
                val imgFile = File(path)
                if (imgFile.exists()) imgFile.delete()
            }
        }
        persist(_detections.value.filterNot { it.id == detection.id })
    }

    fun getDetectionsSince(since: Long): List<DetectionEntity> =
        _detections.value.filter { it.timestamp >= since }.sortedBy { it.timestamp }

    companion object {
        @Volatile
        private var instance: HistoryManager? = null

        operator fun invoke(context: Context): HistoryManager =
            instance ?: synchronized(this) {
                instance ?: HistoryManager(context).also { instance = it }
            }
    }
}
