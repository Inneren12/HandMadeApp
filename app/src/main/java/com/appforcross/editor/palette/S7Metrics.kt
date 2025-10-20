package com.appforcross.editor.palette

import java.util.ArrayList
import java.util.HashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class S7Metrics(
    val k: Int,
    val de95: Float,
    val deMed: Float,
    val gbi: Float,
    val tc: Float,
    val isl: Float
)

internal class S7MetricsWorkspace(
    private val sampling: S7SamplingResult,
    private val maxK: Int,
    seed: Long
) {
    private val samples = sampling.samples
    private val sampleCount = samples.size
    private val bestErrors = FloatArray(sampleCount) { Float.POSITIVE_INFINITY }
    private val bestIndices = IntArray(sampleCount) { -1 }
    private val tcNeighbors: Array<IntArray>
    private val islNeighbors: Array<IntArray>
    private val tcCounts = IntArray(max(1, maxK))
    private val tcTouched = IntArray(max(1, maxK))

    init {
        tcNeighbors = buildNeighbors(includeDiagonals = false, maxRadius = 4, limit = 6)
        islNeighbors = buildNeighbors(includeDiagonals = true, maxRadius = 2, limit = 12)
    }

    fun updateWithColor(color: S7InitColor, index: Int) {
        if (index < 0 || index >= maxK) return
        val lab = color.okLab
        for (i in 0 until sampleCount) {
            val sample = samples[i]
            val d = deltaE(sample.oklab, lab)
            if (d < bestErrors[i]) {
                bestErrors[i] = d
                bestIndices[i] = index
            } else if (d == bestErrors[i] && (bestIndices[i] < 0 || index < bestIndices[i])) {
                bestIndices[i] = index
            }
        }
    }

    fun computeMetrics(currentK: Int): S7Metrics {
        if (currentK <= 0 || sampleCount == 0) {
            return S7Metrics(currentK, 0f, 0f, 0f, 0f, 0f)
        }
        val de95 = percentile(bestErrors.copyOf(), 0.95)
        val deMed = percentile(bestErrors.copyOf(), 0.5)
        var gbiCount = 0
        for (i in 0 until sampleCount) {
            val err = bestErrors[i]
            val grad = abs(samples[i].E)
            if (err >= S7KneedleSpec.tau_low && err <= S7KneedleSpec.tau_high && grad < S7KneedleSpec.g_low) {
                gbiCount++
            }
        }
        val gbi = if (sampleCount > 0) gbiCount.toFloat() / sampleCount.toFloat() else 0f
        val tc = computeTc(currentK)
        val isl = computeIsl(currentK)
        return S7Metrics(currentK, de95, deMed, gbi, tc, isl)
    }

    fun snapshotErrors(): FloatArray = bestErrors.copyOf()

    private fun computeTc(currentK: Int): Float {
        if (currentK <= 0 || sampleCount == 0) return 0f
        var mismatch = 0
        for (i in 0 until sampleCount) {
            val neighbors = tcNeighbors[i]
            if (neighbors.isEmpty()) continue
            var touchedCount = 0
            for (neighbor in neighbors) {
                val idx = bestIndices[neighbor]
                if (idx < 0 || idx >= currentK) continue
                if (tcCounts[idx] == 0) {
                    tcTouched[touchedCount++] = idx
                }
                tcCounts[idx]++
            }
            if (touchedCount == 0) continue
            var modeIdx = -1
            var modeCount = -1
            for (t in 0 until touchedCount) {
                val idx = tcTouched[t]
                val count = tcCounts[idx]
                if (count > modeCount || (count == modeCount && idx < modeIdx)) {
                    modeCount = count
                    modeIdx = idx
                }
            }
            val currentIdx = bestIndices[i]
            if (modeIdx >= 0 && currentIdx >= 0 && currentIdx < currentK && currentIdx != modeIdx) {
                mismatch++
            }
            for (t in 0 until touchedCount) {
                val idx = tcTouched[t]
                tcCounts[idx] = 0
            }
        }
        return mismatch.toFloat() / sampleCount.toFloat()
    }

    private fun computeIsl(currentK: Int): Float {
        if (currentK <= 0 || sampleCount == 0) return 0f
        val visited = BooleanArray(sampleCount)
        val queue = IntArray(sampleCount)
        var islands = 0
        for (i in 0 until sampleCount) {
            if (visited[i]) continue
            val label = bestIndices[i]
            if (label < 0 || label >= currentK) {
                visited[i] = true
                continue
            }
            var head = 0
            var tail = 0
            queue[tail++] = i
            visited[i] = true
            var size = 0
            while (head < tail) {
                val idx = queue[head++]
                size++
                val neighbors = islNeighbors[idx]
                for (neighbor in neighbors) {
                    if (visited[neighbor]) continue
                    val neighborLabel = bestIndices[neighbor]
                    if (neighborLabel != label) continue
                    visited[neighbor] = true
                    queue[tail++] = neighbor
                }
            }
            if (size >= MIN_ISLAND_SIZE) {
                islands++
            }
        }
        val norm = max(1f, sampleCount / 1000f)
        return islands.toFloat() / norm
    }

    private fun buildNeighbors(includeDiagonals: Boolean, maxRadius: Int, limit: Int): Array<IntArray> {
        val map = HashMap<Long, Int>(sampleCount)
        for (i in samples.indices) {
            val sample = samples[i]
            map[pack(sample.x, sample.y)] = i
        }
        val neighbors = Array(sampleCount) { IntArray(0) }
        for (i in samples.indices) {
            val sample = samples[i]
            val list = ArrayList<Int>()
            var radius = 1
            while (radius <= maxRadius && list.size < limit) {
                val range = -radius..radius
                for (dy in range) {
                    for (dx in range) {
                        if (dx == 0 && dy == 0) continue
                        val cond = if (includeDiagonals) {
                            max(abs(dx), abs(dy)) == radius
                        } else {
                            abs(dx) + abs(dy) == radius
                        }
                        if (!cond) continue
                        val key = pack(sample.x + dx, sample.y + dy)
                        val neighbor = map[key] ?: continue
                        if (neighbor == i) continue
                        if (!list.contains(neighbor)) {
                            list.add(neighbor)
                            if (list.size >= limit) break
                        }
                    }
                    if (list.size >= limit) break
                }
                radius++
            }
            neighbors[i] = if (list.isEmpty()) IntArray(0) else list.toIntArray()
        }
        return neighbors
    }

    private fun percentile(values: FloatArray, p: Double): Float {
        if (values.isEmpty()) return 0f
        values.sort()
        val n = values.size
        if (n == 1) return values[0]
        val pos = p * (n - 1)
        val lower = pos.toInt()
        val upper = min(n - 1, lower + 1)
        val weight = pos - lower
        val value = values[lower] * (1.0 - weight) + values[upper] * weight
        return value.toFloat()
    }

    private fun pack(x: Int, y: Int): Long {
        return (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
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
        val termL = deltaLp / sl
        val termC = deltaCp / sc
        val termH = deltaHp / sh
        val deltaE = sqrt(termL * termL + termC * termC + termH * termH + rt * termC * termH)
        return deltaE.toFloat()
    }

    private fun hueAngle(b: Double, ap: Double): Double {
        if (ap == 0.0 && b == 0.0) return 0.0
        var angle = kotlin.math.atan2(b, ap)
        if (angle < 0) angle += 2.0 * Math.PI
        return angle
    }

    private fun hueDelta(c1p: Double, c2p: Double, h1p: Double, h2p: Double): Double {
        if (c1p * c2p == 0.0) return 0.0
        val diff = h2p - h1p
        return when {
            kotlin.math.abs(diff) <= Math.PI -> diff
            diff > Math.PI -> diff - 2.0 * Math.PI
            diff < -Math.PI -> diff + 2.0 * Math.PI
            else -> diff
        }
    }

    private fun meanHue(h1p: Double, h2p: Double, c1p: Double, c2p: Double): Double {
        if (c1p * c2p == 0.0) return h1p + h2p
        val diff = kotlin.math.abs(h1p - h2p)
        return when {
            diff <= Math.PI -> (h1p + h2p) * 0.5
            (h1p + h2p) < 2.0 * Math.PI -> (h1p + h2p + 2.0 * Math.PI) * 0.5
            else -> (h1p + h2p - 2.0 * Math.PI) * 0.5
        }
    }

    private fun degToRad(value: Double): Double = value * Math.PI / 180.0

    companion object {
        private const val MIN_ISLAND_SIZE = 3
    }
}

object S7MetricsComputer {
    fun computeForK(
        sampling: S7SamplingResult,
        colorsK: List<S7InitColor>,
        seed: Long
    ): S7Metrics {
        val k = colorsK.size
        if (k == 0 || sampling.samples.isEmpty()) {
            return S7Metrics(k, 0f, 0f, 0f, 0f, 0f)
        }
        val workspace = S7MetricsWorkspace(sampling, k, seed)
        colorsK.forEachIndexed { index, color ->
            workspace.updateWithColor(color, index)
        }
        return workspace.computeMetrics(k)
    }
}
