package com.maddog.stencilforge.processor

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pipeline:
 *  1. Grayscale
 *  2. Contrast stretch
 *  3. Guided filter (edge-preserving, O(n) — replaces slow bilateral)
 *  4. Sobel gradients
 *  5. Non-maximum suppression → 1-px-wide edges
 *  6. Hysteresis threshold (double threshold + BFS)
 *  7. Erosion (removes isolated noise dots)
 *  8. Shadow simulation
 *  9. Dilation (line thickness)
 * 10. Optional inversion
 */
object StencilProcessor {

    fun process(source: Bitmap, params: StencilParams): Bitmap {
        val w = source.width
        val h = source.height

        val gray       = toGrayscale(source)
        val contrasted = applyContrast(gray, w, h, params.contrast)
        val filtered   = guidedFilter(contrasted, w, h, guidedRadius(params.blurRadius), guidedEps(params.blurRadius))
        val (mag, gx, gy, angles) = sobelEdges(filtered, w, h)
        val suppressed = nonMaxSuppression(mag, angles, w, h)
        val edges      = hysteresisThreshold(suppressed, w, h, params.edgeThreshold)
        val clean      = erode(edges, w, h)
        val withShadow = applyShadow(clean, gx, gy, w, h, params.shadowIntensity)
        val thick      = dilate(withShadow, w, h, dilationSize(params.lineThickness))
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

    // --- Step 2: Contrast stretch ---

    private fun applyContrast(gray: IntArray, w: Int, h: Int, amount: Float): IntArray {
        val factor = 1f + amount * 2.5f
        return IntArray(gray.size) { i ->
            val v = gray[i] / 255f
            val stretched = ((v - 0.5f) * factor + 0.5f).coerceIn(0f, 1f)
            (stretched * 255).roundToInt()
        }
    }

    // --- Step 3: Guided filter (edge-preserving, O(n)) ---
    // Uses box-filter mean/variance: each pass is a 1-D sliding window → O(w*h*r) total
    // but implemented as prefix sums so it's truly O(w*h) regardless of radius.
    // eps controls smoothing strength: high eps → more smoothing of textures.

    private fun guidedRadius(param: Float): Int = (param * 7 + 3).roundToInt().coerceIn(3, 10)
    private fun guidedEps(param: Float): Float  = (param * param * 0.08f + 0.005f)  // 0.005..0.085

    private fun guidedFilter(src: IntArray, w: Int, h: Int, r: Int, eps: Float): IntArray {
        // Normalize to 0..1
        val I = FloatArray(src.size) { src[it] / 255f }

        // Box-filter mean using prefix sums
        fun boxMean(arr: FloatArray): FloatArray {
            val tmp = FloatArray(arr.size)
            // horizontal
            for (y in 0 until h) {
                var sum = 0f
                for (x in 0..r) sum += arr[y * w + x.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    if (x > 0) {
                        sum -= arr[y * w + (x - r - 1).coerceAtLeast(0)]
                        if (x + r < w) sum += arr[y * w + (x + r)]
                    }
                    tmp[y * w + x] = sum / (2 * r + 1).toFloat()
                }
            }
            val out = FloatArray(arr.size)
            // vertical
            for (x in 0 until w) {
                var sum = 0f
                for (y in 0..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    if (y > 0) {
                        sum -= tmp[(y - r - 1).coerceAtLeast(0) * w + x]
                        if (y + r < h) sum += tmp[(y + r) * w + x]
                    }
                    out[y * w + x] = sum / (2 * r + 1).toFloat()
                }
            }
            return out
        }

        val meanI  = boxMean(I)
        val meanI2 = boxMean(FloatArray(I.size) { I[it] * I[it] })
        val varI   = FloatArray(I.size) { meanI2[it] - meanI[it] * meanI[it] }

        // Linear coefficients: a = var / (var + eps), b = mean*(1-a)
        val a = FloatArray(I.size) { varI[it] / (varI[it] + eps) }
        val b = FloatArray(I.size) { meanI[it] * (1f - a[it]) }

        val meanA = boxMean(a)
        val meanB = boxMean(b)

        return IntArray(I.size) { i ->
            ((meanA[i] * I[i] + meanB[i]) * 255f).roundToInt().coerceIn(0, 255)
        }
    }

    // --- Step 4: Sobel ---

    private data class SobelResult(
        val magnitude: FloatArray,
        val gx: FloatArray,
        val gy: FloatArray,
        val angles: FloatArray
    )

    private fun sobelEdges(gray: IntArray, w: Int, h: Int): SobelResult {
        val mag = FloatArray(w * h)
        val gx  = FloatArray(w * h)
        val gy  = FloatArray(w * h)
        val ang = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val tl = gray[(y-1)*w+(x-1)].toFloat(); val tc = gray[(y-1)*w+x].toFloat(); val tr = gray[(y-1)*w+(x+1)].toFloat()
                val ml = gray[y*w+(x-1)].toFloat();                                           val mr = gray[y*w+(x+1)].toFloat()
                val bl = gray[(y+1)*w+(x-1)].toFloat(); val bc = gray[(y+1)*w+x].toFloat(); val br = gray[(y+1)*w+(x+1)].toFloat()
                val sx = -tl - 2*ml - bl + tr + 2*mr + br
                val sy = -tl - 2*tc - tr + bl + 2*bc + br
                gx[y*w+x] = sx; gy[y*w+x] = sy
                mag[y*w+x] = sqrt(sx*sx + sy*sy)
                ang[y*w+x] = atan2(sy, sx).toFloat()
            }
        }
        val max = mag.max().takeIf { it > 0f } ?: 1f
        for (i in mag.indices) mag[i] /= max
        return SobelResult(mag, gx, gy, ang)
    }

    // --- Step 5: Non-maximum suppression ---

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

    // --- Step 6: Hysteresis threshold ---

    private fun hysteresisThreshold(suppressed: FloatArray, w: Int, h: Int, threshold: Float): IntArray {
        val high = threshold * 0.5f + 0.05f
        val low  = high * 0.35f

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
            for ((dy, dx) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                val ny = y + dy; val nx = x + dx
                if (ny < 0 || ny >= h || nx < 0 || nx >= w) continue
                val ni = ny * w + nx
                if (label[ni] == WEAK) { label[ni] = STRONG; queue.add(ni) }
            }
        }
        return IntArray(suppressed.size) { i -> if (label[i] == STRONG) 0 else 255 }
    }

    // --- Step 7: Erosion — kills isolated noise dots ---

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

    // --- Step 8: Shadow simulation ---

    private fun applyShadow(
        edges: IntArray, gx: FloatArray, gy: FloatArray,
        w: Int, h: Int, intensity: Float
    ): IntArray {
        if (intensity < 0.05f) return edges
        val out = edges.copyOf()
        val ss  = intensity * 0.85f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (out[idx] == 0) continue
                val len = sqrt(gx[idx]*gx[idx] + gy[idx]*gy[idx])
                if (len < 1f) continue
                val shade = ((len / 1020f).coerceIn(0f, 1f) * ss * 255f).roundToInt()
                if (shade > 30) out[idx] = (255 - shade).coerceAtLeast(0)
            }
        }
        return out
    }

    // --- Step 9: Dilation ---

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

    // --- Step 10: Invert ---

    private fun invert(src: IntArray): IntArray = IntArray(src.size) { 255 - src[it] }

    // --- Output ---

    fun toBitmap(pixels: IntArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(IntArray(pixels.size) { i -> val v = pixels[i]; Color.rgb(v, v, v) }, 0, w, 0, 0, w, h)
        return bmp
    }

    fun processAndConvert(source: Bitmap, params: StencilParams): Bitmap = process(source, params)
}
