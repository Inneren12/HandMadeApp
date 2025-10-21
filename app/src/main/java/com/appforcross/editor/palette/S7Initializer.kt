package com.appforcross.editor.palette

import android.graphics.Color
import com.appforcross.editor.config.FeatureFlags
import com.handmadeapp.color.ColorMgmt
import com.handmadeapp.logging.Logger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class S7InitColor(
    val okLab: FloatArray,
    val sRGB: Int,
    val protected: Boolean,
    val role: S7InitSpec.PaletteZone,
    val spreadMin: Float,
    val clipped: Boolean
)

data class S7InitResult(
    val colors: List<S7InitColor>,
    val anchors: Map<String, Int>,
    val quotasUsed: Map<S7InitSpec.PaletteZone, Int>,
    val params: Map<String, Any?>,
    val notes: List<String>
)

object S7Initializer {

    fun run(sampling: S7SamplingResult, seed: Long): S7InitResult {
        S7ThreadGuard.assertBackground("s7.initializer.run")
        require(sampling.samples.isNotEmpty()) { "Sampling is empty" }
        val notes = ArrayList<String>()
        val params = LinkedHashMap<String, Any?>()
        val rngSeed = seed

        val totalSamples = sampling.samples.size
        val coverage = computeCoverage(sampling)
        val logStart = mapOf(
            "K0_target" to S7InitSpec.K0_TARGET,
            "s_min" to S7InitSpec.S_MIN,
            "quotas" to S7InitSpec.ROI_QUOTAS.mapKeys { it.key.name },
            "seed" to rngSeed,
            "Nsamp" to totalSamples,
            "coverage_skin" to coverage[S7InitSpec.PaletteZone.SKIN],
            "coverage_sky" to coverage[S7InitSpec.PaletteZone.SKY]
        )
        Logger.i("PALETTE", "init.start", logStart)

        params["K0_target"] = S7InitSpec.K0_TARGET
        params["s_min"] = S7InitSpec.S_MIN
        params["seed"] = rngSeed
        params["Nsamp"] = totalSamples
        params["coverage"] = coverage.mapKeys { it.key.name }

        val protectedColors = ArrayList<MutableColor>()
        val anchorIndices = LinkedHashMap<String, Int>()

        val black = detectBlack(sampling)
        protectedColors += black.copy(anchorName = "black")
        val white = detectWhite(sampling)
        protectedColors += white.copy(anchorName = "white")
        val neutral = detectNeutralMid(sampling)
        protectedColors += neutral.copy(anchorName = "neutral_mid")

        val skinCoverage = coverage[S7InitSpec.PaletteZone.SKIN] ?: 0f
        val skyCoverage = coverage[S7InitSpec.PaletteZone.SKY] ?: 0f

        val skinAnchor = detectSkinAnchor(sampling, skinCoverage, notes)
        val skyAnchor = detectSkyAnchor(sampling, skyCoverage, notes)
        skinAnchor?.let { protectedColors += it.copy(anchorName = "skin") }
        skyAnchor?.let { protectedColors += it.copy(anchorName = "sky") }
        params["free_slots"] = max(0, S7InitSpec.K0_TARGET - protectedColors.size)

        Logger.i(
            "PALETTE",
            "anchors.detect",
            run {
                val skinNote: Map<String, Any> =
                    if (skinAnchor != null) mapOf("ok" to true)
                    else mapOf(
                        "ok" to false,
                "reason" to if (skinCoverage >= S7InitSpec.TAU_COVER) "no_candidate" else "fallback"
                )
                val skyNote: Map<String, Any> =
                    if (skyAnchor != null) mapOf("ok" to true)
                    else mapOf(
                        "ok" to false,
                "reason" to if (skyCoverage >= S7InitSpec.TAU_COVER) "no_candidate" else "fallback"
                )
                linkedMapOf<String, Any>(
                    "black"       to mapOf<String, Any>("L" to black.lab[0],   "C" to chroma(black.lab)),
                    "white"       to mapOf<String, Any>("L" to white.lab[0],   "C" to chroma(white.lab)),
                    "neutral_mid" to mapOf<String, Any>("L" to neutral.lab[0], "a" to neutral.lab[1], "b" to neutral.lab[2]),
                    "skin"        to skinNote,
                    "sky"         to skyNote
                )
            }
        )

        val requestedK = max(S7InitSpec.K0_TARGET, protectedColors.size)
        val freeSlots = max(0, requestedK - protectedColors.size)

        val zoneBuckets = buildZoneBuckets(sampling)
        val quotas = computeQuotas(freeSlots, zoneBuckets, notes)
        params["quotas_target"] = quotas.mapKeys { it.key.name }

        val candidateStats = CandidateStats()
        val palette = ArrayList<MutableColor>()
        for (anchor in protectedColors) {
            shouldKeep(anchor, palette)
            palette += anchor
        }

        for (zone in S7InitSpec.ROLE_ORDER) {
            if (zone == S7InitSpec.PaletteZone.NEUTRAL) continue
            val need = quotas[zone] ?: 0
            if (need <= 0) continue
            val picks = pickZoneColors(zone, need, sampling, palette, candidateStats)
            palette.addAll(picks)
        }

        if (palette.size < requestedK) {
            val deficit = requestedK - palette.size
            if (deficit > 0) {
                val filler = fillDeficit(deficit, sampling, palette, candidateStats)
                if (filler.isNotEmpty()) {
                    palette.addAll(filler)
                    notes += "quota_redistributed"
                }
            }
        }

        val finalPalette = palette.distinctBy { keyForLab(it.lab) }
        if (finalPalette.size < palette.size) {
            notes += "duplicates_removed"
        }

        val roles = assignRoles(finalPalette, sampling)
        val paired = finalPalette.indices.map { idx -> PaletteWithRole(finalPalette[idx], roles[idx]) }
        val sorted = paired.sortedWith(paletteComparator())

        val sortedPalette = sorted.map { it.color }
        val sortedRoles = sorted.map { it.role }

        sorted.forEachIndexed { index, item ->
            val anchorName = item.color.anchorName
            if (anchorName != null) {
                anchorIndices[anchorName] = index
            }
        }

        val srgbResults = sortedPalette.map { labToArgb(it.lab) }
        val spreadValues = computeSpread(sortedPalette)
        val paletteWithMeta = sortedPalette.mapIndexed { idx, color ->
            val (argb, clipped) = srgbResults[idx]
            S7InitColor(
                okLab = color.lab.copyOf(),
                sRGB = argb,
                protected = color.protected,
                role = sortedRoles[idx],
                spreadMin = spreadValues[idx],
                clipped = clipped
            )
        }

        val roleCounts = sortedRoles.groupingBy { it }.eachCount()
        val quotasUsed = S7InitSpec.ROLE_ORDER.associateWith { roleCounts[it] ?: 0 }

        val minSpread = spreadValues.filter { it.isFinite() }.minOrNull() ?: Float.POSITIVE_INFINITY
        val clippedIndices = paletteWithMeta.withIndex().filter { it.value.clipped }.map { it.index }

        Logger.i(
            "PALETTE",
            "modes.pick",
            mapOf(
                "roi_counts" to sampling.roiHist.mapKeys { it.key.name },
                "requested" to quotas.mapKeys { it.key.name },
                "accepted" to sortedRoles.groupingBy { it }.eachCount().mapKeys { it.key.name },
                "rejected" to candidateStats.toMap()
            )
        )

        Logger.i(
            "PALETTE",
            "roles.assign",
            mapOf(
                "counts_per_role" to roleCounts.mapKeys { it.key.name },
                "unclassified" to roles.count { it == S7InitSpec.PaletteZone.NEUTRAL }
            )
        )

        Logger.i(
            "PALETTE",
            "init.done",
            mapOf(
                "K0" to paletteWithMeta.size,
                "min_spread" to minSpread,
                "clipped_colors" to clippedIndices,
                "protected" to paletteWithMeta.withIndex().filter { it.value.protected }.map { it.index },
                "order" to S7InitSpec.ROLE_ORDER.map { it.name }
            )
        )

        return S7InitResult(
            colors = paletteWithMeta,
            anchors = anchorIndices,
            quotasUsed = quotasUsed,
            params = params,
            notes = notes
        )
    }

