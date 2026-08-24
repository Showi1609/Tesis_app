package com.example.tesis.util

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Superficie de calor interpolada, para dibujarla encima del mapa.
 *
 * Interpola por distancia inversa ponderada (IDW), que es el método estándar
 * para estimar entre estaciones de muestreo, con dos restricciones que no son
 * cosméticas:
 *
 * 1. **Solo se pinta hasta [maxDistanceM] de alguna trampa.** Más allá no hay
 *    soporte para afirmar nada, y un degradado bonito cubriendo todo el
 *    invernadero estaría inventando densidades donde nadie midió. El borde
 *    difuso de la mancha es precisamente el límite de lo medido.
 *
 * 2. **La interpolación se confina al polígono del invernadero.** El plástico
 *    es una barrera real para *Trialeurodes vaporariorum*: la población de un
 *    invernadero no es continua con la del vecino, aunque estén a diez metros.
 *    Interpolar a través de la pared supondría un gradiente que la biología del
 *    cultivo protegido no respalda. Así que cada polígono se resuelve por
 *    separado, con sus propias trampas, y el color se corta en el borde.
 *
 * Sin mapa de finca importado no hay polígonos que respetar y la superficie se
 * comporta como antes: libre, limitada solo por la distancia.
 */

data class HeatSample(val lat: Double, val lon: Double, val value: Double)

data class HeatBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
)

/** Metros aproximados por grado de latitud. */
private const val M_PER_DEG_LAT = 111_320.0

/**
 * Holgura para dar por dentro una trampa que el GPS dejó justo afuera.
 *
 * Una trampa contra la pared puede caer un par de metros fuera del polígono por
 * error del receptor o por cómo se trazó el contorno en My Maps. Descartarla
 * dejaría al invernadero sin su dato, que es peor error que admitirla.
 */
const val AREA_SNAP_TOLERANCE_M = 15.0

/** Sin grupo: fuera de todo polígono. */
private const val NO_AREA = -1

/**
 * Marco que envuelve las muestras con un margen igual al radio de influencia,
 * para que la mancha no quede cortada en el borde.
 */
fun heatBounds(samples: List<HeatSample>, marginM: Double): HeatBounds? {
    if (samples.isEmpty()) return null
    val meanLat = samples.sumOf { it.lat } / samples.size
    val degLat = marginM / M_PER_DEG_LAT
    val degLon = marginM / (M_PER_DEG_LAT * cos(Math.toRadians(meanLat)).coerceAtLeast(0.1))
    return HeatBounds(
        minLat = samples.minOf { it.lat } - degLat,
        maxLat = samples.maxOf { it.lat } + degLat,
        minLon = samples.minOf { it.lon } - degLon,
        maxLon = samples.maxOf { it.lon } + degLon
    )
}

/**
 * Cuántas muestras no caen dentro de ningún invernadero.
 *
 * Con mapa importado, esas muestras no se pintan: una coordenada fuera de todo
 * polígono casi siempre significa que la ficha de esa trampa quedó mal ubicada,
 * y conviene decirlo en vez de dibujar una mancha en mitad del campo.
 */
fun samplesOutsideAreas(
    samples: List<HeatSample>,
    areas: List<MapArea>,
    toleranceM: Double = AREA_SNAP_TOLERANCE_M
): Int {
    val polygons = areas.filter { it.points.size >= 3 }
    if (polygons.isEmpty() || samples.isEmpty()) return 0
    val mPerDegLon = mPerDegLonAt(samples.sumOf { it.lat } / samples.size)
    return samples.count { areaIndexFor(it, polygons, mPerDegLon, toleranceM) == NO_AREA }
}

/**
 * Construye el mapa de calor.
 *
 * @param areas polígonos de los invernaderos; si va vacío no hay confinamiento.
 * @param maxDistanceM radio de influencia; más allá, transparente.
 * @param maxValue valor que corresponde al rojo pleno.
 */
