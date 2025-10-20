package com.appforcross.editor.palette

import android.graphics.Color
import com.appforcross.editor.config.FeatureFlags
import com.handmadeapp.logging.Logger
import kotlin.collections.buildMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val ALGO_VERSION = "S7.3-greedy-v1"
private const val EPS = 1e-6
private const val CLUSTER_MIN = 50

data class S7GreedyIterStat(
    val k: Int,
    val zone: S7SamplingSpec.Zone,
    val impSum: Double,
    val clusterSize: Int,
    val medoidOkLab: FloatArray,
    val nearestDe: Float,
    val added: Boolean,
    val reason: String?
)

data class S7GreedyResidual(
    val deMed: Float,
    val de95: Float,
    val impTotal: Double
)

data class S7GreedyResult(
    val colors: List<S7InitColor>,
    val iters: List<S7GreedyIterStat>,
    val residual: S7GreedyResidual,
    val params: Map<String, Any?>
)

object S7Greedy {
    fun run(
        sampling: S7SamplingResult,
        init: S7InitResult,
        kTry: Int,
        seed: Long
    ): S7GreedyResult {
        FeatureFlags.logGreedyFlag()
        require(kTry >= 0) { "kTry must be non-negative" }
        val samples = sampling.samples
        require(samples.isNotEmpty()) { "Sampling is empty" }
        val startTime = System.currentTimeMillis()
        val initialColors = init.colors.map { copyColor(it) }
        val palette = ArrayList<S7InitColor>(initialColors)
        val initialSize = initialColors.size
        val params = LinkedHashMap<String, Any?>()
        val roiQuotas = S7GreedySpec.roi_quotas
        val roiCounts = LinkedHashMap<S7InitSpec.PaletteZone, Int>().apply {
            S7InitSpec.PaletteZone.entries.forEach { zone ->
                if (zone != S7InitSpec.PaletteZone.NEUTRAL) put(zone, 0)
            }
        }
        for (color in palette) {
            val role = color.role
            if (roiCounts.containsKey(role)) {
                roiCounts[role] = (roiCounts[role] ?: 0) + 1
            }
        }

        val logStart = linkedMapOf<String, Any?>(
            "algo" to ALGO_VERSION,
            "seed" to seed,
            "K0" to initialSize,
            "kTry" to kTry,
            "BL" to S7GreedySpec.B_L,
            "Ba" to S7GreedySpec.B_a,
            "Bb" to S7GreedySpec.B_b,
            "gamma_quota" to S7GreedySpec.gamma_quota,
            "s_dup" to S7GreedySpec.s_dup,
            "r0" to S7GreedySpec.R0,
            "r_min" to S7GreedySpec.r_min,
            "r_decay" to S7GreedySpec.r_decay,
            "quotas" to roiQuotas.mapKeys { it.key.name }
        )
        Logger.i("PALETTE", "greedy.start", logStart)

        params.putAll(logStart)
        params["algo_version"] = ALGO_VERSION

        val binIndices = computeBinIndices(samples)
        var currentResidual = computeResidual(samples, palette)
        params["imp_total_start"] = currentResidual.impTotal
        params["de95_start"] = currentResidual.de95
        params["deMed_start"] = currentResidual.deMed

        val iterStats = ArrayList<S7GreedyIterStat>()
        var consecutiveSkips = 0
        var rejectedDup = 0
        var addedTotal = 0

        if (kTry > 0) {
            repeat(kTry) {
                val currentK = palette.size
                val histogram = buildHistogram(samples, currentResidual.importances, binIndices)
                val selection = selectBin(histogram, roiCounts, roiQuotas, currentK)
                val radius = adaptiveRadius(currentK, initialSize)
                val cluster = collectCluster(
                    samples,
                    binIndices,
                    selection.bin,
                    radius
                )
                val clusterImp = cluster.sumOf { currentResidual.importances[it] }
                val clusterSize = cluster.size
                var reason: String? = if (clusterSize < CLUSTER_MIN) "empty_cluster" else selection.reason
                var added = false
                var medoidLab = floatArrayOf(0f, 0f, 0f)
                var nearestDe = Float.POSITIVE_INFINITY

                Logger.d(
                    "PALETTE",
                    "greedy.cluster",
                    mapOf(
                        "k" to currentK,
                        "bin" to mapOf(
                            "l" to selection.bin.l,
                            "a" to selection.bin.a,
                            "b" to selection.bin.b
                        ),
                        "score" to selection.score,
                        "imp_sum" to selection.impSum,
                        "zone" to selection.zone.name,
                        "cluster_size" to clusterSize,
                        "quota_shortfall" to selection.shortfall
                    )
                )

                if (clusterSize >= CLUSTER_MIN) {
                    val medoidIndex = findMedoid(samples, cluster)
                    if (medoidIndex >= 0) {
                        val sample = samples[medoidIndex]
                        medoidLab = sample.oklab.copyOf()
                        nearestDe = distanceToPalette(medoidLab, palette)
                        val zoneRole = selection.zone.toPaletteZone()
                        val action: String
                        if (nearestDe < S7GreedySpec.s_dup) {
                            action = "reject"
                            reason = "dup"
                            rejectedDup++
                        } else {
                            val (argb, clipped) = labToArgb(medoidLab)
                            val newColor = S7InitColor(
                                okLab = medoidLab.copyOf(),
                                sRGB = argb,
                                protected = false,
                                role = zoneRole,
                                spreadMin = Float.POSITIVE_INFINITY,
                                clipped = clipped
                            )
                            palette.add(newColor)
                            if (roiCounts.containsKey(zoneRole)) {
                                roiCounts[zoneRole] = (roiCounts[zoneRole] ?: 0) + 1
                            }
                            added = true
                            addedTotal++
                            action = "add"
                            currentResidual = computeResidual(samples, palette)
                        }
                        Logger.d(
                            "PALETTE",
                            "greedy.medoid",
                            buildMap {
                                put("k", currentK)
                                put("okLab", mapOf(
                                    "L" to medoidLab[0],
                                    "a" to medoidLab[1],
                                    "b" to medoidLab[2]
                                ))
                                put("nearestDe", nearestDe)
                                put("action", action)
                                reason?.let { put("reason", it) }
                            }
                        )
                    }
                }

                if (!added) {
                    consecutiveSkips++
                } else {
                    consecutiveSkips = 0
                }

                iterStats += S7GreedyIterStat(
                    k = palette.size,
                    zone = selection.zone,
                    impSum = clusterImp,
                    clusterSize = clusterSize,
                    medoidOkLab = medoidLab,
                    nearestDe = nearestDe,
                    added = added,
                    reason = reason
                )

                Logger.i(
                    "PALETTE",
                    "greedy.iter.done",
                    mapOf(
                        "k" to palette.size,
                        "added" to added,
                        "de95" to currentResidual.de95,
                        "deMed" to currentResidual.deMed,
                        "impTotal" to currentResidual.impTotal
                    )
                )

                if (!added && consecutiveSkips >= 3) {
                    break
                }
            }
        }

        params["iterations"] = iterStats.size
        params["added_total"] = addedTotal
        params["rejected_dup"] = rejectedDup
        params["residual_errors"] = currentResidual.errors.copyOf()
        params["residual_importance"] = currentResidual.importances.copyOf()

        val residual = S7GreedyResidual(
            deMed = currentResidual.deMed,
            de95 = currentResidual.de95,
            impTotal = currentResidual.impTotal
        )

        val elapsed = System.currentTimeMillis() - startTime
        Logger.i(
            "PALETTE",
            "greedy.done",
            mapOf(
                "K_final" to palette.size,
                "added_total" to addedTotal,
                "rejected_dup" to rejectedDup,
                "de95_final" to residual.de95,
                "time_ms" to elapsed
            )
        )

        return S7GreedyResult(
            colors = palette.map { copyColor(it) },
            iters = iterStats.toList(),
            residual = residual,
            params = params
        )
    }

