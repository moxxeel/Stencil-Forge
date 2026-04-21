package com.maddog.stencilforge.processor

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pipeline:
 *  1. Grayscale (perceptual weights)
 *  2. CLAHE-style histogram equalization (improves local contrast)
 *  3. Gaussian blur (noise reduction, separable O(n*r))
 *  4. Sobel gradients
 *  5. Non-maximum suppression → 1-px-wide edges
 *  6. Hysteresis threshold (8-connected BFS)
 *  7. Erosion (remove isolated dots)
 *  8. Dilation (line thickness)
 *  9. Optional inversion
 */
object StencilProcessor {

    fun process(source: Bitmap, params: StencilParams): Bitmap {
        val w = source.width
        val h = source.height

        val gray       = toGrayscale(source)
        val equalized  = claheEqualize(gray, w, h, params.contrast)
        val sharpened  = if (params.sharpness > 0f) unsharpMask(equalized, w, h, params.sharpness) else equalized
        val blurred    = gaussianBlur(sharpened, w, h, blurRadius(params.blurRadius))
        val (mag, ang) = sobelEdges(blurred, w, h)
        val suppressed = nonMaxSuppression(mag, ang, w, h)
        val edges      = hysteresisThreshold(suppressed, w, h, params.edgeThreshold, params.edgeConnectivity)
        val clean      = erode(edges, w, h)
        val thick      = dilate(clean, w, h, dilationSize(params.lineThickness))
        val result     = if (params.invertColors) invert(thick) else thick
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

    // --- Step 2: CLAHE-style equalization ---
    // Divides image into tiles, equalizes each tile's histogram independently,
    // then blends (bilinear interpolation) between tiles so there are no hard borders.
    // This dramatically improves contrast in dark fur/hair without blowing out bright areas.
    // `strength` (0..1) blends between original and fully equalized.

    private fun claheEqualize(gray: IntArray, w: Int, h: Int, strength: Float): IntArray {
        val tileW = (w / 4).coerceAtLeast(32)
        val tileH = (h / 4).coerceAtLeast(32)
        val tilesX = (w + tileW - 1) / tileW
        val tilesY = (h + tileH - 1) / tileH

        // Build a CDF-based LUT for each tile
        val luts = Array(tilesY) { ty ->
            Array(tilesX) { tx ->
                val x0 = tx * tileW; val x1 = (x0 + tileW).coerceAtMost(w)
                val y0 = ty * tileH; val y1 = (y0 + tileH).coerceAtMost(h)
                val hist = IntArray(256)
                for (y in y0 until y1) for (x in x0 until x1) hist[gray[y * w + x]]++
                val total = (x1 - x0) * (y1 - y0)
                // Clip histogram to reduce over-amplification (the C in CLAHE)
                val clipLimit = (total / 256 * 3).coerceAtLeast(1)
                var excess = 0
                for (i in 0..255) { if (hist[i] > clipLimit) { excess += hist[i] - clipLimit; hist[i] = clipLimit } }
                val redistribute = excess / 256
                for (i in 0..255) hist[i] += redistribute
                // Build cumulative distribution → LUT
                val cdf = IntArray(256)
                cdf[0] = hist[0]
                for (i in 1..255) cdf[i] = cdf[i - 1] + hist[i]
                val cdfMin = cdf.first { it > 0 }
                IntArray(256) { i ->
                    ((cdf[i] - cdfMin).toFloat() / (total - cdfMin) * 255f).roundToInt().coerceIn(0, 255)
                }
            }
        }

        // Bilinear interpolation between tile LUTs
        return IntArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            val v = gray[idx]

            // Tile coordinates (center-based)
            val tx = ((x - tileW / 2f) / tileW).coerceIn(0f, (tilesX - 1).toFloat())
            val ty = ((y - tileH / 2f) / tileH).coerceIn(0f, (tilesY - 1).toFloat())
            val tx0 = tx.toInt().coerceIn(0, tilesX - 1)
            val ty0 = ty.toInt().coerceIn(0, tilesY - 1)
            val tx1 = (tx0 + 1).coerceIn(0, tilesX - 1)
            val ty1 = (ty0 + 1).coerceIn(0, tilesY - 1)
            val fx = tx - tx0; val fy = ty - ty0

            val v00 = luts[ty0][tx0][v].toFloat()
            val v10 = luts[ty0][tx1][v].toFloat()
            val v01 = luts[ty1][tx0][v].toFloat()
            val v11 = luts[ty1][tx1][v].toFloat()
            val equalized = (v00 * (1 - fx) * (1 - fy) + v10 * fx * (1 - fy) +
                             v01 * (1 - fx) * fy       + v11 * fx * fy).roundToInt().coerceIn(0, 255)

            // Blend original + equalized based on strength param
            (v + (equalized - v) * strength).roundToInt().coerceIn(0, 255)
        }
    }