fun buildHeatBitmap(
    samples: List<HeatSample>,
    bounds: HeatBounds,
    areas: List<MapArea> = emptyList(),
    widthPx: Int = 640,
    heightPx: Int = 640,
    maxDistanceM: Double = 40.0,
    maxValue: Double,
    snapToleranceM: Double = AREA_SNAP_TOLERANCE_M
): Bitmap? {
    if (samples.isEmpty() || maxValue <= 0.0 || widthPx <= 0 || heightPx <= 0) return null

    val meanLat = samples.sumOf { it.lat } / samples.size
    val mPerDegLon = mPerDegLonAt(meanLat)

    val spanLat = (bounds.maxLat - bounds.minLat).takeIf { it > 0 } ?: return null
    val spanLon = (bounds.maxLon - bounds.minLon).takeIf { it > 0 } ?: return null

    val polygons = areas.filter { it.points.size >= 3 }
    val confined = polygons.isNotEmpty()

    // Cada trampa se adscribe a un invernadero; las que quedan fuera de todo
    // polígono se agrupan aparte y, con mapa importado, no se pintan.
    val byArea = HashMap<Int, MutableList<HeatSample>>()
    samples.forEach { sample ->
        val group = if (confined) {
            areaIndexFor(sample, polygons, mPerDegLon, snapToleranceM)
        } else NO_AREA
        byArea.getOrPut(group) { mutableListOf() }.add(sample)
    }

    // Mapa de pertenencia por píxel. Se recorre polígono por polígono y solo
    // dentro de su rectángulo envolvente, que es lo que evita probar los cuatro
    // millones de combinaciones píxel × polígono.
    val pixelArea = IntArray(widthPx * heightPx) { NO_AREA }
    if (confined) {
        polygons.forEachIndexed { index, polygon ->
            if (byArea[index].isNullOrEmpty()) return@forEachIndexed

            val pMinLat = polygon.points.minOf { it.lat }
            val pMaxLat = polygon.points.maxOf { it.lat }
            val pMinLon = polygon.points.minOf { it.lon }
            val pMaxLon = polygon.points.maxOf { it.lon }

            val yFrom = pyFor(pMaxLat, bounds, spanLat, heightPx).coerceIn(0, heightPx - 1)
            val yTo = pyFor(pMinLat, bounds, spanLat, heightPx).coerceIn(0, heightPx - 1)
            val xFrom = pxFor(pMinLon, bounds, spanLon, widthPx).coerceIn(0, widthPx - 1)
            val xTo = pxFor(pMaxLon, bounds, spanLon, widthPx).coerceIn(0, widthPx - 1)
            if (yFrom > yTo || xFrom > xTo) return@forEachIndexed

            for (py in yFrom..yTo) {
                val lat = bounds.maxLat - spanLat * (py + 0.5) / heightPx
                val rowOffset = py * widthPx
                for (px in xFrom..xTo) {
                    val i = rowOffset + px
                    if (pixelArea[i] != NO_AREA) continue
                    val lon = bounds.minLon + spanLon * (px + 0.5) / widthPx
                    if (polygon.contains(GeoPoint(lat, lon))) pixelArea[i] = index
                }
            }
        }
    }

    val pixels = IntArray(widthPx * heightPx)

    for (py in 0 until heightPx) {
        // La fila 0 del bitmap es el borde norte del recuadro.
        val lat = bounds.maxLat - spanLat * (py + 0.5) / heightPx
        val rowOffset = py * widthPx

        for (px in 0 until widthPx) {
            val i = rowOffset + px
            val group = pixelArea[i]

            // Con mapa importado, fuera de los invernaderos no se pinta nada.
            val groupSamples = if (confined && group == NO_AREA) null else byArea[group]
            if (groupSamples.isNullOrEmpty()) {
                pixels[i] = Color.TRANSPARENT
                continue
            }

            val lon = bounds.minLon + spanLon * (px + 0.5) / widthPx

            var weightSum = 0.0
            var valueSum = 0.0
            var nearest = Double.MAX_VALUE

            groupSamples.forEach { sample ->
                val dx = (lon - sample.lon) * mPerDegLon
                val dy = (lat - sample.lat) * M_PER_DEG_LAT
                val d = sqrt(dx * dx + dy * dy)
                if (d < nearest) nearest = d
                if (d <= maxDistanceM) {
                    // Suavizado en el denominador para no dividir por cero justo
                    // encima de una trampa.
                    val w = 1.0 / ((d * d) + 1.0)
                    weightSum += w
                    valueSum += w * sample.value
                }
            }

            pixels[i] = if (weightSum <= 0.0 || nearest > maxDistanceM) {
                Color.TRANSPARENT
            } else {
                // El alfa cae cerca del límite de influencia: el desvanecido marca
                // hasta dónde llega el respaldo de los datos.
                val fade = (1.0 - (nearest / maxDistanceM)).coerceIn(0.0, 1.0)
                colorFor(valueSum / weightSum / maxValue, fade)
            }
        }
    }

    return Bitmap.createBitmap(pixels, widthPx, heightPx, Bitmap.Config.ARGB_8888)
}

