package com.example.tesis.util

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class CropSettings(
    val name: String,
    val lowThreshold: Int,
    val mediumThreshold: Int
)

data class AppSettings(
    val crops: List<CropSettings> = listOf(
        CropSettings("Rosa bajo invernadero", 10, 25),
        CropSettings("Tomate", 5, 15),
        CropSettings("Pimiento", 3, 10),
        CropSettings("Genérico", 5, 15)
    ),
    val selectedCropName: String = "Rosa bajo invernadero",
    val selectedLotName: String = "Lote 1",
    val autoShotEnabled: Boolean = true,

    // Contexto de muestreo, se recuerda entre capturas
    val selectedFarmName: String = "Finca 1",
    val selectedGreenhouseName: String = "Invernadero 1",
    val selectedTrapId: String = "T-01",

    /**
     * Umbrales de inferencia. Nulos a propósito: así un archivo guardado por una
     * versión anterior toma el valor de fábrica sin necesidad de migrarlo.
     */
    val confThreshold: Float? = null,
    val iouNms: Float? = null,

    /** Detección por mosaico. Apagada por omisión: se enciende para comparar. */
    val tiledDetection: Boolean? = true,
    val tileGrid: Int? = 2
)

val AppSettings.confThresholdOrDefault: Float
    get() = ModelInfo.CONF_THRESHOLD

val AppSettings.iouNmsOrDefault: Float
    get() = ModelInfo.IOU_NMS

val AppSettings.tiledDetectionOrDefault: Boolean
    get() = tiledDetection ?: true

val AppSettings.tileGridOrDefault: Int
    get() = (tileGrid ?: 2)
        .coerceIn(ModelInfo.TILE_GRID_MIN, ModelInfo.TILE_GRID_MAX)

/** Tope del lado largo al capturar, según si hay mosaico. */
val AppSettings.captureMaxDim: Float
    get() = if (tiledDetectionOrDefault) ModelInfo.CAPTURE_MAX_DIM_TILED else ModelInfo.CAPTURE_MAX_DIM

val AppSettings.galleryMaxDim: Float
    get() = if (tiledDetectionOrDefault) ModelInfo.GALLERY_MAX_DIM_TILED else ModelInfo.GALLERY_MAX_DIM

/** Si los umbrales siguen en los valores de fábrica. */
val AppSettings.usesDefaultThresholds: Boolean
    get() = confThresholdOrDefault == ModelInfo.CONF_THRESHOLD &&
        iouNmsOrDefault == ModelInfo.IOU_NMS

// La geometría de la trampa y su fecha de instalación NO viven aquí: son
// propiedades de cada trampa física y de cada finca, así que están en
// [TrapRegistry]. Tenerlas como un valor global suponía una sola trampa.

class SettingsManager(context: Context) {

    private val file = File(context.applicationContext.filesDir, "settings.json")
    private val gson = Gson()
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings

    init {
        // La normalización se persiste AQUÍ y no dentro de load(): load() corre en
        // el inicializador de _settings, cuando el campo aún no está asignado, así
        // que llamar a save() desde ahí lanzaba una NPE que quedaba atrapada por el
        // catch y hacía que la app arrancara con los ajustes por defecto.
        val current = _settings.value
        if (current != readRaw()) persistOnly(current)
    }

    /** Lo que hay en disco, sin normalizar. */
    private fun readRaw(): AppSettings? = try {
        if (file.exists()) gson.fromJson(file.readText(), AppSettings::class.java) else null
    } catch (_: Exception) {
        null
    }

    private fun persistOnly(settings: AppSettings) {
        try {
            file.writeText(gson.toJson(settings))
        } catch (_: Exception) {
            // Mejor esfuerzo: si no se puede escribir, el valor sigue en memoria.
        }
    }

    // Los elvis de abajo parecen redundantes porque los tipos son no-nulos, pero
    // Gson puede dejarlos en null igualmente. La supresión es intencional.
    @Suppress("USELESS_ELVIS", "SENSELESS_COMPARISON")
    private fun load(): AppSettings = try {
        val loaded = if (file.exists()) {
            gson.fromJson(file.readText(), AppSettings::class.java) ?: AppSettings()
        } else AppSettings()

        // Gson puede dejar campos ausentes en null aunque el tipo sea no-nulo,
        // porque instancia la clase sin pasar por el constructor de Kotlin.
        // Esta normalización repara los settings guardados por versiones previas.
        val defaults = AppSettings()
        var normalized = loaded.copy(
            crops = loaded.crops ?: defaults.crops,
            selectedCropName = loaded.selectedCropName ?: defaults.selectedCropName,
            selectedLotName = loaded.selectedLotName ?: defaults.selectedLotName,
            selectedFarmName = loaded.selectedFarmName ?: defaults.selectedFarmName,
            selectedGreenhouseName = loaded.selectedGreenhouseName ?: defaults.selectedGreenhouseName,
            selectedTrapId = loaded.selectedTrapId ?: defaults.selectedTrapId
        )

        if (normalized.crops.none { it.name == "Rosa bajo invernadero" }) {
            normalized = normalized.copy(
                crops = listOf(CropSettings("Rosa bajo invernadero", 10, 25)) + normalized.crops,
                selectedCropName = "Rosa bajo invernadero"
            )
        }

        normalized
    } catch (_: Exception) {
        AppSettings()
    }

    fun save(newSettings: AppSettings) {
        persistOnly(newSettings)
        _settings.value = newSettings
    }

    fun updateSelectedCrop(name: String) = save(_settings.value.copy(selectedCropName = name))

    fun updateSelectedLot(name: String) = save(_settings.value.copy(selectedLotName = name))

    fun updateSamplingContext(farm: String, greenhouse: String, trapId: String) =
        save(
            _settings.value.copy(
                selectedFarmName = farm,
                selectedGreenhouseName = greenhouse,
                selectedTrapId = trapId
            )
        )

    /** Umbrales de inferencia. Se acotan al guardar, no solo al leer. */
    fun updateDetectionThresholds(conf: Float, iou: Float) = save(
        _settings.value.copy(
            confThreshold = conf.coerceIn(ModelInfo.CONF_MIN, ModelInfo.CONF_MAX),
            iouNms = iou.coerceIn(ModelInfo.IOU_MIN, ModelInfo.IOU_MAX)
        )
    )

    /** Vuelve a los valores con los que se validó el modelo originalmente. */
    fun resetDetectionThresholds() = save(
        _settings.value.copy(confThreshold = null, iouNms = null)
    )

    fun updateTiling(enabled: Boolean, grid: Int) = save(
        _settings.value.copy(
            tiledDetection = enabled,
            tileGrid = grid.coerceIn(ModelInfo.TILE_GRID_MIN, ModelInfo.TILE_GRID_MAX)
        )
    )

    fun getThresholdsFor(cropName: String): Pair<Int, Int> {
        val crops = _settings.value.crops
        val crop = crops.find { it.name == cropName } ?: crops.firstOrNull()
        return (crop?.lowThreshold ?: 5) to (crop?.mediumThreshold ?: 15)
    }
}
