package com.appforcross.editor.palette

import android.graphics.Color
import com.appforcross.editor.config.FeatureFlags
import com.appforcross.editor.palette.s7.assign.AssignmentSummarySnapshot
import com.appforcross.editor.palette.s7.assign.S7AssignCache
import com.appforcross.editor.palette.s7.assign.S7TileErrorMap
import com.handmadeapp.color.ColorMgmt
import com.handmadeapp.logging.Logger
import java.util.ArrayList
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val ALGO_VERSION = "S7.4-spread2opt-v1"
private const val TILE_ERROR_THRESHOLD = 2.0f

data class S7SpreadViolation(val i: Int, val j: Int, val de: Float)

data class S7Move(
    val i: Int,
    val from: FloatArray,
    val to: FloatArray,
    val delta: Float,
    val clipped: Boolean
)

data class S7PairFix(
    val i: Int,
    val j: Int,
    val deBefore: Float,
    val deAfter: Float,
    val variant: String,
    val gain: Float,
    val moves: List<S7Move>,
    val reason: String?
)

data class S7Spread2OptResult(
    val colors: List<S7InitColor>,
    val violationsBefore: List<S7SpreadViolation>,
    val violationsAfter: List<S7SpreadViolation>,
    val pairFixes: List<S7PairFix>,
    val deMinBefore: Float,
    val deMinAfter: Float,
    val de95Before: Float,
    val de95After: Float,
    val gbiBefore: Float,
    val gbiAfter: Float,
    val tileErrors: S7TileErrorMap?,
    val params: Map<String, Any?>
)

object S7Spread2Opt {