    private data class BinKey(val l: Int, val a: Int, val b: Int)

    private data class ResidualData(
        val errors: FloatArray,
        val importances: DoubleArray,
        val impTotal: Double,
        val deMed: Float,
        val de95: Float
    )

    private data class HistogramBin(
        val impSum: Double,
        val zone: S7SamplingSpec.Zone,
        val shortfall: Double,
        val score: Double,
        val bin: BinKey,
        val reason: String?
    )

    private data class Selection(
        val bin: BinKey,
        val zone: S7SamplingSpec.Zone,
        val shortfall: Double,
        val score: Double,
        val impSum: Double,
        val reason: String?
    )

    private fun computeBinIndices(samples: List<S7Sample>): Array<BinKey> {
        val result = Array(samples.size) { BinKey(0, 0, 0) }
        for (i in samples.indices) {
            val lab = samples[i].oklab
            val l = floor(lab[0].coerceIn(0f, 0.9999f) * S7GreedySpec.B_L).toInt()
            val a = floor(((lab[1] + 0.5f).coerceIn(0f, 0.9999f)) * S7GreedySpec.B_a).toInt()
            val b = floor(((lab[2] + 0.5f).coerceIn(0f, 0.9999f)) * S7GreedySpec.B_b).toInt()
            result[i] = BinKey(l.coerceIn(0, S7GreedySpec.B_L - 1), a.coerceIn(0, S7GreedySpec.B_a - 1), b.coerceIn(0, S7GreedySpec.B_b - 1))
        }
        return result
    }