    private fun computeCoverage(sampling: S7SamplingResult): Map<S7InitSpec.PaletteZone, Float> {
        val total = sampling.samples.size.takeIf { it > 0 } ?: return emptyMap()
        val counts = HashMap<S7InitSpec.PaletteZone, Int>()
        for (zone in S7SamplingSpec.Zone.entries) {
            val mapped = zone.toPaletteZone()
            counts[mapped] = sampling.roiHist[zone] ?: 0
        }
        return counts.mapValues { it.value / total.toFloat() }
    }

    private fun detectBlack(sampling: S7SamplingResult): MutableColor {
        val sorted = sampling.samples.sortedBy { it.oklab[0] }
        val cutoffIndex = max(sorted.size / 20, 1)
        val subset = sorted.take(cutoffIndex)
        val candidate = subset.minByOrNull { chroma(it.oklab) } ?: sorted.first()
        return MutableColor(candidate.oklab.copyOf(), true, S7InitSpec.PaletteZone.NEUTRAL, "anchor.black")
    }

    private fun detectWhite(sampling: S7SamplingResult): MutableColor {
        val sorted = sampling.samples.sortedByDescending { it.oklab[0] }
        val cutoffIndex = max(sorted.size / 20, 1)
        val subset = sorted.take(cutoffIndex)
        val candidate = subset.minByOrNull { chroma(it.oklab) } ?: sorted.first()
        return MutableColor(candidate.oklab.copyOf(), true, S7InitSpec.PaletteZone.NEUTRAL, "anchor.white")
    }

