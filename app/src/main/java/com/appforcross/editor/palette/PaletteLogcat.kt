package com.appforcross.editor.palette

import android.util.Log
import com.handmadeapp.logging.Logger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Резюме финальной палитры: K*, minΔE00, head[N] с ролями, сводка по ролям.
 * Вывод в logcat (AiX/PALETTE) и в структурный лог (log.jsonl).
 */
object PaletteLogcat {

    @JvmStatic
    fun printFinalPalette(
        colors: List<S7InitColor>,
        tagCat: String = "PALETTE",
        headN: Int = 20
    ) {
        val k = colors.size
        if (k == 0) {
            Log.i("AiX/$tagCat", "K*=0 (empty palette)")
            Logger.i(tagCat, "palette.summary", mapOf("K" to 0))
            return
        }

        val minSpread = minDeltaE00(colors)

        // Сводка по ролям (детерминированный порядок ключей)
        val roleCounts: Map<String, Int> =
            colors.groupingBy { it.role.name }.eachCount().toSortedMap()

        // Первые N цветов с ролями: "#RRGGBB[ROLE]"
        val n = headN.coerceAtMost(k)
        val headItems = colors.take(n).joinToString(",") { c ->
            val hex = "#%06X".format(c.sRGB and 0xFFFFFF)
            val role = c.role.name
            "$hex[$role]"
        }
        val more = (k - n).coerceAtLeast(0)
        val moreStr = if (more > 0) " …+$more" else ""

        // logcat — короткая строка
        Log.i(
            "AiX/$tagCat",
            "K*=$k  minΔE00=%.2f  roles=%s  head[%d]=[%s]%s"
                .format(minSpread, roleCounts, n, headItems, moreStr)
        )

        // Структурный лог — уходит в log.jsonl
        Logger.i(
            tagCat,
            "palette.summary",
            mapOf(
                "K" to k,
                "minSpread" to String.format("%.2f", minSpread),
                "headN" to n,
                "roles" to roleCounts,
                "head" to headItems,
                "more" to more
            )
        )
    }

    /**
     * Компактный формат для logcat: роли в виде аббревиатур SKY, SKN, EDG, HTX, FLT, NEU
     * и короткая "голова" палитры (по умолчанию 8 цветов).
     */
    @JvmStatic
    fun printFinalPaletteCompact(
        colors: List<S7InitColor>,
        headN: Int = 8,
        tagCat: String = "PALETTE"
    ) {
        val k = colors.size
        if (k == 0) {
            Log.i("AiX/$tagCat", "K*=0")
            Logger.i(tagCat, "palette.summary.compact", mapOf("K" to 0))
            return
        }

        val minSpread = minDeltaE00(colors)
        val rolesCompact = compactRolesSummary(colors)

        val n = headN.coerceAtMost(k)
        val head = colors.take(n).joinToString(",") { "#%06X".format(it.sRGB and 0xFFFFFF) }
        val more = (k - n).coerceAtLeast(0)
        val moreStr = if (more > 0) " …+$more" else ""

        Log.i(
            "AiX/$tagCat",
            "K*=%d minΔE=%.2f roles{%s} head%d=%s%s".format(k, minSpread, rolesCompact, n, head, moreStr)
        )

        Logger.i(
            tagCat,
            "palette.summary.compact",
            mapOf(
                "K" to k,
                "minSpread" to String.format("%.2f", minSpread),
                "roles" to rolesCompact,
                "headN" to n,
                "head" to head,
                "more" to more
            )
        )
    }

    /**
     * Компактная строка для UI-статуса: "S7.5: K*=42 | roles SKY=7 SKN=8 EDG=6 | minΔE=3.68"
     * Можно передать de95/GBI, если есть.
     */
    @JvmStatic
    fun buildUiStatusCompact(
        colors: List<S7InitColor>,
        prefix: String = "S7.5",
        de95: Float? = null,
        gbi: Float? = null
    ): String {
        val k = colors.size
        val minSpread = minDeltaE00(colors)
        val roles = compactRolesSummary(colors).replace(',', ' ')

        val parts = mutableListOf<String>()
        parts += "$prefix: K*=$k"
        parts += "roles $roles"
        parts += "minΔE=%.2f".format(minSpread)
        if (de95 != null) parts += "ΔE95=%.2f".format(de95)
        if (gbi != null) parts += "GBI=%.3f".format(gbi)
        return parts.joinToString(" | ")
    }

    private fun compactRolesSummary(colors: List<S7InitColor>): String {
        val order = listOf(
            S7InitSpec.PaletteZone.SKY,
            S7InitSpec.PaletteZone.SKIN,
            S7InitSpec.PaletteZone.EDGE,
            S7InitSpec.PaletteZone.HITEX,
            S7InitSpec.PaletteZone.FLAT,
            S7InitSpec.PaletteZone.NEUTRAL
        )
        val abbr = mapOf(
            S7InitSpec.PaletteZone.SKY to "SKY",
            S7InitSpec.PaletteZone.SKIN to "SKN",
            S7InitSpec.PaletteZone.EDGE to "EDG",
            S7InitSpec.PaletteZone.HITEX to "HTX",
            S7InitSpec.PaletteZone.FLAT to "FLT",
            S7InitSpec.PaletteZone.NEUTRAL to "NEU"
        )

        val counts = colors.groupingBy { it.role }.eachCount()
        return order.joinToString(",") { role -> "${abbr[role]}=${counts[role] ?: 0}" }
    }

