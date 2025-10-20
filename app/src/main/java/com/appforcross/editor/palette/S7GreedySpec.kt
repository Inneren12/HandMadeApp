package com.appforcross.editor.palette

object S7GreedySpec {
    const val B_L: Int = 24
    const val B_A: Int = 24
    const val B_B: Int = 24

    const val GAMMA_QUOTA: Double = 0.5
    const val S_DUP: Float = 1.0f

    const val R0: Float = 5.0f
    const val R_MIN: Float = 2.5f
    const val R_DECAY: Float = 0.08f

    const val KTRY_DEFAULT: Int = 8

    val ROI_QUOTAS: Map<S7InitSpec.PaletteZone, Float> = mapOf(
        S7InitSpec.PaletteZone.EDGE to 0.20f,
        S7InitSpec.PaletteZone.SKIN to 0.20f,
        S7InitSpec.PaletteZone.SKY to 0.15f,
        S7InitSpec.PaletteZone.HITEX to 0.25f,
        S7InitSpec.PaletteZone.FLAT to 0.20f
    )

    val B_a: Int
        get() = B_A
    val B_b: Int
        get() = B_B
    val gamma_quota: Double
        get() = GAMMA_QUOTA
    val s_dup: Float
        get() = S_DUP
    val r_min: Float
        get() = R_MIN
    val r_decay: Float
        get() = R_DECAY
    val kTry_default: Int
        get() = KTRY_DEFAULT
    val roi_quotas: Map<S7InitSpec.PaletteZone, Float>
        get() = ROI_QUOTAS
}