    private data class AssignmentSummary(
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

    private fun AssignmentSummarySnapshot.toAssignmentSummary(): AssignmentSummary {
        return AssignmentSummary(
            nearest = nearest,
            nearestDist = nearestDist,
            second = second,
            secondDist = secondDist,
            perColorSamples = perColorSamples,
            perColorImportance = perColorImportance,
            totalImportance = totalImportance,
            de95 = de95,
            gbi = gbi
        )
    }

    private data class PaletteState(
        val colors: MutableList<S7InitColor>,
        val assignments: AssignmentSummary,
        val assignCache: S7AssignCache,
        val violations: List<S7SpreadViolation>,
        val deMin: Float,
        val ambiguity: FloatArray
    )

    private data class CandidateEvaluation(
        val variant: String,
        val moves: List<S7Move>,
        val gain: Float,
        val de95: Float,
        val gbi: Float,
        val dePair: Float,
        val clippedMoves: Int,
        val reason: String?
    )

    private data class ProjectedColor(
        val lab: FloatArray,
        val argb: Int,
        val clipped: Boolean,
        val clipDelta: Float
    )

    fun run(
        sampling: S7SamplingResult,
        greedy: S7GreedyResult,
        passes: Int,
        seed: Long,
        deviceTier: String
    ): S7Spread2OptResult {
        FeatureFlags.logSpread2OptFlag()
        require(passes >= 1) { "passes must be >=1" }
        val effectivePasses = passes.coerceIn(1, S7Spread2OptSpec.P_PASSES_MAX)
        val samples = sampling.samples
        require(samples.isNotEmpty()) { "Sampling is empty" }
        val initialPalette = greedy.colors.map { copyColor(it) }
        val tier = S7SamplingSpec.DeviceTier.fromKey(deviceTier)
        val timeBudgetMs = S7Spread2OptSpec.timeBudgetMsForTier(tier)
        val pairBudget = S7Spread2OptSpec.pairsBudgetForTier(tier)
        val logStart = linkedMapOf<String, Any?>(
            "algo" to ALGO_VERSION,
            "s_min" to S7Spread2OptSpec.S_MIN,
            "delta_max" to S7Spread2OptSpec.DELTA_MAX,
            "alpha" to S7Spread2OptSpec.ALPHA,
            "beta" to S7Spread2OptSpec.BETA,
            "mu" to S7Spread2OptSpec.MU,
            "gamma_err" to S7Spread2OptSpec.GAMMA_ERR,
            "M" to pairBudget,
            "passes" to effectivePasses,
            "time_budget_ms" to timeBudgetMs,
            "seed" to seed,
            "device_tier" to tier.key
        )
        Logger.i("PALETTE", "spread2opt.start", logStart)

        val startTime = System.currentTimeMillis()
        val previewWidth = (samples.maxOfOrNull { it.x } ?: 0) + 1
        val previewHeight = (samples.maxOfOrNull { it.y } ?: 0) + 1
        val tileSpec = S7IndexSpec.tileForTier(deviceTier, previewWidth, previewHeight)
        val initialCache = S7AssignCache.create(
            samples = samples,
            paletteLabs = initialPalette.map { it.okLab },
            tileWidth = tileSpec.width,
            tileHeight = tileSpec.height,
            threshold = TILE_ERROR_THRESHOLD
        )
        val initialAssignments = initialCache.buildSummary().toAssignmentSummary()
        val violations = findViolations(initialPalette)
        val deMinBefore = violations.firstOrNull()?.de ?: computeMinDistance(initialPalette)
        Logger.i(
            "PALETTE",
            "spread.violations",
            mapOf(
                "count_before" to violations.size,
                "deMinBefore" to deMinBefore
            )
        )

        val ambiguity = computeAmbiguity(initialPalette, initialAssignments)
        val initialState = PaletteState(
            colors = initialPalette.toMutableList(),
            assignments = initialAssignments,
            assignCache = initialCache,
            violations = violations,
            deMin = deMinBefore,
            ambiguity = ambiguity
        )

        var currentState = initialState
        var currentDe95 = initialAssignments.de95
        var currentGbi = initialAssignments.gbi
        val pairFixes = ArrayList<S7PairFix>()
        var clippedMoves = 0
        var acceptedFixes = 0
        var rejectedFixes = 0
        var notes = ArrayList<String>()

        outer@ for (passIdx in 0 until effectivePasses) {
            val passStart = System.currentTimeMillis()
            var passAccepted = 0
            var passRejected = 0
            val candidates = selectPairs(currentState, pairBudget)
            for ((index, pair) in candidates.withIndex()) {
                val now = System.currentTimeMillis()
                if (now - startTime > timeBudgetMs) {
                    val fix = S7PairFix(
                        i = pair.first,
                        j = pair.second,
                        deBefore = distance(currentState.colors[pair.first].okLab, currentState.colors[pair.second].okLab),
                        deAfter = distance(currentState.colors[pair.first].okLab, currentState.colors[pair.second].okLab),
                        variant = "none",
                        gain = 0f,
                        moves = emptyList(),
                        reason = "time_budget"
                    )
                    pairFixes += fix
                    Logger.i(
                        "PALETTE",
                        "spread.pair",
                        mapOf(
                            "i" to pair.first,
                            "j" to pair.second,
                            "deBefore" to fix.deBefore,
                            "delta_planned" to 0f,
                            "variant" to "none",
                            "gain" to 0f,
                            "accepted" to false,
                            "reason" to "time_budget"
                        )
                    )
                    notes += "partial_fix_due_to_budget"
                    passRejected++
                    rejectedFixes++
                    // mark remaining pairs as skipped due to time
                    val remaining = candidates.drop(index + 1)
                    for (rest in remaining) {
                        pairFixes += S7PairFix(
                            i = rest.first,
                            j = rest.second,
                            deBefore = distance(
                                currentState.colors[rest.first].okLab,
                                currentState.colors[rest.second].okLab
                            ),
                            deAfter = distance(
                                currentState.colors[rest.first].okLab,
                                currentState.colors[rest.second].okLab
                            ),
                            variant = "none",
                            gain = 0f,
                            moves = emptyList(),
                            reason = "time_budget"
                        )
                    }
                    break@outer
                }

                val fix = processPair(
                    pair.first,
                    pair.second,
                    currentState,
                    samples,
                    currentDe95,
                    currentGbi
                )
                if (fix.moves.isNotEmpty()) {
                    passAccepted++
                    acceptedFixes++
                    clippedMoves += fix.moves.count { it.clipped }
                    currentState = applyFix(currentState, fix)
                    currentDe95 = currentState.assignments.de95
                    currentGbi = currentState.assignments.gbi
                } else {
                    passRejected++
                    rejectedFixes++
                }
                pairFixes += fix
            }
            val passTime = System.currentTimeMillis() - passStart
            Logger.i(
                "PALETTE",
                "2opt.iter",
                mapOf(
                    "pass" to passIdx,
                    "fixes_accepted" to passAccepted,
                    "fixes_rejected" to passRejected,
                    "time_ms" to passTime
                )
            )
        }

        val finalViolations = findViolations(currentState.colors)
        val deMinAfter = currentState.deMin
        val finalAssignments = currentState.assignments
        val affectedHeat = computeAffected(finalAssignments, initialAssignments)
        val de95After = finalAssignments.de95
        val gbiAfter = finalAssignments.gbi
        val tileErrorMap = if (FeatureFlags.S7_TILE_ERRORMAP_ENABLED) {
            currentState.assignCache.snapshotTileErrors()
        } else {
            null
        }
        val duration = System.currentTimeMillis() - startTime

        Logger.i(
            "PALETTE",
            "spread2opt.done",
            mapOf(
                "deMinAfter" to deMinAfter,
                "de95Before" to initialAssignments.de95,
                "de95After" to de95After,
                "gbiBefore" to initialAssignments.gbi,
                "gbiAfter" to gbiAfter,
                "fixes_total" to acceptedFixes,
                "clipped_total" to clippedMoves,
                "time_ms" to duration
            )
        )

        if (finalViolations.isNotEmpty() && !notes.contains("partial_fix_due_to_budget")) {
            notes += "spread_residual"
        }

        val spreadMinValues = computeSpreadPerColor(currentState.colors)
        val finalPalette = currentState.colors.mapIndexed { index, color ->
            val spreadMin = spreadMinValues.getOrNull(index) ?: Float.POSITIVE_INFINITY
            color.copy(spreadMin = spreadMin)
        }

        val params = LinkedHashMap<String, Any?>().apply {
            putAll(logStart)
            put("algo", ALGO_VERSION)
            put("passes_effective", effectivePasses)
            put("time_ms", duration)
            put("fixes_accepted", acceptedFixes)
            put("fixes_rejected", rejectedFixes)
            put("clipped_moves", clippedMoves)
            put("notes", notes.distinct())
            put("colors_before", initialPalette.map { copyColor(it) })
            put("heatmap_ambiguity", initialState.ambiguity)
            put("heatmap_affected", affectedHeat)
            tileErrorMap?.let { put("tile_error_map", it.toDiagnostics()) }
        }

        return S7Spread2OptResult(
            colors = finalPalette,
            violationsBefore = initialState.violations,
            violationsAfter = finalViolations,
            pairFixes = pairFixes,
            deMinBefore = initialState.deMin,
            deMinAfter = deMinAfter,
            de95Before = initialAssignments.de95,
            de95After = de95After,
            gbiBefore = initialAssignments.gbi,
            gbiAfter = gbiAfter,
            tileErrors = tileErrorMap,
            params = params
        )
    }

    private fun S7AssignCache.updateColorWithFallback(colorIdx: Int, lab: FloatArray, threshold: Float) {
        if (FeatureFlags.S7_INCREMENTAL_ASSIGN_ENABLED) {
            updateWithColor(colorIdx, lab, threshold)
        } else {
            invalidateAll()
            updateWithColor(colorIdx, lab, Float.POSITIVE_INFINITY)
        }
    }

    private fun findViolations(palette: List<S7InitColor>): List<S7SpreadViolation> {
        val sMin = S7Spread2OptSpec.S_MIN
        val result = ArrayList<S7SpreadViolation>()
        for (i in 0 until palette.size) {
            for (j in i + 1 until palette.size) {
                val de = distance(palette[i].okLab, palette[j].okLab)
                if (de < sMin) {
                    result += S7SpreadViolation(i, j, de)
                }
            }
        }
        result.sortBy { it.de }
        return result
    }

    private fun computeAmbiguity(
        palette: List<S7InitColor>,
        assignments: AssignmentSummary
    ): FloatArray {
        val ambiguity = FloatArray(assignments.nearest.size)
        val sMin = S7Spread2OptSpec.S_MIN
        for (idx in ambiguity.indices) {
            val first = assignments.nearest[idx]
            val second = assignments.second[idx]
            if (first >= 0 && second >= 0) {
                val de = distance(palette[first].okLab, palette[second].okLab)
                val value = (sMin - de).coerceAtLeast(0f)
                ambiguity[idx] = value
            } else {
                ambiguity[idx] = 0f
            }
        }
        return ambiguity
    }

    private fun computeAffected(
        finalAssignments: AssignmentSummary,
        initialAssignments: AssignmentSummary
    ): FloatArray {
        val affected = FloatArray(finalAssignments.nearest.size)
        for (idx in affected.indices) {
            affected[idx] = if (finalAssignments.nearest[idx] != initialAssignments.nearest[idx]) 1f else 0f
        }
        return affected
    }

    private fun computeMinDistance(palette: List<S7InitColor>): Float {
        var best = Float.POSITIVE_INFINITY
        for (i in 0 until palette.size) {
            for (j in i + 1 until palette.size) {
                val d = distance(palette[i].okLab, palette[j].okLab)
                if (d < best) best = d
            }
        }
        return if (best.isFinite()) best else Float.POSITIVE_INFINITY
    }

    private fun computeSpreadPerColor(palette: List<S7InitColor>): List<Float> {
        val result = FloatArray(palette.size) { Float.POSITIVE_INFINITY }
        for (i in 0 until palette.size) {
            var best = Float.POSITIVE_INFINITY
            for (j in 0 until palette.size) {
                if (i == j) continue
                val d = distance(palette[i].okLab, palette[j].okLab)
                if (d < best) best = d
            }
            result[i] = best
        }
        return result.toList()
    }

    private fun selectPairs(
        state: PaletteState,
        pairBudget: Int
    ): List<Pair<Int, Int>> {
        val importance = state.assignments.perColorImportance
        val totalImp = state.assignments.totalImportance.coerceAtLeast(1e-6)
        val sMin = S7Spread2OptSpec.S_MIN
        val gamma = S7Spread2OptSpec.GAMMA_ERR
        val scores = ArrayList<Triple<Float, Int, Int>>()
        for (i in 0 until state.colors.size) {
            for (j in i + 1 until state.colors.size) {
                val de = distance(state.colors[i].okLab, state.colors[j].okLab)
                val spreadTerm = (sMin - de).coerceAtLeast(0f)
                if (spreadTerm <= 0f) continue
                val impTerm = ((importance[i] + importance[j]) / totalImp).toFloat()
                val score = spreadTerm + gamma * impTerm
                scores += Triple(score, i, j)
            }
        }
        scores.sortWith(compareByDescending<Triple<Float, Int, Int>> { it.first }
            .thenBy { it.second }
            .thenBy { it.third })
        return scores.take(pairBudget).map { it.second to it.third }
    }

    private fun processPair(
        i: Int,
        j: Int,
        state: PaletteState,
        samples: List<S7Sample>,
        currentDe95: Float,
        currentGbi: Float
    ): S7PairFix {
        val colors = state.colors
        val labI = colors[i].okLab
        val labJ = colors[j].okLab
        val deBefore = distance(labI, labJ)
        val deltaPlanned = ((S7Spread2OptSpec.S_MIN - deBefore) / 2f).coerceIn(0f, S7Spread2OptSpec.DELTA_MAX)
        if (deltaPlanned <= 1e-6f) {
            return S7PairFix(i, j, deBefore, deBefore, "none", 0f, emptyList(), "no_gain")
        }
        val direction = unitVector(labJ, labI)
        val pushI = floatArrayOf(labI[0] - deltaPlanned * direction[0], labI[1] - deltaPlanned * direction[1], labI[2] - deltaPlanned * direction[2])
        val pushJ = floatArrayOf(labJ[0] + deltaPlanned * direction[0], labJ[1] + deltaPlanned * direction[1], labJ[2] + deltaPlanned * direction[2])
        val pushCandidate = evaluateCandidate(
            i,
            j,
            pushI,
            pushJ,
            state,
            samples,
            currentDe95,
            currentGbi,
            deltaPlanned,
            variant = "push"
        )
        val medoidCandidate = evaluateMedoidCandidate(
            i,
            j,
            pushCandidate,
            state,
            samples,
            currentDe95,
            currentGbi
        )

        val candidates = listOfNotNull(pushCandidate, medoidCandidate)
        val best = candidates.maxWithOrNull(compareBy<CandidateEvaluation> { it.gain }
            .thenBy { it.dePair })
        val accepted = best?.takeIf { it.gain > 0f && it.moves.isNotEmpty() }
        val variant = accepted?.variant ?: "none"
        val gain = accepted?.gain ?: 0f
        val deAfter = accepted?.dePair ?: deBefore
        val reason = when {
            accepted == null -> pushCandidate?.reason ?: medoidCandidate?.reason ?: "no_gain"
            else -> "accepted"
        }
        Logger.i(
            "PALETTE",
            "spread.pair",
            mapOf(
                "i" to i,
                "j" to j,
                "deBefore" to deBefore,
                "delta_planned" to deltaPlanned,
                "variant" to variant,
                "gain" to gain,
                "accepted" to (accepted != null),
                "reason" to reason
            )
        )
        val moves = accepted?.moves ?: emptyList()
        for (move in moves) {
            if (move.clipped) {
                Logger.i(
                    "PALETTE",
                    "spread.clip",
                    mapOf(
                        "index" to move.i,
                        "delta" to move.delta,
                        "note" to "srgb_gamut_projection"
                    )
                )
            }
        }
        return S7PairFix(
            i = i,
            j = j,
            deBefore = deBefore,
            deAfter = deAfter,
            variant = variant,
            gain = gain,
            moves = moves,
            reason = reason
        )
    }

    private fun evaluateCandidate(
        i: Int,
        j: Int,
        candI: FloatArray,
        candJ: FloatArray,
        state: PaletteState,
        samples: List<S7Sample>,
        currentDe95: Float,
        currentGbi: Float,
        deltaPlanned: Float,
        variant: String
    ): CandidateEvaluation? {
        val projectedI = projectLab(candI)
        val projectedJ = projectLab(candJ)
        if (projectedI.clipDelta > 1.0f || projectedJ.clipDelta > 1.0f) {
            return CandidateEvaluation(
                variant = variant,
                moves = emptyList(),
                gain = Float.NEGATIVE_INFINITY,
                de95 = currentDe95,
                gbi = currentGbi,
                dePair = distance(state.colors[i].okLab, state.colors[j].okLab),
                clippedMoves = 0,
                reason = "clip_too_large"
            )
        }
        val candidatePalette = state.colors.mapIndexed { idx, color ->
            when (idx) {
                i -> color.copy(okLab = projectedI.lab, sRGB = projectedI.argb, clipped = projectedI.clipped)
                j -> color.copy(okLab = projectedJ.lab, sRGB = projectedJ.argb, clipped = projectedJ.clipped)
                else -> color
            }
        }
        val candidateCache = state.assignCache.copy()
        candidateCache.updateColorWithFallback(i, projectedI.lab, TILE_ERROR_THRESHOLD)
        candidateCache.updateColorWithFallback(j, projectedJ.lab, TILE_ERROR_THRESHOLD)
        val assignmentsSnapshot = candidateCache.buildSummary()
        val assignments = assignmentsSnapshot.toAssignmentSummary()
        val de95 = assignments.de95
        val gbi = assignments.gbi
        val shiftI = distance(state.colors[i].okLab, projectedI.lab)
        val shiftJ = distance(state.colors[j].okLab, projectedJ.lab)
        val gain = S7Spread2OptSpec.ALPHA * (currentDe95 - de95) +
            S7Spread2OptSpec.BETA * (currentGbi - gbi) -
            S7Spread2OptSpec.MU * (shiftI + shiftJ)
        val moves = listOf(
            S7Move(i, state.colors[i].okLab.copyOf(), projectedI.lab.copyOf(), shiftI, projectedI.clipped),
            S7Move(j, state.colors[j].okLab.copyOf(), projectedJ.lab.copyOf(), shiftJ, projectedJ.clipped)
        )
        val dePair = distance(projectedI.lab, projectedJ.lab)
        return CandidateEvaluation(
            variant = variant,
            moves = moves,
            gain = gain,
            de95 = de95,
            gbi = gbi,
            dePair = dePair,
            clippedMoves = moves.count { it.clipped },
            reason = null
        )
    }

    private fun evaluateMedoidCandidate(
        i: Int,
        j: Int,
        base: CandidateEvaluation?,
        state: PaletteState,
        samples: List<S7Sample>,
        currentDe95: Float,
        currentGbi: Float
    ): CandidateEvaluation? {
        val baseEval = base ?: return null
        if (baseEval.moves.isEmpty()) return null
        val candLabI = baseEval.moves.first { it.i == i }.to
        val candLabJ = baseEval.moves.first { it.i == j }.to
        val indices = mergeArrays(state.assignments.perColorSamples[i], state.assignments.perColorSamples[j])
        if (indices.isEmpty()) return null
        val assignedToI = ArrayList<Int>()
        val assignedToJ = ArrayList<Int>()
        for (idx in indices) {
            val sampleLab = samples[idx].oklab
            val di = distance(sampleLab, candLabI)
            val dj = distance(sampleLab, candLabJ)
            if (di <= dj) {
                assignedToI += idx
            } else {
                assignedToJ += idx
            }
        }
        val medoidI = findWeightedMedoid(samples, assignedToI)
        val medoidJ = findWeightedMedoid(samples, assignedToJ)
        val labI = medoidI ?: candLabI
        val labJ = medoidJ ?: candLabJ
        val projectedI = projectLab(labI)
        val projectedJ = projectLab(labJ)
        if (projectedI.clipDelta > 1.0f || projectedJ.clipDelta > 1.0f) {
            return CandidateEvaluation(
                variant = "medoid",
                moves = emptyList(),
                gain = Float.NEGATIVE_INFINITY,
                de95 = currentDe95,
                gbi = currentGbi,
                dePair = distance(state.colors[i].okLab, state.colors[j].okLab),
                clippedMoves = 0,
                reason = "clip_too_large"
            )
        }
        val candidatePalette = state.colors.mapIndexed { idx, color ->
            when (idx) {
                i -> color.copy(okLab = projectedI.lab, sRGB = projectedI.argb, clipped = projectedI.clipped)
                j -> color.copy(okLab = projectedJ.lab, sRGB = projectedJ.argb, clipped = projectedJ.clipped)
                else -> color
            }
        }
        val candidateCache = state.assignCache.copy()
        candidateCache.updateColorWithFallback(i, projectedI.lab, TILE_ERROR_THRESHOLD)
        candidateCache.updateColorWithFallback(j, projectedJ.lab, TILE_ERROR_THRESHOLD)
        val assignmentsSnapshot = candidateCache.buildSummary()
        val assignments = assignmentsSnapshot.toAssignmentSummary()
        val de95 = assignments.de95
        val gbi = assignments.gbi
        val shiftI = distance(state.colors[i].okLab, projectedI.lab)
        val shiftJ = distance(state.colors[j].okLab, projectedJ.lab)
        val gain = S7Spread2OptSpec.ALPHA * (currentDe95 - de95) +
            S7Spread2OptSpec.BETA * (currentGbi - gbi) -
            S7Spread2OptSpec.MU * (shiftI + shiftJ)
        val moves = listOf(
            S7Move(i, state.colors[i].okLab.copyOf(), projectedI.lab.copyOf(), shiftI, projectedI.clipped),
            S7Move(j, state.colors[j].okLab.copyOf(), projectedJ.lab.copyOf(), shiftJ, projectedJ.clipped)
        )
        val dePair = distance(projectedI.lab, projectedJ.lab)
        return CandidateEvaluation(
            variant = "medoid",
            moves = moves,
            gain = gain,
            de95 = de95,
            gbi = gbi,
            dePair = dePair,
            clippedMoves = moves.count { it.clipped },
            reason = null
        )
    }

    private fun findWeightedMedoid(samples: List<S7Sample>, indices: List<Int>): FloatArray? {
        if (indices.isEmpty()) return null
        var bestIdx = indices[0]
        var bestScore = Double.POSITIVE_INFINITY
        for (candidate in indices) {
            val lab = samples[candidate].oklab
            var sum = 0.0
            for (other in indices) {
                val weight = samples[other].w.toDouble()
                val d = distance(lab, samples[other].oklab).toDouble()
                sum += weight * d
            }
            if (sum < bestScore - 1e-6 || (abs(sum - bestScore) <= 1e-6 && candidate < bestIdx)) {
                bestScore = sum
                bestIdx = candidate
            }
        }
        return samples[bestIdx].oklab.copyOf()
    }

    private fun mergeArrays(a: IntArray, b: IntArray): IntArray {
        if (a.isEmpty()) return b.copyOf()
        if (b.isEmpty()) return a.copyOf()
        val result = IntArray(a.size + b.size)
        var idx = 0
        for (v in a) result[idx++] = v
        for (v in b) result[idx++] = v
        result.sort()
        return result
    }

    private fun applyFix(state: PaletteState, fix: S7PairFix): PaletteState {
        if (fix.moves.isEmpty()) return state
        val newColors = state.colors.toMutableList()
        val cache = state.assignCache
        for (move in fix.moves) {
            val current = newColors[move.i]
            newColors[move.i] = current.copy(okLab = move.to.copyOf(), sRGB = labToArgb(move.to), clipped = move.clipped)
            cache.updateColorWithFallback(move.i, move.to, TILE_ERROR_THRESHOLD)
        }
        val assignments = cache.buildSummary().toAssignmentSummary()
        val violations = findViolations(newColors)
        val deMin = computeMinDistance(newColors)
        return state.copy(
            colors = newColors,
            assignments = assignments,
            assignCache = cache,
            violations = violations,
            deMin = deMin
        )
    }

    private fun projectLab(lab: FloatArray): ProjectedColor {
        val linear = ColorMgmt.oklabToRgbLinear(lab[0], lab[1], lab[2])
        var clipped = false
        val clamped = FloatArray(3)
        for (idx in 0..2) {
            var value = linear[idx]
            if (value < 0f) {
                value = 0f
                clipped = true
            } else if (value > 1f) {
                value = 1f
                clipped = true
            }
            clamped[idx] = value
        }
        val srgb = FloatArray(3)
        for (idx in 0..2) {
            srgb[idx] = ColorMgmt.linearToSrgb(clamped[idx])
        }
        val r8 = (srgb[0].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        val g8 = (srgb[1].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        val b8 = (srgb[2].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        val argb = Color.argb(255, r8, g8, b8)
        val labBack = ColorMgmt.rgbLinearToOKLab(clamped[0], clamped[1], clamped[2])
        val labArr = floatArrayOf(labBack.L, labBack.a, labBack.b)
        val delta = distance(lab, labArr)
        return ProjectedColor(lab = labArr, argb = argb, clipped = clipped, clipDelta = delta)
    }

    private fun labToArgb(lab: FloatArray): Int {
        val linear = ColorMgmt.oklabToRgbLinear(lab[0], lab[1], lab[2])
        val srgb = FloatArray(3)
        for (idx in 0..2) {
            val clamped = linear[idx].coerceIn(0f, 1f)
            srgb[idx] = ColorMgmt.linearToSrgb(clamped)
        }
        val r8 = (srgb[0].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        val g8 = (srgb[1].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        val b8 = (srgb[2].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        return Color.argb(255, r8, g8, b8)
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        return deltaE(a[0], a[1], a[2], b[0], b[1], b[2])
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
        if (angle < 0.0) angle += 2.0 * Math.PI
        return angle
    }

    private fun hueDelta(c1p: Double, c2p: Double, h1p: Double, h2p: Double): Double {
        if (c1p * c2p == 0.0) return 0.0
        val diff = h2p - h1p
        return when {
            diff > Math.PI -> (h2p - 2.0 * Math.PI) - h1p
            diff < -Math.PI -> (h2p + 2.0 * Math.PI) - h1p
            else -> diff
        }
    }

    private fun meanHue(h1p: Double, h2p: Double, c1p: Double, c2p: Double): Double {
        if (c1p * c2p == 0.0) return h1p + h2p
        val diff = abs(h1p - h2p)
        return when {
            diff <= Math.PI -> (h1p + h2p) / 2.0
            h1p + h2p < 2.0 * Math.PI -> (h1p + h2p + 2.0 * Math.PI) / 2.0
            else -> (h1p + h2p - 2.0 * Math.PI) / 2.0
        }
    }

    private fun degToRad(deg: Double): Double = deg / 180.0 * Math.PI

    private fun unitVector(to: FloatArray, from: FloatArray): FloatArray {
        val dx = to[0] - from[0]
        val dy = to[1] - from[1]
        val dz = to[2] - from[2]
        val norm = sqrt(dx * dx + dy * dy + dz * dz)
        if (norm <= 1e-6f) return floatArrayOf(1f, 0f, 0f)
        val inv = 1f / norm
        return floatArrayOf(dx * inv, dy * inv, dz * inv)
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
}
