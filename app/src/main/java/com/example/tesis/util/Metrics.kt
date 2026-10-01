package com.example.tesis.util

import com.example.tesis.BoxedDeteccion
import kotlin.math.abs

/**
 * Metadatos del modelo y de la inferencia.
 *
 * Estos valores se guardan en cada registro y se exportan al CSV: sin ellos un
 * conteo no es reproducible, porque el mismo modelo con otro umbral de confianza
 * o de NMS produce un conteo distinto sobre la misma imagen.
 *
 * Deben coincidir con los usados en [com.example.tesis.WhiteflyDetector].
 */
object ModelInfo {
    const val VERSION = "whitefly_yolov8n_combinado-6_v2_int8-dinamica"

    /** Valores de fábrica. Ahora son ajustables; cada muestra guarda los suyos. */
    const val CONF_THRESHOLD = 0.25f
    const val IOU_NMS = 0.45f

    /** Márgenes razonables para los deslizadores de Ajustes. */
    const val CONF_MIN = 0.05f
    const val CONF_MAX = 0.90f
    const val IOU_MIN = 0.20f
    const val IOU_MAX = 0.95f

    /**
     * Mosaico. El traslape no es opcional: sin él, una mosca en la costura de
     * dos trozos se parte por la mitad y ninguna mitad se parece a una mosca.
     */
    const val TILE_GRID_MIN = 2
    const val TILE_GRID_MAX = 4
    const val TILE_GRID_DEFAULT = 3
    const val TILE_OVERLAP = 0.20f

    /**
     * Tope del lado largo al capturar.
     *
     * Sin mosaico da igual subirlo, porque el letterbox comprime al tamaño del
     * modelo de todos modos; con mosaico es justo al revés, porque no se puede
     * trocear un detalle que ya se descartó.
     */
    const val CAPTURE_MAX_DIM = 1280f
    const val CAPTURE_MAX_DIM_TILED = 2048f
    const val GALLERY_MAX_DIM = 1600f
    const val GALLERY_MAX_DIM_TILED = 2560f
}

/** Cara de la trampa cromática. Ambas caras son adhesivas y capturan de forma independiente. */
enum class TrapFace(val code: String, val label: String) {
    A("A", "Cara A"),
    B("B", "Cara B");

    companion object {
        fun from(code: String?): TrapFace = entries.firstOrNull { it.code == code } ?: A
    }
}

/**
 * Cómo se encuadró la foto respecto a la cara de la trampa.
 *
 * Determina el denominador de la densidad:
 *  - [CARA_COMPLETA]: el área muestreada es el área de la cara de la trampa.
 *  - [VENTANA]: el área muestreada es la de la ventana de muestreo, y la densidad
 *    se extrapola a la trampa multiplicando por (área de la cara / área de la ventana).
 */
enum class FramingMode(val code: String, val label: String) {
    CARA_COMPLETA("CARA_COMPLETA", "Cara completa"),
    VENTANA("VENTANA", "Ventana de muestreo");

    companion object {
        fun from(code: String?): FramingMode = entries.firstOrNull { it.code == code } ?: CARA_COMPLETA
    }
}

/** Estado del semáforo MIP. */
enum class MipStatus(val label: String) {
    BAJO("Bajo"),
    MEDIO("Medio"),
    ALTO("Alto")
}

private const val MILLIS_PER_DAY = 86_400_000.0

/**
 * Cálculos derivados de un muestreo.
 *
 * Todas las funciones devuelven `null` cuando falta el dato necesario, en vez de
 * asumir un valor. Es deliberado: un `null` en el CSV es honesto; un cero o un
 * promedio inventado no lo es.
 */
object Metrics {

    /** Área de la cara de la trampa, en cm². */
    fun trapFaceAreaCm2(e: DetectionEntity): Float? {
        val w = e.trapWidthCm ?: return null
        val h = e.trapHeightCm ?: return null
        if (w <= 0f || h <= 0f) return null
        return w * h
    }

    /** Área de la ventana de muestreo, en cm². */
    fun windowAreaCm2(e: DetectionEntity): Float? {
        val w = e.windowWidthCm ?: return null
        val h = e.windowHeightCm ?: return null
        if (w <= 0f || h <= 0f) return null
        return w * h
    }

