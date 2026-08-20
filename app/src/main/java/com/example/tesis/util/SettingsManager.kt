package com.example.tesis.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    val autoShotEnabled: Boolean = true
)

class SettingsManager(context: Context) {
    private val file = File(context.filesDir, "settings.json")
    private val gson = Gson()
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings

    private fun load(): AppSettings {
        return try {
            val settings = if (file.exists()) {
                val json = file.readText()
                gson.fromJson(json, AppSettings::class.java) ?: AppSettings()
            } else AppSettings()

            // Forzar inclusión de Rosa si no existe en la lista cargada
            val hasRosa = settings.crops.any { it.name == "Rosa bajo invernadero" }
            if (!hasRosa) {
                val updatedCrops = settings.crops.toMutableList().apply {
                    add(0, CropSettings("Rosa bajo invernadero", 10, 25))
                }
                val migrated = settings.copy(
                    crops = updatedCrops,
                    selectedCropName = "Rosa bajo invernadero"
                )
                save(migrated)
                migrated
            } else {
                settings
            }
        } catch (e: Exception) {
            AppSettings()
        }
    }

    fun save(newSettings: AppSettings) {
        try {
            file.writeText(gson.toJson(newSettings))
            _settings.value = newSettings
        } catch (e: Exception) {
            // Error al guardar
        }
    }

    fun updateSelectedCrop(name: String) {
        save(_settings.value.copy(selectedCropName = name))
    }

    fun updateSelectedLot(name: String) {
        save(_settings.value.copy(selectedLotName = name))
    }
    
    fun getThresholdsFor(cropName: String): Pair<Int, Int> {
        val crop = _settings.value.crops.find { it.name == cropName } ?: _settings.value.crops.first()
        return crop.lowThreshold to crop.mediumThreshold
    }
}