    private fun computeResidual(samples: List<S7Sample>, palette: List<S7InitColor>): ResidualData {
        val errors = FloatArray(samples.size)
        val importances = DoubleArray(samples.size)
        var impTotal = 0.0
        if (palette.isEmpty()) {
            return ResidualData(errors, importances, impTotal, 0f, 0f)
        }
        for (i in samples.indices) {
            val sample = samples[i]
            var best = Float.POSITIVE_INFINITY
            for (color in palette) {
                val d = deltaE(sample.oklab, color.okLab)
                if (d < best) best = d
            }
            val imp = best * sample.w
            errors[i] = best
            importances[i] = imp.toDouble()
            impTotal += importances[i]
        }
        val deMed = percentile(errors, 0.5)
        val de95 = percentile(errors, 0.95)
        return ResidualData(errors, importances, impTotal, deMed, de95)
    }

    private fun percentile(values: FloatArray, p: Double): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.map { it.toDouble() }.sorted()
        if (sorted.isEmpty()) return 0f
        val n = sorted.size
        if (n == 1) return sorted[0].toFloat()
        val pos = p * (n - 1)
        val lower = pos.toInt()
        val upper = min(n - 1, lower + 1)
        val weight = pos - lower
        val value = sorted[lower] * (1.0 - weight) + sorted[upper] * weight
        return value.toFloat()
    }

    private fun buildHistogram(
        samples: List<S7Sample>,
        importances: DoubleArray,
        binIndices: Array<BinKey>
    ): Map<BinKey, HistogramBin> {
        val zoneOrder = S7SamplingSpec.Zone.entries
        val zoneImp = HashMap<BinKey, DoubleArray>()
        val totals = HashMap<BinKey, Double>()
        for (i in samples.indices) {
            val bin = binIndices[i]
            val imp = importances[i]
            if (imp <= 0.0) continue
            val key = BinKey(bin.l, bin.a, bin.b)
            val arr = zoneImp.getOrPut(key) { DoubleArray(zoneOrder.size) }
            val zoneIdx = samples[i].zone.ordinal
            arr[zoneIdx] += imp
            totals[key] = (totals[key] ?: 0.0) + imp
        }
        val result = LinkedHashMap<BinKey, HistogramBin>()
        for ((key, total) in totals.entries.sortedWith(compareBy({ it.key.l }, { it.key.a }, { it.key.b }))) {
            val arr = zoneImp[key] ?: continue
            var maxZoneIdx = 0
            var maxZoneImp = -1.0
            for (idx in arr.indices) {
                val value = arr[idx]
                if (value > maxZoneImp + EPS) {
                    maxZoneImp = value
                    maxZoneIdx = idx
                } else if (abs(value - maxZoneImp) <= EPS && idx < maxZoneIdx) {
                    maxZoneIdx = idx
                }
            }
            val zone = zoneOrder[maxZoneIdx]
            result[key] = HistogramBin(
                impSum = total,
                zone = zone,
                shortfall = 0.0,
                score = total,
                bin = key,
                reason = null
            )
        }
        return result
    }

    private fun selectBin(
        histogram: Map<BinKey, HistogramBin>,
        roiCounts: Map<S7InitSpec.PaletteZone, Int>,
        roiQuotas: Map<S7InitSpec.PaletteZone, Float>,
        k: Int
    ): Selection {
        var best: HistogramBin? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var bestReason: String? = null
        var bestShortfall = 0.0
        var maxImpBin: HistogramBin? = null
        var maxImp = Double.NEGATIVE_INFINITY
        for (bin in histogram.values) {
            if (bin.impSum <= 0.0) continue
            if (bin.impSum > maxImp + EPS || (abs(bin.impSum - maxImp) <= EPS && isBinLess(bin.bin, maxImpBin?.bin))) {
                maxImp = bin.impSum
                maxImpBin = bin
            }
            val targetZone = bin.zone.toPaletteZone()
            val quotaTarget = roiQuotas[targetZone] ?: 0f
            val used = if (quotaTarget <= 0f || k <= 0) 0.0 else {
                val count = roiCounts[targetZone] ?: 0
                count.toDouble() / k.toDouble()
            }
            val shortfall = if (quotaTarget <= 0f) {
                0.0
            } else {
                val need = (quotaTarget - used).coerceAtLeast(0.0)
                (need / max(quotaTarget.toDouble(), EPS)).coerceIn(0.0, 1.0)
            }
            val score = bin.impSum * (1.0 + S7GreedySpec.gamma_quota * shortfall)
            val reason = if (shortfall > EPS) "quota_redress" else null
            if (score > bestScore + EPS || (abs(score - bestScore) <= EPS && isBinLess(bin.bin, best?.bin))) {
                best = bin.copy(shortfall = shortfall, score = score, reason = reason)
                bestScore = score
                bestShortfall = shortfall
                bestReason = if (reason != null && maxImpBin != null && maxImpBin.bin != bin.bin) reason else null
            }
        }
        val selected = best ?: HistogramBin(0.0, S7SamplingSpec.Zone.FLAT, 0.0, 0.0, BinKey(0, 0, 0), null)
        val reason = bestReason
        return Selection(
            bin = selected.bin,
            zone = selected.zone,
            shortfall = bestShortfall,
            score = selected.score,
            impSum = selected.impSum,
            reason = reason
        )
    }

    private fun isBinLess(a: BinKey?, b: BinKey?): Boolean {
        if (b == null) return true
        if (a == null) return false
        return when {
            a.l != b.l -> a.l < b.l
            a.a != b.a -> a.a < b.a
            else -> a.b < b.b
        }
    }

    private fun adaptiveRadius(k: Int, k0: Int): Float {
        val decay = S7GreedySpec.r_decay * (k - k0)
        return max(S7GreedySpec.r_min, S7GreedySpec.R0 - decay)
    }

    private fun collectCluster(
        samples: List<S7Sample>,
        binIndices: Array<BinKey>,
        center: BinKey,
        radius: Float
    ): List<Int> {
        val cluster = ArrayList<Int>()
        if (samples.isEmpty()) return cluster
        val centerLab = binCenter(center)
        val r = radius
        for (i in samples.indices) {
            val bin = binIndices[i]
            val dl = abs(bin.l - center.l)
            val da = abs(bin.a - center.a)
            val db = abs(bin.b - center.b)
            val neighbor = (dl + da + db == 0) || (dl + da + db == 1 && max(max(dl, da), db) == 1)
            val inRadius = deltaE(samples[i].oklab, centerLab) <= r
            if (neighbor || inRadius) {
                cluster.add(i)
            }
        }
        return cluster
    }

    private fun binCenter(bin: BinKey): FloatArray {
        val l = (bin.l + 0.5f) / S7GreedySpec.B_L
        val a = -0.5f + (bin.a + 0.5f) / S7GreedySpec.B_a
        val b = -0.5f + (bin.b + 0.5f) / S7GreedySpec.B_b
        return floatArrayOf(l, a, b)
    }

    private fun findMedoid(samples: List<S7Sample>, cluster: List<Int>): Int {
        if (cluster.isEmpty()) return -1
        var bestIdx = cluster[0]
        var bestScore = Double.POSITIVE_INFINITY
        for (candidate in cluster) {
            val lab = samples[candidate].oklab
            var sum = 0.0
            for (other in cluster) {
                val weight = samples[other].w.toDouble()
                val d = deltaE(lab, samples[other].oklab).toDouble()
                sum += weight * d
            }
            if (sum < bestScore - EPS || (abs(sum - bestScore) <= EPS && candidate < bestIdx)) {
                bestScore = sum
                bestIdx = candidate
            }
        }
        return bestIdx
    }

    private fun distanceToPalette(lab: FloatArray, palette: List<S7InitColor>): Float {
        var best = Float.POSITIVE_INFINITY
        for (color in palette) {
            val d = deltaE(lab, color.okLab)
            if (d < best) best = d
        }
        return best
    }

    private fun copyColor(color: S7InitColor): S7InitColor {
        return S7InitColor(
            okLab = color.okLab.copyOf(),
            sRGB = color.sRGB,
            protected = color.protected,
            role = color.role,
            spreadMin = color.spreadMin,
            clipped = color.clipped
        )
    }

    private fun deltaE(lab1: FloatArray, lab2: FloatArray): Float {
        return deltaE(lab1[0], lab1[1], lab1[2], lab2[0], lab2[1], lab2[2])
    }

    private fun deltaE(L1: Float, a1: Float, b1: Float, L2: Float, a2: Float, b2: Float): Float {
        val L1d = L1.toDouble()
        val a1d = a1.toDouble()
        val b1d = b1.toDouble()
        val L2d = L2.toDouble()
        val a2d = a2.toDouble()
        val b2d = b2.toDouble()
        val avgL = (L1d + L2d) * 0.5
        val c1 = sqrt(a1d * a1d + b1d * b1d)
        val c2 = sqrt(a2d * a2d + b2d * b2d)
        val avgC = (c1 + c2) * 0.5
        val g = 0.5 * (1.0 - sqrt(avgC.pow(7.0) / (avgC.pow(7.0) + 25.0.pow(7.0))))
        val a1p = a1d * (1.0 + g)
        val a2p = a2d * (1.0 + g)
        val c1p = sqrt(a1p * a1p + b1d * b1d)
        val c2p = sqrt(a2p * a2p + b2d * b2d)
        val avgCp = (c1p + c2p) * 0.5
        val h1p = hueAngle(b1d, a1p)
        val h2p = hueAngle(b2d, a2p)
        val deltahp = hueDelta(c1p, c2p, h1p, h2p)
        val deltaLp = L2d - L1d
        val deltaCp = c2p - c1p
        val deltaHp = 2.0 * sqrt(c1p * c2p) * sin(deltahp / 2.0)
        val avgHp = meanHue(h1p, h2p, c1p, c2p)
        val t = 1.0 - 0.17 * cos(avgHp - degToRad(30.0)) + 0.24 * cos(2.0 * avgHp) +
            0.32 * cos(3.0 * avgHp + degToRad(6.0)) - 0.20 * cos(4.0 * avgHp - degToRad(63.0))
        val sl = 1.0 + (0.015 * (avgL - 50.0).pow(2.0)) / sqrt(20.0 + (avgL - 50.0).pow(2.0))
        val sc = 1.0 + 0.045 * avgCp
        val sh = 1.0 + 0.015 * avgCp * t
        val deltaTheta = degToRad(30.0) * exp(-((avgHp - degToRad(275.0)) / degToRad(25.0)).pow(2.0))
        val rc = 2.0 * sqrt(avgCp.pow(7.0) / (avgCp.pow(7.0) + 25.0.pow(7.0)))
        val rt = -sin(2.0 * deltaTheta) * rc
        val termL = deltaLp / (1.0 * sl)
        val termC = deltaCp / (1.0 * sc)
        val termH = deltaHp / (1.0 * sh)
        val deltaE = sqrt(termL * termL + termC * termC + termH * termH + rt * termC * termH)
        return deltaE.toFloat()
    }

    private fun hueAngle(b: Double, ap: Double): Double {
        if (ap == 0.0 && b == 0.0) return 0.0
        var angle = atan2(b, ap)
        if (angle < 0.0) angle += 2.0 * PI
        return angle
    }

    private fun hueDelta(c1p: Double, c2p: Double, h1p: Double, h2p: Double): Double {
        if (c1p * c2p == 0.0) return 0.0
        val diff = h2p - h1p
        return when {
            diff > PI -> (h2p - 2.0 * PI) - h1p
            diff < -PI -> (h2p + 2.0 * PI) - h1p
            else -> diff
        }
    }

    private fun meanHue(h1p: Double, h2p: Double, c1p: Double, c2p: Double): Double {
        if (c1p * c2p == 0.0) return h1p + h2p
        val diff = abs(h1p - h2p)
        return when {
            diff <= PI -> (h1p + h2p) / 2.0
            h1p + h2p < 2.0 * PI -> (h1p + h2p + 2.0 * PI) / 2.0
            else -> (h1p + h2p - 2.0 * PI) / 2.0
        }
    }

    private fun degToRad(deg: Double): Double = deg / 180.0 * PI

    private fun labToArgb(lab: FloatArray): Pair<Int, Boolean> {
        val L = lab[0].toDouble()
        val a = lab[1].toDouble()
        val b = lab[2].toDouble()
        val lScaled = L * 100.0
        val x = lScaled + 0.3963377774 * a + 0.2158037573 * b
        val y = lScaled - 0.1055613458 * a - 0.0638541728 * b
        val z = lScaled - 0.0894841775 * a - 1.2914855480 * b
        val xr = x / 100.0
        val yr = y / 100.0
        val zr = z / 100.0
        val fx = pivotLab(xr)
        val fy = pivotLab(yr)
        val fz = pivotLab(zr)
        val r =  4.0767416621 * fx - 3.3077115913 * fy + 0.2309699292 * fz
        val g = -1.2684380046 * fx + 2.6097574011 * fy - 0.3413193965 * fz
        val bVal = -0.0041960863 * fx - 0.7034186147 * fy + 1.7076147010 * fz
        val srgb = doubleArrayOf(r, g, bVal).map { toSrgb(it) }
        val clipped = srgb.any { it < 0.0 || it > 1.0 }
        val r8 = (srgb[0].coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceIn(0, 255)
        val g8 = (srgb[1].coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceIn(0, 255)
        val b8 = (srgb[2].coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceIn(0, 255)
        val argb = Color.argb(255, r8, g8, b8)
        return argb to clipped
    }

    private fun pivotLab(value: Double): Double {
        val v = value / 100.0
        return when {
            v > 0.008856 -> v.pow(1.0 / 3.0)
            else -> (7.787 * v) + (16.0 / 116.0)
        }
    }

    private fun toSrgb(value: Double): Double {
        return when {
            value <= 0.0031308 -> 12.92 * value
            else -> 1.055 * value.pow(1.0 / 2.4) - 0.055
        }
    }

    private fun S7SamplingSpec.Zone.toPaletteZone(): S7InitSpec.PaletteZone = when (this) {
        S7SamplingSpec.Zone.SKIN -> S7InitSpec.PaletteZone.SKIN
        S7SamplingSpec.Zone.SKY -> S7InitSpec.PaletteZone.SKY
        S7SamplingSpec.Zone.EDGE -> S7InitSpec.PaletteZone.EDGE
        S7SamplingSpec.Zone.HITEX -> S7InitSpec.PaletteZone.HITEX
        S7SamplingSpec.Zone.FLAT -> S7InitSpec.PaletteZone.FLAT
    }
}