/** Rampa amarillo → naranja → rojo, con el alfa modulado por el respaldo de datos. */
private fun colorFor(ratio: Double, fade: Double): Int {
    val t = ratio.coerceIn(0.0, 1.0)
    val (r, g, b) = when {
        t < 0.5 -> {
            val k = t / 0.5
            Triple(255, (235 - 83 * k).toInt(), (59 * (1 - k)).toInt())
        }
        else -> {
            val k = (t - 0.5) / 0.5
            Triple(255, (152 - 85 * k).toInt(), 0)
        }
    }
    val alpha = (200 * fade * (0.35 + 0.65 * t)).toInt().coerceIn(0, 255)
    return Color.argb(alpha, r, min(255, max(0, g)), b)
}

// ---------------------------------------------------------------------------
// Geometría auxiliar
// ---------------------------------------------------------------------------

private fun mPerDegLonAt(lat: Double): Double =
    M_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.1)

private fun pyFor(lat: Double, bounds: HeatBounds, spanLat: Double, heightPx: Int): Int =
    (((bounds.maxLat - lat) / spanLat) * heightPx).toInt()

private fun pxFor(lon: Double, bounds: HeatBounds, spanLon: Double, widthPx: Int): Int =
    (((lon - bounds.minLon) / spanLon) * widthPx).toInt()

/**
 * Invernadero al que pertenece una muestra: el que la contiene o, si ninguno lo
 * hace, el más cercano dentro de [toleranceM] de su contorno.
 */
private fun areaIndexFor(
    sample: HeatSample,
    polygons: List<MapArea>,
    mPerDegLon: Double,
    toleranceM: Double
): Int {
    val point = GeoPoint(sample.lat, sample.lon)
    val inside = polygons.indexOfFirst { it.contains(point) }
    if (inside >= 0) return inside

    var best = NO_AREA
    var bestDistance = Double.MAX_VALUE
    polygons.forEachIndexed { index, polygon ->
        val d = distanceToOutlineM(point, polygon, mPerDegLon)
        if (d < bestDistance) {
            bestDistance = d
            best = index
        }
    }
    return if (best != NO_AREA && bestDistance <= toleranceM) best else NO_AREA
}

/** Distancia al contorno del polígono, en metros. */
private fun distanceToOutlineM(point: GeoPoint, area: MapArea, mPerDegLon: Double): Double {
    val poly = area.points
    if (poly.size < 2) return Double.MAX_VALUE
    var best = Double.MAX_VALUE
    var j = poly.size - 1
    for (i in poly.indices) {
        val d = segmentDistanceM(point, poly[j], poly[i], mPerDegLon)
        if (d < best) best = d
        j = i
    }
    return best
}

/** Distancia de un punto al segmento a–b, en metros, sobre plano local. */
private fun segmentDistanceM(
    point: GeoPoint,
    a: GeoPoint,
    b: GeoPoint,
    mPerDegLon: Double
): Double {
    val px = (point.lon - a.lon) * mPerDegLon
    val py = (point.lat - a.lat) * M_PER_DEG_LAT
    val bx = (b.lon - a.lon) * mPerDegLon
    val by = (b.lat - a.lat) * M_PER_DEG_LAT
    val len2 = bx * bx + by * by
    val t = if (len2 <= 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
    val dx = px - t * bx
    val dy = py - t * by
    return sqrt(dx * dx + dy * dy)
}
