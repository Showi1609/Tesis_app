package com.example.tesis.util

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mapa de la finca importado desde Google My Maps.
 *
 * Se lee el KML con el parser XML de Android en vez de añadir android-maps-utils:
 * una dependencia menos que pueda chocar con la versión de maps-compose del
 * proyecto, a cambio de soportar solo el subconjunto de KML que My Maps produce
 * (Placemark con Point o Polygon, agrupados en Folder por capa).
 */

data class GeoPoint(val lat: Double, val lon: Double)

/** Un recinto delimitado en el mapa: normalmente un invernadero. */
data class MapArea(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Capa de My Maps a la que pertenece; suele corresponder a la finca. */
    val layer: String? = null,
    val outline: List<GeoPoint>? = null
) {
    val points: List<GeoPoint> get() = outline.orEmpty()

    /**
     * Prueba de punto en polígono por lanzamiento de rayo.
     *
     * Se opera directamente sobre latitud y longitud: a la escala de un
     * invernadero la distorsión de tratar los grados como plano es despreciable.
     */
    fun contains(p: GeoPoint): Boolean {
        val poly = points
        if (poly.size < 3) return false
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a.lat > p.lat) != (b.lat > p.lat)) {
                val denom = (b.lat - a.lat)
                if (abs(denom) > 1e-12) {
                    val x = (b.lon - a.lon) * (p.lat - a.lat) / denom + a.lon
                    if (p.lon < x) inside = !inside
                }
            }
            j = i
        }
        return inside
    }

    fun centroid(): GeoPoint? {
        val poly = points
        if (poly.isEmpty()) return null
        return GeoPoint(poly.sumOf { it.lat } / poly.size, poly.sumOf { it.lon } / poly.size)
    }
}

/** Un marcador suelto del mapa. */
data class MapPlace(
    val name: String,
    val lat: Double,
    val lon: Double,
    val layer: String? = null
)

data class ImportedMap(
    val areas: List<MapArea>? = null,
    val places: List<MapPlace>? = null,
    val sourceName: String? = null,
    val importedAt: Long? = null,
    /** Nombre del mapa en My Maps; se usa como nombre de finca. */
    val documentName: String? = null,
    /** Enlaces de red hallados en el archivo, cuando el KML no trae geometría propia. */
    val networkLinks: List<String>? = null
) {
    val areaList: List<MapArea> get() = areas.orEmpty()
    val placeList: List<MapPlace> get() = places.orEmpty()
    val networkLinkList: List<String> get() = networkLinks.orEmpty()
    val isEmpty: Boolean get() = areaList.isEmpty() && placeList.isEmpty()

    /** Primer recinto que contiene el punto dado. */
    fun areaAt(point: GeoPoint): MapArea? = areaList.firstOrNull { it.contains(point) }
}

// ---------------------------------------------------------------------------
// Distancias
// ---------------------------------------------------------------------------

private const val EARTH_RADIUS_M = 6_371_000.0

/** Distancia en metros entre dos coordenadas (haversine). */
fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
}

// ---------------------------------------------------------------------------
// Lectura de KML / KMZ
// ---------------------------------------------------------------------------

/**
 * Lee un archivo KML o KMZ desde un Uri del selector de documentos.
 *
 * My Maps ofrece dos exportaciones. La normal trae la geometría dentro del
 * archivo. La otra, la que mantiene el mapa sincronizado, trae solo un
 * NetworkLink que apunta a la URL del mapa; en ese caso se descarga el KML real
 * desde ahí, de modo que ambos archivos sirven.
 *
 * Debe llamarse fuera del hilo principal: puede hacer red.
 */
fun readFarmMap(context: Context, uri: Uri, displayName: String?): ImportedMap? = try {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    if (bytes == null || bytes.isEmpty()) null
    else {
        val kml = if (isZip(bytes)) extractKmlFromKmz(bytes) else bytes
        val parsed = kml?.let { parseKml(ByteArrayInputStream(it)) }

        val resolved = if (parsed != null && parsed.isEmpty && parsed.networkLinkList.isNotEmpty()) {
            parsed.networkLinkList.firstNotNullOfOrNull { link ->
                downloadKml(link)?.let { downloaded -> parseKml(ByteArrayInputStream(downloaded)) }
                    ?.takeIf { !it.isEmpty }
            } ?: parsed
        } else parsed

        resolved?.copy(
            sourceName = displayName,
            importedAt = System.currentTimeMillis()
        )
    }
} catch (_: Exception) {
    null
}

/**
 * Descarga el KML apuntado por un NetworkLink.
 *
 * Solo funciona si el mapa de My Maps es accesible por enlace; si es privado,
 * Google responde con un error y la importación cae de vuelta al contenido del
 * archivo, que en ese caso está vacío.
 */
private fun downloadKml(url: String): ByteArray? = try {
    val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 15_000
        readTimeout = 20_000
        requestMethod = "GET"
    }
    val data = connection.inputStream.use { it.readBytes() }
    connection.disconnect()
    when {
        data.isEmpty() -> null
        isZip(data) -> extractKmlFromKmz(data)
        else -> data
    }
} catch (_: Exception) {
    null
}

private fun isZip(bytes: ByteArray): Boolean =
    bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

