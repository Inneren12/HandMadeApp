package com.appforcross.editor.palette

import android.graphics.Bitmap
import android.graphics.Color
import com.handmadeapp.analysis.Masks
import com.handmadeapp.logging.Logger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

data class S7Sample(
    val x: Int,
    val y: Int,
    val oklab: FloatArray,
    val zone: S7SamplingSpec.Zone,
    val E: Float,
    val R: Float,
    val N: Float,
    val w: Float
)

data class S7SamplingResult(
    val samples: List<S7Sample>,
    val roiHist: Map<S7SamplingSpec.Zone, Int>,
    val params: Map<String, Any?>
)

object S7Sampler {
    fun run(previewBitmap: Bitmap, masks: Masks, deviceTier: String, seed: Long): S7SamplingResult {
        S7ThreadGuard.assertBackground("s7.sampler.run")
        val width = previewBitmap.width
        val height = previewBitmap.height
        val totalPixels = width * height
        require(totalPixels > 0) { "Bitmap is empty" }

        val tier = S7SamplingSpec.DeviceTier.fromKey(deviceTier)
        val target = S7SamplingSpec.targetSamplesForTier(deviceTier)

        val logStartData = mapOf(
            "Nsamp_target" to target,
            "device_tier" to tier.key,
            "seed" to seed,
            "w_roi" to S7SamplingSpec.ROI_WEIGHTS.mapValues { it.value },
            "betas" to mapOf(
                "edge" to S7SamplingSpec.BETA_EDGE,
                "band" to S7SamplingSpec.BETA_BAND,
                "noise" to S7SamplingSpec.BETA_NOISE
            )
        )
        Logger.i("PALETTE", "sampling.start", logStartData)

        val luma = extractLuma(previewBitmap)
        val edgeEnergy = normalize(computeSobelMagnitude(luma, width, height))
        val noiseValues = normalize(computeLaplacianAbs(luma, width, height))
        val bandingRisk = computeBandingRisk(luma, width, height)

        val edgeMask = bitmapToFloatArray(masks.edge, width, height)
        val flatMask = bitmapToFloatArray(masks.flat, width, height)
        val hiTexFineMask = bitmapToFloatArray(masks.hiTexFine, width, height)
        val hiTexCoarseMask = bitmapToFloatArray(masks.hiTexCoarse, width, height)
        val skinMask = bitmapToFloatArray(masks.skin, width, height)
        val skyMask = bitmapToFloatArray(masks.sky, width, height)

        val zoneBuckets = LinkedHashMap<S7SamplingSpec.Zone, MutableList<Int>>().apply {
            S7SamplingSpec.Zone.entries.forEach { put(it, ArrayList()) }
        }
        val zoneWeights = S7SamplingSpec.ROI_WEIGHTS

        for (idx in 0 until totalPixels) {
            val zone = resolveZone(
                idx,
                edgeMask,
                flatMask,
                hiTexFineMask,
                hiTexCoarseMask,
                skinMask,
                skyMask
            )
            zoneBuckets[zone]?.add(idx)
        }

        val available = zoneBuckets.values.sumOf { it.size }
        val effectiveTarget = min(target, available)
        val coverageOk = effectiveTarget >= target
        val coverageReason = if (coverageOk) null else if (available == 0) "no_pixels" else "insufficient_pixels"

        val zoneTargets = allocatePerZone(zoneBuckets, zoneWeights, effectiveTarget)

        val rng = Random(seed)
        val samples = ArrayList<S7Sample>(effectiveTarget)
        val roiHist = LinkedHashMap<S7SamplingSpec.Zone, Int>()
        for ((zone, bucket) in zoneBuckets) {
            val need = zoneTargets[zone] ?: 0
            if (need <= 0 || bucket.isEmpty()) {
                roiHist[zone] = 0
                continue
            }
            bucket.shuffle(rng)
            val takeCount = min(need, bucket.size)
            for (i in 0 until takeCount) {
                val idx = bucket[i]
                val x = idx % width
                val y = idx / width
                val color = previewBitmap.getPixel(x, y)
                val ok = colorToOklab(color)
                val E = edgeEnergy[idx]
                val R = bandingRisk[idx]
                val N = noiseValues[idx]
                val w = computeWeight(zoneWeights[zone] ?: 1f, E, R, N)
                samples.add(
                    S7Sample(
                        x = x,
                        y = y,
                        oklab = ok,
                        zone = zone,
                        E = E,
                        R = R,
                        N = N,
                        w = w
                    )
                )
            }
            roiHist[zone] = takeCount
        }

        val params = LinkedHashMap<String, Any?>().apply {
            put("algo", "S7.1-sampling-v1")
            put("device_tier", tier.key)
            put("Nsamp_target", target)
            put("Nsamp_real", samples.size)
            put("seed", seed)
            put("w_roi", zoneWeights.mapValues { it.value })
            put(
                "betas",
                mapOf(
                    "edge" to S7SamplingSpec.BETA_EDGE,
                    "band" to S7SamplingSpec.BETA_BAND,
                    "noise" to S7SamplingSpec.BETA_NOISE
                )
            )
            put("coverage_ok", coverageOk)
            if (coverageReason != null) put("coverage_reason", coverageReason)
        }

        val logDoneData = LinkedHashMap<String, Any?>().apply {
            put("Nsamp_real", samples.size)
            put("roi_hist", roiHist.mapKeys { it.key.name })
            put("coverage_ok", coverageOk)
            if (coverageReason != null) put("reason", coverageReason)
        }
        Logger.i("PALETTE", "sampling.done", logDoneData)

        return S7SamplingResult(samples, roiHist, params)
    }

