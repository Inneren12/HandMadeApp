package com.appforcross.editor.config

import com.handmadeapp.logging.Logger

object FeatureFlags {
    const val S7_SAMPLING = true
    const val S7_OVERLAY = true
    const val S7_INIT = true
    const val S7_INIT_FALLBACKS = true
    const val S7_GREEDY = true
    const val S7_SPREAD2OPT = true
    const val S7_KNEEDLE = true

    @Volatile
    private var logged = false

    fun logFlagsOnce() {
        if (logged) return
        synchronized(this) {
            if (logged) return
            Logger.i("FEATURE", "flag", mapOf("name" to "S7_SAMPLING", "enabled" to S7_SAMPLING))
            Logger.i("FEATURE", "flag", mapOf("name" to "S7_OVERLAY", "enabled" to S7_OVERLAY))
            Logger.i("FEATURE", "flag", mapOf("name" to "S7_INIT", "enabled" to S7_INIT))
            Logger.i("FEATURE", "flag", mapOf("name" to "S7_INIT_FALLBACKS", "enabled" to S7_INIT_FALLBACKS))
            Logger.i("FEATURE", "flag", mapOf("name" to "S7_GREEDY", "enabled" to S7_GREEDY))
            Logger.i("FEATURE", "flag", mapOf("name" to "S7_SPREAD2OPT", "enabled" to S7_SPREAD2OPT))
            Logger.i("FEATURE", "flag", mapOf("name" to "S7_KNEEDLE", "enabled" to S7_KNEEDLE))
            logged = true
        }
    }

    fun logGreedyFlag() {
        Logger.i("FEATURE", "flag", mapOf("name" to "S7_GREEDY", "enabled" to S7_GREEDY))
    }

    fun logSpread2OptFlag() {
        Logger.i("FEATURE", "flag", mapOf("name" to "S7_SPREAD2OPT", "enabled" to S7_SPREAD2OPT))
    }

    fun logKneedleFlag() {
        Logger.i("FEATURE", "flag", mapOf("name" to "S7_KNEEDLE", "enabled" to S7_KNEEDLE))
    }
}