    private fun detectNeutralMid(sampling: S7SamplingResult): MutableColor {
        val candidates = sampling.samples.filter {
            abs(it.oklab[1]) <= S7InitSpec.TAU_N && abs(it.oklab[2]) <= S7InitSpec.TAU_N
        }
        val targetL = 0.55f
        val sample = candidates.minByOrNull { abs(it.oklab[0] - targetL) }
            ?: sampling.samples.minByOrNull { abs(it.oklab[0] - targetL) }!!
        return MutableColor(sample.oklab.copyOf(), true, S7InitSpec.PaletteZone.NEUTRAL, "anchor.neutral")
    }

    private fun detectSkinAnchor(
        sampling: S7SamplingResult,
        coverage: Float,
        notes: MutableList<String>
    ): MutableColor? {
        val samples = sampling.samples.filter { it.zone == S7SamplingSpec.Zone.SKIN }
        if (coverage >= S7InitSpec.TAU_COVER && samples.isNotEmpty()) {
            val candidate = weightedMedian(samples)
            return MutableColor(candidate.copyOf(), true, S7InitSpec.PaletteZone.SKIN, "anchor.skin")
        }
        if (FeatureFlags.S7_INIT_FALLBACKS) {
            val fallback = selectFallback(sampling, S7InitSpec.SKIN_FALLBACK_RANGE)
            if (fallback != null) {
                notes += "skin_anchor:fallback"
                return MutableColor(fallback, true, S7InitSpec.PaletteZone.SKIN, "anchor.skin")
            }
        }
        notes += if (FeatureFlags.S7_INIT_FALLBACKS) "skin_anchor:missing" else "skin_anchor:fallback_disabled"
        return null
    }

