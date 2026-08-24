package com.example.tesis.util

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Traslado de la configuración entre teléfonos.
 *
 * Lo que viaja: el mapa de la finca, el registro de trampas y los ajustes
 * (umbrales del cultivo, umbrales del detector, mosaico).
 *
 * Lo que NO viaja, a propósito: los muestreos, las fotos y las sesiones. El
 * segundo teléfono arranca a tomar fotos desde cero, que es justamente lo
 * pedido. Mezclar historiales al importar sería además la manera más fácil de
 * duplicar registros sin darse cuenta.
 *
 * Por qué vale la pena frente a configurar a mano: si en un teléfono la trampa
 * se llama `T-01` y en el otro `T-1`, los reportes salen partidos en dos y el
 * error solo se descubre al final, cuando ya no se puede volver al invernadero.
 * Con el mismo registro en ambos, los códigos coinciden por construcción.
 */
data class ConfigBundle(
    val format: String? = null,
    val version: Int? = null,
    val exportedAt: Long? = null,
    val device: String? = null,
    val settings: AppSettings? = null,
    val traps: TrapRegistryState? = null,
    val farmMap: ImportedMap? = null
)

private const val CONFIG_FORMAT = "biocount-config"
private const val CONFIG_VERSION = 1

data class ConfigImportResult(
    val ok: Boolean,
    val message: String,
    val trapCount: Int = 0,
    val areaCount: Int = 0
)

/** Escribe la configuración actual en Descargas. */
fun exportConfig(context: Context): SavedFile? {
    val bundle = ConfigBundle(
        format = CONFIG_FORMAT,
        version = CONFIG_VERSION,
        exportedAt = System.currentTimeMillis(),
        device = SessionManager.deviceLabel(),
        settings = SettingsManager(context).settings.value,
        traps = TrapRegistry(context).state.value,
        farmMap = FarmMapStore(context).map.value
    )
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val json = Gson().toJson(bundle)
    return saveToDownloads(
        context = context,
        fileName = "biocount_config_$stamp.json",
        mimeType = "application/json"
    ) { out ->
        out.write(json.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Lee un archivo de configuración y lo aplica.
 *
 * Reemplaza por completo trampas, mapa y ajustes. No es una fusión: fusionar dos
 * registros de trampas sin una regla clara de qué gana produce duplicados
 * silenciosos, y eso es peor que sobrescribir a sabiendas. El historial de
 * muestreos queda intacto en cualquier caso.
 */
fun importConfig(context: Context, uri: Uri): ConfigImportResult {
    val json = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    } ?: return ConfigImportResult(false, "No se pudo leer el archivo")

    val bundle = try {
        val type = object : TypeToken<ConfigBundle>() {}.type
        Gson().fromJson<ConfigBundle>(json, type)
    } catch (_: Exception) {
        null
    } ?: return ConfigImportResult(false, "El archivo no tiene el formato esperado")

    if (bundle.format != null && bundle.format != CONFIG_FORMAT) {
        return ConfigImportResult(false, "Ese archivo no es una configuración de BioCount")
    }

    // Un archivo sin nada útil casi siempre significa que se eligió el archivo
    // equivocado; sobrescribir con vacío sería destruir la configuración buena.
    val traps = bundle.traps
    val map = bundle.farmMap
    val settings = bundle.settings
    if (traps == null && map == null && settings == null) {
        return ConfigImportResult(false, "El archivo no trae configuración")
    }

    return try {
        traps?.let { TrapRegistry(context).replaceState(it) }
        map?.let { FarmMapStore(context).save(it) }
        settings?.let { SettingsManager(context).save(it) }

        val trapCount = traps?.trapList?.size ?: 0
        val areaCount = map?.areaList?.size ?: 0
        ConfigImportResult(
            ok = true,
            message = "Configuración importada: $trapCount trampa(s) y $areaCount invernadero(s). " +
                "El historial de muestreos no se tocó.",
            trapCount = trapCount,
            areaCount = areaCount
        )
    } catch (e: Exception) {
        ConfigImportResult(false, "Falló al aplicar: ${e.message ?: "error desconocido"}")
    }
}
