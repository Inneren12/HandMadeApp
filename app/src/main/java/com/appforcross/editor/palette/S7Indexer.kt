package com.appforcross.editor.palette

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.appforcross.editor.config.FeatureFlags
import com.appforcross.editor.palette.s7.S7WorkspacePool
import com.appforcross.editor.palette.s7.tiles.S7TileScheduler
import com.handmadeapp.analysis.Masks
import com.handmadeapp.diagnostics.DiagnosticsManager
import com.handmadeapp.logging.Logger
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class S7IndexStats(
    val kStar: Int,
    val countsPerColor: IntArray,
    val foreignZoneHits: Int,
    val edgeBreakPenaltySum: Double,
    val cohBonusSum: Double,
    val meanCost: Double,
    val timeMs: Long,
    val prepareMs: Long,
    val assignMs: Long,
    val ditherMs: Long,
    val totalMs: Long,
    val distEvalsTotal: Long,
    val ownerChanges: Long,
    val tilesUpdated: Int,
    val params: Map<String, Any?>
)

data class S7IndexResult(
    val width: Int,
    val height: Int,
    val kStar: Int,
    val indexBpp: Int,
    val indexPath: String,
    val previewPath: String,
    val legendCsvPath: String,
    val stats: S7IndexStats,
    val costHeatmapPath: String?,
    val gateConfig: DiagnosticsManager.S7GateConfig
)

object S7Indexer {