    private fun detectSkyAnchor(
        sampling: S7SamplingResult,
        coverage: Float,
        notes: MutableList<String>
    ): MutableColor? {
        val samples = sampling.samples.filter { it.zone == S7SamplingSpec.Zone.SKY }
        if (coverage >= S7InitSpec.TAU_COVER && samples.isNotEmpty()) {
            val candidate = weightedMedian(samples)
            return MutableColor(candidate.copyOf(), true, S7InitSpec.PaletteZone.SKY, "anchor.sky")
        }
        if (FeatureFlags.S7_INIT_FALLBACKS) {
            val fallback = selectFallback(sampling, S7InitSpec.SKY_FALLBACK_RANGE)
            if (fallback != null) {
                notes += "sky_anchor:fallback"
                return MutableColor(fallback, true, S7InitSpec.PaletteZone.SKY, "anchor.sky")
            }
        }
        notes += if (FeatureFlags.S7_INIT_FALLBACKS) "sky_anchor:missing" else "sky_anchor:fallback_disabled"
        return null
    }

    private fun weightedMedian(samples: List<S7Sample>): FloatArray {
        val sorted = samples.sortedBy { it.oklab[0] }
        val totalWeight = sorted.sumOf { it.w.toDouble() }.toFloat().takeIf { it > 0f } ?: return sorted.first().oklab
        var acc = 0f
        val target = totalWeight / 2f
        for (sample in sorted) {
            acc += sample.w
            if (acc >= target) return sample.oklab
        }
        return sorted.last().oklab
    }

    private fun selectFallback(sampling: S7SamplingResult, range: S7InitSpec.AnchorFallbackRange): FloatArray? {
        val candidates = sampling.samples.filter { s ->
            val lab = s.oklab
            val L = lab[0]
            val a = lab[1]
            val b = lab[2]
            val C = chroma(lab)
            if (L < range.lMin || L > range.lMax) return@filter false
            if (C < range.cMin || C > range.cMax) return@filter false
            val hue = hueDeg(a, b)
            hue in range.hueMinDeg..range.hueMaxDeg
        }
        return candidates.maxByOrNull { it.w }?.oklab?.copyOf()
    }

    private fun buildZoneBuckets(sampling: S7SamplingResult): Map<S7InitSpec.PaletteZone, List<S7Sample>> {
        val buckets = LinkedHashMap<S7InitSpec.PaletteZone, MutableList<S7Sample>>()
        for (zone in S7InitSpec.PaletteZone.values()) {
            if (zone != S7InitSpec.PaletteZone.NEUTRAL) {
                buckets[zone] = ArrayList()
            }
        }
        for (sample in sampling.samples) {
            val zone = sample.zone.toPaletteZone()
            buckets[zone]?.add(sample)
        }
        return buckets
    }

    private fun computeQuotas(
        freeSlots: Int,
        zoneBuckets: Map<S7InitSpec.PaletteZone, List<S7Sample>>,
        notes: MutableList<String>
    ): Map<S7InitSpec.PaletteZone, Int> {
        if (freeSlots <= 0) return emptyMap()
        val quotas = LinkedHashMap<S7InitSpec.PaletteZone, Int>()
        val remainders = ArrayList<Pair<S7InitSpec.PaletteZone, Float>>()
        var allocated = 0
        for ((zone, share) in S7InitSpec.ROI_QUOTAS) {
            val bucket = zoneBuckets[zone]
            val target = share * freeSlots
            val base = target.toInt()
            val available = bucket?.size ?: 0
            val value = min(base, available)
            quotas[zone] = value
            allocated += value
            remainders += zone to (target - base)
        }
        var remaining = freeSlots - allocated
        if (remaining > 0) {
            for ((zone, fraction) in remainders.sortedByDescending { it.second }) {
                if (remaining <= 0) break
                val bucket = zoneBuckets[zone]
                val current = quotas[zone] ?: 0
                val available = bucket?.size ?: 0
                if (current < available) {
                    quotas[zone] = current + 1
                    remaining--
                }
            }
        }
        if (remaining > 0) {
            notes += "quota_redistributed"
        }
        return quotas
    }

