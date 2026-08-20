package com.example.tesis.util

import android.content.Context
import com.example.tesis.BoxedDeteccion
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class DetectionEntity(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long,
    val count: Int,
    val latitude: Double?,
    val longitude: Double?,
    val crop: String = "Genérico",
    val lot: String = "Lote 1",
    val imagePath: String? = null,
    val detections: List<BoxedDeteccion>? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0
)

class HistoryManager(private val context: Context) {
    private val file = File(context.filesDir, "history.json")
    private val gson = Gson()
    private val _detections = MutableStateFlow<List<DetectionEntity>>(load())
    val detections: StateFlow<List<DetectionEntity>> = _detections

    private fun load(): List<DetectionEntity> {
        return try {
            if (file.exists()) {
                val json = file.readText()
                val type = object : TypeToken<List<DetectionEntity>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<DetectionEntity>) {
        try {
            file.writeText(gson.toJson(list))
            _detections.value = list
        } catch (e: Exception) {
            // Error al guardar
        }
    }

    fun add(detection: DetectionEntity) {
        val current = load().toMutableList()
        current.add(0, detection)
        save(current)
    }

    fun delete(detection: DetectionEntity) {
        val current = load().toMutableList()
        current.removeAll { item ->
            if (item.id == detection.id) {
                item.imagePath?.let { path ->
                    val imgFile = File(path)
                    if (imgFile.exists()) imgFile.delete()
                }
                true
            } else false
        }
        save(current)
    }
    
    fun getDetectionsSince(since: Long): List<DetectionEntity> {
        return load().filter { it.timestamp >= since }.sortedBy { it.timestamp }
    }
}