    fun run(
        ctx: Context,
        preScaledImage: Bitmap,
        masks: Masks,
        paletteK: List<S7InitColor>,
        seed: Long,
        deviceTier: String
    ): S7IndexResult {
        FeatureFlags.logIndexFlag()
        val totalStart = SystemClock.elapsedRealtime()
        val width = preScaledImage.width
        val height = preScaledImage.height
        require(width > 0 && height > 0) { "Image is empty" }
        val total = width * height
        val k = paletteK.size
        require(k > 0) { "Palette is empty" }

        val tileSpec = S7IndexSpec.tileForTier(deviceTier, width, height)
        val indexBpp = if (k <= 255) 8 else 16
        val bytesPerPixel = if (indexBpp == 8) 1 else 2

        val gateConfig = DiagnosticsManager.loadS7GateConfig(ctx)
        Logger.i(
            "PALETTE",
            "s7.gates",
            mapOf(
                "aa_mask" to gateConfig.aaMask,
                "gates" to gateConfig.toParamMap()["gates"]
            )
        )

        return S7WorkspacePool.acquire(total, k, bytesPerPixel).use { workspace ->
            val indexBuffer = workspace.indexBuffer
            val indexData = workspace.indexBytes
            val previewPixels = IntArray(total)
            val assigned = IntArray(total) { -1 }
            val counts = IntArray(k)
            val costs = workspace.costPlane

            val paletteLab = workspace.paletteLabData
            val paletteHues = workspace.paletteHueData
            val paletteRoles = ByteArray(k)
            val paletteColors = IntArray(k)
            for (i in 0 until k) {
                val lab = paletteK[i].okLab
                val base = i * 3
                paletteLab[base] = lab[0].toDouble()
                paletteLab[base + 1] = lab[1].toDouble()
                paletteLab[base + 2] = lab[2].toDouble()
                paletteHues[i] = atan2(lab[2].toDouble(), lab[1].toDouble())
                paletteRoles[i] = paletteK[i].role.ordinal.toByte()
                paletteColors[i] = paletteK[i].sRGB
            }

            val lPlane = workspace.lPlane
            val aPlane = workspace.aPlane
            val bPlane = workspace.bPlane
            val huePlane = workspace.huePlane
            val row = IntArray(width)
            for (y in 0 until height) {
                preScaledImage.getPixels(row, 0, width, 0, y, width, 1)
                var idx = y * width
                for (x in 0 until width) {
                    val argb = row[x]
                    val rLin = srgbToLinearDouble(Color.red(argb) / 255.0)
                    val gLin = srgbToLinearDouble(Color.green(argb) / 255.0)
                    val bLin = srgbToLinearDouble(Color.blue(argb) / 255.0)
                    val lVal = 0.4122214708 * rLin + 0.5363325363 * gLin + 0.0514459929 * bLin
                    val mVal = 0.2119034982 * rLin + 0.6806995451 * gLin + 0.1073969566 * bLin
                    val sVal = 0.0883024619 * rLin + 0.2817188376 * gLin + 0.6299787005 * bLin
                    val lRoot = cbrt(lVal.coerceAtLeast(0.0))
                    val mRoot = cbrt(mVal.coerceAtLeast(0.0))
                    val sRoot = cbrt(sVal.coerceAtLeast(0.0))
                    val L = 0.2104542553 * lRoot + 0.7936177850 * mRoot - 0.0040720468 * sRoot
                    val A = 1.9779984951 * lRoot - 2.4285922050 * mRoot + 0.4505937099 * sRoot
                    val B = 0.0259040371 * lRoot + 0.7827717662 * mRoot - 0.8086757660 * sRoot
                    lPlane[idx] = L
                    aPlane[idx] = A
                    bPlane[idx] = B
                    huePlane[idx] = atan2(B, A)
                    idx++
                }
            }

            val masksPlanes = workspace.masks
            val edgeMask = masksPlanes[0]
            val flatMask = masksPlanes[1]
            val hiTexFineMask = masksPlanes[2]
            val hiTexCoarseMask = masksPlanes[3]
            val skinMask = masksPlanes[4]
            val skyMask = masksPlanes[5]

            bitmapToFloatArray(masks.edge, width, height, edgeMask)
            bitmapToFloatArray(masks.flat, width, height, flatMask)
            bitmapToFloatArray(masks.hiTexFine, width, height, hiTexFineMask)
            bitmapToFloatArray(masks.hiTexCoarse, width, height, hiTexCoarseMask)
            bitmapToFloatArray(masks.skin, width, height, skinMask)
            bitmapToFloatArray(masks.sky, width, height, skyMask)

            val zones = ByteArray(total)
            for (idx in 0 until total) {
                zones[idx] = resolveZoneOrdinal(
                    idx,
                    edgeMask,
                    flatMask,
                    hiTexFineMask,
                    hiTexCoarseMask,
                    skinMask,
                    skyMask
                ).toByte()
            }


            val scheduler = S7TileScheduler(
                width = width,
                height = height,
                tileWidth = tileSpec.width,
                tileHeight = tileSpec.height,
                overlap = S7IndexSpec.TILE_OVERLAP
            )
            val workerCount = computeWorkerCount(scheduler.tileCount)

            val workspaceMetrics = linkedMapOf(
                "width" to width,
                "height" to height,
                "total" to total,
                "k" to k,
                "bytes_per_pixel" to bytesPerPixel,
                "index_bytes" to indexBuffer.capacity(),
                "double_planes" to workspace.doublePlaneCount,
                "float_planes" to workspace.floatPlaneCount
            )
            val baselineStartNs = System.nanoTime()
            val baselineStartLog = LinkedHashMap(workspaceMetrics).apply { put("phase", "start") }
            Logger.i("PALETTE", "s7.baseline", baselineStartLog)

            val startParams = linkedMapOf(
                "algo" to S7IndexSpec.ALGO_VERSION,
                "Kstar" to k,
                "alpha0" to S7IndexSpec.ALPHA0,
                "beta_fz" to S7IndexSpec.BETA_FZ,
                "beta_edge" to S7IndexSpec.BETA_EDGE,
                "beta_skin" to S7IndexSpec.BETA_SKIN,
                "beta_coh" to S7IndexSpec.BETA_COH,
                "tau_h_deg" to S7IndexSpec.TAU_H_DEG,
                "tile_w" to tileSpec.width,
                "tile_h" to tileSpec.height,
                "tile_overlap" to S7IndexSpec.TILE_OVERLAP,
                "tiles" to scheduler.tileCount,
                "workers" to workerCount,
                "seed" to seed,
                "device_tier" to deviceTier,
                "index_bpp" to indexBpp,
                "diag_gates" to gateConfig.toParamMap()
            )
            Logger.i("PALETTE", "index.start", startParams)

            var sumCost = 0.0
            var sumEb = 0.0
            var sumCoh = 0.0
            var foreignHits = 0
            var distEvalsTotal = 0L
            var ownerChanges = 0L
            var tilesUpdated = 0
            var prepareMs = 0L
            var assignMs = 0L
            var ditherMs = 0L

            val zoneEntries = S7SamplingSpec.Zone.entries
            val tileAggregates = Array(scheduler.tileCount) { TileAggregate() }

            val meanCost = try {
                val assignStart = SystemClock.elapsedRealtime()
                prepareMs = assignStart - totalStart
                val gcBeforeAssign = captureGc()
                val metrics = if (FeatureFlags.S7_PARALLEL_TILES_ENABLED) {
                    assignUsingTiles(
                        scheduler = scheduler,
                        tileAggregates = tileAggregates,
                        width = width,
                        height = height,
                        total = total,
                        k = k,
                        zoneEntries = zoneEntries,
                        zones = zones,
                        lPlane = lPlane,
                        aPlane = aPlane,
                        bPlane = bPlane,
                        huePlane = huePlane,
                        edgeMask = edgeMask,
                        paletteLab = paletteLab,
                        paletteHues = paletteHues,
                        paletteRoles = paletteRoles,
                        paletteColors = paletteColors,
                        assigned = assigned,
                        counts = counts,
                        costs = costs,
                        previewPixels = previewPixels,
                        indexData = indexData,
                        indexBpp = indexBpp
                    )
                } else {
                    assignSequential(
                        width = width,
                        height = height,
                        total = total,
                        k = k,
                        zoneEntries = zoneEntries,
                        zones = zones,
                        lPlane = lPlane,
                        aPlane = aPlane,
                        bPlane = bPlane,
                        huePlane = huePlane,
                        edgeMask = edgeMask,
                        paletteLab = paletteLab,
                        paletteHues = paletteHues,
                        paletteRoles = paletteRoles,
                        paletteColors = paletteColors,
                        assigned = assigned,
                        counts = counts,
                        costs = costs,
                        previewPixels = previewPixels,
                        indexData = indexData,
                        indexBpp = indexBpp
                    )
                }
                sumCost = metrics.sumCost
                sumEb = metrics.sumEb
                sumCoh = metrics.sumCoh
                foreignHits = metrics.foreignHits
                distEvalsTotal = metrics.distEvals
                ownerChanges = metrics.ownerChanges
                tilesUpdated = metrics.tilesUpdated
                val assignEnd = SystemClock.elapsedRealtime()
                assignMs = assignEnd - assignStart
                val gcAfterAssign = captureGc()
                logGc("assign", gcBeforeAssign, gcAfterAssign)
                metrics.meanCost
            } catch (t: Throwable) {
                Logger.e(
                    "PALETTE",
                    "index.fail",
                    mapOf("stage" to "assign", "error" to (t.message ?: t.toString())),
                    err = t
                )
                throw t
            }

            val baselineDurationMs = (System.nanoTime() - baselineStartNs) / 1_000_000
            val baselineEndLog = LinkedHashMap(workspaceMetrics).apply {
                put("phase", "end")
                put("duration_ms", baselineDurationMs)
            }
            Logger.i("PALETTE", "s7.baseline", baselineEndLog)

            val topCounts = counts.withIndex()
                .sortedByDescending { it.value }
                .take(S7IndexSpec.COUNTS_TOP_N)
                .map { mapOf("index" to it.index, "count" to it.value) }

            Logger.i(
                "PALETTE",
                "index.assign",
                linkedMapOf(
                    "index_bpp" to indexBpp,
                    "counts_per_color_topN" to topCounts,
                    "foreign_zone_hits" to foreignHits,
                    "edge_break_penalty_sum" to sumEb,
                    "coh_bonus_sum" to sumCoh,
                    "mean_cost" to meanCost,
                    "prepare_ms" to prepareMs,
                    "assign_ms" to assignMs,
                    "dist_evals_total" to distEvalsTotal,
                    "owner_changes" to ownerChanges,
                    "tiles_updated" to tilesUpdated
                )
            )

            val ditherStart = SystemClock.elapsedRealtime()
            val gcBeforeDither = captureGc()
            val previewBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            previewBitmap.setPixels(previewPixels, 0, width, 0, 0, width, height)
            val costHeatmapBitmap = createCostHeatmap(width, height, costs)

            val sessionDir = DiagnosticsManager.currentSessionDir(ctx) ?: File(ctx.filesDir, "diag/index-temp").apply {
                mkdirs()
            }
            val indexDir = File(sessionDir, "index").apply { mkdirs() }
            val indexFile = File(indexDir, "index.bin")
            val previewFile = File(indexDir, "index_preview_k$k.png")
            val legendFile = File(indexDir, "palette_legend.csv")
            val metaFile = File(indexDir, "index_meta.json")
            val heatmapFile = File(indexDir, "cost_heatmap.png")

            S7IndexIo.writeIndexBin(indexFile, width, height, k, indexBpp, indexData)
            S7IndexIo.writeIndexPreviewPng(previewFile, previewBitmap)
            S7IndexIo.writeLegendCsv(legendFile, paletteK)

            val costHeatmapPath = if (costHeatmapBitmap != null) {
                S7IndexIo.writeCostHeatmapPng(heatmapFile, costHeatmapBitmap)
                heatmapFile.absolutePath
            } else {
                null
            }

            val ditherEnd = SystemClock.elapsedRealtime()
            ditherMs = ditherEnd - ditherStart
            val gcAfterDither = captureGc()
            logGc("dither", gcBeforeDither, gcAfterDither)

            val totalEnd = SystemClock.elapsedRealtime()
            val totalMs = totalEnd - totalStart

            val params = LinkedHashMap<String, Any?>(startParams)
            params["workspace"] = workspaceMetrics
            params["workspace_duration_ms"] = baselineDurationMs
            params["timings_ms"] = linkedMapOf(
                "prepare" to prepareMs,
                "assign" to assignMs,
                "dither" to ditherMs,
                "total" to totalMs
            )
            params["dist_evals_total"] = distEvalsTotal
            params["owner_changes"] = ownerChanges
            params["tiles_updated"] = tilesUpdated
            val foreignFraction = if (total > 0) foreignHits.toDouble() / total.toDouble() else 0.0
            if (foreignFraction >= S7IndexSpec.FOREIGN_ZONE_NOTE_FRACTION) {
                params["note"] = "high_fz_hits"
            }
            val stats = S7IndexStats(
                kStar = k,
                countsPerColor = counts.copyOf(),
                foreignZoneHits = foreignHits,
                edgeBreakPenaltySum = sumEb,
                cohBonusSum = sumCoh,
                meanCost = meanCost,
                timeMs = totalMs,
                prepareMs = prepareMs,
                assignMs = assignMs,
                ditherMs = ditherMs,
                totalMs = totalMs,
                distEvalsTotal = distEvalsTotal,
                ownerChanges = ownerChanges,
                tilesUpdated = tilesUpdated,
                params = params
            )
            S7IndexIo.writeIndexMetaJson(metaFile, stats)

            previewBitmap.recycle()
            costHeatmapBitmap?.recycle()

            Logger.i(
                "PALETTE",
                "index.done",
                linkedMapOf(
                    "width" to width,
                    "height" to height,
                    "Kstar" to k,
                    "path_index" to indexFile.absolutePath,
                    "path_preview" to previewFile.absolutePath,
                    "path_heatmap" to costHeatmapPath,
                    "time_ms" to stats.totalMs,
                    "prepare_ms" to stats.prepareMs,
                    "assign_ms" to stats.assignMs,
                    "dither_ms" to stats.ditherMs,
                    "dist_evals_total" to stats.distEvalsTotal,
                    "owner_changes" to stats.ownerChanges,
                    "tiles_updated" to stats.tilesUpdated
                )
            )

            Log.i("AiX/PALETTE", "Index built: K*=${paletteK.size}, index_bpp=$indexBpp")

            S7IndexResult(
                width = width,
                height = height,
                kStar = k,
                indexBpp = indexBpp,
                indexPath = indexFile.absolutePath,
                previewPath = previewFile.absolutePath,
                legendCsvPath = legendFile.absolutePath,
                stats = stats,
                costHeatmapPath = costHeatmapPath,
                gateConfig = gateConfig
            )
        }
    }
    private fun bitmapToFloatArray(bitmap: Bitmap, width: Int, height: Int, dest: FloatArray) {
        require(dest.size >= width * height)
        val source = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        val row = IntArray(width)
        for (y in 0 until height) {
            source.getPixels(row, 0, width, 0, y, width, 1)
            var idx = y * width
            for (x in 0 until width) {
                dest[idx] = Color.red(row[x]) / 255f
                idx++
            }
        }
        if (source !== bitmap) {
            source.recycle()
        }
    }

