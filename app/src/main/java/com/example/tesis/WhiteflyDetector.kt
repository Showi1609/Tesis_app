package com.example.tesis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import com.example.tesis.util.ModelInfo
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantLock

data class Letterbox(val bitmap: Bitmap, val escala: Float, val padX: Float, val padY: Float)

data class Deteccion(val cx: Float, val cy: Float, val w: Float, val h: Float, val score: Float)

data class BoxedDeteccion(val rect: RectF, val score: Float)

data class DetectionResult(
    val detections: List<BoxedDeteccion>,
    val imageWidth: Int,
    val imageHeight: Int,
    val rotation: Int = 0,
    val maxScore: Float = 0f,
    val modelOutputInfo: String = "",
    /**
     * Umbrales con los que se produjo ESTE resultado.
     *
     * Van en el resultado y no leídos de una constante al guardar, porque desde
     * que son ajustables la constante ya no describe lo que ocurrió: dos
     * muestreos del mismo día pueden haberse contado con umbrales distintos, y
     * sin este dato el conteo deja de ser reproducible.
     */
    val confThreshold: Float = ModelInfo.CONF_THRESHOLD,
    val iouThreshold: Float = ModelInfo.IOU_NMS,
    /** "COMPLETA" o "MOSAICO_3x3": con qué estrategia se produjo el conteo. */
    val detectionMode: String = MODE_WHOLE
)

const val MODE_WHOLE = "COMPLETA"
const val MODE_TILED_PREFIX = "MOSAICO_"

class WhiteflyDetector(context: Context, modelPath: String = "whitefly.tflite") {

    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    private val inputBuffer: ByteBuffer
    private val inputSize: Int
    private val intPixels: IntArray
    
    // Reutilizar buffer de salida para evitar OOM y GC pauses en mosaico
    private val outputBuffer: Any
    
    private val detectorLock = ReentrantLock()
    private var isClosed = false
    
    private val isInputUint8: Boolean
    private val isOutputUint8: Boolean
    private val inputScale: Float
    private val inputZeroPoint: Int
    private val outputScale: Float
    private val outputZeroPoint: Int

    private var outputRows: Int = 0
    private var outputCols: Int = 0
    private var isTransposed: Boolean = false

    var activeDelegate: String? = null
        private set

    init {
        val model = cargarModeloDesdeAssets(context, modelPath)
        
        val options = Interpreter.Options().apply {
            try {
                gpuDelegate = GpuDelegate()
                addDelegate(gpuDelegate)
                activeDelegate = "GPU"
                Log.d("WhiteflyDetector", "GPU Delegate added successfully")
            } catch (e: Throwable) {
                Log.w("WhiteflyDetector", "GPU Delegate failed to load, falling back to CPU", e)
                activeDelegate = "CPU"
                setNumThreads(4)
            }
        }
        
        interpreter = Interpreter(model, options)
        
        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()
        inputSize = inputShape[1]
        intPixels = IntArray(inputSize * inputSize)
        
        isInputUint8 = inputTensor.dataType() == DataType.UINT8 || inputTensor.dataType() == DataType.INT8
        inputScale = if (inputTensor.quantizationParams().scale != 0f) inputTensor.quantizationParams().scale else 1f
        inputZeroPoint = inputTensor.quantizationParams().zeroPoint
        
        val outputTensor = interpreter.getOutputTensor(0)
        val shape = outputTensor.shape()
        outputRows = shape[1]
        outputCols = shape[2]
        
        isTransposed = outputRows > outputCols 
        
        isOutputUint8 = outputTensor.dataType() == DataType.UINT8 || outputTensor.dataType() == DataType.INT8
        outputScale = if (outputTensor.quantizationParams().scale != 0f) outputTensor.quantizationParams().scale else 1f
        outputZeroPoint = outputTensor.quantizationParams().zeroPoint
        
        // Inicializar el buffer de salida una sola vez
        outputBuffer = if (isOutputUint8) {
            Array(1) { Array(outputRows) { ByteArray(outputCols) } }
        } else {
            Array(1) { Array(outputRows) { FloatArray(outputCols) } }
        }
        
        val bytesPerChannel = if (isInputUint8) 1 else 4
        inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * bytesPerChannel).order(ByteOrder.nativeOrder())
        