/** Un KMZ es un zip; el mapa vive en la primera entrada .kml (doc.kml en My Maps). */
private fun extractKmlFromKmz(bytes: ByteArray): ByteArray? = try {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var found: ByteArray? = null
        var entry = zip.nextEntry
        while (entry != null && found == null) {
            if (entry.name.lowercase().endsWith(".kml")) found = zip.readBytes()
            entry = zip.nextEntry
        }
        found
    }
} catch (_: Exception) {
    null
}

/**
 * Parser del subconjunto de KML que exporta My Maps.
 *
 * De cada Placemark se toma el nombre y, o bien su Point, o bien el contorno
 * exterior de su Polygon. Un Placemark con varias geometrías (MultiGeometry)
 * aporta solo su primer contorno, que para un invernadero delimitado a mano es
 * lo esperable.
 */
fun parseKml(input: InputStream): ImportedMap {
    val areas = mutableListOf<MapArea>()
    val places = mutableListOf<MapPlace>()
    val links = mutableListOf<String>()
    var documentName: String? = null

    try {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val stack = ArrayDeque<String>()
        var currentFolder: String? = null
        var placemarkName: String? = null
        var polygonCoords: String? = null
        var pointCoords: String? = null
        var text = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    stack.addLast(tag)
                    text = StringBuilder()
                    if (tag.equals("Placemark", true)) {
                        placemarkName = null
                        polygonCoords = null
                        pointCoords = null
                    }
                }

                XmlPullParser.TEXT -> text.append(parser.text)

                XmlPullParser.END_TAG -> {
                    val tag = parser.name
                    if (stack.isNotEmpty()) stack.removeLast()
                    val value = text.toString().trim()
                    val parent = stack.lastOrNull()

                    when {
                        tag.equals("name", true) && parent.equals("Placemark", true) ->
                            placemarkName = value

                        tag.equals("name", true) && parent.equals("Folder", true) ->
                            currentFolder = value

                        tag.equals("name", true) && parent.equals("Document", true) ->
                            if (documentName == null) documentName = value

                        tag.equals("coordinates", true) -> {
                            val inPoint = stack.any { it.equals("Point", true) }
                            val inOuter = stack.any { it.equals("outerBoundaryIs", true) }
                            when {
                                inPoint -> if (pointCoords == null) pointCoords = value
                                inOuter -> if (polygonCoords == null) polygonCoords = value
                            }
                        }

                        // Solo los href de NetworkLink: los de Style apuntan a
                        // iconos de Google, no a mapas.
                        tag.equals("href", true) && stack.any { it.equals("NetworkLink", true) } ->
                            if (value.startsWith("http", true)) links += value

                        tag.equals("Folder", true) -> currentFolder = null

                        tag.equals("Placemark", true) -> {
                            val label = placemarkName?.takeIf { it.isNotBlank() } ?: "Sin nombre"
                            val outline = polygonCoords?.let { parseCoordinates(it) }
                            if (outline != null && outline.size >= 3) {
                                areas += MapArea(
                                    name = label,
                                    layer = currentFolder,
                                    outline = outline
                                )
                            } else {
                                parseCoordinates(pointCoords.orEmpty()).firstOrNull()?.let { p ->
                                    places += MapPlace(label, p.lat, p.lon, currentFolder)
                                }
                            }
                        }
                    }
                    text = StringBuilder()
                }
            }
            event = parser.next()
        }
    } catch (_: Exception) {
        // Un KML corrupto devuelve lo que se alcanzó a leer en vez de tumbar la importación.
    }

    return ImportedMap(
        areas = areas,
        places = places,
        documentName = documentName,
        networkLinks = links.distinct()
    )
}

/** Las coordenadas KML vienen como "lon,lat[,alt]" separadas por espacios. */
private fun parseCoordinates(raw: String): List<GeoPoint> =
    raw.split(Regex("\\s+"))
        .mapNotNull { chunk ->
            val parts = chunk.split(',')
            if (parts.size < 2) return@mapNotNull null
            val lon = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            GeoPoint(lat, lon)
        }

// ---------------------------------------------------------------------------
// Persistencia
// ---------------------------------------------------------------------------

class FarmMapStore private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, "farm_map.json")
    private val gson = Gson()
    private val _map = MutableStateFlow(load())
    val map: StateFlow<ImportedMap> = _map

    private fun load(): ImportedMap = try {
        if (file.exists()) {
            val type = object : TypeToken<ImportedMap>() {}.type
            gson.fromJson<ImportedMap>(file.readText(), type) ?: ImportedMap()
        } else ImportedMap()
    } catch (_: Exception) {
        ImportedMap()
    }

    fun save(imported: ImportedMap) {
        try {
            file.writeText(gson.toJson(imported))
        } catch (_: Exception) {
            // Mejor esfuerzo.
        }
        _map.value = imported
    }

    fun clear() {
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {
            // Ignorado: basta con vaciar el estado en memoria.
        }
        _map.value = ImportedMap()
    }

    companion object {
        @Volatile
        private var instance: FarmMapStore? = null

        operator fun invoke(context: Context): FarmMapStore =
            instance ?: synchronized(this) {
                instance ?: FarmMapStore(context).also { instance = it }
            }
    }
}