    private fun computeWorkerCount(tileCount: Int): Int {
        if (tileCount <= 0) return 1
        val available = Runtime.getRuntime().availableProcessors()
        val filtered = if (available > 0) available else 1
        return max(1, min(filtered, tileCount))
    }

    private data class GcSnapshot(val alloc: Long, val freed: Long)

    private fun captureGc(): GcSnapshot {
        return GcSnapshot(
            alloc = Debug.getGlobalAllocCount().toLong(),
            freed = Debug.getGlobalFreedCount().toLong()
        )
    }

    private fun logGc(phase: String, start: GcSnapshot, end: GcSnapshot) {
        Logger.i(
            "PALETTE",
            "s7.gc",
            linkedMapOf(
                "phase" to phase,
                "alloc_before" to start.alloc,
                "alloc_after" to end.alloc,
                "alloc_delta" to (end.alloc - start.alloc),
                "freed_before" to start.freed,
                "freed_after" to end.freed,
                "freed_delta" to (end.freed - start.freed)
            )
        )
    }

    private class TileAggregate {
        var sumCost: Double = 0.0
        var sumEb: Double = 0.0
        var sumCoh: Double = 0.0
        var foreignHits: Int = 0

        @Suppress("unused")
        private val padding: LongArray = LongArray(8)
    }