    /**
     * Área efectivamente fotografiada, en cm². Es el denominador real de la densidad.
     *
     * Con encuadre de cara completa es el área de la cara; con ventana de muestreo
     * es el área de la ventana.
     */
    fun sampledAreaCm2(e: DetectionEntity): Float? = when (e.framingModeEnum) {
        FramingMode.CARA_COMPLETA -> trapFaceAreaCm2(e)
        FramingMode.VENTANA -> windowAreaCm2(e)
    }

    /** Densidad observada, en individuos por cm² del área fotografiada. */
    fun densityPerCm2(e: DetectionEntity): Double? {
        val area = sampledAreaCm2(e) ?: return null
        return e.count.toDouble() / area.toDouble()
    }

    /**
     * Densidad observada, en individuos por 100 cm².
     *
     * Es la unidad de reporte principal: ind/cm² produce números demasiado pequeños
     * para leerse de un vistazo (una cara de 10x25 cm con 30 moscas da 0,12 ind/cm²
     * frente a 12 ind/100 cm²).
     */
    fun densityPer100Cm2(e: DetectionEntity): Double? =
        densityPerCm2(e)?.let { it * 100.0 }

    /**
     * Capturas estimadas en la cara completa de la trampa.
     *
     * Con encuadre de cara completa es el conteo tal cual. Con ventana de muestreo
     * es una extrapolación por área, y por eso se marca como estimada.
     */
    fun estimatedFaceCount(e: DetectionEntity): Double? = when (e.framingModeEnum) {
        FramingMode.CARA_COMPLETA -> e.count.toDouble()
        FramingMode.VENTANA -> {
            val face = trapFaceAreaCm2(e)
            val window = windowAreaCm2(e)
            if (face == null || window == null || window <= 0f) null
            else e.count.toDouble() * (face.toDouble() / window.toDouble())
        }
    }

    /** Días que la trampa estuvo expuesta antes del muestreo. */
    fun exposureDays(e: DetectionEntity): Double? {
        val install = e.installTimestamp ?: return null
        if (install <= 0L || install > e.timestamp) return null
        val days = (e.timestamp - install) / MILLIS_PER_DAY
        return if (days <= 0.0) null else days
    }

    /**
     * Capturas por cara por día. Normalizar por tiempo de exposición es lo que
     * hace comparables dos trampas retiradas en fechas distintas, y es la métrica
     * habitual en la literatura de monitoreo con trampas cromáticas.
     */
    fun catchPerFacePerDay(e: DetectionEntity): Double? {
        val days = exposureDays(e) ?: return null
        val faceCount = estimatedFaceCount(e) ?: return null
        return faceCount / days
    }

    /**
     * ¿La trampa llevaba más tiempo expuesto del previsto por su ciclo de reposición?
     * Un adhesivo saturado deja de capturar de forma proporcional, así que un
     * muestreo marcado aquí subestima la población y conviene tratarlo aparte.
     */
    fun exceedsCycle(e: DetectionEntity): Boolean? {
        val days = exposureDays(e) ?: return null
        val cycle = e.replacementCycleDays?.takeIf { it > 0 } ?: return null
        return days > cycle
    }

    // ---------------------------------------------------------------------
    // Confianza del modelo
    //
    // Son las puntuaciones que dio el detector a cada caja. NO son precisión ni
    // acierto: miden lo seguro que estaba el modelo de lo que marcó, no si
    // acertó. Sirven para juzgar si un conteo es sólido o conviene revisarlo a
    // mano; el acierto real solo sale del conteo del experto.
    // ---------------------------------------------------------------------

    // Version sobre la lista cruda de detecciones: la usa tanto la pantalla de
    // captura (antes de guardar, sobre un DetectionResult que todavia no es una
    // entidad) como el historial (sobre un DetectionEntity ya guardado). Una
    // sola fuente de verdad para los umbrales, para que "certeza alta" signifique
    // lo mismo en las dos pantallas.
    fun meanScore(detections: List<BoxedDeteccion>?): Double? =
        detections?.takeIf { it.isNotEmpty() }?.map { it.score.toDouble() }?.average()

    fun meanScore(e: DetectionEntity): Double? = meanScore(e.detections)

