package com.appforcross.editor.palette

/**
 * Константы шага S7.2 (инициализация K₀ палитры).
 */
object S7InitSpec {
    enum class PaletteZone {
        SKIN,
        SKY,
        EDGE,
        HITEX,
        FLAT,
        NEUTRAL
    }

    data class AnchorFallbackRange(
        val lMin: Float,
        val lMax: Float,
        val hueMinDeg: Float,
        val hueMaxDeg: Float,
        val cMin: Float,
        val cMax: Float
    )

    const val K0_TARGET: Int = 14
    const val S_MIN: Float = 3.5f
    const val C_LOW: Float = 0.03f
    const val TAU_N: Float = 0.02f
    const val TAU_COVER: Float = 0.05f

    val SKIN_FALLBACK_RANGE = AnchorFallbackRange(
        lMin = 0.60f,
        lMax = 0.90f,
        hueMinDeg = 20f,
        hueMaxDeg = 70f,
        cMin = 0.02f,
        cMax = 0.20f
    )

    val SKY_FALLBACK_RANGE = AnchorFallbackRange(
        lMin = 0.60f,
        lMax = 1.00f,
        hueMinDeg = 200f,
        hueMaxDeg = 250f,
        cMin = 0.02f,
        cMax = 0.20f
    )

    val ROLE_ORDER: List<PaletteZone> = listOf(
        PaletteZone.SKIN,
        PaletteZone.SKY,
        PaletteZone.EDGE,
        PaletteZone.HITEX,
        PaletteZone.FLAT,
        PaletteZone.NEUTRAL
    )

    val ROI_QUOTAS: Map<PaletteZone, Float> = mapOf(
        PaletteZone.EDGE to 0.20f,
        PaletteZone.SKIN to 0.20f,
        PaletteZone.SKY to 0.15f,
        PaletteZone.HITEX to 0.25f,
        PaletteZone.FLAT to 0.20f
    )

    const val DEFAULT_SEED: Long = 7331L
}