    private fun computeWeight(wRoi: Float, E: Float, R: Float, N: Float): Float {
        val termEdge = 1f + S7SamplingSpec.BETA_EDGE * E
        val termBand = 1f + S7SamplingSpec.BETA_BAND * R
        val termNoise = 1f + S7SamplingSpec.BETA_NOISE * N
        return wRoi * termEdge * termBand / termNoise
    }

    private fun resolveZone(
        idx: Int,
        edgeMask: FloatArray,
        flatMask: FloatArray,
        hiTexFineMask: FloatArray,
        hiTexCoarseMask: FloatArray,
        skinMask: FloatArray,
        skyMask: FloatArray
    ): S7SamplingSpec.Zone {
        val skin = skinMask[idx]
        if (skin >= 0.55f) return S7SamplingSpec.Zone.SKIN
        val sky = skyMask[idx]
        if (sky >= 0.55f) return S7SamplingSpec.Zone.SKY
        val edge = edgeMask[idx]
        if (edge >= 0.35f) return S7SamplingSpec.Zone.EDGE
        val hiTex = max(hiTexFineMask[idx], hiTexCoarseMask[idx])
        if (hiTex >= 0.45f) return S7SamplingSpec.Zone.HITEX
        val flat = flatMask[idx]
        return if (flat >= 0.40f) {
            S7SamplingSpec.Zone.FLAT
        } else {
            // fallback — назначим по доминирующей маске
            when {
                hiTex >= 0.25f -> S7SamplingSpec.Zone.HITEX
                edge >= 0.20f -> S7SamplingSpec.Zone.EDGE
                else -> S7SamplingSpec.Zone.FLAT
            }
        }
    }

    private fun allocatePerZone(
        zoneBuckets: Map<S7SamplingSpec.Zone, List<Int>>,
        zoneWeights: Map<S7SamplingSpec.Zone, Float>,
        target: Int
    ): Map<S7SamplingSpec.Zone, Int> {
        if (target <= 0) return zoneBuckets.mapValues { 0 }
        val weightSum = zoneBuckets.entries.sumOf { entry ->
            val count = entry.value.size
            val w = zoneWeights[entry.key] ?: 1f
            (count * w).toDouble()
        }
        if (weightSum <= 0.0) return zoneBuckets.mapValues { 0 }

        val alloc = LinkedHashMap<S7SamplingSpec.Zone, Int>()
        var allocated = 0
        for ((zone, bucket) in zoneBuckets) {
            val count = bucket.size
            if (count == 0) {
                alloc[zone] = 0
                continue
            }
            val weight = zoneWeights[zone] ?: 1f
            val fraction = (count * weight) / weightSum
            val desired = (target * fraction).roundToInt()
            val value = min(count, desired)
            alloc[zone] = value
            allocated += value
        }

        val totalAvailable = zoneBuckets.values.sumOf { it.size }
        val totalTarget = min(target, totalAvailable)
        if (allocated > totalTarget) {
            // trim to target
            var overflow = allocated - totalTarget
            S7SamplingSpec.DEFAULT_ZONE_ORDER.forEach { zone ->
                if (overflow <= 0) return@forEach
                val current = alloc[zone] ?: 0
                if (current <= 0) return@forEach
                val reduce = min(current, overflow)
                alloc[zone] = current - reduce
                overflow -= reduce
            }
            if (overflow > 0) {
                val zones = alloc.keys
                for (zone in zones) {
                    if (overflow <= 0) break
                    val current = alloc[zone] ?: 0
                    if (current <= 0) continue
                    val reduce = min(current, overflow)
                    alloc[zone] = current - reduce
                    overflow -= reduce
                }
            }
        } else if (allocated < totalTarget) {
            var remaining = totalTarget - allocated
            val zonesByCapacity = zoneBuckets.entries.sortedByDescending { entry ->
                (zoneWeights[entry.key] ?: 1f) * entry.value.size
            }
            for (entry in zonesByCapacity) {
                if (remaining <= 0) break
                val zone = entry.key
                val capacity = entry.value.size
                val current = alloc[zone] ?: 0
                if (capacity <= current) continue
                val canAdd = capacity - current
                val add = min(canAdd, remaining)
                alloc[zone] = current + add
                remaining -= add
            }
        }
        return alloc
    }

