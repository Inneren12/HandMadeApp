package com.appforcross.editor.palette

object S7Spread2OptSpec {
    const val S_MIN: Float = 3.5f
    const val DELTA_MAX: Float = 0.6f
    const val ALPHA: Float = 1.0f
    const val BETA: Float = 0.6f
    const val MU: Float = 0.05f
    const val GAMMA_ERR: Float = 0.5f

    const val M_LOW: Int = 12
    const val M_MID: Int = 24
    const val M_HIGH: Int = 36

    const val P_PASSES_DEFAULT: Int = 1
    const val P_PASSES_MAX: Int = 2

    private val TIME_BUDGET_MS: Map<S7SamplingSpec.DeviceTier, Long> = mapOf(
        S7SamplingSpec.DeviceTier.LOW to 250L,
        S7SamplingSpec.DeviceTier.MID to 400L,
        S7SamplingSpec.DeviceTier.HIGH to 650L
    )

    fun timeBudgetMsForTier(tier: S7SamplingSpec.DeviceTier): Long {
        return TIME_BUDGET_MS[tier] ?: 400L
    }

    fun pairsBudgetForTier(tier: S7SamplingSpec.DeviceTier): Int {
        return when (tier) {
            S7SamplingSpec.DeviceTier.LOW -> M_LOW
            S7SamplingSpec.DeviceTier.MID -> M_MID
            S7SamplingSpec.DeviceTier.HIGH -> M_HIGH
        }
    }
}
