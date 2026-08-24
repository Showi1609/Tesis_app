package com.example.tesis.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

/**
 * Registro de trampas.
 *
 * Antes la fecha de instalación era un único valor global, lo cual solo tiene
 * sentido si existe una sola trampa. En una malla real cada trampa se instala y
 * se repone en su propio ciclo, así que la fecha vive en la ficha de la trampa.
 *
 * La geometría vive en el perfil de la finca: lo normal es que una finca use un
 * solo tipo de trampa, pero otra finca puede usar otro formato. Una trampa
 * concreta puede sobrescribir las medidas si hace falta.
 */

const val DEFAULT_REPLACEMENT_DAYS = 7

/** Ficha creada al importar un punto del KML, no dada de alta a mano. */
const val SOURCE_MAP = "MAPA"
private const val MILLIS_PER_DAY = 86_400_000.0

/** Geometría y ciclo de reposición de una finca. */
data class FarmProfile(
    val name: String,
    val trapWidthCm: Float? = null,
    val trapHeightCm: Float? = null,
    val windowWidthCm: Float? = null,
    val windowHeightCm: Float? = null,
    val defaultFramingMode: String? = null,
    val replacementDays: Int? = null
) {
    val widthCm: Float get() = trapWidthCm?.takeIf { it > 0f } ?: 10f
    val heightCm: Float get() = trapHeightCm?.takeIf { it > 0f } ?: 25f
    val windowWidth: Float get() = windowWidthCm?.takeIf { it > 0f } ?: 10f
    val windowHeight: Float get() = windowHeightCm?.takeIf { it > 0f } ?: 10f
    val framingMode: FramingMode get() = FramingMode.from(defaultFramingMode)
    val cycleDays: Int get() = replacementDays?.takeIf { it > 0 } ?: DEFAULT_REPLACEMENT_DAYS
}

/**
 * Una trampa física en la malla de monitoreo.
 *
 * [installedAt] es la fecha del adhesivo ACTUAL. Al reponer la trampa el reloj se
 * reinicia, pero los muestreos ya guardados conservan su propia copia de la fecha,
 * así que reponer hoy no altera lo medido antes.
 */
data class TrapRecord(
    val id: String = UUID.randomUUID().toString(),
    val farm: String,
    val greenhouse: String,
    val code: String,
    val installedAt: Long? = null,
    /** Sobrescritura opcional de la geometría de la finca. */
    val widthCm: Float? = null,
    val heightCm: Float? = null,

    /** Coordenada fija de la trampa, capturada una vez al instalarla. */
    val latitude: Double? = null,
    val longitude: Double? = null,

    /**
     * Posición dentro del invernadero: cama y surco, o fila y columna.
     *
     * Es independiente del GPS y es lo que permite dibujar el mapa de calor: a
     * escala de invernadero el GPS no distingue trampas vecinas, pero "cama 3,
     * surco 12" es exacto por construcción.
     */
    val row: Int? = null,
    val column: Int? = null,

    val active: Boolean? = null,
    val notes: String? = null,
    /** "MAPA" si la ficha se creó importando un punto del KML; a mano queda en null. */
    val source: String? = null
) {
    val isActive: Boolean get() = active != false
    val fromMapImport: Boolean get() = source == SOURCE_MAP
    val label: String get() = "$greenhouse / $code"

    val point: GeoPoint?
        get() {
            val la = latitude ?: return null
            val lo = longitude ?: return null
            return GeoPoint(la, lo)
        }

    val positionLabel: String?
        get() = when {
            row != null && column != null -> "Cama $row · surco $column"
            row != null -> "Cama $row"
            column != null -> "Surco $column"
            else -> null
        }
}

/** Trampa candidata al asignar un muestreo por cercanía. */
data class TrapCandidate(val trap: TrapRecord, val distanceM: Double)

/**
 * Resultado de resolver la ubicación actual contra el registro.
 *
 * [confident] solo es cierto cuando la segunda candidata está más lejos que la
 * primera por un margen mayor que el error del GPS. Si no, la app muestra las
 * candidatas en vez de decidir: una asignación equivocada contamina dos series
 * temporales a la vez y no deja rastro.
 */
data class LocationMatch(
    val area: MapArea?,
    val candidates: List<TrapCandidate>,
    val accuracyM: Float?,
    val confident: Boolean
) {
    val best: TrapRecord? get() = candidates.firstOrNull()?.trap
}

data class TrapRegistryState(
    val farms: List<FarmProfile>? = null,
    val traps: List<TrapRecord>? = null
) {
    val farmList: List<FarmProfile> get() = farms.orEmpty()
    val trapList: List<TrapRecord> get() = traps.orEmpty()
}