    private fun pickZoneColors(
        zone: S7InitSpec.PaletteZone,
        need: Int,
        sampling: S7SamplingResult,
        palette: MutableList<MutableColor>,
        stats: CandidateStats
    ): List<MutableColor> {
        val samples = sampling.samples.filter { it.zone.toPaletteZone() == zone }
        if (samples.isEmpty()) return emptyList()
        val sorted = samples.sortedWith(compareByDescending<S7Sample> { it.w }.thenBy { it.x }.thenBy { it.y })
        val picked = ArrayList<MutableColor>()
        for (sample in sorted) {
            if (picked.size >= need) break
            val candidate = MutableColor(sample.oklab.copyOf(), false, zone, "mode.${zone.name.lowercase()}")
            if (shouldKeep(candidate, palette + picked)) {
                picked += candidate
                stats.accepted++
            } else {
                stats.rejectedSpread++
            }
        }
        return picked
    }

    private fun fillDeficit(
        deficit: Int,
        sampling: S7SamplingResult,
        palette: MutableList<MutableColor>,
        stats: CandidateStats
    ): List<MutableColor> {
        if (deficit <= 0) return emptyList()
        val sorted = sampling.samples.sortedWith(compareByDescending<S7Sample> { it.w }.thenBy { it.x }.thenBy { it.y })
        val picked = ArrayList<MutableColor>()
        for (sample in sorted) {
            if (picked.size >= deficit) break
            val zone = sample.zone.toPaletteZone()
            val candidate = MutableColor(sample.oklab.copyOf(), false, zone, "filler.${zone.name.lowercase()}")
            if (shouldKeep(candidate, palette + picked)) {
                picked += candidate
                stats.accepted++
            } else {
                stats.rejectedSpread++
            }
        }
        return picked
    }

    private fun shouldKeep(candidate: MutableColor, current: List<MutableColor>): Boolean {
        val distances = current.map { deltaE(candidate.lab, it.lab) }
        val spreadMin = distances.minOrNull() ?: Float.POSITIVE_INFINITY
        val action = when {
            candidate.protected -> "keep"
            spreadMin.isInfinite() -> "keep"
            spreadMin >= S7InitSpec.S_MIN -> "keep"
            else -> "drop"
        }
        Logger.d(
            "PALETTE",
            "spread.enforce",
            mapOf(
                "candidate" to candidate.tag,
                "spreadMin" to spreadMin,
                "s_min" to S7InitSpec.S_MIN,
                "action" to action,
                "protected" to candidate.protected
            )
        )
        return action != "drop"
    }

    private fun assignRoles(palette: List<MutableColor>, sampling: S7SamplingResult): List<S7InitSpec.PaletteZone> {
        val totals = FloatArray(palette.size)
        val perZone = Array(palette.size) { hashMapOf<S7InitSpec.PaletteZone, Float>() }
        for (sample in sampling.samples) {
            val lab = sample.oklab
            var bestIndex = 0
            var bestDistance = Float.MAX_VALUE
            for (i in palette.indices) {
                val d = deltaE(lab, palette[i].lab)
                if (d < bestDistance) {
                    bestDistance = d
                    bestIndex = i
                }
            }
            val zone = sample.zone.toPaletteZone()
            val map = perZone[bestIndex]
            map[zone] = (map[zone] ?: 0f) + sample.w
            totals[bestIndex] += sample.w
        }
        return palette.mapIndexed { index, color ->
            if (color.anchorName == "skin") {
                S7InitSpec.PaletteZone.SKIN
            } else if (color.anchorName == "sky") {
                S7InitSpec.PaletteZone.SKY
            } else {
                val map = perZone[index]
                val total = totals[index]
                if (total <= 0f) {
                    S7InitSpec.PaletteZone.NEUTRAL
                } else {
                    val (zone, weight) = map.maxByOrNull { it.value } ?: return@mapIndexed S7InitSpec.PaletteZone.NEUTRAL
                    if (weight / total >= 0.45f) zone else S7InitSpec.PaletteZone.NEUTRAL
                }
            }
        }
    }