    fun minScore(detections: List<BoxedDeteccion>?): Double? =
        detections?.takeIf { it.isNotEmpty() }?.minOf { it.score.toDouble() }

    fun minScore(e: DetectionEntity): Double? = minScore(e.detections)

    /** Detecciones apenas por encima del umbral, que son las más dudosas. */
    fun lowConfidenceCount(detections: List<BoxedDeteccion>?, below: Double = 0.35): Int? =
        detections?.count { it.score < below }

    fun lowConfidenceCount(e: DetectionEntity, below: Double = 0.35): Int? =
        lowConfidenceCount(e.detections, below)

    /** Etiqueta cualitativa de la confianza media, para leerla de un vistazo. */
    fun confidenceLabel(mean: Double?): String? {
        mean ?: return null
        return when {
            mean >= 0.60 -> "Alta"
            mean >= 0.40 -> "Media"
            else -> "Baja"
        }
    }

    fun confidenceLabel(e: DetectionEntity): String? = confidenceLabel(meanScore(e))

    /** Estado del semáforo MIP según los umbrales de conteo del cultivo. */
    fun mipStatus(count: Int, low: Int, medium: Int): MipStatus = when {
        count < low -> MipStatus.BAJO
        count < medium -> MipStatus.MEDIO
        else -> MipStatus.ALTO
    }

    // ---------------------------------------------------------------------
    // Validación contra conteo manual del experto
    //
    // Estas métricas SOLO existen si hay un conteo manual de referencia. La app
    // no tiene ground truth propio, así que sin conteo manual devuelven null.
    //
    // Ojo: esto NO es la precisión ni el recall del modelo. Precisión y recall
    // requieren emparejar cajas una a una contra anotaciones; esto compara
    // conteos agregados, que es lo que corresponde a un análisis de acuerdo
    // tipo Bland-Altman.
    // ---------------------------------------------------------------------

    /** Error absoluto con signo: positivo = la app sobreestimó. */
    fun absoluteError(e: DetectionEntity): Int? {
        val manual = e.manualCount ?: return null
        return e.count - manual
    }

    /**
     * Error relativo porcentual, con signo. El signo importa: indica la dirección
     * del sesgo, que es justamente lo que se grafica en Bland-Altman.
     */
    fun relativeErrorPct(e: DetectionEntity): Double? {
        val manual = e.manualCount ?: return null
        if (manual == 0) return null // indefinido, no cero
        return (e.count - manual).toDouble() / manual.toDouble() * 100.0
    }

    /** Acierto porcentual = 100 - |error relativo|, acotado a [0, 100]. */
    fun accuracyPct(e: DetectionEntity): Double? {
        val rel = relativeErrorPct(e) ?: return null
        return (100.0 - abs(rel)).coerceIn(0.0, 100.0)
    }

    // ---------------------------------------------------------------------
    // Precisión, sensibilidad y F1
    //
    // Comparar solo los conteos agregados esconde los errores que se cancelan:
    // un muestreo con 9 moscas perdidas y 9 falsos positivos da el mismo total
    // que el conteo real y se reportaría como 100 % de acierto, aunque el modelo
    // se haya equivocado dos veces. Por eso el experto anota también cuántas de
    // las detecciones eran correctas, y de ahí salen las tres métricas que sí
    // distinguen ambos errores.
    //
    // Nomenclatura: VP verdaderos positivos, FP falsos positivos (marcó algo que
    // no era), FN falsos negativos (se saltó una mosca real).
    // ---------------------------------------------------------------------

    /**
     * VP validados, acotados a lo que la aritmética permite.
     *
     * No pueden superar ni lo que la app marcó ni lo que el experto contó: si el
     * dato llega fuera de rango, por un error al teclear o por un registro viejo,
     * se recorta en vez de propagar una precisión mayor que 100 %.
     */
    fun truePositives(e: DetectionEntity): Int? {
        val vp = e.truePositives ?: return null
        val manual = e.manualCount ?: return null
        return vp.coerceIn(0, minOf(e.count, manual))
    }

    /** Detecciones que no correspondían a una mosca. */
    fun falsePositives(e: DetectionEntity): Int? =
        truePositives(e)?.let { e.count - it }