    private data class AssignmentMetrics(
        val sumCost: Double,
        val sumEb: Double,
        val sumCoh: Double,
        val foreignHits: Int,
        val distEvals: Long,
        val ownerChanges: Long,
        val tilesUpdated: Int,
        val meanCost: Double
    )

    private fun assignUsingTiles(
        scheduler: S7TileScheduler,
        tileAggregates: Array<TileAggregate>,
        width: Int,
        height: Int,
        total: Int,
        k: Int,
        zoneEntries: Array<S7SamplingSpec.Zone>,
        zones: ByteArray,
        lPlane: DoubleArray,
        aPlane: DoubleArray,
        bPlane: DoubleArray,
        huePlane: DoubleArray,
        edgeMask: FloatArray,
        paletteLab: DoubleArray,
        paletteHues: DoubleArray,
        paletteRoles: ByteArray,
        paletteColors: IntArray,
        assigned: IntArray,
        counts: IntArray,
        costs: DoubleArray,
        previewPixels: IntArray,
        indexData: ByteArray,
        indexBpp: Int
    ): AssignmentMetrics {
        var sumCost = 0.0
        var sumEb = 0.0
        var sumCoh = 0.0
        var foreignHits = 0
        var distEvals = 0L
        var ownerChanges = 0L
        var tilesUpdated = 0
        scheduler.forEachTile { tile ->
            val tileStart = SystemClock.elapsedRealtime()
            tilesUpdated++
            val tileAggregate = tileAggregates[tile.tileId]
            var tileSumCost = 0.0
            var tileSumEb = 0.0
            var tileSumCoh = 0.0
            var tileForeignHits = 0
            val xStart = tile.x
            val xEnd = xStart + tile.width
            val yStart = tile.y
            val yEnd = yStart + tile.height
            for (y in yStart until yEnd) {
                val yOffset = y * width
                for (x in xStart until xEnd) {
                    val idx = yOffset + x
                    val zone = zoneEntries[zones[idx].toInt()]
                    val L = lPlane[idx]
                    val a = aPlane[idx]
                    val b = bPlane[idx]
                    val hue = huePlane[idx]
                    val edge = edgeMask[idx].toDouble()
                    val leftAssigned = if (x > 0) assigned[idx - 1] else -1
                    val topAssigned = if (y > 0) assigned[idx - width] else -1

                    var bestIdx = 0
                    var bestCost = Double.POSITIVE_INFINITY
                    var bestEb = 0.0
                    var bestCoh = 0.0
                    var bestFz = 0.0

                    for (c in 0 until k) {
                        val base = c * 3
                        val deltaE = deltaE00(
                            L,
                            a,
                            b,
                            paletteLab[base],
                            paletteLab[base + 1],
                            paletteLab[base + 2]
                        )
                        val fz = foreignZonePenalty(zone, paletteRoles[c].toInt())
                        var matches = 0
                        var mismatches = 0
                        if (leftAssigned >= 0) {
                            if (leftAssigned == c) matches++ else mismatches++
                        }
                        if (topAssigned >= 0) {
                            if (topAssigned == c) matches++ else mismatches++
                        }
                        val denom = matches + mismatches
                        val coh = if (denom > 0) matches.toDouble() / denom else 0.0
                        val eb = if (denom > 0) edge * (mismatches.toDouble() / denom) else 0.0
                        val sh = if (zone == S7SamplingSpec.Zone.SKIN) {
                            val hueDelta = hueDeltaRad(hue, paletteHues[c])
                            val excess = abs(hueDelta) - S7IndexSpec.TAU_H_RAD
                            if (excess > 0.0) excess / Math.PI else 0.0
                        } else {
                            0.0
                        }
                        val cost = S7IndexSpec.ALPHA0 * deltaE +
                            S7IndexSpec.BETA_FZ * fz +
                            S7IndexSpec.BETA_EDGE * eb +
                            S7IndexSpec.BETA_SKIN * sh -
                            S7IndexSpec.BETA_COH * coh
                        if (cost < bestCost || (cost == bestCost && c < bestIdx)) {
                            bestCost = cost
                            bestIdx = c
                            bestEb = eb
                            bestCoh = coh
                            bestFz = fz
                        }
                    }

                    distEvals += k.toLong()
                    val prevOwner = assigned[idx]
                    assigned[idx] = bestIdx
                    if (bestIdx != prevOwner) {
                        ownerChanges++
                    }
                    counts[bestIdx]++
                    costs[idx] = bestCost
                    tileSumCost += bestCost
                    tileSumEb += bestEb
                    tileSumCoh += bestCoh
                    if (bestFz > 0.0) tileForeignHits++
                    previewPixels[idx] = paletteColors[bestIdx]
                    if (indexBpp == 8) {
                        indexData[idx] = bestIdx.toByte()
                    } else {
                        val off = idx * 2
                        indexData[off] = (bestIdx and 0xFF).toByte()
                        indexData[off + 1] = ((bestIdx ushr 8) and 0xFF).toByte()
                    }
                }
            }
            tileAggregate.sumCost = tileSumCost
            tileAggregate.sumEb = tileSumEb
            tileAggregate.sumCoh = tileSumCoh
            tileAggregate.foreignHits = tileForeignHits
            val tilePixels = tile.width * tile.height
            val tileMeanCost = if (tilePixels > 0) tileSumCost / tilePixels else 0.0
            Logger.i(
                "PALETTE",
                "index.tile",
                mapOf(
                    "id" to tile.tileId,
                    "x" to tile.x,
                    "y" to tile.y,
                    "w" to tile.width,
                    "h" to tile.height,
                    "ms" to (SystemClock.elapsedRealtime() - tileStart),
                    "meanCost" to tileMeanCost,
                    "EBsum" to tileSumEb,
                    "COHsum" to tileSumCoh
                )
            )
            sumCost += tileSumCost
            sumEb += tileSumEb
            sumCoh += tileSumCoh
            foreignHits += tileForeignHits
        }
        val meanCost = if (total > 0) sumCost / total else 0.0
        return AssignmentMetrics(
            sumCost = sumCost,
            sumEb = sumEb,
            sumCoh = sumCoh,
            foreignHits = foreignHits,
            distEvals = distEvals,
            ownerChanges = ownerChanges,
            tilesUpdated = tilesUpdated,
            meanCost = meanCost
        )
    }

