package com.maddog.stencilforge.processor

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure image-processing pipeline. No AI/ML — only classical CV algorithms:
 *  1. Grayscale conversion
 *  2. Contrast stretch
 *  3. Gaussian blur (noise reduction)
 *  4. Sobel edge detection
 *  5. Shadow simulation via directional gradient shading
 *  6. Line thickness via dilation
 *  7. Optional color inversion
 */
object StencilProcessor {

    fun process(source: Bitmap, params: StencilParams): Bitmap {
        val w = source.width
        val h = source.height

        val gray = toGrayscale(source)
        val contrasted = applyContrast(gray, w, h, params.contrast)
        val blurred = gaussianBlur(contrasted, w, h, blurRadius(params.blurRadius))
        val (edges, gradientX, gradientY) = sobelEdges(blurred, w, h)
        val thresholded = thresholdEdges(edges, w, h, params.edgeThreshold)
        val withShadow = applyShadow(thresholded, gradientX, gradientY, w, h, params.shadowIntensity)
        val thick = dilate(withShadow, w, h, dilationSize(params.lineThickness))
        val result = if (params.invertColors) invert(thick, w, h) else thick
        return toBitmap(result, w, h)
    }

    // --- Step 1: Grayscale ---

    private fun toGrayscale(src: Bitmap): IntArray {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        return IntArray(w * h) { i ->
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255)
        }
    }

    // --- Step 2: Contrast stretch ---

    private fun applyContrast(gray: IntArray, w: Int, h: Int, amount: Float): IntArray {
        // amount 0=none, 1=full S-curve stretch
        val factor = 1f + amount * 2.5f
        return IntArray(gray.size) { i ->
            val v = gray[i] / 255f
            val stretched = ((v - 0.5f) * factor + 0.5f).coerceIn(0f, 1f)
            (stretched * 255).roundToInt()
        }
    }

    // --- Step 3: Gaussian Blur ---

    private fun blurRadius(param: Float): Int = (param * 4).roundToInt().coerceAtLeast(1)

    private fun gaussianBlur(gray: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius <= 1) return gray
        val kernel = gaussianKernel(radius)
        val tmp = IntArray(gray.size)
        // horizontal pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f; var weight = 0f
                for (k in kernel.indices) {
                    val nx = (x + k - radius).coerceIn(0, w - 1)
                    sum += gray[y * w + nx] * kernel[k]
                    weight += kernel[k]
                }
                tmp[y * w + x] = (sum / weight).roundToInt()
            }
        }
        val out = IntArray(gray.size)
        // vertical pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f; var weight = 0f
                for (k in kernel.indices) {
                    val ny = (y + k - radius).coerceIn(0, h - 1)
                    sum += tmp[ny * w + x] * kernel[k]
                    weight += kernel[k]
                }
                out[y * w + x] = (sum / weight).roundToInt()
            }
        }
        return out
    }

    private fun gaussianKernel(radius: Int): FloatArray {
        val size = radius * 2 + 1
        val sigma = radius / 2.0
        val kernel = FloatArray(size)
        var sum = 0f
        for (i in 0 until size) {
            val x = (i - radius).toDouble()
            kernel[i] = Math.exp(-x * x / (2 * sigma * sigma)).toFloat()
            sum += kernel[i]
        }
        return FloatArray(size) { kernel[it] / sum }
    }

    // --- Step 4: Sobel edge detection ---

    data class SobelResult(val magnitude: FloatArray, val gx: FloatArray, val gy: FloatArray)

    private fun sobelEdges(gray: IntArray, w: Int, h: Int): SobelResult {
        val mag = FloatArray(w * h)
        val gx = FloatArray(w * h)
        val gy = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val tl = gray[(y - 1) * w + (x - 1)].toFloat()
                val tc = gray[(y - 1) * w + x].toFloat()
                val tr = gray[(y - 1) * w + (x + 1)].toFloat()
                val ml = gray[y * w + (x - 1)].toFloat()
                val mr = gray[y * w + (x + 1)].toFloat()
                val bl = gray[(y + 1) * w + (x - 1)].toFloat()
                val bc = gray[(y + 1) * w + x].toFloat()
                val br = gray[(y + 1) * w + (x + 1)].toFloat()
                val sx = (-tl - 2 * ml - bl + tr + 2 * mr + br)
                val sy = (-tl - 2 * tc - tr + bl + 2 * bc + br)
                gx[y * w + x] = sx
                gy[y * w + x] = sy
                mag[y * w + x] = sqrt(sx * sx + sy * sy)
            }
        }
        // normalize to 0..1
        val max = mag.max().takeIf { it > 0f } ?: 1f
        for (i in mag.indices) mag[i] = mag[i] / max
        return SobelResult(mag, gx, gy)
    }

    // --- Step 5a: Threshold edges ---

    private fun thresholdEdges(mag: FloatArray, w: Int, h: Int, threshold: Float): IntArray {
        val adjustedThreshold = threshold * 0.6f + 0.05f // map 0..1 → 0.05..0.65
        return IntArray(mag.size) { i ->
            if (mag[i] >= adjustedThreshold) 0 else 255 // black edge on white bg
        }
    }

    // --- Step 5b: Shadow simulation (directional gradient shading) ---
    // Adds crosshatch-style shading in dark areas using gradient direction from Sobel

    private fun applyShadow(
        edges: IntArray,
        gradientX: FloatArray,
        gradientY: FloatArray,
        w: Int,
        h: Int,
        intensity: Float
    ): IntArray {
        if (intensity < 0.05f) return edges
        val out = edges.copyOf()
        val shadowStrength = intensity * 0.85f

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (out[idx] == 0) continue // already an edge pixel

                val gx = gradientX[idx]
                val gy = gradientY[idx]
                val len = sqrt(gx * gx + gy * gy)
                if (len < 1f) continue

                // shade only pixels where the gradient indicates a dark-to-light transition
                val normalizedLen = (len / 1020f).coerceIn(0f, 1f) // 1020 = max possible
                val shade = (normalizedLen * shadowStrength * 255f).roundToInt()
                if (shade > 30) {
                    out[idx] = (255 - shade).coerceAtLeast(0)
                }
            }
        }
        return out
    }

    // --- Step 6: Dilation (thicker lines) ---

    private fun dilationSize(param: Float): Int = (param * 2).roundToInt() // 0..2 px

    private fun dilate(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius == 0) return src
        val out = src.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (src[y * w + x] == 0) { // spread black pixels
                    for (dy in -radius..radius) {
                        for (dx in -radius..radius) {
                            val nx = (x + dx).coerceIn(0, w - 1)
                            val ny = (y + dy).coerceIn(0, h - 1)
                            out[ny * w + nx] = 0
                        }
                    }
                }
            }
        }
        return out
    }

    // --- Step 7: Invert ---

    private fun invert(src: IntArray, w: Int, h: Int): IntArray =
        IntArray(src.size) { 255 - src[it] }

    // --- Output: convert grayscale IntArray → Bitmap ---

    fun toBitmap(pixels: IntArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val argb = IntArray(pixels.size) { i ->
            val v = pixels[i]
            Color.rgb(v, v, v)
        }
        bmp.setPixels(argb, 0, w, 0, 0, w, h)
        return bmp
    }

    fun processAndConvert(source: Bitmap, params: StencilParams): Bitmap = process(source, params)
}