        Log.d("WhiteflyDetector", "Model Loaded: InputSize $inputSize, Output Shape: ${shape.contentToString()}")
    }

    private fun cargarModeloDesdeAssets(context: Context, modelPath: String): ByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun letterbox(origen: Bitmap): Letterbox {
        val escala = minOf(inputSize.toFloat() / origen.width, inputSize.toFloat() / origen.height)
        val nuevoAncho = (origen.width * escala).toInt()
        val nuevoAlto = (origen.height * escala).toInt()
        
        // Redimensionar solo si es necesario
        val redimensionado = if (nuevoAncho == origen.width && nuevoAlto == origen.height) {
            origen
        } else {
            Bitmap.createScaledBitmap(origen, nuevoAncho, nuevoAlto, true)
        }

        val padX = (inputSize - nuevoAncho) / 2f
        val padY = (inputSize - nuevoAlto) / 2f

        val resultado = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultado)
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(redimensionado, padX, padY, null)

        // Si creamos un bitmap nuevo para redimensionar, lo reciclamos
        if (redimensionado != origen) {
            redimensionado.recycle()
        }

        return Letterbox(resultado, escala, padX, padY)
    }

    fun bitmapATensor(bitmap: Bitmap): ByteBuffer {
        inputBuffer.rewind()
        try {
            bitmap.getPixels(intPixels, 0, inputSize, 0, 0, inputSize, inputSize)
            
            if (isInputUint8) {
                for (pixel in intPixels) {
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    inputBuffer.put(((r / 255f / inputScale) + inputZeroPoint).toInt().toByte())
                    inputBuffer.put(((g / 255f / inputScale) + inputZeroPoint).toInt().toByte())
                    inputBuffer.put(((b / 255f / inputScale) + inputZeroPoint).toInt().toByte())
                }
            } else {
                val floatBuffer = inputBuffer.asFloatBuffer()
                for (pixel in intPixels) {
                    floatBuffer.put(((pixel shr 16) and 0xFF) / 255f)
                    floatBuffer.put(((pixel shr 8) and 0xFF) / 255f)
                    floatBuffer.put((pixel and 0xFF) / 255f)
                }
            }
        } catch (e: Exception) {
            Log.e("WhiteflyDetector", "Error filling input buffer", e)
        }
        inputBuffer.rewind()
        return inputBuffer
    }

    /**
     * @param confThreshold confianza mínima para aceptar un candidato.
     * @param iouThreshold solape a partir del cual el NMS considera duplicada una
     *   caja. En trampas cromáticas las moscas se pegan unas a otras, así que un
     *   valor bajo borra vecinas reales y subestima justo en alta densidad.
     */
    fun detect(
        bitmap: Bitmap,
        rotation: Int = 0,
        isLive: Boolean = true,
        confThreshold: Float = ModelInfo.CONF_THRESHOLD,
        iouThreshold: Float = ModelInfo.IOU_NMS
    ): DetectionResult {
        val conf = confThreshold.coerceIn(ModelInfo.CONF_MIN, ModelInfo.CONF_MAX)
        val iou = iouThreshold.coerceIn(ModelInfo.IOU_MIN, ModelInfo.IOU_MAX)

        if (isLive) {
            if (!detectorLock.tryLock()) {
                return DetectionResult(
                    emptyList(), bitmap.width, bitmap.height, rotation, 0f, "Busy", conf, iou
                )
            }
        } else {
            detectorLock.lock()
        }

        return try {
            if (isClosed) return DetectionResult(
                emptyList(), bitmap.width, bitmap.height, rotation, 0f, "Closed", conf, iou
            )

            val (boxedDetections, maxScore) = inferBoxes(bitmap, conf, iou, 0f, 0f)

            Log.d("WhiteflyDetector", "Detections: ${boxedDetections.size}, MaxScore: $maxScore")

            DetectionResult(
                detections = boxedDetections,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                rotation = rotation,
                maxScore = maxScore,
                modelOutputInfo = "In: $inputSize, Out: [$outputRows, $outputCols]" +
                    ", conf ${"%.2f".format(conf)}, IoU ${"%.2f".format(iou)}",
                confThreshold = conf,
                iouThreshold = iou,
                detectionMode = MODE_WHOLE
            )
        } catch (e: Exception) {
            Log.e("WhiteflyDetector", "Error en detección", e)
            val errorMsg = e.toString().take(50)
            DetectionResult(
                emptyList(), bitmap.width, bitmap.height, rotation, 0f, "Err: $errorMsg", conf, iou
            )
        } finally {
            detectorLock.unlock()
        }
    }

    /**
     * Detección por mosaico.
     *
     * El tamaño de entrada del modelo está grabado en el .tflite y no se puede
     * cambiar: si es 640, siempre es 640. Lo que sí se puede cambiar es cuántos
     * centímetros de trampa caben en esos 640 píxeles. Al partir la foto en una
     * rejilla y pasar cada trozo entero por el modelo, cada píxel de entrada
     * cubre la tercera parte de trampa (con rejilla 3×3), así que una mosca que
     * medía 4 px pasa a medir unos 12. Ahí está la diferencia entre "no la ve" y
     * "la ve".
     *
     * El precio son [grid]² inferencias en vez de una. Solo tiene sentido sobre
     * la foto fija, nunca sobre el visor en vivo.
     *
     * @param grid lado de la rejilla: 3 significa 3×3 = 9 trozos.
     * @param overlapFraction traslape entre trozos vecinos, como fracción del
     *   lado del trozo. Sin traslape, una mosca justo en la costura se parte en
     *   dos y ninguna mitad se parece a una mosca.
     */
    fun detectTiled(
        bitmap: Bitmap,
        rotation: Int = 0,
        grid: Int = ModelInfo.TILE_GRID_DEFAULT,
        overlapFraction: Float = ModelInfo.TILE_OVERLAP,
        confThreshold: Float = ModelInfo.CONF_THRESHOLD,
        iouThreshold: Float = ModelInfo.IOU_NMS,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): DetectionResult {
        val g = grid.coerceIn(ModelInfo.TILE_GRID_MIN, ModelInfo.TILE_GRID_MAX)
        if (g <= 1) return detect(bitmap, rotation, isLive = false, confThreshold, iouThreshold)

        val conf = confThreshold.coerceIn(ModelInfo.CONF_MIN, ModelInfo.CONF_MAX)
        val iou = iouThreshold.coerceIn(ModelInfo.IOU_MIN, ModelInfo.IOU_MAX)

        detectorLock.lock()
        return try {
            if (isClosed) return DetectionResult(
                emptyList(), bitmap.width, bitmap.height, rotation, 0f, "Closed", conf, iou
            )

            val columns = tilePositions(bitmap.width, g, overlapFraction)
            val rows = tilePositions(bitmap.height, g, overlapFraction)
            val totalTiles = columns.size * rows.size
            var currentTile = 0

            val all = mutableListOf<BoxedDeteccion>()
            var maxScore = 0f

            columns.forEach { (x0, tileW) ->
                rows.forEach { (y0, tileH) ->
                    val tile = Bitmap.createBitmap(bitmap, x0, y0, tileW, tileH)
                    try {
                        val (boxes, score) = inferBoxes(
                            tile, conf, iou, x0.toFloat(), y0.toFloat()
                        )
                        all += boxes
                        if (score > maxScore) maxScore = score
                        
                        currentTile++
                        onProgress(currentTile, totalTiles)
                    } finally {
                        if (tile != bitmap) tile.recycle()
                    }
                }
            }

            val merged = mergeOverlapping(all, iou)

            Log.d(
                "WhiteflyDetector",
                "Mosaico ${g}x$g: ${all.size} crudas -> ${merged.size} tras fusionar"
            )

            DetectionResult(
                detections = merged,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                rotation = rotation,
                maxScore = maxScore,
                modelOutputInfo = "In: $inputSize x ${g * g} trozos" +
                    ", conf ${"%.2f".format(conf)}, IoU ${"%.2f".format(iou)}",
                confThreshold = conf,
                iouThreshold = iou,
                detectionMode = "$MODE_TILED_PREFIX${g}x$g"
            )
        } catch (e: Exception) {
            Log.e("WhiteflyDetector", "Error en detección por mosaico", e)
            val errorMsg = e.toString().take(50)
            DetectionResult(
                emptyList(), bitmap.width, bitmap.height, rotation, 0f, "Err: $errorMsg", conf, iou
            )
        } finally {
            detectorLock.unlock()
        }
    }

    /**
     * Núcleo de inferencia sobre un bitmap, con las cajas trasladadas al sistema
     * de coordenadas de la imagen completa. Exige el lock ya tomado, porque
     * [bitmapATensor] escribe sobre un búfer compartido.
     */
    private fun inferBoxes(
        source: Bitmap,
        conf: Float,
        iou: Float,
        offsetX: Float,
        offsetY: Float
    ): Pair<List<BoxedDeteccion>, Float> {
        val lb = letterbox(source)
        return try {
            val entrada = bitmapATensor(lb.bitmap)

            interpreter.run(entrada, outputBuffer)

            val (deteccionesRaw, maxScore) = decodificarFlexible(outputBuffer, umbralConfianza = conf)

            val boxes = nms(deteccionesRaw, iou).map { d ->
                val r = aCoordenadasOriginales(d, lb)
                BoxedDeteccion(
                    RectF(
                        r.left + offsetX,
                        r.top + offsetY,
                        r.right + offsetX,
                        r.bottom + offsetY
                    ),
                    d.score
                )
            }
            boxes to maxScore
        } finally {
            lb.bitmap.recycle()
        }
    }

    /**
     * Posiciones de los trozos a lo largo de un eje, como (inicio, tamaño).
     *
     * Con traslape f, los trozos miden total / (grid - (grid-1)·f) y se reparten
     * uniformemente entre 0 y total - tamaño, de modo que el primero empieza en
     * el borde y el último termina en el borde: ningún píxel queda sin mirar.
     */
    private fun tilePositions(total: Int, grid: Int, overlap: Float): List<Pair<Int, Int>> {
        if (grid <= 1 || total <= 1) return listOf(0 to total)
        val f = overlap.coerceIn(0f, 0.5f)
        val span = grid - (grid - 1) * f
        val tile = kotlin.math.ceil(total / span).toInt().coerceIn(1, total)
        if (tile >= total) return listOf(0 to total)
        return (0 until grid).map { i ->
            val start = ((total - tile).toFloat() * i / (grid - 1)).toInt()
                .coerceIn(0, total - tile)
            start to tile
        }.distinct()
    }

    /**
     * Fusiona las cajas de todos los trozos.
     *
     * Además del IoU normal se descarta una caja cuando queda casi contenida en
     * otra mayor. Hace falta para las costuras: una mosca cortada por el borde de
     * un trozo produce media caja, y media caja frente a la caja completa da un
     * IoU de apenas 0.5 — con umbrales altos sobreviviría y la mosca se contaría
     * dos veces. La contención sí la detecta.
     */
    private fun mergeOverlapping(
        boxes: List<BoxedDeteccion>,
        umbralIou: Float,
        umbralContencion: Float = 0.65f
    ): List<BoxedDeteccion> {
        val ordenadas = boxes.sortedByDescending { it.score }.toMutableList()
        val resultado = mutableListOf<BoxedDeteccion>()
        while (ordenadas.isNotEmpty()) {
            val mejor = ordenadas.removeAt(0)
            resultado.add(mejor)
            ordenadas.removeAll { otra ->
                val inter = intersectionArea(mejor.rect, otra.rect)
                if (inter <= 0f) return@removeAll false
                val areaA = mejor.rect.width() * mejor.rect.height()
                val areaB = otra.rect.width() * otra.rect.height()
                val union = areaA + areaB - inter
                val iou = if (union > 0f) inter / union else 0f
                val menor = minOf(areaA, areaB)
                val contencion = if (menor > 0f) inter / menor else 0f
                iou > umbralIou || contencion > umbralContencion
            }
        }
        return resultado
    }

    private fun intersectionArea(a: RectF, b: RectF): Float {
        val ix1 = maxOf(a.left, b.left)
        val iy1 = maxOf(a.top, b.top)
        val ix2 = minOf(a.right, b.right)
        val iy2 = minOf(a.bottom, b.bottom)
        return maxOf(0f, ix2 - ix1) * maxOf(0f, iy2 - iy1)
    }

    private fun decodificarFlexible(salida: Any, umbralConfianza: Float): Pair<List<Deteccion>, Float> {
        val detecciones = mutableListOf<Deteccion>()
        var globalMaxScore = 0f
        
        val numBoxes = if (isTransposed) outputRows else outputCols
        val numClasses = if (isTransposed) outputCols else outputRows

        val floatSalida = if (!isOutputUint8) salida as Array<Array<FloatArray>> else null
        val byteSalida = if (isOutputUint8) salida as Array<Array<ByteArray>> else null
        
        for (i in 0 until numBoxes) {
            val startClassIndex = 4
            
            var maxClassScore = if (isTransposed) {
                if (floatSalida != null) floatSalida[0][i][startClassIndex]
                else (byteSalida!![0][i][startClassIndex].toInt() and 0xFF - outputZeroPoint) * outputScale
            } else {
                if (floatSalida != null) floatSalida[0][startClassIndex][i]
                else (byteSalida!![0][startClassIndex][i].toInt() and 0xFF - outputZeroPoint) * outputScale
            }
            
            if (numClasses > 5) {
                for (classIdx in (startClassIndex + 1) until numClasses) {
                    val s = if (isTransposed) {
                        if (floatSalida != null) floatSalida[0][i][classIdx]
                        else (byteSalida!![0][i][classIdx].toInt() and 0xFF - outputZeroPoint) * outputScale
                    } else {
                        if (floatSalida != null) floatSalida[0][classIdx][i]
                        else (byteSalida!![0][classIdx][i].toInt() and 0xFF - outputZeroPoint) * outputScale
                    }
                    if (s > maxClassScore) maxClassScore = s
                }
            }
            
            if (maxClassScore > globalMaxScore) globalMaxScore = maxClassScore
            
            if (maxClassScore >= umbralConfianza) {
                var cx: Float; var cy: Float; var w: Float; var h: Float
                
                if (isTransposed) {
                    if (floatSalida != null) {
                        cx = floatSalida[0][i][0]; cy = floatSalida[0][i][1]
                        w = floatSalida[0][i][2]; h = floatSalida[0][i][3]
                    } else {
                        cx = (byteSalida!![0][i][0].toInt() and 0xFF - outputZeroPoint) * outputScale
                        cy = (byteSalida[0][i][1].toInt() and 0xFF - outputZeroPoint) * outputScale
                        w = (byteSalida[0][i][2].toInt() and 0xFF - outputZeroPoint) * outputScale
                        h = (byteSalida[0][i][3].toInt() and 0xFF - outputZeroPoint) * outputScale
                    }
                } else {
                    if (floatSalida != null) {
                        cx = floatSalida[0][0][i]; cy = floatSalida[0][1][i]
                        w = floatSalida[0][2][i]; h = floatSalida[0][3][i]
                    } else {
                        cx = (byteSalida!![0][0][i].toInt() and 0xFF - outputZeroPoint) * outputScale
                        cy = (byteSalida[0][1][i].toInt() and 0xFF - outputZeroPoint) * outputScale
                        w = (byteSalida[0][2][i].toInt() and 0xFF - outputZeroPoint) * outputScale
                        h = (byteSalida[0][3][i].toInt() and 0xFF - outputZeroPoint) * outputScale
                    }
                }
                
                if (cx <= 1.01f && w <= 1.01f) {
                    cx *= inputSize; cy *= inputSize; w *= inputSize; h *= inputSize
                }
                
                detecciones.add(Deteccion(cx, cy, w, h, maxClassScore))
            }
        }
        return detecciones to globalMaxScore
    }

    private fun nms(detecciones: List<Deteccion>, umbralIou: Float = ModelInfo.IOU_NMS): List<Deteccion> {
        val ordenadas = detecciones.sortedByDescending { it.score }.toMutableList()
        val resultado = mutableListOf<Deteccion>()
        while (ordenadas.isNotEmpty()) {
            val mejor = ordenadas.removeAt(0)
            resultado.add(mejor)
            ordenadas.removeAll { iou(mejor, it) > umbralIou }
        }
        return resultado
    }

    private fun iou(a: Deteccion, b: Deteccion): Float {
        val ax1 = a.cx - a.w / 2; val ax2 = a.cx + a.w / 2
        val ay1 = a.cy - a.h / 2; val ay2 = a.cy + a.h / 2
        val bx1 = b.cx - b.w / 2; val bx2 = b.cx + b.w / 2
        val by1 = b.cy - b.h / 2; val by2 = b.cy + b.h / 2

        val ix1 = maxOf(ax1, bx1); val iy1 = maxOf(ay1, by1)
        val ix2 = minOf(ax2, bx2); val iy2 = minOf(ay2, by2)
        val inter = maxOf(0f, ix2 - ix1) * maxOf(0f, iy2 - iy1)
        val union = a.w * a.h + b.w * b.h - inter
        return if (union > 0) inter / union else 0f
    }

    fun aCoordenadasOriginales(d: Deteccion, lb: Letterbox): RectF {
        val x1 = (d.cx - d.w / 2 - lb.padX) / lb.escala
        val y1 = (d.cy - d.h / 2 - lb.padY) / lb.escala
        val x2 = (d.cx + d.w / 2 - lb.padX) / lb.escala
        val y2 = (d.cy + d.h / 2 - lb.padY) / lb.escala
        return RectF(x1, y1, x2, y2)
    }
    
    fun close() {
        detectorLock.lock()
        try {
            isClosed = true
            interpreter.close()
            gpuDelegate?.close()
        } finally {
            detectorLock.unlock()
        }
    }
}