    private fun assignSequential(
        width: Int,
        height: Int,
        total: Int,
        k: Int,
        zoneEntries: Array<S7SamplingSpec.Zone>,
        zones: ByteArray,
        lPlane: DoubleArray,
        aPlane: DoubleArray,
        bPlane: DoubleArray,
        huePlane: DoubleArray,
        edgeMask: FloatArray,
        paletteLab: DoubleArray,
        paletteHues: DoubleArray,
        paletteRoles: ByteArray,
        paletteColors: IntArray,
        assigned: IntArray,
        counts: IntArray,
        costs: DoubleArray,
        previewPixels: IntArray,
        indexData: ByteArray,
        indexBpp: Int
    ): AssignmentMetrics {
        var sumCost = 0.0
        var sumEb = 0.0
        var sumCoh = 0.0
        var foreignHits = 0
        var distEvals = 0L
        var ownerChanges = 0L
        for (y in 0 until height) {
            val yOffset = y * width
            for (x in 0 until width) {
                val idx = yOffset + x
                val zone = zoneEntries[zones[idx].toInt()]
                val L = lPlane[idx]
                val a = aPlane[idx]
                val b = bPlane[idx]
                val hue = huePlane[idx]
                val edge = edgeMask[idx].toDouble()
                val leftAssigned = if (x > 0) assigned[idx - 1] else -1
                val topAssigned = if (y > 0) assigned[idx - width] else -1

                var bestIdx = 0
                var bestCost = Double.POSITIVE_INFINITY
                var bestEb = 0.0
                var bestCoh = 0.0
                var bestFz = 0.0

                for (c in 0 until k) {
                    val base = c * 3
                    val deltaE = deltaE00(
                        L,
                        a,
                        b,
                        paletteLab[base],
                        paletteLab[base + 1],
                        paletteLab[base + 2]
                    )
                    val fz = foreignZonePenalty(zone, paletteRoles[c].toInt())
                    var matches = 0
                    var mismatches = 0
                    if (leftAssigned >= 0) {
                        if (leftAssigned == c) matches++ else mismatches++
                    }
                    if (topAssigned >= 0) {
                        if (topAssigned == c) matches++ else mismatches++
                    }
                    val denom = matches + mismatches
                    val coh = if (denom > 0) matches.toDouble() / denom else 0.0
                    val eb = if (denom > 0) edge * (mismatches.toDouble() / denom) else 0.0
                    val sh = if (zone == S7SamplingSpec.Zone.SKIN) {
                        val hueDelta = hueDeltaRad(hue, paletteHues[c])
                        val excess = abs(hueDelta) - S7IndexSpec.TAU_H_RAD
                        if (excess > 0.0) excess / Math.PI else 0.0
                    } else {
                        0.0
                    }
                    val cost = S7IndexSpec.ALPHA0 * deltaE +
                        S7IndexSpec.BETA_FZ * fz +
                        S7IndexSpec.BETA_EDGE * eb +
                        S7IndexSpec.BETA_SKIN * sh -
                        S7IndexSpec.BETA_COH * coh
                    if (cost < bestCost || (cost == bestCost && c < bestIdx)) {
                        bestCost = cost
                        bestIdx = c
                        bestEb = eb
                        bestCoh = coh
                        bestFz = fz
                    }
                }

                distEvals += k.toLong()
                val prevOwner = assigned[idx]
                assigned[idx] = bestIdx
                if (bestIdx != prevOwner) {
                    ownerChanges++
                }
                counts[bestIdx]++
                costs[idx] = bestCost
                sumCost += bestCost
                sumEb += bestEb
                sumCoh += bestCoh
                if (bestFz > 0.0) foreignHits++
                previewPixels[idx] = paletteColors[bestIdx]
                if (indexBpp == 8) {
                    indexData[idx] = bestIdx.toByte()
                } else {
                    val off = idx * 2
                    indexData[off] = (bestIdx and 0xFF).toByte()
                    indexData[off + 1] = ((bestIdx ushr 8) and 0xFF).toByte()
                }
            }
        }
        val meanCost = if (total > 0) sumCost / total else 0.0
        return AssignmentMetrics(
            sumCost = sumCost,
            sumEb = sumEb,
            sumCoh = sumCoh,
            foreignHits = foreignHits,
            distEvals = distEvals,
            ownerChanges = ownerChanges,
            tilesUpdated = 1,
            meanCost = meanCost
        )
    }