    // --- Step 2.5: Unsharp mask — enhances fine details before edge detection ---
    // Subtracts a blurred copy from the original: result = original + strength*(original - blurred)

    private fun unsharpMask(gray: IntArray, w: Int, h: Int, strength: Float): IntArray {
        val blurred = gaussianBlur(gray, w, h, 2)
        return IntArray(gray.size) { i ->
            (gray[i] + strength * (gray[i] - blurred[i])).roundToInt().coerceIn(0, 255)
        }
    }

    // --- Step 3: Gaussian blur (separable, O(n*r)) ---

    private fun blurRadius(param: Float): Int = (param * 5 + 1).roundToInt().coerceIn(1, 6)

    private fun gaussianBlur(gray: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius <= 1) return gray
        val kernel = gaussianKernel(radius)
        val tmp = FloatArray(gray.size)
        // Horizontal pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f; var wt = 0f
                for (k in kernel.indices) {
                    val nx = (x + k - radius).coerceIn(0, w - 1)
                    val kv = kernel[k]
                    sum += gray[y * w + nx] * kv; wt += kv
                }
                tmp[y * w + x] = sum / wt
            }
        }
        val out = IntArray(gray.size)
        // Vertical pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f; var wt = 0f
                for (k in kernel.indices) {
                    val ny = (y + k - radius).coerceIn(0, h - 1)
                    val kv = kernel[k]
                    sum += tmp[ny * w + x] * kv; wt += kv
                }
                out[y * w + x] = (sum / wt).roundToInt().coerceIn(0, 255)
            }
        }
        return out
    }

    private fun gaussianKernel(radius: Int): FloatArray {
        val size = radius * 2 + 1
        val sigma = radius / 2.0
        var sum = 0f
        val k = FloatArray(size) { i ->
            val x = (i - radius).toDouble()
            Math.exp(-x * x / (2 * sigma * sigma)).toFloat().also { sum += it }
        }
        return FloatArray(size) { k[it] / sum }
    }

    // --- Step 4: Sobel ---

    private data class SobelResult(val magnitude: FloatArray, val angles: FloatArray)

    private fun sobelEdges(gray: IntArray, w: Int, h: Int): SobelResult {
        val mag = FloatArray(w * h)
        val ang = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val tl = gray[(y-1)*w+(x-1)].toFloat(); val tc = gray[(y-1)*w+x].toFloat(); val tr = gray[(y-1)*w+(x+1)].toFloat()
                val ml = gray[y*w+(x-1)].toFloat()                                          ; val mr = gray[y*w+(x+1)].toFloat()
                val bl = gray[(y+1)*w+(x-1)].toFloat(); val bc = gray[(y+1)*w+x].toFloat(); val br = gray[(y+1)*w+(x+1)].toFloat()
                val sx = -tl - 2*ml - bl + tr + 2*mr + br
                val sy = -tl - 2*tc - tr + bl + 2*bc + br
                mag[y*w+x] = sqrt(sx*sx + sy*sy)
                ang[y*w+x] = atan2(sy, sx).toFloat()
            }
        }
        val max = mag.max().takeIf { it > 0f } ?: 1f
        for (i in mag.indices) mag[i] /= max
        return SobelResult(mag, ang)
    }

    // --- Step 5: Non-maximum suppression → 1-px-wide edges ---

    private fun nonMaxSuppression(mag: FloatArray, angles: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(mag.size)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val deg = ((angles[idx] * 180f / PI.toFloat()) + 180f) % 180f
                val (n1, n2) = when {
                    deg < 22.5f || deg >= 157.5f -> mag[y*w+(x-1)] to mag[y*w+(x+1)]
                    deg < 67.5f                  -> mag[(y-1)*w+(x+1)] to mag[(y+1)*w+(x-1)]
                    deg < 112.5f                 -> mag[(y-1)*w+x] to mag[(y+1)*w+x]
                    else                         -> mag[(y-1)*w+(x-1)] to mag[(y+1)*w+(x+1)]
                }
                out[idx] = if (mag[idx] >= n1 && mag[idx] >= n2) mag[idx] else 0f
            }
        }
        return out
    }

    // --- Step 6: Hysteresis threshold (8-connected BFS) ---

    private fun hysteresisThreshold(suppressed: FloatArray, w: Int, h: Int, threshold: Float, edgeConnectivity: Float = 0.4f): IntArray {
        // Map slider 0..1 → high: 0.08..0.58 — wider usable range
        val high = threshold * 0.5f + 0.08f
        // edgeConnectivity: low/high ratio — higher = more weak edges connected (0.1..0.7)
        val low  = high * (edgeConnectivity * 0.6f + 0.1f)
        val STRONG = 2; val WEAK = 1
        val label = IntArray(suppressed.size) { i ->
            when {
                suppressed[i] >= high -> STRONG
                suppressed[i] >= low  -> WEAK
                else                  -> 0
            }
        }
        val queue = ArrayDeque<Int>()
        for (i in label.indices) if (label[i] == STRONG) queue.add(i)
        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val y = idx / w; val x = idx % w
            for (dy in -1..1) for (dx in -1..1) {
                if (dy == 0 && dx == 0) continue
                val ny = y + dy; val nx = x + dx
                if (ny < 0 || ny >= h || nx < 0 || nx >= w) continue
                val ni = ny * w + nx
                if (label[ni] == WEAK) { label[ni] = STRONG; queue.add(ni) }
            }
        }
        return IntArray(suppressed.size) { i -> if (label[i] == STRONG) 0 else 255 }
    }

    // --- Step 7: Erosion — removes isolated noise dots ---

    private fun erode(edges: IntArray, w: Int, h: Int): IntArray {
        val out = edges.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                if (edges[y * w + x] != 0) continue
                var n = 0
                if (edges[(y-1)*w+x] == 0) n++
                if (edges[(y+1)*w+x] == 0) n++
                if (edges[y*w+(x-1)] == 0) n++
                if (edges[y*w+(x+1)] == 0) n++
                if (n < 2) out[y * w + x] = 255
            }
        }
        return out
    }

    // --- Step 8: Dilation ---

    private fun dilationSize(param: Float): Int = (param * 2).roundToInt()

    private fun dilate(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius == 0) return src
        val out = src.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (src[y*w+x] != 0) continue
                for (dy in -radius..radius)
                    for (dx in -radius..radius)
                        out[(y+dy).coerceIn(0,h-1)*w+(x+dx).coerceIn(0,w-1)] = 0
            }
        }
        return out
    }

    // --- Step 9: Invert ---

    private fun invert(src: IntArray): IntArray = IntArray(src.size) { 255 - src[it] }

    // --- Output ---

    fun toBitmap(pixels: IntArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(IntArray(pixels.size) { i -> val v = pixels[i]; Color.rgb(v, v, v) }, 0, w, 0, 0, w, h)
        return bmp
    }

    fun processAndConvert(source: Bitmap, params: StencilParams): Bitmap = process(source, params)

    fun toGrayscaleBitmap(source: Bitmap): Bitmap {
        val w = source.width; val h = source.height
        val gray = toGrayscale(source)
        return toBitmap(gray, w, h)
    }
}
