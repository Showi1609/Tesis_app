package com.example.tesis

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.camera.core.ImageProxy
import androidx.core.graphics.get

enum class FramingStatus {
    SEARCHING,    // No trap detected
    NOT_CENTERED, // Trap detected but too small or far
    FOCUSING,     // Centered but blurry
    READY         // Centered and sharp
}

data class FramingResult(
    val status: FramingStatus,
    val trapBounds: RectF?,
    val sharpness: Double,
    val imageWidth: Int,
    val imageHeight: Int,
    val rotation: Int = 0
)

class FramingAssistant {

    private val sharpnessThreshold = 120.0
    private val minTrapAreaRatio = 0.10f // Reduced to 10% for easier detection
    
    // Smoothing
    private var smoothedRect: RectF? = null
    private val alpha = 0.2f // Factor for moving average

    private var stableFrames = 0
    private val stabilityRequired = 5 // Frames required to turn Green

    fun analyzeFraming(imageProxy: ImageProxy, bitmap: Bitmap): FramingResult {
        val sharpness = calculateSharpness(imageProxy)
        val rawBounds = detectLargestYellowCluster(bitmap)
        val rotation = imageProxy.imageInfo.rotationDegrees
        
        // Apply smoothing to the rectangle
        smoothedRect = if (rawBounds != null) {
            val current = smoothedRect
            if (current == null) rawBounds
            else {
                RectF(
                    current.left * (1 - alpha) + rawBounds.left * alpha,
                    current.top * (1 - alpha) + rawBounds.top * alpha,
                    current.right * (1 - alpha) + rawBounds.right * alpha,
                    current.bottom * (1 - alpha) + rawBounds.bottom * alpha
                )
            }
        } else {
            null
        }

        val status = when {
            smoothedRect == null -> {
                stableFrames = 0
                FramingStatus.SEARCHING
            }
            !isSizeAcceptable(smoothedRect!!, bitmap.width, bitmap.height) -> {
                stableFrames = 0
                FramingStatus.NOT_CENTERED
            }
            sharpness < sharpnessThreshold -> {
                stableFrames = 0
                FramingStatus.FOCUSING
            }
            else -> {
                stableFrames++
                if (stableFrames >= stabilityRequired) FramingStatus.READY 
                else FramingStatus.FOCUSING
            }
        }
        
        return FramingResult(status, smoothedRect, sharpness, bitmap.width, bitmap.height, rotation)
    }

    private fun isSizeAcceptable(rect: RectF, imgW: Int, imgH: Int): Boolean {
        val area = rect.width() * rect.height()
        val totalArea = imgW.toFloat() * imgH
        return (area / totalArea) > minTrapAreaRatio
    }

    private fun detectLargestYellowCluster(bitmap: Bitmap): RectF? {
        val width = bitmap.width
        val height = bitmap.height
        
        // Fast scan with high step
        val step = 15
        var minX = width.toFloat()
        var maxX = 0f
        var minY = height.toFloat()
        var maxY = 0f
        var foundAny = false

        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val pixel = bitmap[x, y]
                if (isYellow(pixel)) {
                    if (x < minX) minX = x.toFloat()
                    if (x > maxX) maxX = x.toFloat()
                    if (y < minY) minY = y.toFloat()
                    if (y > maxY) maxY = y.toFloat()
                    foundAny = true
                }
            }
        }

        if (!foundAny) return null
        
        // Add a small margin
        val padding = 20f
        return RectF(
            (minX - padding).coerceAtLeast(0f),
            (minY - padding).coerceAtLeast(0f),
            (maxX + padding).coerceAtMost(width.toFloat()),
            (maxY + padding).coerceAtMost(height.toFloat())
        )
    }

    private fun isYellow(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        // Yellow: R and G high, B low. 
        // Making it slightly more permissive
        return r > 110 && g > 110 && b < 120 && kotlin.math.abs(r - g) < 70
    }

    private fun calculateSharpness(image: ImageProxy): Double {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        // Use central region for faster calculation
        val step = 8
        for (y in (height/4) until (3*height/4) step step) {
            for (x in (width/4) until (3*width/4) step step) {
                val center = buffer.get(y * rowStride + x * pixelStride).toInt() and 0xFF
                val left = buffer.get(y * rowStride + (x - 1) * pixelStride).toInt() and 0xFF
                val right = buffer.get(y * rowStride + (x + 1) * pixelStride).toInt() and 0xFF
                val top = buffer.get((y - 1) * rowStride + x * pixelStride).toInt() and 0xFF
                val bottom = buffer.get((y + 1) * rowStride + x * pixelStride).toInt() and 0xFF

                val laplacian = (left + right + top + bottom - 4 * center).toDouble()
                sum += laplacian
                sumSq += laplacian * laplacian
                count++
            }
        }

        if (count == 0) return 0.0
        val mean = sum / count
        return (sumSq / count) - (mean * mean)
    }
}