    private fun resolveZoneOrdinal(
        idx: Int,
        edgeMask: FloatArray,
        flatMask: FloatArray,
        hiTexFineMask: FloatArray,
        hiTexCoarseMask: FloatArray,
        skinMask: FloatArray,
        skyMask: FloatArray
    ): Int {
        val skin = skinMask[idx]
        if (skin >= 0.55f) return S7SamplingSpec.Zone.SKIN.ordinal
        val sky = skyMask[idx]
        if (sky >= 0.55f) return S7SamplingSpec.Zone.SKY.ordinal
        val edge = edgeMask[idx]
        if (edge >= 0.35f) return S7SamplingSpec.Zone.EDGE.ordinal
        val hiTex = max(hiTexFineMask[idx], hiTexCoarseMask[idx])
        if (hiTex >= 0.45f) return S7SamplingSpec.Zone.HITEX.ordinal
        val flat = flatMask[idx]
        return if (flat >= 0.40f) {
            S7SamplingSpec.Zone.FLAT.ordinal
        } else {
            when {
                hiTex >= 0.25f -> S7SamplingSpec.Zone.HITEX.ordinal
                edge >= 0.20f -> S7SamplingSpec.Zone.EDGE.ordinal
                else -> S7SamplingSpec.Zone.FLAT.ordinal
            }
        }
    }

