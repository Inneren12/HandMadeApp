package com.appforcross.editor.palette

import com.appforcross.editor.config.FeatureFlags
import com.handmadeapp.logging.Logger
import java.util.ArrayList
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val ALGO_VERSION = "S7.5-kneedle-v1"
private const val EPS = 1e-6f

data class S7KneedleRow(
    val k: Int,
    val de95: Float,
    val deMed: Float,
    val gbi: Float,
    val tc: Float,
    val isl: Float,
    val gain: Float,
    val F: Float,
    val D: Float,
    val flags: String? = null
)

data class S7KneedleResult(
    val rows: List<S7KneedleRow>,
    val Kstar: Int,
    val reason: String,
    val params: Map<String, Any?>
)

object S7Kneedle {
    fun run(
        sampling: S7SamplingResult,
        paletteAfterS74: List<S7InitColor>,
        K0: Int,
        K_try: Int,
        seed: Long
    ): S7KneedleResult {
        S7ThreadGuard.assertBackground("s7.kneedle.run")
        FeatureFlags.logKneedleFlag()
        require(K_try <= paletteAfterS74.size) { "K_try exceeds palette size" }
        val startTime = System.currentTimeMillis()
        val startParams = linkedMapOf(
            "algo" to ALGO_VERSION,
            "alpha" to S7KneedleSpec.alpha,
            "beta" to S7KneedleSpec.beta,
            "lambda_TC" to S7KneedleSpec.lambdaTc,
            "lambda_ISL" to S7KneedleSpec.lambdaIsl,
            "tau_low" to S7KneedleSpec.tau_low,
            "tau_high" to S7KneedleSpec.tau_high,
            "g_low" to S7KneedleSpec.g_low,
            "tau_knee" to S7KneedleSpec.tau_knee,
            "tau_s" to S7KneedleSpec.tau_s,
            "tau_gain" to S7KneedleSpec.tau_gain,
            "K0" to K0,
            "K_try" to K_try,
            "seed" to seed,
            "smooth" to S7KneedleSpec.median_window,
            "de95_target" to S7KneedleSpec.de95_target
        )
        Logger.i("PALETTE", "kneedle.start", startParams)
        val params = LinkedHashMap<String, Any?>(startParams)
        val rows = ArrayList<S7KneedleRow>()
        if (sampling.samples.isEmpty() || K_try <= 0) {
            val resultRow = S7KneedleRow(
                k = max(0, K0),
                de95 = 0f,
                deMed = 0f,
                gbi = 0f,
                tc = 0f,
                isl = 0f,
                gain = 0f,
                F = 0f,
                D = 0f,
                flags = "K*"
            )
            val result = S7KneedleResult(listOf(resultRow), resultRow.k, "k_max", params)
            Logger.i(
                "PALETTE",
                "kneedle.done",
                mapOf(
                    "Kstar" to resultRow.k,
                    "de95" to 0f,
                    "gbi" to 0f,
                    "tc" to 0f,
                    "isl" to 0f,
                    "time_ms" to (System.currentTimeMillis() - startTime)
                )
            )
            return result
        }
        return try {
            val workspace = S7MetricsWorkspace(sampling, K_try, seed)
            val metricsByK = LinkedHashMap<Int, S7Metrics>()
            val errorsByK = LinkedHashMap<Int, FloatArray>()
            for (index in 0 until K_try) {
                workspace.updateWithColor(paletteAfterS74[index], index)
                val currentK = index + 1
                if (currentK >= K0) {
                    val metrics = workspace.computeMetrics(currentK)
                    metricsByK[currentK] = metrics
                    errorsByK[currentK] = workspace.snapshotErrors()
                    Logger.d(
                        "PALETTE",
                        "kneedle.metrics",
                        mapOf(
                            "k" to currentK,
                            "de95" to metrics.de95,
                            "deMed" to metrics.deMed,
                            "gbi" to metrics.gbi,
                            "tc" to metrics.tc,
                            "isl" to metrics.isl
                        )
                    )
                }
            }
            val kValues = metricsByK.keys.sorted()
            if (kValues.isEmpty()) {
                throw IllegalStateException("No metrics computed for requested K range")
            }
            var cumulative = 0f
            var prevMetrics: S7Metrics? = null
            for (k in kValues) {
                val metrics = metricsByK[k] ?: continue
                val gain = if (prevMetrics == null) {
                    0f
                } else {
                    computeGain(prevMetrics!!, metrics)
                }
                cumulative += gain
                rows += S7KneedleRow(
                    k = k,
                    de95 = metrics.de95,
                    deMed = metrics.deMed,
                    gbi = metrics.gbi,
                    tc = metrics.tc,
                    isl = metrics.isl,
                    gain = gain,
                    F = cumulative,
                    D = 0f,
                    flags = null
                )
                prevMetrics = metrics
            }
            val Fsmoothed = medianSmooth(rows.map { it.F }, S7KneedleSpec.median_window)
            val F0 = Fsmoothed.firstOrNull() ?: 0f
            val FEnd = Fsmoothed.lastOrNull() ?: F0
            val denomX = max(1, K_try - K0).toFloat()
            val denomY = if (abs(FEnd - F0) < EPS) 1f else (FEnd - F0)
            val updatedRows = ArrayList<S7KneedleRow>(rows.size)
            for (idx in rows.indices) {
                val row = rows[idx]
                val x = if (K_try == K0) 1f else (row.k - K0).toFloat() / denomX
                val y = if (abs(denomY) < EPS) 0f else (Fsmoothed[idx] - F0) / denomY
                val d = y - x
                updatedRows += row.copy(F = Fsmoothed[idx], D = d)
            }
            val maxGain = updatedRows.drop(1).maxOfOrNull { it.gain }?.takeIf { it > 0f } ?: 1f
            val sNorm = FloatArray(updatedRows.size) { idx ->
                if (idx == 0) 0f else updatedRows[idx].gain / maxGain
            }
            val (bestIdx, bestD) = findBestDeviation(updatedRows)
            val kneeOk = bestD >= S7KneedleSpec.tau_knee && hasSNormWindow(sNorm, bestIdx, S7KneedleSpec.tau_s)
            val lowGainK = detectLowGain(updatedRows)
            val earlyQualityK = detectEarlyQuality(updatedRows)
            params["Dmax"] = bestD
            params["low_gain_k"] = lowGainK
            params["early_quality_k"] = earlyQualityK
            val (kStar, reason) = if (kneeOk) {
                updatedRows[bestIdx].k to "knee"
            } else when {
                lowGainK != null -> lowGainK to "low_gain_3x"
                earlyQualityK != null -> earlyQualityK to "early_quality"
                else -> updatedRows.last().k to "k_max"
            }
            val guardMap = linkedMapOf(
                "low_gain_3x" to (lowGainK != null),
                "early_quality" to (earlyQualityK != null),
                "k_max" to (reason == "k_max")
            )
            val finalRows = updatedRows.map { row ->
                if (row.k == kStar) {
                    row.copy(flags = "K*")
                } else {
                    row
                }
            }
            val errors = errorsByK[kStar]?.copyOf()
            if (errors != null) {
                params["errors_kstar"] = errors
            }
            finalRows.forEach { row ->
                Logger.d(
                    "PALETTE",
                    "kneedle.gain",
                    mapOf(
                        "k" to row.k,
                        "gain" to row.gain,
                        "F" to row.F,
                        "D" to row.D
                    )
                )
            }
            Logger.i(
                "PALETTE",
                "kneedle.pick",
                mapOf(
                    "Kstar" to kStar,
                    "reason" to reason,
                    "Dmax" to bestD,
                    "tau_knee" to S7KneedleSpec.tau_knee,
                    "guards" to guardMap,
                    "tau_gain" to S7KneedleSpec.tau_gain
                )
            )
            val resultMetrics = metricsByK[kStar] ?: metricsByK[finalRows.last().k]
            val duration = System.currentTimeMillis() - startTime
            Logger.i(
                "PALETTE",
                "kneedle.done",
                mapOf(
                    "Kstar" to kStar,
                    "de95" to (resultMetrics?.de95 ?: 0f),
                    "gbi" to (resultMetrics?.gbi ?: 0f),
                    "tc" to (resultMetrics?.tc ?: 0f),
                    "isl" to (resultMetrics?.isl ?: 0f),
                    "time_ms" to duration
                )
            )
            S7KneedleResult(finalRows, kStar, reason, params)
        } catch (t: Throwable) {
            Logger.e(
                "PALETTE",
                "kneedle.fail",
                mapOf("stage" to "run", "err" to (t.message ?: t.toString())),
                err = t
            )
            throw t
        }
    }