    private fun computeSpread(palette: List<MutableColor>): List<Float> {
        return palette.mapIndexed { index, color ->
            var minDist = Float.POSITIVE_INFINITY
            for (j in palette.indices) {
                if (j == index) continue
                val d = deltaE(color.lab, palette[j].lab)
                if (d < minDist) minDist = d
            }
            if (minDist == Float.POSITIVE_INFINITY) Float.POSITIVE_INFINITY else minDist
        }
    }

    private fun labToArgb(lab: FloatArray): Pair<Int, Boolean> {
        val rgbLin = ColorMgmt.oklabToRgbLinear(lab[0], lab[1], lab[2])
        var clipped = false
        for (i in 0 until 3) {
            if (rgbLin[i] < 0f || rgbLin[i] > 1f) {
                clipped = true
            }
        }
        val linearClamped = FloatArray(3) { idx -> rgbLin[idx].coerceIn(0f, 1f) }
        val srgb = FloatArray(3) { idx -> ColorMgmt.linearToSrgb(linearClamped[idx]).coerceIn(0f, 1f) }
        val r = (srgb[0] * 255f).roundToInt().coerceIn(0, 255)
        val g = (srgb[1] * 255f).roundToInt().coerceIn(0, 255)
        val b = (srgb[2] * 255f).roundToInt().coerceIn(0, 255)
        val argb = Color.argb(255, r, g, b)
        return argb to clipped
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

    private fun hueDeg(a: Float, b: Float): Float {
        val angle = Math.toDegrees(atan2(b.toDouble(), a.toDouble()))
        val normalized = if (angle < 0) angle + 360.0 else angle
        return normalized.toFloat()
    }

    private fun chroma(lab: FloatArray): Float = sqrt(lab[1] * lab[1] + lab[2] * lab[2])

    private fun S7SamplingSpec.Zone.toPaletteZone(): S7InitSpec.PaletteZone = when (this) {
        S7SamplingSpec.Zone.SKIN -> S7InitSpec.PaletteZone.SKIN
        S7SamplingSpec.Zone.SKY -> S7InitSpec.PaletteZone.SKY
        S7SamplingSpec.Zone.EDGE -> S7InitSpec.PaletteZone.EDGE
        S7SamplingSpec.Zone.HITEX -> S7InitSpec.PaletteZone.HITEX
        S7SamplingSpec.Zone.FLAT -> S7InitSpec.PaletteZone.FLAT
    }

    private fun keyForLab(lab: FloatArray): String {
        return "${String.format(java.util.Locale.US, "%.5f", lab[0])}_${String.format(java.util.Locale.US, "%.5f", lab[1])}_${String.format(java.util.Locale.US, "%.5f", lab[2])}"
    }

    private fun paletteComparator(): Comparator<PaletteWithRole> {
        val roleIndex = S7InitSpec.ROLE_ORDER.withIndex().associate { it.value to it.index }
        return Comparator { a, b ->
            val roleCmp = (roleIndex[a.role] ?: Int.MAX_VALUE).compareTo(roleIndex[b.role] ?: Int.MAX_VALUE)
            if (roleCmp != 0) return@Comparator roleCmp
            val lCmp = a.color.lab[0].compareTo(b.color.lab[0])
            if (lCmp != 0) return@Comparator lCmp
            val hueA = hueDeg(a.color.lab[1], a.color.lab[2])
            val hueB = hueDeg(b.color.lab[1], b.color.lab[2])
            hueA.compareTo(hueB)
        }
    }

    private data class MutableColor(
        val lab: FloatArray,
        val protected: Boolean,
        val preferredZone: S7InitSpec.PaletteZone,
        val tag: String,
        val anchorName: String? = null
    ) {
        fun copy(anchorName: String? = this.anchorName): MutableColor =
            MutableColor(lab.copyOf(), protected, preferredZone, tag, anchorName)
    }

    private data class PaletteWithRole(val color: MutableColor, val role: S7InitSpec.PaletteZone)

    private class CandidateStats {
        var accepted: Int = 0
        var rejectedSpread: Int = 0
        fun toMap(): Map<String, Any> = mapOf(
            "accepted" to accepted,
            "spread" to rejectedSpread
        )
    }
}