    private fun foreignZonePenalty(zone: S7SamplingSpec.Zone, roleOrdinal: Int): Double {
        val role = S7InitSpec.PaletteZone.entries[roleOrdinal]
        return when (zone) {
            S7SamplingSpec.Zone.SKIN -> if (role == S7InitSpec.PaletteZone.SKY) 1.5 else 0.0
            S7SamplingSpec.Zone.SKY -> if (role == S7InitSpec.PaletteZone.SKIN) 1.0 else 0.0
            S7SamplingSpec.Zone.EDGE -> if (role == S7InitSpec.PaletteZone.FLAT) 0.7 else 0.0
            S7SamplingSpec.Zone.FLAT -> if (role == S7InitSpec.PaletteZone.EDGE) 0.7 else 0.0
            S7SamplingSpec.Zone.HITEX -> if (role == S7InitSpec.PaletteZone.FLAT) 0.4 else 0.0
        }
    }

    private fun deltaE00(L1: Double, a1: Double, b1: Double, L2: Double, a2: Double, b2: Double): Double {
        val avgL = (L1 + L2) * 0.5
        val c1 = sqrt(a1 * a1 + b1 * b1)
        val c2 = sqrt(a2 * a2 + b2 * b2)
        val avgC = (c1 + c2) * 0.5
        val g = 0.5 * (1.0 - sqrt(avgC.pow(7.0) / (avgC.pow(7.0) + 25.0.pow(7.0))))
        val a1p = a1 * (1.0 + g)
        val a2p = a2 * (1.0 + g)
        val c1p = sqrt(a1p * a1p + b1 * b1)
        val c2p = sqrt(a2p * a2p + b2 * b2)
        val avgCp = (c1p + c2p) * 0.5
        val h1p = hueAngle(b1, a1p)
        val h2p = hueAngle(b2, a2p)
        val deltaLp = L2 - L1
        val deltaCp = c2p - c1p
        val deltaHp = if (c1p * c2p == 0.0) {
            0.0
        } else {
            var dh = h2p - h1p
            if (dh > Math.PI) dh -= 2.0 * Math.PI
            if (dh < -Math.PI) dh += 2.0 * Math.PI
            2.0 * sqrt(c1p * c2p) * sin(dh / 2.0)
        }
        val avgHp = if (c1p * c2p == 0.0) {
            h1p + h2p
        } else {
            val diff = abs(h1p - h2p)
            when {
                diff <= Math.PI -> (h1p + h2p) * 0.5
                (h1p + h2p) < 2.0 * Math.PI -> (h1p + h2p + 2.0 * Math.PI) * 0.5
                else -> (h1p + h2p - 2.0 * Math.PI) * 0.5
            }
        }
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
        return deltaE
    }