class TrapRegistry private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, "traps.json")
    private val gson = Gson()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<TrapRegistryState> = _state

    private fun load(): TrapRegistryState = try {
        if (file.exists()) {
            val type = object : TypeToken<TrapRegistryState>() {}.type
            gson.fromJson<TrapRegistryState>(file.readText(), type) ?: TrapRegistryState()
        } else TrapRegistryState()
    } catch (_: Exception) {
        TrapRegistryState()
    }

    private fun persist(next: TrapRegistryState) {
        try {
            file.writeText(gson.toJson(next))
        } catch (_: Exception) {
            // Mejor esfuerzo: el estado sigue vivo en memoria.
        }
        _state.value = next
    }

    // ------------------------------------------------------------------
    // Consulta
    // ------------------------------------------------------------------

    /** Nombres de finca conocidos, sea por perfil explícito o por tener trampas. */
    fun farmNames(): List<String> =
        (_state.value.farmList.map { it.name } + _state.value.trapList.map { it.farm })
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

    fun greenhouseNames(farm: String): List<String> =
        _state.value.trapList.filter { it.farm == farm }
            .map { it.greenhouse }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

    fun trapsIn(farm: String, greenhouse: String): List<TrapRecord> =
        _state.value.trapList
            .filter { it.farm == farm && it.greenhouse == greenhouse && it.isActive }
            .sortedBy { it.code }

    fun findTrap(farm: String, greenhouse: String, code: String): TrapRecord? =
        _state.value.trapList.firstOrNull {
            it.farm == farm && it.greenhouse == greenhouse && it.code == code
        }

    fun trapById(id: String): TrapRecord? = _state.value.trapList.firstOrNull { it.id == id }

    /** Perfil de la finca, o uno por defecto si aún no se ha configurado. */
    fun farmProfile(farm: String): FarmProfile =
        _state.value.farmList.firstOrNull { it.name == farm } ?: FarmProfile(name = farm)

    /** Ancho y alto efectivos de una trampa: su sobrescritura, o la geometría de su finca. */
    fun effectiveSize(trap: TrapRecord): Pair<Float, Float> {
        val profile = farmProfile(trap.farm)
        val w = trap.widthCm?.takeIf { it > 0f } ?: profile.widthCm
        val h = trap.heightCm?.takeIf { it > 0f } ?: profile.heightCm
        return w to h
    }

    /** Trampas con coordenada conocida, ordenadas por cercanía al punto dado. */
    fun nearestTraps(
        point: GeoPoint,
        farm: String? = null,
        greenhouse: String? = null,
        limit: Int = 3
    ): List<TrapCandidate> =
        _state.value.trapList
            .asSequence()
            .filter { it.isActive }
            .filter { farm == null || it.farm == farm }
            .filter { greenhouse == null || it.greenhouse == greenhouse }
            .mapNotNull { trap -> trap.point?.let { TrapCandidate(trap, distanceMeters(point, it)) } }
            .sortedBy { it.distanceM }
            .take(limit)
            .toList()

    /**
     * Resuelve dónde está parado el operario.
     *
     * El invernadero sale del polígono que lo contiene, que es fiable porque un
     * invernadero mide decenas de metros y el error del GPS cabe holgadamente
     * dentro. La trampa sale de la cercanía, que es mucho más frágil, y por eso
     * solo se marca como fiable si el desempate supera el error del GPS.
     */
    fun resolveLocation(
        point: GeoPoint,
        accuracyM: Float?,
        farmMap: ImportedMap
    ): LocationMatch {
        val area = farmMap.areaAt(point)
        val greenhouse = area?.name
        val farm = area?.layer

        val candidates = nearestTraps(
            point = point,
            farm = farm?.takeIf { f -> _state.value.trapList.any { it.farm == f } },
            greenhouse = greenhouse?.takeIf { g -> _state.value.trapList.any { it.greenhouse == g } }
        )

        val margin = (accuracyM ?: 15f).toDouble()
        val confident = when {
            candidates.isEmpty() -> false
            candidates.size == 1 -> candidates[0].distanceM <= margin
            else -> (candidates[1].distanceM - candidates[0].distanceM) > margin
        }

        return LocationMatch(area, candidates, accuracyM, confident)
    }

    /**
     * Trampas contenidas en un recinto del mapa.
     *
     * La pertenencia se decide por geometría, no por que coincida el nombre del
     * invernadero: una trampa pertenece al invernadero cuyo polígono la contiene.
     * Así un error de tecleo no reparte las trampas de un invernadero entre dos.
     */
    fun trapsInArea(area: MapArea): List<TrapRecord> =
        _state.value.trapList
            .filter { it.isActive }
            .filter { trap -> trap.point?.let { area.contains(it) } == true }
            .sortedBy { it.code }

    /**
     * Elimina las fichas creadas al importar puntos del mapa.
     *
     * Las creadas antes de existir la marca de origen se reconocen porque
     * coinciden en código y caen prácticamente sobre un punto del KML (menos de
     * dos metros). Una trampa dada de alta a mano no cumple ambas condiciones.
     */
    fun removeMapImportedTraps(map: ImportedMap): Int {
        val places = map.placeList
        val before = _state.value.trapList
        val survivors = before.filterNot { trap -> isFromMap(trap, places) }
        val removed = before.size - survivors.size
        if (removed > 0) persist(_state.value.copy(traps = survivors))
        return removed
    }

    /** Cuántas fichas del registro provienen de la importación del mapa. */
    fun countMapImported(map: ImportedMap): Int =
        _state.value.trapList.count { isFromMap(it, map.placeList) }

    private fun isFromMap(trap: TrapRecord, places: List<MapPlace>): Boolean {
        if (trap.fromMapImport) return true
        val point = trap.point ?: return false
        return places.any { place ->
            place.name == trap.code &&
                distanceMeters(point, GeoPoint(place.lat, place.lon)) < 2.0
        }
    }

    fun exposureDays(trap: TrapRecord, now: Long = System.currentTimeMillis()): Double? {
        val installed = trap.installedAt ?: return null
        if (installed <= 0L || installed > now) return null
        return (now - installed) / MILLIS_PER_DAY
    }

    /**
     * Una trampa que supera su ciclo de reposición se satura: el adhesivo se llena
     * y deja de capturar de forma proporcional, así que subestima la población.
     */
    fun isOverdue(trap: TrapRecord, now: Long = System.currentTimeMillis()): Boolean {
        val days = exposureDays(trap, now) ?: return false
        return days > farmProfile(trap.farm).cycleDays
    }

    // ------------------------------------------------------------------
    // Mutación
    // ------------------------------------------------------------------

    fun upsertFarm(profile: FarmProfile) {
        val farms = _state.value.farmList.filterNot { it.name == profile.name } + profile
        persist(_state.value.copy(farms = farms.sortedBy { it.name }))
    }

    /**
     * Reemplaza el registro completo. Solo lo usa la importación de
     * configuración desde otro teléfono; el resto de la app edita por partes.
     */
    fun replaceState(next: TrapRegistryState) {
        persist(next)
    }

    fun upsertTrap(trap: TrapRecord) {
        val traps = _state.value.trapList.filterNot { it.id == trap.id } + trap
        persist(_state.value.copy(traps = traps))
    }

    /** Reponer el adhesivo: reinicia el reloj de exposición de esa trampa. */
    fun replaceTrap(id: String, at: Long = System.currentTimeMillis()) {
        trapById(id)?.let { upsertTrap(it.copy(installedAt = at)) }
    }

    fun deleteTrap(id: String) {
        persist(_state.value.copy(traps = _state.value.trapList.filterNot { it.id == id }))
    }

    /**
     * Crea o actualiza fichas de trampa a partir de los puntos del mapa importado.
     *
     * A cada punto se le asigna su invernadero por el polígono que lo contiene, lo
     * cual es exacto: no depende del GPS del teléfono sino de dónde se dibujó el
     * punto en My Maps. La capa del polígono se guarda como bloque, y el nombre
     * del mapa como finca.
     *
     * Las trampas ya existentes con el mismo código en la misma finca se
     * actualizan en vez de duplicarse, para no perder su fecha de instalación.
     *
     * @return cuántas se crearon y cuántas se actualizaron.
     */
    fun importTrapsFromMap(map: ImportedMap, farmName: String): Pair<Int, Int> {
        if (map.placeList.isEmpty()) return 0 to 0

        val existing = _state.value.trapList.toMutableList()
        var created = 0
        var updated = 0

        map.placeList.forEach { place ->
            val point = GeoPoint(place.lat, place.lon)
            val area = map.areaAt(point)
            val greenhouse = area?.name ?: place.layer ?: "Sin invernadero"

            val index = existing.indexOfFirst { it.farm == farmName && it.code == place.name }
            if (index >= 0) {
                existing[index] = existing[index].copy(
                    greenhouse = greenhouse,
                    latitude = place.lat,
                    longitude = place.lon
                )
                updated++
            } else {
                existing += TrapRecord(
                    farm = farmName,
                    greenhouse = greenhouse,
                    code = place.name,
                    latitude = place.lat,
                    longitude = place.lon,
                    source = SOURCE_MAP
                )
                created++
            }
        }

        persist(_state.value.copy(traps = existing))
        return created to updated
    }

    /**
     * Da de alta las trampas que ya aparecen en el historial pero aún no tienen
     * ficha, para no tener que redigitar la malla al estrenar esta pantalla.
     * No inventa fecha de instalación: queda vacía hasta que se indique.
     */
    fun seedFrom(detections: List<DetectionEntity>) {
        if (detections.isEmpty()) return
        val existing = _state.value.trapList
            .map { Triple(it.farm, it.greenhouse, it.code) }
            .toSet()

        val discovered = detections
            .map { Triple(it.farmOrDefault, it.greenhouseOrDefault, it.trapIdOrDefault) }
            .distinct()
            .filterNot { it in existing }

        if (discovered.isEmpty()) return

        val added = discovered.map { (farm, greenhouse, code) ->
            TrapRecord(farm = farm, greenhouse = greenhouse, code = code)
        }
        persist(_state.value.copy(traps = _state.value.trapList + added))
    }

    companion object {
        @Volatile
        private var instance: TrapRegistry? = null

        operator fun invoke(context: Context): TrapRegistry =
            instance ?: synchronized(this) {
                instance ?: TrapRegistry(context).also { instance = it }
            }
    }
}
