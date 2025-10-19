package com.appforcross.editor.palette

import android.app.ActivityManager
import android.content.Context

/**
 * Константы и параметры шага S7.1: веса зон, беты и размеры выборки.
 */
object S7SamplingSpec {
    enum class Zone { EDGE, SKIN, SKY, HITEX, FLAT }

    enum class DeviceTier(val key: String, val targetSamples: Int) {
        LOW("LOW", 30_000),
        MID("MID", 60_000),
        HIGH("HIGH", 120_000);

        companion object {
            fun fromKey(key: String?): DeviceTier {
                if (key == null) return MID
                return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: MID
            }
        }
    }

    const val DEFAULT_SEED: Long = 1337L

    const val BETA_EDGE = 0.8f
    const val BETA_BAND = 0.6f
    const val BETA_NOISE = 0.5f

    val ROI_WEIGHTS: Map<Zone, Float> = mapOf(
        Zone.EDGE to 1.40f,
        Zone.SKIN to 1.30f,
        Zone.SKY to 1.20f,
        Zone.HITEX to 1.10f,
        Zone.FLAT to 0.80f
    )

    val DEFAULT_ZONE_ORDER: List<Zone> = listOf(
        Zone.SKIN,
        Zone.SKY,
        Zone.EDGE,
        Zone.HITEX,
        Zone.FLAT
    )

    fun targetSamplesForTier(tierKey: String): Int = DeviceTier.fromKey(tierKey).targetSamples

    fun detectDeviceTier(context: Context): DeviceTier {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryClass = am?.memoryClass ?: 128
        return when {
            memoryClass <= 96 -> DeviceTier.LOW
            memoryClass >= 192 -> DeviceTier.HIGH
            else -> DeviceTier.MID
        }
    }
}
