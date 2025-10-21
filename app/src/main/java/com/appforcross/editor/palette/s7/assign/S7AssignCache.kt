package com.appforcross.editor.palette.s7.assign

import com.appforcross.editor.palette.S7Sample
import com.handmadeapp.quant.PaletteQuantBuffers
import java.io.Closeable
import java.util.ArrayList
import java.util.HashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class S7AssignCache private constructor(
    private val samples: List<S7Sample>,
    private val tileWidth: Int,
    private val tileHeight: Int,
    private val tileColumns: Int,
    private val tileRows: Int,
    private val tileIndices: IntArray,
    private val tileToSamples: Array<IntArray>,
    private val weights: FloatArray,
    private val riskWeights: FloatArray,
    private val errorThreshold: Float,
    private val paletteLabs: Array<FloatArray>,
    private val owners: IntArray,
    private val secondOwners: IntArray,
    private val d2min: FloatArray,
    private val d2second: FloatArray,
    private val errorPerTile: FloatArray,
    private val perColorImportance: DoubleArray,
    private var totalImportance: Double,
    private var bandNumerator: Double,
    private val bandDenominator: Double,
    private val invalidTiles: BooleanArray,
    private val buffers: PaletteQuantBuffers.Workspace
) : Closeable {

    val ownerIndices: IntArray get() = owners
    val minDistancesSquared: FloatArray get() = d2min
    val secondOwnerIndices: IntArray get() = secondOwners
    val secondDistancesSquared: FloatArray get() = d2second
    val tileErrors: FloatArray get() = errorPerTile
    val columns: Int get() = tileColumns
    val rows: Int get() = tileRows
    val tileW: Int get() = tileWidth
    val tileH: Int get() = tileHeight

    fun copy(): S7AssignCache {
        val sampleCount = owners.size
        val paletteSize = paletteLabs.size
        val tileCount = tileColumns * tileRows
        val workspace = PaletteQuantBuffers.acquire(sampleCount, paletteSize, tileCount)
        try {
            val tileIndexCopy = workspace.tileIndices
            tileIndices.copyInto(tileIndexCopy)
            val ownersCopy = workspace.owners
            owners.copyInto(ownersCopy)
            val secondOwnersCopy = workspace.secondOwners
            secondOwners.copyInto(secondOwnersCopy)
            val d2MinCopy = workspace.d2min
            d2min.copyInto(d2MinCopy)
            val d2SecondCopy = workspace.d2second
            d2second.copyInto(d2SecondCopy)
            val errorPerTileCopy = workspace.errorPerTile
            errorPerTile.copyInto(errorPerTileCopy)
            val weightsCopy = workspace.weights
            weights.copyInto(weightsCopy)
            val riskCopy = workspace.riskWeights
            riskWeights.copyInto(riskCopy)
            val importanceCopy = workspace.perColorImportance
            perColorImportance.copyInto(importanceCopy)
            val invalidCopy = workspace.invalidTiles
            invalidTiles.copyInto(invalidCopy)
            return S7AssignCache(
                samples = samples,
                tileWidth = tileWidth,
                tileHeight = tileHeight,
                tileColumns = tileColumns,
                tileRows = tileRows,
                tileIndices = tileIndexCopy,
                tileToSamples = tileToSamples,
                weights = weightsCopy,
                riskWeights = riskCopy,
                errorThreshold = errorThreshold,
                paletteLabs = Array(paletteLabs.size) { paletteLabs[it].copyOf() },
                owners = ownersCopy,
                secondOwners = secondOwnersCopy,
                d2min = d2MinCopy,
                d2second = d2SecondCopy,
                errorPerTile = errorPerTileCopy,
                perColorImportance = importanceCopy,
                totalImportance = totalImportance,
                bandNumerator = bandNumerator,
                bandDenominator = bandDenominator,
                invalidTiles = invalidCopy,
                buffers = workspace
            )
        } catch (t: Throwable) {
            workspace.close()
            throw t
        }
    }

    fun updateWithColor(colorIdx: Int, lab: FloatArray, threshold: Float = errorThreshold) {
        if (colorIdx !in paletteLabs.indices) return
        paletteLabs[colorIdx] = lab.copyOf()
        val mask = BooleanArray(tileColumns * tileRows)
        for (i in invalidTiles.indices) {
            if (invalidTiles[i]) mask[i] = true
        }
        for (idx in owners.indices) {
            if (owners[idx] == colorIdx) {
                mask[tileIndices[idx]] = true
            }
        }
        if (threshold.isFinite() && threshold > 0f) {
            for (tile in errorPerTile.indices) {
                if (errorPerTile[tile] >= threshold) {
                    mask[tile] = true
                }
            }
        }
        val tiles = mask.indices.filter { mask[it] }.toIntArray()
        if (tiles.isEmpty()) return
        recomputeTiles(tiles)
    }

    fun invalidateTiles(indices: IntArray) {
        for (tile in indices) {
            if (tile in invalidTiles.indices) {
                invalidTiles[tile] = true
            }
        }
    }

    fun invalidateAll() {
        invalidTiles.fill(true)
    }

    fun recomputeAll() {
        val tiles = IntArray(tileColumns * tileRows) { it }
        recomputeTiles(tiles)
    }

    fun buildSummary(): AssignmentSummarySnapshot {
        val nearest = owners.copyOf()
        val nearestDist = FloatArray(d2min.size) { sqrtSafe(d2min[it]) }
        val second = secondOwners.copyOf()
        val secondDist = FloatArray(d2second.size) { sqrtSafe(d2second[it]) }
        val perColorLists = Array(paletteLabs.size) { ArrayList<Int>() }
        for (idx in owners.indices) {
            val owner = owners[idx]
            if (owner >= 0) {
                perColorLists[owner].add(idx)
            }
        }
        val perColorSamples = Array(perColorLists.size) { i ->
            perColorLists[i].toIntArray()
        }
        val de95 = percentile(nearestDist, 0.95)
        val gbi = if (bandDenominator > 0.0) {
            (bandNumerator / bandDenominator).toFloat()
        } else {
            0f
        }
        return AssignmentSummarySnapshot(
            nearest = nearest,
            nearestDist = nearestDist,
            second = second,
            secondDist = secondDist,
            perColorSamples = perColorSamples,
            perColorImportance = perColorImportance.copyOf(),
            totalImportance = totalImportance,
            de95 = de95,
            gbi = gbi
        )
    }

    fun snapshotTileErrors(): S7TileErrorMap {
        return S7TileErrorMap(
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            columns = tileColumns,
            rows = tileRows,
            errors = errorPerTile.copyOf()
        )
    }

    private fun recomputeTiles(tiles: IntArray) {
        if (tiles.isEmpty()) return
        for (tile in tiles) {
            recomputeTile(tile)
        }
        totalImportance = perColorImportance.sum()
        if (bandNumerator < 0.0) {
            bandNumerator = 0.0
        }
    }

    private fun recomputeTile(tileIdx: Int) {
        if (tileIdx !in tileToSamples.indices) return
        val sampleIndices = tileToSamples[tileIdx]
        if (sampleIndices.isEmpty()) {
            errorPerTile[tileIdx] = 0f
            invalidTiles[tileIdx] = false
            return
        }
        for (sampleIdx in sampleIndices) {
            val currentOwner = owners[sampleIdx]
            if (currentOwner >= 0) {
                val oldDist = sqrtSafe(d2min[sampleIdx])
                val weight = weights[sampleIdx]
                val contribution = (oldDist * weight).toDouble()
                perColorImportance[currentOwner] = (perColorImportance[currentOwner] - contribution).coerceAtLeast(0.0)
                bandNumerator -= (oldDist * riskWeights[sampleIdx]).toDouble()
            }
        }
        var tileError = 0.0
        var tileWeight = 0.0
        if (paletteLabs.isEmpty()) {
            for (sampleIdx in sampleIndices) {
                owners[sampleIdx] = -1
                secondOwners[sampleIdx] = -1
                d2min[sampleIdx] = Float.POSITIVE_INFINITY
                d2second[sampleIdx] = Float.POSITIVE_INFINITY
            }
            errorPerTile[tileIdx] = 0f
            invalidTiles[tileIdx] = false
            return
        }
        for (sampleIdx in sampleIndices) {
            val sample = samples[sampleIdx]
            val lab = sample.oklab
            var bestIdx = 0
            var bestD2 = Float.POSITIVE_INFINITY
            var secondIdx = -1
            var secondD2 = Float.POSITIVE_INFINITY
            for (colorIdx in paletteLabs.indices) {
                val d2 = deltaESquared(lab, paletteLabs[colorIdx])
                if (d2 < bestD2) {
                    secondD2 = bestD2
                    secondIdx = bestIdx
                    bestD2 = d2
                    bestIdx = colorIdx
                } else if (d2 < secondD2) {
                    secondD2 = d2
                    secondIdx = colorIdx
                }
            }
            owners[sampleIdx] = bestIdx
            d2min[sampleIdx] = bestD2
            secondOwners[sampleIdx] = secondIdx
            d2second[sampleIdx] = secondD2
            val dist = sqrtSafe(bestD2)
            val weight = weights[sampleIdx]
            val contribution = (dist * weight).toDouble()
            perColorImportance[bestIdx] += contribution
            bandNumerator += (dist * riskWeights[sampleIdx]).toDouble()
            tileError += dist * weight
            tileWeight += weight.toDouble()
        }
        errorPerTile[tileIdx] = if (tileWeight > 0.0) (tileError / tileWeight).toFloat() else 0f
        invalidTiles[tileIdx] = false
    }

    override fun close() {
        buffers.close()
    }

    companion object {
        private fun sqrtSafe(value: Float): Float {
            return if (value.isFinite()) {
                sqrt(value.toDouble()).toFloat()
            } else {
                Float.POSITIVE_INFINITY
            }
        }

        private fun deltaESquared(a: FloatArray, b: FloatArray): Float {
            return deltaESquared(a[0], a[1], a[2], b[0], b[1], b[2])
        }

        private fun deltaESquared(L1: Float, a1: Float, b1: Float, L2: Float, a2: Float, b2: Float): Float {
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
            return (deltaE * deltaE).toFloat()
        }

        private fun hueAngle(b: Double, ap: Double): Double {
            if (ap == 0.0 && b == 0.0) return 0.0
            var angle = kotlin.math.atan2(b, ap)
            if (angle < 0.0) angle += 2.0 * PI
            return angle
        }

        private fun hueDelta(c1p: Double, c2p: Double, h1p: Double, h2p: Double): Double {
            if (c1p * c2p == 0.0) return 0.0
            var diff = h2p - h1p
            if (abs(diff) > PI) {
                diff += if (diff > 0) -2.0 * PI else 2.0 * PI
            }
            return diff
        }

        private fun meanHue(h1p: Double, h2p: Double, c1p: Double, c2p: Double): Double {
            return when {
                c1p * c2p == 0.0 -> h1p + h2p
                abs(h1p - h2p) <= PI -> (h1p + h2p) * 0.5
                (h1p + h2p) < 2.0 * PI -> (h1p + h2p + 2.0 * PI) * 0.5
                else -> (h1p + h2p - 2.0 * PI) * 0.5
            }
        }

        private fun degToRad(value: Double): Double = value * PI / 180.0

        private fun percentile(values: FloatArray, p: Double): Float {
            if (values.isEmpty()) return 0f
            val sorted = values.map { it.toDouble() }.sorted()
            if (sorted.isEmpty()) return 0f
            val n = sorted.size
            if (n == 1) return sorted[0].toFloat()
            val pos = p * (n - 1)
            val lower = pos.toInt()
            val upper = minOf(n - 1, lower + 1)
            val weight = pos - lower
            val value = sorted[lower] * (1.0 - weight) + sorted[upper] * weight
            return value.toFloat()
        }

        fun create(
            samples: List<S7Sample>,
            paletteLabs: List<FloatArray>,
            tileWidth: Int,
            tileHeight: Int,
            threshold: Float
        ): S7AssignCache {
            val width = (samples.maxOfOrNull { it.x } ?: 0) + 1
            val height = (samples.maxOfOrNull { it.y } ?: 0) + 1
            val cols = maxOf(1, (width + tileWidth - 1) / tileWidth)
            val rows = maxOf(1, (height + tileHeight - 1) / tileHeight)
            val tileCount = cols * rows
            val sampleCount = samples.size
            val paletteSize = paletteLabs.size
            val workspace = PaletteQuantBuffers.acquire(sampleCount, paletteSize, tileCount)
            try {
                val tileIdx = workspace.tileIndices
                val counts = workspace.scratchCounts
                    ?: error("scratchCounts not allocated")
                counts.fill(0)
                for (i in samples.indices) {
                    val sx = samples[i].x.coerceAtLeast(0)
                    val sy = samples[i].y.coerceAtLeast(0)
                    val cx = (sx / tileWidth).coerceIn(0, cols - 1)
                    val cy = (sy / tileHeight).coerceIn(0, rows - 1)
                    val index = cy * cols + cx
                    tileIdx[i] = index
                    counts[index]++
                }
                val tileSamples = Array(tileCount) { IntArray(counts[it]) }
                val offsets = workspace.scratchOffsets
                    ?: error("scratchOffsets not allocated")
                offsets.fill(0)
                for (i in samples.indices) {
                    val tile = tileIdx[i]
                    val offset = offsets[tile]
                    tileSamples[tile][offset] = i
                    offsets[tile] = offset + 1
                }
                val palette = Array(paletteSize) { idx -> paletteLabs[idx].copyOf() }
                val owners = workspace.owners
                owners.fill(-1)
                val secondOwners = workspace.secondOwners
                secondOwners.fill(-1)
                val d2min = workspace.d2min
                d2min.fill(Float.POSITIVE_INFINITY)
                val d2second = workspace.d2second
                d2second.fill(Float.POSITIVE_INFINITY)
                val errorPerTile = workspace.errorPerTile
                errorPerTile.fill(0f)
                val weights = workspace.weights
                for (i in samples.indices) {
                    weights[i] = samples[i].w
                }
                val riskWeights = workspace.riskWeights
                for (i in samples.indices) {
                    riskWeights[i] = samples[i].R * samples[i].w
                }
                val perColorImportance = workspace.perColorImportance
                perColorImportance.fill(0.0)
                val invalidTiles = workspace.invalidTiles
                invalidTiles.fill(false)
                val bandDenominator = riskWeights.sumOf { it.toDouble() }
                val cache = S7AssignCache(
                    samples = samples,
                    tileWidth = tileWidth,
                    tileHeight = tileHeight,
                    tileColumns = cols,
                    tileRows = rows,
                    tileIndices = tileIdx,
                    tileToSamples = tileSamples,
                    weights = weights,
                    riskWeights = riskWeights,
                    errorThreshold = threshold,
                    paletteLabs = palette,
                    owners = owners,
                    secondOwners = secondOwners,
                    d2min = d2min,
                    d2second = d2second,
                    errorPerTile = errorPerTile,
                    perColorImportance = perColorImportance,
                    totalImportance = 0.0,
                    bandNumerator = 0.0,
                    bandDenominator = bandDenominator,
                    invalidTiles = invalidTiles,
                    buffers = workspace
                )
                cache.recomputeAll()
                return cache
            } catch (t: Throwable) {
                workspace.close()
                throw t
            }
        }
    }
}

class AssignmentSummarySnapshot(
    val nearest: IntArray,
    val nearestDist: FloatArray,
    val second: IntArray,
    val secondDist: FloatArray,
    val perColorSamples: Array<IntArray>,
    val perColorImportance: DoubleArray,
    val totalImportance: Double,
    val de95: Float,
    val gbi: Float
)

class S7TileErrorMap(
    val tileWidth: Int,
    val tileHeight: Int,
    val columns: Int,
    val rows: Int,
    val errors: FloatArray
) {
    fun toDiagnostics(): Map<String, Any?> {
        val summary = HashMap<String, Any?>()
        summary["tile_w"] = tileWidth
        summary["tile_h"] = tileHeight
        summary["cols"] = columns
        summary["rows"] = rows
        summary["errors"] = errors.copyOf()
        val maxError = errors.maxOrNull() ?: 0f
        val meanError = if (errors.isNotEmpty()) errors.sum() / errors.size else 0f
        summary["max_error"] = maxError
        summary["mean_error"] = meanError
        return summary
    }
}