    /** Moscas reales que el modelo no vio. Son las que la confianza nunca ve. */
    fun falseNegatives(e: DetectionEntity): Int? {
        val vp = truePositives(e) ?: return null
        val manual = e.manualCount ?: return null
        return manual - vp
    }

    /** De lo que marcó, qué proporción era correcta. */
    fun precisionPct(e: DetectionEntity): Double? {
        val vp = truePositives(e) ?: return null
        if (e.count == 0) return null // sin detecciones la precisión no está definida
        return vp.toDouble() / e.count.toDouble() * 100.0
    }

    /** De lo que había, qué proporción vio. Es la métrica que delata el subconteo. */
    fun recallPct(e: DetectionEntity): Double? {
        val vp = truePositives(e) ?: return null
        val manual = e.manualCount ?: return null
        if (manual == 0) return null
        return vp.toDouble() / manual.toDouble() * 100.0
    }

    /** Media armónica de precisión y sensibilidad, en [0, 1]. */
    fun f1(e: DetectionEntity): Double? {
        val p = precisionPct(e)?.div(100.0) ?: return null
        val r = recallPct(e)?.div(100.0) ?: return null
        if (p + r <= 0.0) return 0.0
        return 2.0 * p * r / (p + r)
    }

    /**
     * Si el conteo manual quedó registrado sin decir cuántas eran correctas.
     *
     * Sirve para señalar en pantalla que a esa validación le falta la mitad útil.
     */
    fun needsTruePositives(e: DetectionEntity): Boolean =
        e.manualCount != null && e.truePositives == null
}

/**
 * Agregado de las dos caras de una misma trampa en una misma fecha.
 *
 * Se construye solo agrupando registros; una trampa con una sola cara registrada
 * queda marcada como incompleta ([isComplete] = false) para que no se mezcle con
 * trampas completas en los promedios sin que se note.
 */
data class TrapAggregate(
    val farm: String,
    val greenhouse: String,
    val trapId: String,
    val dayStart: Long,
    val faceA: DetectionEntity?,
    val faceB: DetectionEntity?
) {
    val isComplete: Boolean get() = faceA != null && faceB != null

    val records: List<DetectionEntity> get() = listOfNotNull(faceA, faceB)

    /** Capturas totales de la trampa (suma de caras registradas). */
    val totalCount: Int get() = records.sumOf { it.count }

    /** Densidad media entre las caras registradas, en ind/100 cm². */
    val meanDensityPer100Cm2: Double?
        get() {
            val values = records.mapNotNull { Metrics.densityPer100Cm2(it) }
            return if (values.isEmpty()) null else values.average()
        }

    /** Capturas por trampa por día (suma de caras / días de exposición). */
    val catchPerTrapPerDay: Double?
        get() {
            val values = records.mapNotNull { Metrics.catchPerFacePerDay(it) }
            return if (values.isEmpty()) null else values.sum()
        }

    /** Capturas por trampa por semana (informativo). */
    val catchPerTrapPerWeek: Double?
        get() = if (isComplete) catchPerTrapPerDay?.times(7.0) else null

    /** Estado MIP basado en la suma de ambas caras de la trampa. */
    fun mipStatusPerTrap(low: Int, medium: Int): MipStatus? {
        if (!isComplete) return null
        return Metrics.mipStatus(totalCount, low, medium)
    }
}

fun dayStartMillis(timestamp: Long): Long {
    val cal = java.util.Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

/**
 * Agrupa registros en trampas por (finca, invernadero, trampa, día).
 * El día se calcula sobre la zona horaria del dispositivo.
 */
fun List<DetectionEntity>.aggregateByTrap(): List<TrapAggregate> =
    groupBy {
        listOf(it.farmOrDefault, it.greenhouseOrDefault, it.trapIdOrDefault, it.dayStartMillis())
    }.map { (_, group) ->
        val first = group.first()
        TrapAggregate(
            farm = first.farmOrDefault,
            greenhouse = first.greenhouseOrDefault,
            trapId = first.trapIdOrDefault,
            dayStart = first.dayStartMillis(),
            faceA = group.lastOrNull { it.faceEnum == TrapFace.A },
            faceB = group.lastOrNull { it.faceEnum == TrapFace.B }
        )
    }.sortedBy { it.dayStart }
