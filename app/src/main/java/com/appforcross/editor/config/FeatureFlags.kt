package com.appforcross.editor.config

import android.content.Context
import android.content.SharedPreferences
import com.handmadeapp.logging.Logger
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

object FeatureFlags {
    const val S7_SAMPLING = true
    const val S7_OVERLAY = true
    const val S7_INIT = true
    const val S7_INIT_FALLBACKS = true
    const val S7_GREEDY = true
    const val S7_SPREAD2OPT = true
    const val S7_KNEEDLE = true
    const val S7_INDEX = true

    val S7_BUFFER_POOL_ENABLED: Boolean
        get() = isFlagEnabled(S7Flag.BUFFER_POOL)
    val S7_INCREMENTAL_ASSIGN_ENABLED: Boolean
        get() = isFlagEnabled(S7Flag.INCREMENTAL_ASSIGN)
    val S7_TILE_ERRORMAP_ENABLED: Boolean
        get() = isFlagEnabled(S7Flag.TILE_ERRORMAP)
    val S7_DITHER_LINEBUFFERS_ENABLED: Boolean
        get() = isFlagEnabled(S7Flag.DITHER_LINEBUFFERS)
    val S7_PARALLEL_TILES_ENABLED: Boolean
        get() = isFlagEnabled(S7Flag.PARALLEL_TILES)

    private const val PREFS_NAME = "s7_feature_flags"
    private const val KEY_BUCKET = "bucket"
    private const val KEY_STAGE_PREFIX = "stage_"
    private const val KEY_OVERRIDE_PREFIX = "override_"

    @Volatile
    private var logged = false

    @Volatile
    private var appContext: Context? = null