    private fun minDeltaE00(colors: List<S7InitColor>): Float {
        if (colors.size < 2) return 0f
        var min = Float.POSITIVE_INFINITY
        for (i in 0 until colors.size - 1) {
            val a = colors[i].okLab
            for (j in i + 1 until colors.size) {
                val b = colors[j].okLab
                val d = de00(a, b)
                if (d < min) min = d
            }
        }
        return if (min.isFinite()) min else 0f
    }

    // Реализация ΔE00 (на основе S7Indexer), fallback — L2 в OKLab.
    private fun de00(a: FloatArray, b: FloatArray): Float {
        return try {
            deltaE00(
                a[0].toDouble(),
                a[1].toDouble(),
                a[2].toDouble(),
                b[0].toDouble(),
                b[1].toDouble(),
                b[2].toDouble()
            ).toFloat()
        } catch (_: Throwable) {
            val dl = a[0] - b[0]
            val da = a[1] - b[1]
            val db = a[2] - b[2]
            sqrt(dl * dl + da * da + db * db)
        }
    }

    private fun deltaE00(L1: Double, a1: Double, b1: Double, L2: Double, a2: Double, b2: Double): Double {
        val avgLp = (L1 + L2) / 2.0
        val c1 = sqrt(a1 * a1 + b1 * b1)
        val c2 = sqrt(a2 * a2 + b2 * b2)
        val avgC = (c1 + c2) / 2.0
        val g = 0.5 * (1.0 - sqrt((avgC.pow(7.0)) / (avgC.pow(7.0) + 25.0.pow(7.0))))
        val a1p = (1.0 + g) * a1
        val a2p = (1.0 + g) * a2
        val c1p = sqrt(a1p * a1p + b1 * b1)
        val c2p = sqrt(a2p * a2p + b2 * b2)
        val avgCp = (c1p + c2p) / 2.0

        fun atan2p(y: Double, x: Double): Double {
            val hue = toDegrees(atan2(y, x))
            return if (hue < 0) hue + 360.0 else hue
        }

        val h1p = if (c1p < 1e-6) 0.0 else atan2p(b1, a1p)
        val h2p = if (c2p < 1e-6) 0.0 else atan2p(b2, a2p)

        val dLp = L2 - L1
        val dCp = c2p - c1p

        val dhp = when {
            c1p < 1e-6 || c2p < 1e-6 -> 0.0
            abs(h2p - h1p) <= 180.0 -> h2p - h1p
            h2p <= h1p -> h2p - h1p + 360.0
            else -> h2p - h1p - 360.0
        }
        val dHp = 2.0 * sqrt(c1p * c2p) * sin(toRadians(dhp / 2.0))

        val avgHp = when {
            c1p < 1e-6 || c2p < 1e-6 -> h1p + h2p
            abs(h1p - h2p) <= 180.0 -> (h1p + h2p) / 2.0
            (h1p + h2p) < 360.0 -> (h1p + h2p + 360.0) / 2.0
            else -> (h1p + h2p - 360.0) / 2.0
        }

        val t = 1.0 - 0.17 * cos(toRadians(avgHp - 30.0)) +
            0.24 * cos(toRadians(2.0 * avgHp)) +
            0.32 * cos(toRadians(3.0 * avgHp + 6.0)) -
            0.20 * cos(toRadians(4.0 * avgHp - 63.0))

        val deltaTheta = 30.0 * exp(-((avgHp - 275.0) / 25.0).pow(2.0))
        val rc = 2.0 * sqrt((avgCp.pow(7.0)) / (avgCp.pow(7.0) + 25.0.pow(7.0)))
        val sl = 1.0 + ((0.015 * (avgLp - 50.0).pow(2.0)) / sqrt(20.0 + (avgLp - 50.0).pow(2.0)))
        val sc = 1.0 + 0.045 * avgCp
        val sh = 1.0 + 0.015 * avgCp * t
        val rt = -sin(toRadians(2.0 * deltaTheta)) * rc

        val kl = 1.0
        val kc = 1.0
        val kh = 1.0

        val lTerm = dLp / (kl * sl)
        val cTerm = dCp / (kc * sc)
        val hTerm = dHp / (kh * sh)

        return sqrt(lTerm * lTerm + cTerm * cTerm + hTerm * hTerm + rt * cTerm * hTerm)
    }

    private fun atan2(y: Double, x: Double): Double = kotlin.math.atan2(y, x)
    private fun toDegrees(rad: Double): Double = rad * 180.0 / PI
    private fun toRadians(deg: Double): Double = deg * PI / 180.0
}
