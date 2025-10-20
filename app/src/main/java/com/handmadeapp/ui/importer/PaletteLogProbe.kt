package com.handmadeapp.ui.importer

import android.content.Context
import android.util.Log
import com.handmadeapp.diagnostics.DiagnosticsManager
import com.handmadeapp.logging.Logger
import java.io.File

/**
 * Быстрый "зонд": читает palette_final_k.json из текущей сессии диагностики
 * и пишет компактное резюме в logcat (AiX/PALETTE) + структурный лог.
 *
 * Не зависит от внутренних моделей — парсит по простым регуляркам.
 */
object PaletteLogProbe {

    @JvmStatic
    fun logFinalPaletteFromDiag(ctx: Context, headN: Int = 8): Boolean {
        val dir = DiagnosticsManager.currentSessionDir(ctx) ?: return false
        val f = File(File(dir, "palette"), "palette_final_k.json")
        if (!f.exists()) return false
        val txt = runCatching { f.readText() }.getOrElse { return false }

        // K: считаем поля sRGB
        val sRgbRegex = Regex("""\"sRGB\"\s*:\s*([0-9]+)""")
        val sRgbMatches = sRgbRegex.findAll(txt).toList()
        val k = sRgbMatches.size
        if (k == 0) return false

        // head HEX
        val head = sRgbMatches.take(headN).joinToString(",") {
            val v = it.groupValues[1].toLongOrNull() ?: 0L
            "#%06X".format((v and 0xFFFFFF))
        }
        val more = (k - headN).coerceAtLeast(0)

        // роли (по json полю "role":"SKIN" и т.п.)
        val roles = listOf("SKY","SKIN","EDGE","HITEX","FLAT","NEUTRAL")
        val roleCounts = roles.associateWith { role ->
            Regex("""\"role\"\s*:\s*\"$role\"""").findAll(txt).count()
        }
        val rolesStr = "SKY=${roleCounts["SKY"]?:0}," +
                "SKN=${roleCounts["SKIN"]?:0}," +
                "EDG=${roleCounts["EDGE"]?:0}," +
                "HTX=${roleCounts["HITEX"]?:0}," +
                "FLT=${roleCounts["FLAT"]?:0}," +
                "NEU=${roleCounts["NEUTRAL"]?:0}"

        // Лаконичный logcat
        Log.i("AiX/PALETTE", "K*=$k roles{$rolesStr} head$headN=$head${if (more>0) " …+$more" else ""}")
        // Структурный лог
        Logger.i("PALETTE", "palette.summary.compact.diag", mapOf(
            "K" to k,
            "roles" to rolesStr,
            "headN" to headN,
            "head" to head,
            "more" to more,
            "src" to "diag"
        ))
        return true
    }
}
