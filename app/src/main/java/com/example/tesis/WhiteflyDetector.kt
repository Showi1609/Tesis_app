package com.example.tesis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
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
    val modelOutputInfo: String = ""
)

class WhiteflyDetector(context: Context, modelPath: String = "whitefly.tflite") {

    private val interpreter: Interpreter
    private val inputBuffer: ByteBuffer
    private val inputSize: Int
    private val intPixels: IntArray
    
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

    init {
        val model = cargarModeloDesdeAssets(context, modelPath)
        interpreter = Interpreter(model)
        
        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape() // Esperado [1, Size, Size, 3]
        inputSize = inputShape[1]
        intPixels = IntArray(inputSize * inputSize)
        
        isInputUint8 = inputTensor.dataType() == DataType.UINT8 || inputTensor.dataType() == DataType.INT8
        inputScale = if (inputTensor.quantizationParams().scale != 0f) inputTensor.quantizationParams().scale else 1f
        inputZeroPoint = inputTensor.quantizationParams().zeroPoint
        
        val outputTensor = interpreter.getOutputTensor(0)
        val shape = outputTensor.shape()
        outputRows = shape[1]
        outputCols = shape[2]
        
        // YOLOv8: [1, 5, 8400] o [1, 5, 33600]
        isTransposed = outputRows > outputCols 
        
        isOutputUint8 = outputTensor.dataType() == DataType.UINT8 || outputTensor.dataType() == DataType.INT8
        outputScale = if (outputTensor.quantizationParams().scale != 0f) outputTensor.quantizationParams().scale else 1f
        outputZeroPoint = outputTensor.quantizationParams().zeroPoint
        
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
            for (pixel in intPixels) {
                val r = ((pixel shr 16) and 0xFF)
                val g = ((pixel shr 8) and 0xFF)
                val b = (pixel and 0xFF)
                
                if (isInputUint8) {
                    inputBuffer.put(((r / 255f / inputScale) + inputZeroPoint).toInt().toByte())
                    inputBuffer.put(((g / 255f / inputScale) + inputZeroPoint).toInt().toByte())
                    inputBuffer.put(((b / 255f / inputScale) + inputZeroPoint).toInt().toByte())
                } else {
                    inputBuffer.putFloat(r / 255f)
                    inputBuffer.putFloat(g / 255f)
                    inputBuffer.putFloat(b / 255f)
                }
            }
        } catch (e: Exception) {
            Log.e("WhiteflyDetector", "Error filling input buffer", e)
        }
        inputBuffer.rewind()
        return inputBuffer
    }

    fun detect(bitmap: Bitmap, rotation: Int = 0, isLive: Boolean = true): DetectionResult {
        if (isLive) {
            if (!detectorLock.tryLock()) {
                return DetectionResult(emptyList(), bitmap.width, bitmap.height, rotation, 0f, "Busy")
            }
        } else {
            detectorLock.lock()
        }
        
        var lb: Letterbox? = null
        return try {
            if (isClosed) return DetectionResult(emptyList(), bitmap.width, bitmap.height, rotation, 0f, "Closed")
            
            lb = letterbox(bitmap)
            val entrada = bitmapATensor(lb.bitmap)
            
            val outputBuffer: Any = if (isOutputUint8) {
                Array(1) { Array(outputRows) { ByteArray(outputCols) } }
            } else {
                Array(1) { Array(outputRows) { FloatArray(outputCols) } }
            }
            
            interpreter.run(entrada, outputBuffer)
            
            val (deteccionesRaw, maxScore) = decodificarFlexible(outputBuffer, umbralConfianza = 0.25f)
            
            val finalDetections = nms(deteccionesRaw)
            val boxedDetections = finalDetections.map { d ->
                BoxedDeteccion(aCoordenadasOriginales(d, lb), d.score)
            }
            
            Log.d("WhiteflyDetector", "Detections: ${boxedDetections.size}, MaxScore: $maxScore")
            
            DetectionResult(boxedDetections, bitmap.width, bitmap.height, rotation, maxScore, "In: $inputSize, Out: [$outputRows, $outputCols]")
        } catch (e: Exception) {
            Log.e("WhiteflyDetector", "Error en detección", e)
            val errorMsg = e.toString().take(50)
            DetectionResult(emptyList(), bitmap.width, bitmap.height, rotation, 0f, "Err: $errorMsg")
        } finally {
            lb?.bitmap?.recycle() // Liberar memoria del bitmap temporal
            detectorLock.unlock()
        }
    }

    private fun decodificarFlexible(salida: Any, umbralConfianza: Float): Pair<List<Deteccion>, Float> {
        val detecciones = mutableListOf<Deteccion>()
        var globalMaxScore = 0f
        
        val numBoxes = if (isTransposed) outputRows else outputCols
        val numClasses = if (isTransposed) outputCols else outputRows

        // Casting fuera del bucle para máximo rendimiento
        val floatSalida = if (!isOutputUint8) salida as Array<Array<FloatArray>> else null
        val byteSalida = if (isOutputUint8) salida as Array<Array<ByteArray>> else null
        
        for (i in 0 until numBoxes) {
            val startClassIndex = 4
            
            // Leer score con acceso directo según el tipo
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
                val coords = FloatArray(4)
                for (cIdx in 0 until 4) {
                    coords[cIdx] = if (isTransposed) {
                        if (floatSalida != null) floatSalida[0][i][cIdx]
                        else (byteSalida!![0][i][cIdx].toInt() and 0xFF - outputZeroPoint) * outputScale
                    } else {
                        if (floatSalida != null) floatSalida[0][cIdx][i]
                        else (byteSalida!![0][cIdx][i].toInt() and 0xFF - outputZeroPoint) * outputScale
                    }
                }

                var cx = coords[0]; var cy = coords[1]; var w = coords[2]; var h = coords[3]
                
                // Normalizar si el modelo devuelve valores en [0, 1]
                if (cx <= 1.01f && w <= 1.01f) {
                    cx *= inputSize; cy *= inputSize; w *= inputSize; h *= inputSize
                }
                
                detecciones.add(Deteccion(cx, cy, w, h, maxClassScore))
            }
        }
        return detecciones to globalMaxScore
    }

    private fun nms(detecciones: List<Deteccion>, umbralIou: Float = 0.45f): List<Deteccion> {
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
        } finally {
            detectorLock.unlock()
        }
    }
}