    private fun hueAngle(b: Double, ap: Double): Double {
        if (ap == 0.0 && b == 0.0) return 0.0
        var angle = atan2(b, ap)
        if (angle < 0.0) angle += 2.0 * Math.PI
        return angle
    }

    private fun degToRad(value: Double): Double = value * Math.PI / 180.0

    private fun hueDeltaRad(h1: Double, h2: Double): Double {
        var diff = h1 - h2
        while (diff > Math.PI) diff -= 2.0 * Math.PI
        while (diff < -Math.PI) diff += 2.0 * Math.PI
        return diff
    }

    private fun srgbToLinearDouble(value: Double): Double {
        return if (value <= 0.04045) {
            value / 12.92
        } else {
            ((value + 0.055) / 1.055).pow(2.4)
        }
    }

    private fun createCostHeatmap(width: Int, height: Int, costs: DoubleArray): Bitmap? {
        if (costs.isEmpty()) return null
        var minValue = Double.POSITIVE_INFINITY
        var maxValue = Double.NEGATIVE_INFINITY
        for (value in costs) {
            if (!value.isFinite()) continue
            if (value < minValue) minValue = value
            if (value > maxValue) maxValue = value
        }
        if (!minValue.isFinite() || !maxValue.isFinite()) return null
        val range = (maxValue - minValue).takeIf { it > 1e-9 } ?: 1.0
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(costs.size)
        for (i in costs.indices) {
            val norm = ((costs[i] - minValue) / range).coerceIn(0.0, 1.0)
            pixels[i] = heatmapColor(norm)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun heatmapColor(norm: Double): Int {
        val hue = (210.0 - 210.0 * norm).coerceIn(0.0, 360.0).toFloat()
        val sat = (0.25f + 0.65f * norm.toFloat()).coerceIn(0f, 1f)
        val value = (0.75f + 0.20f * norm.toFloat()).coerceIn(0f, 1f)
        val alpha = (160 + 95 * norm).toInt().coerceIn(0, 255)
        return Color.HSVToColor(alpha, floatArrayOf(hue, sat, value))
    }
}