    private val statusCache = ConcurrentHashMap<S7Flag, FlagStatus>()
    private val rolloutBucket = AtomicInteger(-1)
    private val lock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
        ensureBucket()
    }

    fun enableS7Flag(flag: S7Flag, stage: Stage) {
        val prefs = prefs() ?: return
        synchronized(lock) {
            prefs.edit().putString(stageKey(flag), stage.storageValue).apply()
            statusCache.remove(flag)
        }
        logFlagStatus(resolveStatus(flag))
    }

    fun overrideS7Flag(flag: S7Flag, overrideStage: Stage?) {
        val prefs = prefs() ?: return
        synchronized(lock) {
            val editor = prefs.edit()
            if (overrideStage == null) {
                editor.remove(overrideKey(flag))
            } else {
                editor.putString(overrideKey(flag), overrideStage.storageValue)
            }
            editor.apply()
            statusCache.remove(flag)
        }
        logFlagStatus(resolveStatus(flag))
    }

    fun clearOverride(flag: S7Flag) {
        overrideS7Flag(flag, null)
    }

    fun getS7FlagStatuses(): List<FlagStatus> {
        return S7Flag.values().map { resolveStatus(it) }
    }

    fun getS7FlagStatus(flag: S7Flag): FlagStatus = resolveStatus(flag)

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
            Logger.i("FEATURE", "flag", mapOf("name" to "S7_INDEX", "enabled" to S7_INDEX))
            getS7FlagStatuses().forEach { logFlagStatus(it) }
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

    fun logIndexFlag() {
        Logger.i("FEATURE", "flag", mapOf("name" to "S7_INDEX", "enabled" to S7_INDEX))
        logFlagStatus(resolveStatus(S7Flag.INCREMENTAL_ASSIGN))
        logFlagStatus(resolveStatus(S7Flag.BUFFER_POOL))
        logFlagStatus(resolveStatus(S7Flag.TILE_ERRORMAP))
        logFlagStatus(resolveStatus(S7Flag.DITHER_LINEBUFFERS))
        logFlagStatus(resolveStatus(S7Flag.PARALLEL_TILES))
    }

    private fun isFlagEnabled(flag: S7Flag): Boolean {
        return resolveStatus(flag).enabled
    }

    private fun prefs(): SharedPreferences? {
        val ctx = appContext ?: return null
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun resolveStatus(flag: S7Flag): FlagStatus {
        statusCache[flag]?.let { return it }
        val prefs = prefs()
        val storedStage = prefs?.getString(stageKey(flag), null)?.let { Stage.fromStorage(it) } ?: Stage.DISABLED
        val overrideStage = prefs?.getString(overrideKey(flag), null)?.let { Stage.fromStorage(it) }
        val effective = overrideStage ?: storedStage
        val bucket = ensureBucket()
        val enabled = effective.coverage > 0 && bucket < effective.coverage
        val source = when {
            overrideStage != null -> Source.OVERRIDE
            prefs?.contains(stageKey(flag)) == true -> Source.STORED
            else -> Source.DEFAULT
        }
        val status = FlagStatus(
            flag = flag,
            enabled = enabled,
            stage = effective,
            source = source,
            bucket = bucket,
            storedStage = storedStage,
            overrideStage = overrideStage
        )
        statusCache[flag] = status
        return status
    }

    private fun ensureBucket(): Int {
        val cached = rolloutBucket.get()
        if (cached >= 0) return cached
        val prefs = prefs() ?: return 0
        synchronized(lock) {
            val current = rolloutBucket.get()
            if (current >= 0) return current
            val stored = prefs.getInt(KEY_BUCKET, -1)
            if (stored >= 0) {
                rolloutBucket.set(stored)
                return stored
            }
            val generated = Random.nextInt(0, 100)
            prefs.edit().putInt(KEY_BUCKET, generated).apply()
            rolloutBucket.set(generated)
            return generated
        }
    }

    private fun stageKey(flag: S7Flag) = KEY_STAGE_PREFIX + flag.key
    private fun overrideKey(flag: S7Flag) = KEY_OVERRIDE_PREFIX + flag.key

    private fun logFlagStatus(status: FlagStatus) {
        val data = linkedMapOf<String, Any?>(
            "name" to status.flag.metricName,
            "enabled" to status.enabled,
            "stage" to status.stage.storageValue,
            "bucket" to status.bucket,
            "source" to status.source.name.lowercase(Locale.US)
        )
        status.overrideStage?.let { data["override"] = it.storageValue }
        data["stored_stage"] = status.storedStage.storageValue
        Logger.i("FEATURE", "flag", data)
    }

    enum class Stage(val coverage: Int) {
        DISABLED(0),
        CANARY(5),
        RAMP(35),
        FULL(100);

        val storageValue: String = name.lowercase(Locale.US)

        companion object {
            fun fromStorage(value: String): Stage {
                return entries.firstOrNull { it.storageValue == value.lowercase(Locale.US) }
                    ?: DISABLED
            }
        }
    }

    enum class Source {
        DEFAULT,
        STORED,
        OVERRIDE
    }

    enum class S7Flag(val key: String, val displayName: String, val metricName: String) {
        BUFFER_POOL("s7_buffer_pool", "Buffer pool", "S7_BUFFER_POOL_ENABLED"),
        INCREMENTAL_ASSIGN("s7_incremental_assign", "Incremental assign", "S7_INCREMENTAL_ASSIGN_ENABLED"),
        TILE_ERRORMAP("s7_tile_errormap", "Tile error map", "S7_TILE_ERRORMAP_ENABLED"),
        DITHER_LINEBUFFERS("s7_dither_linebuffers", "Dither line buffers", "S7_DITHER_LINEBUFFERS_ENABLED"),
        PARALLEL_TILES("s7_parallel_tiles", "Parallel tiles", "S7_PARALLEL_TILES_ENABLED")
    }

    data class FlagStatus(
        val flag: S7Flag,
        val enabled: Boolean,
        val stage: Stage,
        val source: Source,
        val bucket: Int,
        val storedStage: Stage,
        val overrideStage: Stage?
    )
}
