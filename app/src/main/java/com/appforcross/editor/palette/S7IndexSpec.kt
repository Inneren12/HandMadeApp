package com.appforcross.editor.palette

import java.util.Locale

object S7IndexSpec {
    const val ALGO_VERSION: String = "S7.6-index-v1"
    const val ALPHA0: Double = 1.0
    const val BETA_FZ: Double = 0.6
    const val BETA_EDGE: Double = 0.5
    const val BETA_SKIN: Double = 0.4
    const val BETA_COH: Double = 0.2

    const val TAU_H_DEG: Double = 8.0
    val TAU_H_RAD: Double = Math.toRadians(TAU_H_DEG)

    data class TileSpec(val width: Int, val height: Int)

    private val TILE_DEFAULT = TileSpec(512, 512)

    private val TILE_BY_TIER: Map<String, TileSpec> = mapOf(
        "LOW" to TileSpec(320, 320),
        "MID" to TileSpec(448, 448),
        "HIGH" to TileSpec(640, 640)
    )

    const val TILE_OVERLAP: Int = 1

    const val COUNTS_TOP_N: Int = 6

    const val FOREIGN_ZONE_NOTE_FRACTION: Double = 0.12

    fun tileForTier(tier: String?, width: Int, height: Int): TileSpec {
        val key = tier?.uppercase(Locale.US)
        val spec = TILE_BY_TIER[key] ?: TILE_DEFAULT
        val tileW = spec.width.coerceAtLeast(1).coerceAtMost(width)
        val tileH = spec.height.coerceAtLeast(1).coerceAtMost(height)
        return TileSpec(tileW, tileH)
    }
}