    private fun computeBandingRisk(luma: FloatArray, width: Int, height: Int): FloatArray {
        val n = luma.size
        val variance = FloatArray(n)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                var sumSq = 0f
                var count = 0
                for (dy in -1..1) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    val base = yy * width
                    for (dx in -1..1) {
                        val xx = (x + dx).coerceIn(0, width - 1)
                        val v = luma[base + xx]
                        sum += v
                        sumSq += v * v
                        count++
                    }
                }
                val mean = sum / count
                val varValue = max(0f, sumSq / count - mean * mean)
                variance[y * width + x] = varValue
            }
        }
        var maxVar = 0f
        for (v in variance) if (v > maxVar) maxVar = v
        val denom = if (maxVar > 1e-6f) maxVar else 1f
        val risk = FloatArray(n)
        for (i in 0 until n) {
            val norm = (variance[i] / denom).coerceIn(0f, 1f)
            risk[i] = 1f - norm
        }
        return risk
    }

    private fun computeSobelMagnitude(luma: FloatArray, width: Int, height: Int): FloatArray {
        val n = luma.size
        val out = FloatArray(n)
        for (y in 1 until height - 1) {
            var idx = y * width + 1
            for (x in 1 until width - 1) {
                val a = luma[idx - width - 1]
                val b = luma[idx - width]
                val c = luma[idx - width + 1]
                val d = luma[idx - 1]
                val f = luma[idx + 1]
                val g = luma[idx + width - 1]
                val h = luma[idx + width]
                val i = luma[idx + width + 1]
                val gx = -a - 2 * d - g + c + 2 * f + i
                val gy = -a - 2 * b - c + g + 2 * h + i
                val mag = sqrt(gx * gx + gy * gy)
                out[idx] = mag
                idx++
            }
        }
        return out
    }

    private fun computeLaplacianAbs(luma: FloatArray, width: Int, height: Int): FloatArray {
        val n = luma.size
        val out = FloatArray(n)
        for (y in 1 until height - 1) {
            var idx = y * width + 1
            for (x in 1 until width - 1) {
                val center = luma[idx]
                val lap = luma[idx - 1] + luma[idx + 1] + luma[idx - width] + luma[idx + width] - 4 * center
                out[idx] = abs(lap)
                idx++
            }
        }
        return out
    }

    private fun normalize(values: FloatArray): FloatArray {
        var maxVal = 0f
        for (v in values) if (v > maxVal) maxVal = v
        if (maxVal <= 1e-6f) return FloatArray(values.size)
        val out = FloatArray(values.size)
        val inv = 1f / maxVal
        for (i in values.indices) {
            out[i] = (values[i] * inv).coerceIn(0f, 1f)
        }
        return out
    }

    private fun bitmapToFloatArray(bitmap: Bitmap, width: Int, height: Int): FloatArray {
        require(bitmap.width == width && bitmap.height == height) {
            "Mask dimensions ${bitmap.width}x${bitmap.height} mismatch expected ${width}x${height}"
        }
        val arr = FloatArray(width * height)
        val row = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            var idx = y * width
            for (x in 0 until width) {
                val c = row[x]
                val value = Color.alpha(c)
                arr[idx++] = (value / 255f).coerceIn(0f, 1f)
            }
        }
        return arr
    }

    private fun extractLuma(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val luma = FloatArray(width * height)
        val row = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            var idx = y * width
            for (x in 0 until width) {
                val color = row[x]
                val r = srgbToLinear(Color.red(color) / 255f)
                val g = srgbToLinear(Color.green(color) / 255f)
                val b = srgbToLinear(Color.blue(color) / 255f)
                luma[idx++] = 0.2126f * r + 0.7152f * g + 0.0722f * b
            }
        }
        return luma
    }

    private fun srgbToLinear(v: Float): Float {
        return if (v <= 0.04045f) {
            v / 12.92f
        } else {
            ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }
    }

    private fun colorToOklab(color: Int): FloatArray {
        val r = srgbToLinear(Color.red(color) / 255f)
        val g = srgbToLinear(Color.green(color) / 255f)
        val b = srgbToLinear(Color.blue(color) / 255f)
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
        val lRoot = cbrt(l)
        val mRoot = cbrt(m)
        val sRoot = cbrt(s)
        val L = 0.2104542553f * lRoot + 0.7936177850f * mRoot - 0.0040720468f * sRoot
        val A = 1.9779984951f * lRoot - 2.4285922050f * mRoot + 0.4505937099f * sRoot
        val B = 0.0259040371f * lRoot + 0.7827717662f * mRoot - 0.8086757660f * sRoot
        return floatArrayOf(L, A, B)
    }

    private fun cbrt(value: Float): Float {
        return if (value <= 0f) {
            0f
        } else {
            value.toDouble().pow(1.0 / 3.0).toFloat()
        }
    }
}