    private fun computeGain(prev: S7Metrics, current: S7Metrics): Float {
        val g1 = prev.de95 - current.de95
        val g2 = prev.gbi - current.gbi
        val penalty = S7KneedleSpec.lambdaTc * current.tc + S7KneedleSpec.lambdaIsl * current.isl
        return S7KneedleSpec.alpha * g1 + S7KneedleSpec.beta * g2 - penalty
    }

    private fun medianSmooth(values: List<Float>, window: Int): FloatArray {
        if (window <= 1 || values.isEmpty()) return values.toFloatArray()
        val radius = window / 2
        val result = FloatArray(values.size)
        val buffer = ArrayList<Float>(window)
        for (i in values.indices) {
            buffer.clear()
            val start = max(0, i - radius)
            val end = min(values.lastIndex, i + radius)
            for (j in start..end) {
                buffer.add(values[j])
            }
            buffer.sort()
            result[i] = buffer[buffer.size / 2]
        }
        result[0] = values.first()
        result[result.lastIndex] = values.last()
        return result
    }

    private fun findBestDeviation(rows: List<S7KneedleRow>): Pair<Int, Float> {
        var bestIdx = 0
        var bestD = rows.firstOrNull()?.D ?: 0f
        for (i in rows.indices) {
            val d = rows[i].D
            if (d > bestD + EPS || (abs(d - bestD) <= EPS && rows[i].k < rows[bestIdx].k)) {
                bestIdx = i
                bestD = d
            }
        }
        return bestIdx to bestD
    }

    private fun hasSNormWindow(values: FloatArray, center: Int, threshold: Float): Boolean {
        if (values.isEmpty()) return false
        val windows = arrayOf(
            intArrayOf(center - 2, center - 1, center),
            intArrayOf(center - 1, center, center + 1),
            intArrayOf(center, center + 1, center + 2)
        )
        for (window in windows) {
            if (window.any { it < 0 || it >= values.size }) continue
            if (window.contains(center) && window.all { values[it] <= threshold }) {
                return true
            }
        }
        return false
    }

    private fun detectLowGain(rows: List<S7KneedleRow>): Int? {
        var streak = 0
        for (i in 1 until rows.size) {
            if (rows[i].gain < S7KneedleSpec.tau_gain) {
                streak++
                if (streak >= 3) {
                    return rows[i].k
                }
            } else {
                streak = 0
            }
        }
        return null
    }

    private fun detectEarlyQuality(rows: List<S7KneedleRow>): Int? {
        for (row in rows) {
            if (row.gbi < 0.03f && row.de95 <= S7KneedleSpec.de95_target) {
                return row.k
            }
        }
        return null
    }
}
