package com.handmadeapp.ui.importer

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.appforcross.editor.config.FeatureFlags
import com.appforcross.editor.config.FeatureFlags.S7Flag
import com.appforcross.editor.config.FeatureFlags.Source
import com.appforcross.editor.config.FeatureFlags.Stage
import com.appforcross.editor.palette.PaletteLogcat
import com.handmadeapp.BuildConfig
import com.appforcross.editor.palette.S7Greedy
import com.appforcross.editor.palette.S7GreedyIo
import com.appforcross.editor.palette.S7GreedyResult
import com.appforcross.editor.palette.S7GreedySpec
import com.appforcross.editor.palette.S7InitColor
import com.appforcross.editor.palette.S7InitResult
import com.appforcross.editor.palette.S7InitSpec
import com.appforcross.editor.palette.S7Initializer
import com.appforcross.editor.palette.S7IndexResult
import com.appforcross.editor.palette.S7Indexer
import com.appforcross.editor.palette.S7PaletteIo
import com.appforcross.editor.palette.S7Sampler
import com.appforcross.editor.palette.S7SamplingIo
import com.appforcross.editor.palette.S7SamplingResult
import com.appforcross.editor.palette.S7SamplingSpec
import com.appforcross.editor.palette.S7Kneedle
import com.appforcross.editor.palette.S7KneedleIo
import com.appforcross.editor.palette.S7KneedleResult
import com.appforcross.editor.palette.S7KneedleSpec
import com.appforcross.editor.palette.S7Spread2Opt
import com.appforcross.editor.palette.S7Spread2OptIo
import com.appforcross.editor.palette.S7Spread2OptResult
import com.appforcross.editor.palette.S7Spread2OptSpec
import com.appforcross.editor.palette.S7SpreadViolation
import com.handmadeapp.color.ColorMgmt
import com.handmadeapp.preset.Stage4Runner
import com.handmadeapp.prescale.PreScaleRunner
import com.handmadeapp.R
import com.handmadeapp.analysis.Masks
import com.handmadeapp.diagnostics.DiagnosticsManager
import com.handmadeapp.editor.dev.DevPrefs
import com.handmadeapp.logging.Logger
import com.handmadeapp.quant.DitherBuffers
import com.handmadeapp.quant.PaletteQuantBuffers
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import kotlin.io.use
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.pow
import com.appforcross.editor.palette.S7OverlayRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import android.os.Handler
import android.os.Looper
import com.handmadeapp.runtime.S7Dispatchers
import kotlinx.coroutines.flow.collectLatest
import com.handmadeapp.watchdog.MainThreadWatchdog
/**
 * ImportActivity: выбор изображения, предпросмотр и «живые» правки (яркость/контраст/насыщенность).
 * Реализовано без зависимостей на Activity Result API (для совместимости) — используем onActivityResult.
 */
class ImportActivity : AppCompatActivity() {

    private val viewModel: ImportViewModel by viewModels()

    private lateinit var image: ImageView
    private lateinit var pickBtn: Button
    private lateinit var sbBrightness: SeekBar
    private lateinit var sbContrast: SeekBar
    private lateinit var sbSaturation: SeekBar
    private lateinit var progress: ProgressBar
    private lateinit var fileNameView: TextView
    private lateinit var btnProcess: Button
    private lateinit var btnLogPalette: Button
    private lateinit var tvStatus: TextView
    // Debounce для плавной перекраски предпросмотра
    private val adjustDebouncer = Debouncer(90)
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Весь S7 уходим на отдельный однопоточный диспетчер (см. S7Dispatchers.preview).
    private val s7Scope = CoroutineScope(SupervisorJob() + S7Dispatchers.preview)
    private var s7Job: Job? = null
    private var s7JobName: String? = null
    private val palettePreviewThrottler = ThrottledUiUpdater(activityScope, UI_THROTTLE_MS)
    // Поток прогресса (чтобы не дёргать UI на каждый тик)
    private val progressSignals = MutableSharedFlow<ProgressSignal>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var pendingPaletteColors: List<S7InitColor>? = null
    private val s7TriggerDesiredState = mutableMapOf<View, Boolean>()
    private var s7JobActive = false
    private val mainThreadBlockMonitor = MainThreadBlockMonitor()
    private lateinit var btnInitK0: Button
    private lateinit var btnGrowK: Button
    private lateinit var btnSpread2Opt: Button
    private lateinit var btnFinalizeK: Button
    private lateinit var btnIndexK: Button
    private lateinit var cbPalette: CheckBox
    private lateinit var paletteStrip: PaletteStripView
    private lateinit var cbSampling: CheckBox
    private lateinit var cbShowResidual: CheckBox
    private lateinit var cbSpreadBeforeAfter: CheckBox
    private lateinit var cbIndexGrid: CheckBox
    private lateinit var cbIndexCost: CheckBox
    private lateinit var overlay: QuantOverlayView
    private lateinit var cbFlagBufferPool: CheckBox
    private lateinit var cbFlagIncrementalAssign: CheckBox
    private lateinit var cbFlagTileErrormap: CheckBox
    private lateinit var cbFlagDitherBuffers: CheckBox
    private lateinit var cbFlagParallelTiles: CheckBox
    private lateinit var featureFlagViews: Map<S7Flag, CheckBox>

    private var baseBitmap: Bitmap? = null
    private var currentUri: Uri? = null
    private var lastSampling: S7SamplingResult? = null
    private var lastInit: S7InitResult? = null
    private var lastGreedy: S7GreedyResult? = null
    private var overlayImageSize: Size? = null
    private var samplingRunning = false
    private var overlayPending = false
    private var initRunning = false
    private var greedyRunning = false
    private var spreadRunning = false
    private var suppressSamplingToggle = false
    private var suppressPaletteToggle = false
    private var suppressSpreadToggle = false
    private var suppressResidualToggle = false
    private var residualErrors: FloatArray? = null
    private var residualDeMed: Float = 0f
    private var residualDe95: Float = 0f
    private var overlayMode: OverlayMode = OverlayMode.NONE
    private var lastSpread: S7Spread2OptResult? = null
    private var paletteBeforeSpread: List<S7InitColor>? = null
    private var spreadAmbiguity: FloatArray? = null
    private var spreadAffected: FloatArray? = null
    private var kneedleRunning = false
    private var lastKneedle: S7KneedleResult? = null
    private var boundIndexPreview: Bitmap? = null
    private var indexRunning = false
    private var lastIndexResult: S7IndexResult? = null
    private var suppressIndexGridToggle = false
    private var suppressIndexCostToggle = false
    private var suppressFlagToggle = false
    private val mainWatchdog = MainThreadWatchdog()

    private val paletteColorsOrNull: List<S7InitColor>?
        get() = viewModel.uiState.value.paletteColors.takeIf { it.isNotEmpty() }

    private val currentPreScale: PreScaleRunner.Output?
        get() = viewModel.uiState.value.preScale

    private var lastViewModelState = ImportViewModel.UiState()

    private enum class OverlayMode { NONE, SAMPLING, RESIDUAL, SPREAD, INDEX }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // В dev-сборках включаем StrictMode для отслеживания тяжёлых операций на UI.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .permitDiskReads()    // если надо — закомментируй
                    .permitDiskWrites()   // если надо — закомментируй
                    .penaltyLog()
                    .build()
            )
        }
        setContentView(R.layout.activity_import)

        image = findViewById(R.id.previewImage)
        pickBtn = findViewById(R.id.btnPick)
        sbBrightness = findViewById(R.id.sbBrightness)
        sbContrast = findViewById(R.id.sbContrast)
        sbSaturation = findViewById(R.id.sbSaturation)
        progress = findViewById(R.id.progress)
        progress.max = 100
        progress.isIndeterminate = true
        fileNameView = findViewById(R.id.tvFileName)
        btnProcess = findViewById(R.id.btnProcess)
        btnLogPalette = findViewById(R.id.btnLogPalette)
        btnInitK0 = findViewById(R.id.btnInitK0)
        btnGrowK = findViewById(R.id.btnGrowK)
        btnSpread2Opt = findViewById(R.id.btnSpread2Opt)
        btnFinalizeK = findViewById(R.id.btnFinalizeK)
        btnIndexK = findViewById(R.id.btnIndexK)
        tvStatus = findViewById(R.id.tvStatus)
        cbSampling = findViewById(R.id.cbSampling)
        cbPalette = findViewById(R.id.cbPalette)
        cbShowResidual = findViewById(R.id.cbShowResidual)
        cbSpreadBeforeAfter = findViewById(R.id.cbSpreadBeforeAfter)
        cbIndexGrid = findViewById(R.id.cbIndexGrid)
        cbIndexCost = findViewById(R.id.cbIndexCost)
        overlay = findViewById(R.id.quantOverlay)
        cbFlagBufferPool = findViewById(R.id.cbFlagBufferPool)
        cbFlagIncrementalAssign = findViewById(R.id.cbFlagIncrementalAssign)
        cbFlagTileErrormap = findViewById(R.id.cbFlagTileErrormap)
        cbFlagDitherBuffers = findViewById(R.id.cbFlagDitherBuffers)
        cbFlagParallelTiles = findViewById(R.id.cbFlagParallelTiles)
        featureFlagViews = mapOf(
            S7Flag.BUFFER_POOL to cbFlagBufferPool,
            S7Flag.INCREMENTAL_ASSIGN to cbFlagIncrementalAssign,
            S7Flag.TILE_ERRORMAP to cbFlagTileErrormap,
            S7Flag.DITHER_LINEBUFFERS to cbFlagDitherBuffers,
            S7Flag.PARALLEL_TILES to cbFlagParallelTiles
        )
        paletteStrip = findViewById(R.id.paletteStrip)

        observeProgressSignals()
        observeIndexProgress()
        setProgressVisible(false, "init")

        FeatureFlags.logFlagsOnce()
        setupFeatureFlagControls()
        refreshFeatureFlagControls()
        setS7TriggerEnabled(cbSampling, false)
        cbSampling.isChecked = false
        overlay.isVisible = false
        cbSampling.isVisible = FeatureFlags.S7_SAMPLING || FeatureFlags.S7_OVERLAY
        btnInitK0.isVisible = FeatureFlags.S7_INIT
        setS7TriggerEnabled(btnInitK0, false)
        btnGrowK.isVisible = FeatureFlags.S7_GREEDY
        setS7TriggerEnabled(btnGrowK, false)
        btnSpread2Opt.isVisible = FeatureFlags.S7_SPREAD2OPT
        setS7TriggerEnabled(btnSpread2Opt, false)
        btnFinalizeK.isVisible = FeatureFlags.S7_KNEEDLE
        setS7TriggerEnabled(btnFinalizeK, false)
        btnIndexK.isVisible = FeatureFlags.S7_INDEX
        setS7TriggerEnabled(btnIndexK, false)
        cbPalette.isVisible = FeatureFlags.S7_INIT
        cbPalette.isEnabled = false
        cbPalette.isChecked = false
        paletteStrip.isVisible = false
        cbSpreadBeforeAfter.isVisible = false
        cbSpreadBeforeAfter.isEnabled = false
        cbSpreadBeforeAfter.isChecked = false
        cbShowResidual.isVisible = false
        cbShowResidual.isEnabled = false
        cbShowResidual.isChecked = false
        cbIndexGrid.isVisible = false
        cbIndexGrid.isEnabled = false
        cbIndexGrid.isChecked = false
        cbIndexCost.isVisible = false
        cbIndexCost.isEnabled = false
        cbIndexCost.isChecked = false

        cbSampling.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSamplingToggle) return@setOnCheckedChangeListener
            handleOverlayToggle(isChecked)
        }

        cbPalette.setOnCheckedChangeListener { _, isChecked ->
            if (suppressPaletteToggle) return@setOnCheckedChangeListener
            if (!FeatureFlags.S7_INIT) return@setOnCheckedChangeListener
            paletteStrip.isVisible = isChecked && paletteColorsOrNull != null
        }

        cbSpreadBeforeAfter.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSpreadToggle) return@setOnCheckedChangeListener
            handleSpreadToggle(isChecked)
        }

        cbShowResidual.setOnCheckedChangeListener { _, isChecked ->
            if (suppressResidualToggle) return@setOnCheckedChangeListener
            handleResidualToggle(isChecked)
        }

        pickBtn.setOnClickListener {
            openImagePicker()
        }

        btnFinalizeK.setOnClickListener {
            startFinalizeK()
        }

        // Центр — отсутствие изменений
        sbBrightness.max = 200
        sbBrightness.progress = 100
        sbContrast.max = 200
        sbContrast.progress = 100
        sbSaturation.max = 200
        sbSaturation.progress = 100

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Откладываем применение на 90мс, чтобы не перерисовывать на каждый тик
                adjustDebouncer.submit { applyAdjustments() }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        sbBrightness.setOnSeekBarChangeListener(listener)
        sbContrast.setOnSeekBarChangeListener(listener)
        sbSaturation.setOnSeekBarChangeListener(listener)

        // Если пришёл Intent c data (поделиться/открыть)
        intent?.data?.let { onImageChosen(it) }

        // Кнопка запуска конвейера
        btnProcess.setOnClickListener {
            val uri = currentUri
            if (uri == null) {
                Toast.makeText(this, "Сначала выберите изображение", Toast.LENGTH_SHORT).show()
            } else {
                runPipeline(uri, targetWst = 240)
            }
        }

        // Кнопка: вывести компактное резюме палитры в logcat (если уже есть palette_final_k.json)
        btnLogPalette.setOnClickListener {
            activityScope.launch(Dispatchers.Default) {
                val ok = PaletteLogProbe.logFinalPaletteFromDiag(this@ImportActivity, headN = 8)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        Toast.makeText(this@ImportActivity, "Palette summary → logcat (AiX/PALETTE)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@ImportActivity, "Palette not found yet (run S7.5/S7.6)", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnInitK0.setOnClickListener {
            startPaletteInit()
        }

        btnGrowK.setOnClickListener {
            startPaletteGrowth()
        }

        btnSpread2Opt.setOnClickListener {
            startSpread2Opt()
        }

        btnIndexK.setOnClickListener {
            startIndexingK()
        }

        cbIndexGrid.setOnCheckedChangeListener { _, _ ->
            if (suppressIndexGridToggle) return@setOnCheckedChangeListener
            handleIndexOverlayToggle()
        }

        cbIndexCost.setOnCheckedChangeListener { _, _ ->
            if (suppressIndexCostToggle) return@setOnCheckedChangeListener
            handleIndexOverlayToggle()
        }

        observeViewModel()
        updateIndexUiState()
        scheduleWarmupIfNeeded()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    val previous = lastViewModelState
                    if (state !== previous) {
                        onViewModelStateChanged(previous, state)
                        lastViewModelState = state
                    }
                }
            }
        }
    }

    private fun onViewModelStateChanged(
        previous: ImportViewModel.UiState,
        current: ImportViewModel.UiState
    ) {
        if (previous.isIndexRunning != current.isIndexRunning) {
            indexRunning = current.isIndexRunning
        }

        when {
            current.indexResult != null && current.indexResult !== previous.indexResult -> {
                handleIndexSuccess(current)
            }
            current.indexError != null && current.indexError != previous.indexError -> {
                handleIndexError(current.indexError)
            }
            current.indexResult == null && previous.indexResult != null && current.previewBitmap == null -> {
                if (boundIndexPreview != null) {
                    boundIndexPreview = null
                }
            }
        }
    }

    private fun handleIndexSuccess(state: ImportViewModel.UiState) {
        val result = state.indexResult ?: return
        indexRunning = false
        setProgressVisible(false, "s7.index.done")
        lastIndexResult = result
        val previousPreview = boundIndexPreview
        val nextPreview = state.previewBitmap
        boundIndexPreview = nextPreview
        if (nextPreview != null) {
            image.colorFilter = null
            image.setImageBitmap(nextPreview)
        }
        if (previousPreview != null && previousPreview !== nextPreview && !previousPreview.isRecycled) {
            previousPreview.recycle()
        }
        val costBitmap = state.costHeatmap
        suppressIndexGridToggle = true
        cbIndexGrid.isChecked = true
        suppressIndexGridToggle = false
        suppressIndexCostToggle = true
        cbIndexCost.isChecked = false
        suppressIndexCostToggle = false
        cbIndexGrid.isVisible = true
        cbIndexGrid.isEnabled = true
        cbIndexCost.isVisible = true
        cbIndexCost.isEnabled = costBitmap != null
        suppressSamplingToggle = true
        cbSampling.isChecked = false
        suppressSamplingToggle = false
        suppressResidualToggle = true
        cbShowResidual.isChecked = false
        suppressResidualToggle = false
        suppressSpreadToggle = true
        cbSpreadBeforeAfter.isChecked = false
        suppressSpreadToggle = false
        cbSpreadBeforeAfter.isEnabled = false
        overlayMode = OverlayMode.INDEX
        updateIndexOverlay(applyNewData = true)
        val stats = result.stats
        val status = buildString {
            append("S7.6: index=")
            append(result.indexBpp)
            append("-bit, K*=")
            append(result.kStar)
            append(", meanCost=")
            append(String.format(Locale.US, "%.4f", stats.meanCost))
            append(", foreignZoneHits=")
            append(stats.foreignZoneHits)
            append(", EBsum=")
            append(String.format(Locale.US, "%.3f", stats.edgeBreakPenaltySum))
            append(", t_ms={prep=")
            append(stats.prepareMs)
            append(", assign=")
            append(stats.assignMs)
            append(", dither=")
            append(stats.ditherMs)
            append(", total=")
            append(stats.totalMs)
            append("}")
            append(", gates=")
            append(result.gateConfig.summary())
        }
        tvStatus.text = status
        updateIndexUiState()
    }

    private fun handleIndexError(message: String?) {
        indexRunning = false
        setProgressVisible(false, "s7.index.fail")
        val errorMessage = message ?: "ошибка"
        tvStatus.text = "S7.6 ошибка: $errorMessage"
        Toast.makeText(this, "S7.6 ошибка: $errorMessage", Toast.LENGTH_LONG).show()
        updateIndexUiState()
    }

    override fun onResume() {
        super.onResume()
        // Включаем отрисовку S7-оверлеев, когда активити видима
        refreshFeatureFlagControls()
    }

    override fun onPause() {
        super.onPause()
        // Во время скрытия активити просто выключаем и чистим overlay на экране.
        overlay.isVisible = false
        overlay.clearOverlay()
    }

    override fun onStop() {
        super.onStop()
        mainWatchdog.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainThreadBlockMonitor.cancel()
        activityScope.cancel()
        s7Scope.cancel()
        mainWatchdog.stop()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun launchS7Job(taskName: String, block: suspend CoroutineScope.() -> Unit) {
        val previousJob = s7Job
        val previousName = s7JobName
        if (previousJob != null) {
            Logger.i(
                "PALETTE",
                "s7.job.cancel.request",
                mapOf("task" to previousName, "next" to taskName)
            )
            previousJob.cancel(CancellationException("Superseded by $taskName"))
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            onS7JobStarted(taskName)
        } else {
            activityScope.launch(Dispatchers.Main.immediate) { onS7JobStarted(taskName) }
        }

        val job = s7Scope.launch {
            val onMainThread = Looper.myLooper() == Looper.getMainLooper()
            Logger.i(
                "PALETTE",
                "s7.job.dispatch",
                mapOf(
                    "task" to taskName,
                    "thread" to Thread.currentThread().name,
                    "onMain" to onMainThread,
                    "cancelledPrev" to (previousJob != null)
                )
            )
            if (onMainThread) {
                Logger.w(
                    "PALETTE",
                    "s7.job.dispatch.warning",
                    mapOf("task" to taskName, "issue" to "main_thread")
                )
            }
            Logger.i(
                "PALETTE",
                "s7.job.start",
                mapOf("task" to taskName, "cancelledPrev" to (previousJob != null))
            )
            try {
                block()
            } finally {
                if (onMainThread) {
                    Logger.e(
                        "PALETTE",
                        "s7.job.dispatch.violation",
                        mapOf("task" to taskName, "message" to "Executed on main thread")
                    )
                }
            }
        }
        s7Job = job
        s7JobName = taskName
        job.invokeOnCompletion { cause ->
            val completionStatus = when {
                cause == null -> "completed"
                cause is CancellationException -> "cancelled"
                else -> "failed"
            }
            Logger.i(
                "PALETTE",
                "s7.job.finish",
                mapOf("task" to taskName, "status" to completionStatus)
            )
            val isCurrent = s7Job === job
            if (isCurrent) {
                s7Job = null
                s7JobName = null
            }
            activityScope.launch(Dispatchers.Main.immediate) {
                onS7JobFinished(taskName, isCurrent)
            }
        }
    }

    private fun onS7JobStarted(taskName: String) {
        mainThreadBlockMonitor.start(taskName)
        if (!s7JobActive) {
            s7JobActive = true
            applyS7TriggerStates()
        }
    }

    private fun onS7JobFinished(taskName: String, isCurrent: Boolean) {
        mainThreadBlockMonitor.stop(taskName)
        if (isCurrent) {
            s7JobActive = false
            applyS7TriggerStates()
        }
    }

    private fun applyS7TriggerStates() {
        s7TriggerDesiredState.forEach { (view, desired) ->
            view.isEnabled = desired && !s7JobActive
        }
    }

    private fun setS7TriggerEnabled(view: View, enabled: Boolean) {
        s7TriggerDesiredState[view] = enabled
        view.isEnabled = enabled && !s7JobActive
    }

    private fun setProgressVisible(visible: Boolean) {
        val reason = if (visible) "manual.show" else "manual.hide"
        setProgressVisible(visible, reason)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun awaitS7IdleForTest() {
        while (true) {
            val job = s7Job ?: break
            job.join()
            if (s7Job == null) break
        }
    }

    private fun buildWatchdogMetadata(): Map<String, Any?> {
        val data = linkedMapOf<String, Any?>(
            "active_task" to (s7JobName ?: "idle"),
            "sampling_running" to samplingRunning,
            "init_running" to initRunning,
            "greedy_running" to greedyRunning,
            "spread_running" to spreadRunning,
            "kneedle_running" to kneedleRunning,
            "index_running" to indexRunning
        )
        data["bitmap_loaded"] = baseBitmap != null
        currentUri?.let { data["uri"] = it.toString() }
        return data
    }

    private fun setupFeatureFlagControls() {
        featureFlagViews.forEach { (flag, view) ->
            view.setOnCheckedChangeListener { _, isChecked ->
                if (suppressFlagToggle) return@setOnCheckedChangeListener
                handleFeatureFlagToggle(flag, isChecked)
            }
        }
    }

    private fun handleFeatureFlagToggle(flag: S7Flag, enabled: Boolean) {
        val status = FeatureFlags.getS7FlagStatus(flag)
        if (enabled) {
            if (status.source == Source.OVERRIDE && status.overrideStage == Stage.DISABLED) {
                FeatureFlags.clearOverride(flag)
            } else if (status.storedStage == Stage.DISABLED) {
                FeatureFlags.enableS7Flag(flag, Stage.FULL)
                FeatureFlags.clearOverride(flag)
            } else {
                FeatureFlags.clearOverride(flag)
            }
        } else {
            FeatureFlags.overrideS7Flag(flag, Stage.DISABLED)
        }
        refreshFeatureFlagControls()
    }

    private fun refreshFeatureFlagControls() {
        val statuses = FeatureFlags.getS7FlagStatuses()
        val byFlag = statuses.associateBy { it.flag }
        suppressFlagToggle = true
        featureFlagViews.forEach { (flag, view) ->
            val status = byFlag[flag]
            if (status != null) {
                view.isChecked = status.enabled
                view.text = buildFlagLabel(status)
                view.alpha = if (status.enabled) 1f else 0.6f
                view.contentDescription = view.text
            }
        }
        suppressFlagToggle = false
        overlay.setFeatureFlagStatus(statuses)
    }

    private fun buildFlagLabel(status: FeatureFlags.FlagStatus): String {
        val stageLabel = status.stage.label()
        val sourceLabel = when (status.source) {
            Source.DEFAULT -> "default"
            Source.STORED -> "rollout"
            Source.OVERRIDE -> if (status.overrideStage == Stage.DISABLED) {
                "override off"
            } else {
                "override ${status.overrideStage?.label()}"
            }
        }
        val label = "${status.flag.displayName} • $stageLabel"
        return if (sourceLabel.isNotEmpty()) {
            "$label ($sourceLabel)"
        } else {
            label
        }
    }

    private fun Stage.label(): String = when (this) {
        Stage.DISABLED -> "off"
        Stage.CANARY -> "canary"
        Stage.RAMP -> "ramp"
        Stage.FULL -> "full"
    }

    private fun openImagePicker() {
        // ACTION_OPEN_DOCUMENT позволяет работать с SAF и не требует дополнительных разрешений
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, RC_OPEN_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_OPEN_IMAGE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data
            if (uri != null) onImageChosen(uri) else Log.w(TAG, "import.canceled")
        }
    }

    private fun onImageChosen(uri: Uri) {
        setProgressVisible(true)
        image.setImageDrawable(ColorDrawable(0xFF222222.toInt()))
        // Хардварный слой уменьшает лаги при применении ColorMatrix
        image.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        fileNameView.text = queryDisplayName(uri) ?: "Выбран файл"
        tvStatus.text = "Загружаем превью…"
        currentUri = uri
        resetSamplingState()
        setS7TriggerEnabled(cbSampling, false)
        activityScope.launch(Dispatchers.Default) {
            try {
                val bmp = decodePreview(uri, maxDim = 2048)
                withContext(Dispatchers.Main) {
                    baseBitmap = bmp
                    image.setImageBitmap(bmp)
                    overlayImageSize = Size(bmp.width, bmp.height)
                    resetSamplingState()
                    cbSampling.isEnabled = FeatureFlags.S7_SAMPLING
                    setProgressVisible(false, "preview.load.done")
                    applyAdjustments()
                    tvStatus.text = "Предпросмотр готов. Можно запускать конвейер."
                    Log.i(TAG, "preview.built w=${bmp.width} h=${bmp.height}")
                    // Жёсткий фолбэк: если триггеры не стартовали S7 за 2 секунды — делаем попытку сами.
                    activityScope.launch {
                        delay(2000)
                        autoStartIndexingIfReady("preview.fallback.2s")
                    }
                    autoStartIndexingIfReady("preview.ready")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "import.decode.fail: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    setProgressVisible(false, "preview.load.fail")
                    tvStatus.text = "Ошибка загрузки: ${t.message}"
                    Toast.makeText(this@ImportActivity, "Ошибка загрузки: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startIndexingK() {
        if (!FeatureFlags.S7_INDEX) return
        val kneedle = lastKneedle
        if (kneedle == null) {
            Logger.w("PALETTE", "index.fail", mapOf("stage" to "precheck", "reason" to "missing_Kstar"))
            tvStatus.text = "S7.6: нет результата S7.5"
            Toast.makeText(this, "Сначала выполните S7.5", Toast.LENGTH_SHORT).show()
            return
        }
        val palette = paletteColorsOrNull
        if (palette == null || palette.isEmpty()) {
            Logger.w("PALETTE", "index.fail", mapOf("stage" to "precheck", "reason" to "missing_palette"))
            tvStatus.text = "S7.6: нет палитры K*"
            Toast.makeText(this, "Палитра отсутствует", Toast.LENGTH_SHORT).show()
            return
        }
        val preScale = currentPreScale
        if (preScale == null) {
            Logger.w("PALETTE", "index.fail", mapOf("stage" to "precheck", "reason" to "missing_prescale"))
            tvStatus.text = "S7.6: нет изображения Wst"
            Toast.makeText(this, "Сначала выполните конвейер", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = currentUri
        if (uri == null) {
            Logger.w("PALETTE", "index.fail", mapOf("stage" to "precheck", "reason" to "missing_uri"))
            tvStatus.text = "S7.6: нет источника"
            Toast.makeText(this, "Нет исходного изображения", Toast.LENGTH_SHORT).show()
            return
        }
        if (indexRunning) {
            tvStatus.text = "S7.6: перезапуск…"
        }
        val kStar = kneedle.Kstar
        if (kStar <= 0) {
            Logger.w("PALETTE", "index.fail", mapOf("stage" to "precheck", "reason" to "invalid_Kstar"))
            tvStatus.text = "S7.6: K* ≤ 0"
            Toast.makeText(this, "Некорректное K*", Toast.LENGTH_SHORT).show()
            return
        }
        val finalPalette = if (palette.size >= kStar) palette.take(kStar) else palette
        val seed = (kneedle.params["seed"] as? Number)?.toLong() ?: S7SamplingSpec.DEFAULT_SEED
        val tier = S7SamplingSpec.detectDeviceTier(this).key

        indexRunning = true
        setS7TriggerEnabled(btnIndexK, false)
        cbIndexGrid.isEnabled = false
        cbIndexCost.isEnabled = false
        setProgressVisible(true, "s7.index.start")
        tvStatus.text = "S7.6: индексируем…"
        updateIndexUiState()
        viewModel.submitIndex(uri, preScale, finalPalette, finalPalette.size, seed, tier)
    }

    /** Запуск основного конвейера: Stage3 → Stage4 → PreScale. Результат показываем в превью. */
    private fun runPipeline(uri: Uri, targetWst: Int) {
        tvStatus.text = "Запуск конвейера…"
        setProgressVisible(true, "pipeline.start")
        btnProcess.isEnabled = false
        setS7TriggerEnabled(btnInitK0, false)

        activityScope.launch(Dispatchers.Default) {
            try {
                // 1) Stage3 — анализ превью (маски, метрики, классификация)
                val analyze = Stage3Analyze.run(this@ImportActivity, uri)

                // 2) Stage4 — выбор пресета/параметров
                val stage4 = Stage4Runner.run(this@ImportActivity, uri, targetWst = targetWst)

                // 3) PreScale — полный прогон и PNG-выход
                val pre = PreScaleRunner.run(
                    ctx = this@ImportActivity,
                    uri = uri,
                    analyze = analyze,
                    gate = stage4.gate,
                    targetWst = targetWst
                )
                viewModel.updatePreScale(pre)

                val png = File(pre.pngPath)
                val outBmp = BitmapFactory.decodeFile(png.absolutePath)

                withContext(Dispatchers.Main) {
                    if (outBmp != null) {
                        image.colorFilter = null   // сбросить локальные правки предпросмотра
                        image.setImageBitmap(outBmp)
                        tvStatus.text = "Готово: ${png.name} (${outBmp.width}×${outBmp.height})"
                    } else {
                        tvStatus.text = "Готово, но не удалось отобразить результат"
                    }
                    setProgressVisible(false, "pipeline.done")
                    btnProcess.isEnabled = true
                    setS7TriggerEnabled(btnInitK0, FeatureFlags.S7_INIT && lastSampling != null)
                    updateIndexUiState()
                    autoStartIndexingIfReady("pipeline.done")
                    Toast.makeText(this@ImportActivity, "Конвейер завершён", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "pipeline.fail: ${t.message}", t)
                viewModel.updatePreScale(null)
                withContext(Dispatchers.Main) {
                    setProgressVisible(false, "pipeline.fail")
                    btnProcess.isEnabled = true
                    setS7TriggerEnabled(btnInitK0, FeatureFlags.S7_INIT && lastSampling != null)
                    tvStatus.text = "Ошибка конвейера: ${t.message}"
                    Toast.makeText(this@ImportActivity, "Ошибка: ${t.message}", Toast.LENGTH_LONG).show()
                    updateIndexUiState()
                }
            }
        }
    }

    /** Реал-тайм предпросмотр с ColorMatrix (без пересборки битмапа). */
    private fun applyAdjustments() {
        if (viewModel.uiState.value.previewBitmap != null) return
        val bmp = baseBitmap ?: return
        val b = (sbBrightness.progress - 100) / 100f   // [-1..+1]
        val c = (sbContrast.progress) / 100f           // [0..2], 1 — без изм.
        val s = (sbSaturation.progress) / 100f         // [0..2], 1 — без изм.

        val filter = buildColorFilter(b, c, s)
        image.colorFilter = filter
        image.invalidate()
        Log.d(TAG, "preview.adjust b=$b c=$c s=$s")
    }

    private fun buildColorFilter(brightness: Float, contrast: Float, saturation: Float): ColorMatrixColorFilter {
        val cm = ColorMatrix().apply { setSaturation(saturation) }
        val b255 = brightness * 255f
        val c = contrast
        val m = ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, b255,
            0f, c, 0f, 0f, b255,
            0f, 0f, c, 0f, b255,
            0f, 0f, 0f, 1f, 0f
        ))
        cm.postConcat(m)
        return ColorMatrixColorFilter(cm)
    }

    /** Декод превью с ограничением размера и корректировкой EXIF. */
    private fun decodePreview(uri: Uri, maxDim: Int): Bitmap {
        return if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val sample = if (w >= h) max(1, w / maxDim) else max(1, h / maxDim)
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val sample = computeInSampleSize(uri, maxDim)
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sample
            }
            val raw = contentResolver.openInputStream(uri).use { inp ->
                BitmapFactory.decodeStream(inp, null, opts)
            } ?: error("decodeStream=null")
            applyExifRotation(uri, raw)
        }
    }

    private fun computeInSampleSize(uri: Uri, maxDim: Int): Int {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
        val (w, h) = opts.outWidth to opts.outHeight
        var sample = 1
        var maxSide = max(w, h)
        while (maxSide / (sample * 2) > maxDim) sample *= 2
        return sample
    }

    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return bitmap
            val exif = input.use { ExifInterface(it) }
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90  -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_TRANSPOSE  -> { m.postRotate(90f);  m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL   -> m.postScale(1f, -1f)
                else -> return bitmap
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        } catch (t: Throwable) {
            Log.w(TAG, "exif.rotate.fail: ${t.message}")
            bitmap
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        } catch (_: Throwable) { null }
    }

    private fun resetSamplingState() {
        lastSampling = null
        lastInit = null
        lastGreedy = null
        lastSpread = null
        paletteBeforeSpread = null
        spreadAmbiguity = null
        spreadAffected = null
        spreadRunning = false
        residualErrors = null
        overlayMode = OverlayMode.NONE
        overlayPending = false
        samplingRunning = false
        initRunning = false
        greedyRunning = false
        kneedleRunning = false
        lastKneedle = null
        viewModel.updatePaletteColors(emptyList())
        viewModel.updateKstarReady(false)
        lastIndexResult = null
        viewModel.clearIndexResult()
        viewModel.updatePreScale(null)
        viewModel.clearMasks()
        suppressSpreadToggle = true
        cbSpreadBeforeAfter.isChecked = false
        suppressSpreadToggle = false
        cbSpreadBeforeAfter.isVisible = false
        cbSpreadBeforeAfter.isEnabled = false
        overlayImageSize = null
        suppressSamplingToggle = true
        cbSampling.isChecked = false
        suppressSamplingToggle = false
        overlay.isVisible = false
        overlay.clearOverlay()
        setS7TriggerEnabled(btnSpread2Opt, false)
        suppressResidualToggle = true
        cbShowResidual.isChecked = false
        suppressResidualToggle = false
        cbShowResidual.isEnabled = false
        cbShowResidual.isVisible = false
        setS7TriggerEnabled(btnFinalizeK, false)
        resetIndexState()
        resetPaletteState()
    }

    private fun startSamplingInBackground(showOverlayWhenDone: Boolean) {
        if (!FeatureFlags.S7_SAMPLING) return
        val bmp = baseBitmap
        val uri = currentUri
        if (bmp == null || uri == null) {
            Toast.makeText(this, "Сначала выберите изображение", Toast.LENGTH_SHORT).show()
            suppressSamplingToggle = true
            cbSampling.isChecked = false
            suppressSamplingToggle = false
            return
        }
        if (samplingRunning) {
            overlayPending = overlayPending || showOverlayWhenDone
            tvStatus.text = "S7.1: перезапуск…"
        } else {
            overlayPending = showOverlayWhenDone
        }
        samplingRunning = true
        cbSampling.isEnabled = false
        setProgressVisible(true, "s7.sampling.start")
        tvStatus.text = "S7.1: считаем выборку…"
        setS7TriggerEnabled(btnInitK0, false)

        launchS7Job("s7.sampling") {
            try {
                val sampling = S7Sampler.run(
                    bmp,
                    viewModel.ensureScaledMasks(bmp, uri),
                    S7SamplingSpec.detectDeviceTier(this@ImportActivity).key,
                    S7SamplingSpec.DEFAULT_SEED
                )
                DiagnosticsManager.currentSessionDir(this@ImportActivity)?.let { dir ->
                    try {
                        withContext(S7Dispatchers.export) {
                            S7SamplingIo.writeJson(dir, sampling)
                            S7SamplingIo.writeRoiHistogramPng(dir, sampling, bmp.width, bmp.height)
                        }
                    } catch (io: Throwable) {
                        Logger.w("PALETTE", "sampling.io.fail", mapOf("error" to (io.message ?: "io")))
                    }
                }
                withContext(Dispatchers.Main) {
                    samplingRunning = false
                    lastSampling = sampling
                    overlayImageSize = Size(bmp.width, bmp.height)
                    val coverageOk = (sampling.params["coverage_ok"] as? Boolean) ?: true
                    val coverageState = if (coverageOk) "OK" else "низкое"
                    val hist = formatHistogram(sampling.roiHist)
                    tvStatus.text = "S7.1: ${sampling.samples.size} семплов • coverage=$coverageState • $hist • готово к S7.2"
                    setProgressVisible(false, "s7.sampling.done")
                    cbSampling.isEnabled = FeatureFlags.S7_SAMPLING
                    btnInitK0.isEnabled = FeatureFlags.S7_INIT
                    resetPaletteState()
                    if (overlayPending && FeatureFlags.S7_OVERLAY) {
                        suppressSamplingToggle = true
                        cbSampling.isChecked = true
                        suppressSamplingToggle = false
                        showSamplingOverlay(sampling)
                    } else {
                        overlay.isVisible = false
                    }
                    overlayPending = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Logger.e("PALETTE", "sampling.fail", mapOf("error" to (t.message ?: t.toString())), err = t)
                withContext(Dispatchers.Main) {
                    samplingRunning = false
                    overlayPending = false
                    setProgressVisible(false, "s7.sampling.fail")
                    cbSampling.isEnabled = FeatureFlags.S7_SAMPLING
                    btnInitK0.isEnabled = FeatureFlags.S7_INIT && lastSampling != null
                    suppressSamplingToggle = true
                    cbSampling.isChecked = false
                    suppressSamplingToggle = false
                    overlay.isVisible = false
                    tvStatus.text = "S7.1 ошибка: ${t.message}"
                    Toast.makeText(this@ImportActivity, "S7.1 ошибка: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun ensureOverlaySize(): Size? {
        val cached = overlayImageSize
        if (cached != null) return cached
        val bmp = baseBitmap ?: return null
        val size = Size(bmp.width, bmp.height)
        overlayImageSize = size
        return size
    }

    private fun showSamplingOverlay(result: S7SamplingResult) {
        val size = ensureOverlaySize()
        if (size == null) {
            overlay.isVisible = false
            return
        }
        overlay.setSamplingData(size, result, heat = true, points = true)
        overlay.isVisible = true
        overlayMode = OverlayMode.SAMPLING
    }

    private fun showResidualOverlay() {
        val size = ensureOverlaySize() ?: return
        val sampling = lastSampling ?: return
        val errors = residualErrors ?: return
        overlay.setResidualData(size, sampling, errors, residualDeMed, residualDe95)
        overlay.isVisible = true
        overlayMode = OverlayMode.RESIDUAL
    }

    private fun showSpreadOverlay(showBefore: Boolean, reinit: Boolean) {
        val sampling = lastSampling ?: return
        val spread = lastSpread ?: return
        val size = ensureOverlaySize() ?: return
        val ambiguity = spreadAmbiguity
        val affected = spreadAffected
        if (showBefore && ambiguity == null) return
        if (!showBefore && affected == null) return
        if (reinit) {
            overlay.setSpreadData(
                size,
                sampling,
                ambiguity,
                affected,
                showBefore,
                spread.deMinBefore,
                spread.de95Before,
                spread.deMinAfter,
                spread.de95After
            )
        } else {
            overlay.setSpreadMode(showBefore)
        }
        overlay.isVisible = true
        overlayMode = OverlayMode.SPREAD
        suppressResidualToggle = true
        cbShowResidual.isChecked = false
        suppressResidualToggle = false
        suppressSamplingToggle = true
        cbSampling.isChecked = true
        suppressSamplingToggle = false
    }

    private fun handleOverlayToggle(isChecked: Boolean) {
        if (!FeatureFlags.S7_OVERLAY && !FeatureFlags.S7_SAMPLING) return
        if (isChecked) {
            when {
                cbShowResidual.isChecked && residualErrors != null -> {
                    showResidualOverlay()
                }
                overlayMode == OverlayMode.SPREAD -> {
                    showSpreadOverlay(cbSpreadBeforeAfter.isChecked, reinit = false)
                }
                else -> {
                    val sampling = lastSampling
                    if (sampling == null) {
                        overlayPending = true
                        startSamplingInBackground(showOverlayWhenDone = true)
                    } else {
                        showSamplingOverlay(sampling)
                    }
                }
            }
        } else {
            overlay.isVisible = false
            overlay.clearOverlay()
            overlayMode = OverlayMode.NONE
        }
    }

    private fun handleResidualToggle(isChecked: Boolean) {
        if (isChecked) {
            if (residualErrors == null) {
                suppressResidualToggle = true
                cbShowResidual.isChecked = false
                suppressResidualToggle = false
                Toast.makeText(this, "Нет данных residual", Toast.LENGTH_SHORT).show()
                return
            }
            suppressSamplingToggle = true
            cbSampling.isChecked = true
            suppressSamplingToggle = false
            showResidualOverlay()
        } else {
            if (overlayMode == OverlayMode.RESIDUAL) {
                val sampling = lastSampling
                if (cbSampling.isChecked && sampling != null) {
                    showSamplingOverlay(sampling)
                } else {
                    overlay.isVisible = false
                    overlay.clearOverlay()
                    overlayMode = OverlayMode.NONE
                    suppressSamplingToggle = true
                    cbSampling.isChecked = false
                    suppressSamplingToggle = false
                }
            }
        }
    }

    private fun handleSpreadToggle(showBefore: Boolean) {
        val spread = lastSpread ?: return
        val before = paletteBeforeSpread
        val after = spread.colors
        val paletteToShow = if (showBefore) before else after
        val violationsSet = if (showBefore) {
            violationIndices(spread.violationsBefore)
        } else {
            violationIndices(spread.violationsAfter)
        }
        if (paletteToShow != null) {
            paletteStrip.setPalette(paletteToShow, violationsSet)
            suppressPaletteToggle = true
            cbPalette.isChecked = true
            suppressPaletteToggle = false
            paletteStrip.isVisible = true
        }
        showSpreadOverlay(showBefore, reinit = false)
    }

    private fun startPaletteInit() {
        if (!FeatureFlags.S7_INIT) return
        val sampling = lastSampling
        if (sampling == null) {
            Toast.makeText(this, "Сначала выполните S7.1", Toast.LENGTH_SHORT).show()
            return
        }
        if (initRunning) {
            tvStatus.text = "S7.2: перезапуск…"
        }
        initRunning = true
        setS7TriggerEnabled(btnInitK0, false)
        cbPalette.isEnabled = false
        btnGrowK.isEnabled = false
        setProgressVisible(true, "s7.init.start")
        tvStatus.text = "S7.2: инициализация палитры…"

        val seed = (sampling.params["seed"] as? Number)?.toLong() ?: S7InitSpec.DEFAULT_SEED

        launchS7Job("s7.init") {
            try {
                val result = S7Initializer.run(sampling, seed)
                DiagnosticsManager.currentSessionDir(this@ImportActivity)?.let { dir ->
                    try {
                        withContext(S7Dispatchers.export) {
                            S7PaletteIo.writeInitJson(dir, result)
                            S7PaletteIo.writeStripPng(dir, result)
                            S7PaletteIo.writeRolesCsv(dir, result)
                        }
                    } catch (io: Throwable) {
                        Logger.w("PALETTE", "palette.io.fail", mapOf("error" to (io.message ?: "io")))
                    }
                }
                withContext(Dispatchers.Main) {
                    initRunning = false
                    lastInit = result
                    lastGreedy = null
                    residualErrors = null
                    viewModel.updatePaletteColors(result.colors)
                    btnInitK0.isEnabled = FeatureFlags.S7_INIT
                    btnGrowK.isEnabled = FeatureFlags.S7_GREEDY
                    setProgressVisible(false, "s7.init.done")
                    updatePalettePreview(result.colors)
                    val minSpread = result.colors.minOfOrNull { if (it.spreadMin.isInfinite()) Float.MAX_VALUE else it.spreadMin }
                    val spreadStr = if (minSpread == null || minSpread == Float.MAX_VALUE) "∞" else "%.2f".format(minSpread)
                    val clippedCount = result.colors.count { it.clipped }
                    val anchors = formatAnchors(result)
                    tvStatus.text = "K0 готов: K=${result.colors.size}; anchors: $anchors; min spread=$spreadStr; clipped=$clippedCount"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Logger.e("PALETTE", "init.fail", mapOf("stage" to "S7.2", "error" to (t.message ?: t.toString())), err = t)
                withContext(Dispatchers.Main) {
                    initRunning = false
                    setProgressVisible(false, "s7.init.fail")
                    btnInitK0.isEnabled = FeatureFlags.S7_INIT
                    tvStatus.text = "S7.2 ошибка: ${t.message}"
                    Toast.makeText(this@ImportActivity, "S7.2 ошибка: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startPaletteGrowth() {
        if (!FeatureFlags.S7_GREEDY) return
        val sampling = lastSampling
        if (sampling == null) {
            Toast.makeText(this, "Сначала выполните S7.1", Toast.LENGTH_SHORT).show()
            return
        }
        val init = lastInit
        if (init == null) {
            Toast.makeText(this, "Сначала выполните S7.2", Toast.LENGTH_SHORT).show()
            return
        }
        if (greedyRunning) {
            tvStatus.text = "S7.3: перезапуск…"
        }
        greedyRunning = true
        btnGrowK.isEnabled = false
        btnSpread2Opt.isEnabled = false
        setProgressVisible(true, "s7.greedy.start")
        tvStatus.text = "S7.3: рост палитры…"

        val seed = (init.params["seed"] as? Number)?.toLong()
            ?: (sampling.params["seed"] as? Number)?.toLong()
            ?: S7InitSpec.DEFAULT_SEED
        val kTry = S7GreedySpec.kTry_default

        launchS7Job("s7.greedy") {
            var residualBitmap: Bitmap? = null
            try {
                val result = S7Greedy.run(sampling, init, kTry, seed)
                val errors = (result.params["residual_errors"] as? FloatArray)?.copyOf()
                val overlaySize = ensureOverlaySize()
                if (overlaySize != null && errors != null) {
                    residualBitmap = S7OverlayRenderer.createResidualBitmap(
                        overlaySize.width,
                        overlaySize.height,
                        sampling,
                        errors,
                        result.residual.deMed,
                        result.residual.de95
                    )
                }

                DiagnosticsManager.currentSessionDir(this@ImportActivity)?.let { dir ->
                    try {
                        withContext(S7Dispatchers.export) {
                            S7GreedyIo.writeIterCsv(dir, result.iters)
                            val k0 = init.colors.size
                            if (k0 > 0) {
                                S7GreedyIo.writePaletteSnapshot(dir, init.colors, k0)
                            }
                            val finalColors = result.colors
                            val kFinal = finalColors.size
                            val kMid = min(kFinal, k0 + min(4, kFinal - k0))
                            if (kMid > k0) {
                                S7GreedyIo.writePaletteSnapshot(dir, finalColors.take(kMid), kMid)
                            }
                            S7GreedyIo.writePaletteSnapshot(dir, finalColors, kFinal)
                            residualBitmap?.let { bmp ->
                                S7GreedyIo.writeResidualHeatmap(dir, bmp)
                            }
                        }
                    } catch (io: Throwable) {
                        Logger.w("PALETTE", "greedy.io.fail", mapOf("error" to (io.message ?: "io")))
                    }
                }

                val addedCount = result.iters.count { it.added }
                val rejectedDup = result.iters.count { !it.added && it.reason == "dup" }
                val errorsForUi = errors

                withContext(Dispatchers.Main) {
                    greedyRunning = false
                    btnGrowK.isEnabled = FeatureFlags.S7_GREEDY
                    btnSpread2Opt.isEnabled = FeatureFlags.S7_SPREAD2OPT
                    setProgressVisible(false, "s7.greedy.done")
                    lastGreedy = result
                    viewModel.updatePaletteColors(result.colors)
                    if (errorsForUi != null) {
                        residualErrors = errorsForUi
                        residualDeMed = result.residual.deMed
                        residualDe95 = result.residual.de95
                        cbShowResidual.isVisible = true
                        cbShowResidual.isEnabled = true
                        suppressResidualToggle = true
                        cbShowResidual.isChecked = true
                        suppressResidualToggle = false
                        suppressSamplingToggle = true
                        cbSampling.isChecked = true
                        suppressSamplingToggle = false
                        showResidualOverlay()
                    } else {
                        residualErrors = null
                        residualDeMed = 0f
                        residualDe95 = 0f
                        suppressResidualToggle = true
                        cbShowResidual.isChecked = false
                        suppressResidualToggle = false
                        cbShowResidual.isEnabled = false
                        cbShowResidual.isVisible = false
                        overlayMode = OverlayMode.NONE
                        overlay.isVisible = false
                    }
                    updatePalettePreview(result.colors)
                    val de95Str = String.format(Locale.US, "%.2f", result.residual.de95)
                    val status = "S7.3 готово: K=${result.colors.size}; de95=$de95Str; добавлено цветов=$addedCount; отклонено (dup)=$rejectedDup"
                    tvStatus.text = status
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Logger.e("PALETTE", "greedy.fail", mapOf("stage" to "S7.3", "error" to (t.message ?: t.toString())), err = t)
                withContext(Dispatchers.Main) {
                    greedyRunning = false
                    setProgressVisible(false, "s7.greedy.fail")
                    btnGrowK.isEnabled = FeatureFlags.S7_GREEDY
                    btnSpread2Opt.isEnabled = FeatureFlags.S7_SPREAD2OPT && lastSpread != null
                    tvStatus.text = "S7.3 ошибка: ${t.message}"
                    Toast.makeText(this@ImportActivity, "S7.3 ошибка: ${t.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                residualBitmap?.recycle()
            }
        }
    }

    private fun startSpread2Opt() {
        if (!FeatureFlags.S7_SPREAD2OPT) return
        val sampling = lastSampling
        val greedy = lastGreedy
        if (sampling == null || greedy == null) {
            Toast.makeText(this, "Сначала выполните S7.3", Toast.LENGTH_SHORT).show()
            return
        }
        if (spreadRunning) {
            tvStatus.text = "S7.4: перезапуск…"
        }
        val seed = (greedy.params["seed"] as? Number)?.toLong() ?: S7SamplingSpec.DEFAULT_SEED
        val deviceTier = (sampling.params["device_tier"] as? String) ?: S7SamplingSpec.DeviceTier.MID.key
        spreadRunning = true
        btnSpread2Opt.isEnabled = false
        setProgressVisible(true, "s7.spread.start")
        tvStatus.text = "S7.4: запускаем spread 2-opt…"

        launchS7Job("s7.spread2opt") {
            var beforeBitmap: Bitmap? = null
            var afterBitmap: Bitmap? = null
            try {
                val result = S7Spread2Opt.run(
                    sampling = sampling,
                    greedy = greedy,
                    passes = S7Spread2OptSpec.P_PASSES_DEFAULT,
                    seed = seed,
                    deviceTier = deviceTier
                )
                val colorsBefore = (result.params["colors_before"] as? List<*>)
                    ?.mapNotNull { it as? S7InitColor }
                    ?.map { it.copy(okLab = it.okLab.copyOf()) }
                    ?: greedy.colors.map { it.copy(okLab = it.okLab.copyOf()) }
                val ambiguity = (result.params["heatmap_ambiguity"] as? FloatArray)?.copyOf()
                val affected = (result.params["heatmap_affected"] as? FloatArray)?.copyOf()

                DiagnosticsManager.currentSessionDir(this@ImportActivity)?.let { dir ->
                    try {
                        withContext(S7Dispatchers.export) {
                            S7Spread2OptIo.writeDistMatrixCsv(dir, colorsBefore, "before")
                            S7Spread2OptIo.writeDistMatrixCsv(dir, result.colors, "after")
                            S7Spread2OptIo.writeViolationsCsv(dir, result.violationsBefore, "before")
                            S7Spread2OptIo.writeViolationsCsv(dir, result.violationsAfter, "after")
                            S7Spread2OptIo.writePairFixesCsv(dir, result.pairFixes)
                            S7Spread2OptIo.writePaletteStrip(dir, colorsBefore, "before")
                            S7Spread2OptIo.writePaletteStrip(dir, result.colors, "after")
                            val overlaySize = ensureOverlaySize()
                            if (overlaySize != null && ambiguity != null) {
                                beforeBitmap = createSpreadHeatmapBitmap(overlaySize, sampling, ambiguity, true)
                                beforeBitmap?.let { bmp ->
                                    S7Spread2OptIo.writeAffectedHeatmap(dir, bmp, "before")
                                }
                            }
                            if (overlaySize != null && affected != null) {
                                afterBitmap = createSpreadHeatmapBitmap(overlaySize, sampling, affected, false)
                                afterBitmap?.let { bmp ->
                                    S7Spread2OptIo.writeAffectedHeatmap(dir, bmp, "after")
                                }
                            }
                        }
                    } catch (io: Throwable) {
                        Logger.w("PALETTE", "spread2opt.io.fail", mapOf("error" to (io.message ?: "io")))
                    }
                }

                withContext(Dispatchers.Main) {
                    spreadRunning = false
                    btnSpread2Opt.isEnabled = FeatureFlags.S7_SPREAD2OPT
                    setProgressVisible(false, "s7.spread.done")
                    btnFinalizeK.isVisible = FeatureFlags.S7_KNEEDLE
                    setS7TriggerEnabled(btnFinalizeK, FeatureFlags.S7_KNEEDLE)
                    lastSpread = result
                    paletteBeforeSpread = colorsBefore
                    spreadAmbiguity = ambiguity
                    spreadAffected = affected
                    viewModel.updatePaletteColors(result.colors)
                    val violationsAfterSet = violationIndices(result.violationsAfter)
                    suppressSpreadToggle = true
                    cbSpreadBeforeAfter.isChecked = false
                    suppressSpreadToggle = false
                    cbSpreadBeforeAfter.isVisible = true
                    cbSpreadBeforeAfter.isEnabled = true
                    showSpreadOverlay(showBefore = false, reinit = true)
                    paletteStrip.setPalette(result.colors, violationsAfterSet)
                    suppressPaletteToggle = true
                    cbPalette.isChecked = true
                    suppressPaletteToggle = false
                    paletteStrip.isVisible = true
                    overlayMode = OverlayMode.SPREAD
                    val accepted = result.pairFixes.count { it.reason == "accepted" }
                    val rejected = result.pairFixes.count { it.reason != "accepted" }
                    val clippedMoves = result.pairFixes.sumOf { fix -> fix.moves.count { it.clipped } }
                    val status = buildString {
                        append("S7.4: minΔE ")
                        append(String.format(Locale.US, "%.2f", result.deMinBefore))
                        append(" → ")
                        append(String.format(Locale.US, "%.2f", result.deMinAfter))
                        append(", ΔE95 ")
                        append(String.format(Locale.US, "%.2f", result.de95Before))
                        append(" → ")
                        append(String.format(Locale.US, "%.2f", result.de95After))
                        append(", GBI ")
                        append(String.format(Locale.US, "%.3f", result.gbiBefore))
                        append(" → ")
                        append(String.format(Locale.US, "%.3f", result.gbiAfter))
                        append(", принятых ")
                        append(accepted)
                        append(", отклонено ")
                        append(rejected)
                        append(", clipped ")
                        append(clippedMoves)
                    }
                    tvStatus.text = status
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Logger.e("PALETTE", "spread2opt.fail", mapOf("stage" to "S7.4", "error" to (t.message ?: t.toString())), err = t)
                withContext(Dispatchers.Main) {
                    spreadRunning = false
                    setProgressVisible(false, "s7.spread.fail")
                    btnSpread2Opt.isEnabled = FeatureFlags.S7_SPREAD2OPT
                    tvStatus.text = "S7.4 ошибка: ${t.message}"
                    Toast.makeText(this@ImportActivity, "S7.4 ошибка: ${t.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                beforeBitmap?.recycle()
                afterBitmap?.recycle()
            }
        }
    }

    private fun startFinalizeK() {
        if (!FeatureFlags.S7_KNEEDLE) return
        val sampling = lastSampling
        val spread = lastSpread
        val init = lastInit
        if (sampling == null || spread == null || init == null) {
            Toast.makeText(this, "Сначала выполните S7.4", Toast.LENGTH_SHORT).show()
            return
        }
        if (kneedleRunning) {
            tvStatus.text = "S7.5: перезапуск…"
        }
        val palette = spread.colors
        if (palette.isEmpty()) {
            Toast.makeText(this, "Палитра пуста", Toast.LENGTH_SHORT).show()
            return
        }
        val k0 = init.colors.size
        val kTry = palette.size
        resetIndexState()
        kneedleRunning = true
        btnFinalizeK.isEnabled = false
        setProgressVisible(true, "s7.kneedle.start")
        tvStatus.text = "S7.5: подбор K*…"
        val seed = (spread.params["seed"] as? Number)?.toLong()
            ?: (lastGreedy?.params?.get("seed") as? Number)?.toLong()
            ?: S7SamplingSpec.DEFAULT_SEED
        launchS7Job("s7.kneedle") {
            var residualBitmap: Bitmap? = null
            var indexPreview: Bitmap? = null
            try {
                val result = S7Kneedle.run(sampling, palette, k0, kTry, seed)
                val row = result.rows.firstOrNull { it.k == result.Kstar }
                val errors = (result.params["errors_kstar"] as? FloatArray)?.copyOf()
                val overlaySize = ensureOverlaySize()
                if (overlaySize != null && errors != null) {
                    residualBitmap = S7OverlayRenderer.createResidualBitmap(
                        overlaySize.width,
                        overlaySize.height,
                        sampling,
                        errors,
                        row?.deMed ?: 0f,
                        row?.de95 ?: 0f
                    )
                }
                val base = baseBitmap
                val finalPalette = palette.take(result.Kstar)
                if (base != null) {
                    indexPreview = createIndexPreviewBitmap(base, finalPalette)
                }
                DiagnosticsManager.currentSessionDir(this@ImportActivity)?.let { dir ->
                    try {
                        withContext(S7Dispatchers.export) {
                            S7KneedleIo.writeGainCsv(dir, result.rows)
                            S7KneedleIo.writeKneedlePng(dir, result.rows, result.Kstar)
                            S7KneedleIo.writeFinalPalette(dir, finalPalette, result.Kstar)
                            PaletteLogcat.printFinalPalette(finalPalette, headN = 20)
                            residualBitmap?.let { bmp ->
                                S7KneedleIo.writeResidualHeatmap(dir, bmp, result.Kstar)
                            }
                            indexPreview?.let { bmp ->
                                S7KneedleIo.writeIndexPreview(dir, bmp, result.Kstar)
                            }
                        }
                    } catch (io: Throwable) {
                        Logger.w("PALETTE", "kneedle.io.fail", mapOf("error" to (io.message ?: "io")))
                    }
                }
                val errorsForUi = errors
                withContext(Dispatchers.Main) {
                    kneedleRunning = false
                    btnFinalizeK.isEnabled = FeatureFlags.S7_KNEEDLE
                    setProgressVisible(false, "s7.kneedle.done")
                    lastKneedle = result
                    viewModel.updateKstarReady(true)
                    val previousPreview = boundIndexPreview
                    val previewForUi = indexPreview
                    boundIndexPreview = previewForUi
                    autoStartIndexingIfReady("kneedle.done")
                    updatePalettePreview(finalPalette)
                    if (errorsForUi != null) {
                        residualErrors = errorsForUi
                        residualDeMed = row?.deMed ?: 0f
                        residualDe95 = row?.de95 ?: 0f
                        cbShowResidual.isVisible = true
                        cbShowResidual.isEnabled = true
                        suppressResidualToggle = true
                        cbShowResidual.isChecked = true
                        suppressResidualToggle = false
                        suppressSamplingToggle = true
                        cbSampling.isChecked = true
                        suppressSamplingToggle = false
                        showResidualOverlay()
                    } else {
                        residualErrors = null
                        residualDeMed = 0f
                        residualDe95 = 0f
                        suppressResidualToggle = true
                        cbShowResidual.isChecked = false
                        suppressResidualToggle = false
                        cbShowResidual.isEnabled = false
                        cbShowResidual.isVisible = false
                        overlay.isVisible = false
                        overlay.clearOverlay()
                        overlayMode = OverlayMode.NONE
                        suppressSamplingToggle = true
                        cbSampling.isChecked = false
                        suppressSamplingToggle = false
                    }
                    previewForUi?.let {
                        image.colorFilter = null
                        image.setImageBitmap(it)
                    }
                    if (previousPreview != null && previousPreview !== previewForUi && !previousPreview.isRecycled) {
                        previousPreview.recycle()
                    }
                    val dMax = (result.params["Dmax"] as? Number)?.toFloat() ?: 0f
                    val status = buildString {
                        append("S7.5: K*=")
                        append(result.Kstar)
                        append(", причина: ")
                        append(result.reason)
                        append("; ΔE95(K*)=")
                        append(String.format(Locale.US, "%.2f", row?.de95 ?: 0f))
                        append(", GBI(K*)=")
                        append(String.format(Locale.US, "%.3f", row?.gbi ?: 0f))
                        append(", TC=")
                        append(String.format(Locale.US, "%.3f", row?.tc ?: 0f))
                        append(", ISL=")
                        append(String.format(Locale.US, "%.3f", row?.isl ?: 0f))
                        append("; F-D: Dmax=")
                        append(String.format(Locale.US, "%.3f", dMax))
                        append(", τ_knee=")
                        append(String.format(Locale.US, "%.2f", S7KneedleSpec.tau_knee))
                        append(", τ_gain=")
                        append(String.format(Locale.US, "%.2f", S7KneedleSpec.tau_gain))
                    }
                    tvStatus.text = status
                    updateIndexUiState()
                    Logger.i(
                        "PALETTE",
                        "overlay.kstar.ready",
                        mapOf(
                            "Kstar" to result.Kstar,
                            "residual_ready" to (errorsForUi != null),
                            "index_preview" to (previewForUi != null)
                        )
                    )
                }
                indexPreview = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Logger.e(
                    "PALETTE",
                    "kneedle.ui.fail",
                    mapOf("error" to (t.message ?: t.toString())),
                    err = t
                )
                withContext(Dispatchers.Main) {
                    kneedleRunning = false
                    btnFinalizeK.isEnabled = FeatureFlags.S7_KNEEDLE
                    setProgressVisible(false, "s7.kneedle.fail")
                    tvStatus.text = "S7.5 ошибка: ${t.message}"
                    Toast.makeText(this@ImportActivity, "S7.5 ошибка: ${t.message}", Toast.LENGTH_LONG).show()
                    updateIndexUiState()
                }
            } finally {
                residualBitmap?.recycle()
                if (indexPreview != null && indexPreview !== boundIndexPreview) {
                    indexPreview?.recycle()
                }
            }
        }
    }

    private fun updatePalettePreview(colors: List<S7InitColor>) {
        viewModel.updatePaletteColors(colors)
        pendingPaletteColors = colors
        palettePreviewThrottler.submit {
            val palette = pendingPaletteColors
            if (palette != null) {
                pendingPaletteColors = null
                applyPalettePreview(palette)
            }
        }
    }

    private fun applyPalettePreview(colors: List<S7InitColor>) {
        paletteStrip.setPalette(colors)
        suppressPaletteToggle = true
        cbPalette.isChecked = true
        suppressPaletteToggle = false
        cbPalette.isEnabled = true
        cbPalette.isVisible = FeatureFlags.S7_INIT
        paletteStrip.isVisible = true
        setS7TriggerEnabled(btnSpread2Opt, FeatureFlags.S7_SPREAD2OPT && lastGreedy != null)
    }

    private fun resetIndexState() {
        indexRunning = false
        lastIndexResult = null
        viewModel.clearIndexResult()
        boundIndexPreview = null
        suppressIndexGridToggle = true
        cbIndexGrid.isChecked = false
        suppressIndexGridToggle = false
        suppressIndexCostToggle = true
        cbIndexCost.isChecked = false
        suppressIndexCostToggle = false
        cbIndexGrid.isVisible = false
        cbIndexGrid.isEnabled = false
        cbIndexCost.isVisible = false
        cbIndexCost.isEnabled = false
        if (overlayMode == OverlayMode.INDEX) {
            overlay.clearOverlay()
            overlay.isVisible = false
            overlayMode = OverlayMode.NONE
        }
        updateIndexUiState()
    }

    private class ThrottledUiUpdater(
        private val scope: CoroutineScope,
        private val minIntervalMs: Long
    ) {
        private var lastExecutionTime = 0L
        private var pendingJob: Job? = null

        fun submit(block: suspend () -> Unit) {
            val now = SystemClock.uptimeMillis()
            val elapsed = now - lastExecutionTime
            pendingJob?.cancel()
            if (elapsed >= minIntervalMs) {
                lastExecutionTime = now
                pendingJob = scope.launch(Dispatchers.Main.immediate) {
                    block()
                }
            } else {
                val delayMs = minIntervalMs - elapsed
                pendingJob = scope.launch(Dispatchers.Main.immediate) {
                    delay(delayMs)
                    lastExecutionTime = SystemClock.uptimeMillis()
                    block()
                }
            }
        }
    }

    private inner class MainThreadBlockMonitor {
        private val handler = Handler(Looper.getMainLooper())
        private var currentTask: String? = null
        private var lastPostedAt = 0L
        private var maxObservedDelay = 0L
        private val checkRunnable = object : Runnable {
            override fun run() {
                val task = currentTask ?: return
                val now = SystemClock.uptimeMillis()
                val delta = now - lastPostedAt
                if (delta > maxObservedDelay) {
                    maxObservedDelay = delta
                }
                if (delta > MAIN_BLOCK_THRESHOLD_MS) {
                    Logger.w(
                        "PALETTE",
                        "s7.monitor.block",
                        mapOf("task" to task, "delayMs" to delta)
                    )
                }
                if (currentTask == task) {
                    lastPostedAt = now
                    handler.postDelayed(this, MAIN_BLOCK_MONITOR_INTERVAL_MS)
                }
            }
        }

        fun start(taskName: String) {
            handler.removeCallbacks(checkRunnable)
            currentTask = taskName
            lastPostedAt = SystemClock.uptimeMillis()
            maxObservedDelay = 0L
            handler.postDelayed(checkRunnable, MAIN_BLOCK_MONITOR_INTERVAL_MS)
            Logger.i(
                "PALETTE",
                "s7.monitor.start",
                mapOf("task" to taskName)
            )
        }

        fun stop(taskName: String) {
            if (currentTask != taskName) return
            handler.removeCallbacks(checkRunnable)
            val maxDelay = maxObservedDelay
            Logger.i(
                "PALETTE",
                "s7.monitor.stop",
                mapOf(
                    "task" to taskName,
                    "maxDelayMs" to maxDelay,
                    "blocked" to (maxDelay > MAIN_BLOCK_THRESHOLD_MS)
                )
            )
            currentTask = null
            lastPostedAt = 0L
            maxObservedDelay = 0L
        }

        fun cancel() {
            handler.removeCallbacks(checkRunnable)
            currentTask = null
            lastPostedAt = 0L
            maxObservedDelay = 0L
        }
    }

    private fun updateIndexUiState() {
        val state = viewModel.uiState.value
        val canRun = FeatureFlags.S7_INDEX && state.isKstarReady && state.preScale != null && !indexRunning
        btnIndexK.isVisible = FeatureFlags.S7_INDEX
        setS7TriggerEnabled(btnIndexK, canRun)
        val hasResult = lastIndexResult != null
        val costAvailable = state.costHeatmap != null
        cbIndexGrid.isVisible = FeatureFlags.S7_INDEX && hasResult
        cbIndexGrid.isEnabled = hasResult && !indexRunning
        cbIndexCost.isVisible = FeatureFlags.S7_INDEX && hasResult
        cbIndexCost.isEnabled = hasResult && costAvailable && !indexRunning
    }

    private fun updateIndexOverlay(applyNewData: Boolean) {
        val result = lastIndexResult ?: return
        val costBitmap = viewModel.uiState.value.costHeatmap
        val size = Size(result.width, result.height)
        val grid = cbIndexGrid.isChecked
        val costChecked = cbIndexCost.isChecked && costBitmap != null
        if (grid || costChecked) {
            if (applyNewData || overlayMode != OverlayMode.INDEX) {
                overlay.setIndexOverlay(size, result.indexBpp, grid, costBitmap, costChecked)
            } else {
                overlay.updateIndexOverlay(grid, costChecked)
            }
            overlay.isVisible = true
            overlayMode = OverlayMode.INDEX
        } else {
            if (overlayMode == OverlayMode.INDEX) {
                overlay.updateIndexOverlay(false, false)
            }
            overlay.isVisible = false
            overlayMode = OverlayMode.NONE
        }
    }

    private fun handleIndexOverlayToggle() {
        if (lastIndexResult == null) {
            suppressIndexGridToggle = true
            cbIndexGrid.isChecked = false
            suppressIndexGridToggle = false
            suppressIndexCostToggle = true
            cbIndexCost.isChecked = false
            suppressIndexCostToggle = false
            updateIndexUiState()
            return
        }
        val applyNew = overlayMode != OverlayMode.INDEX
        updateIndexOverlay(applyNew)
        updateIndexUiState()
    }

    private fun resetPaletteState() {
        lastInit = null
        viewModel.updatePaletteColors(emptyList())
        lastGreedy = null
        residualErrors = null
        residualDeMed = 0f
        residualDe95 = 0f
        greedyRunning = false
        paletteStrip.setPalette(null)
        paletteStrip.isVisible = false
        pendingPaletteColors = null
        suppressPaletteToggle = true
        cbPalette.isChecked = false
        suppressPaletteToggle = false
        cbPalette.isEnabled = false
        cbPalette.isVisible = FeatureFlags.S7_INIT && lastSampling != null
        setS7TriggerEnabled(btnInitK0, FeatureFlags.S7_INIT && lastSampling != null && !samplingRunning)
        setS7TriggerEnabled(btnGrowK, false)
        lastSpread = null
        paletteBeforeSpread = null
        spreadAmbiguity = null
        spreadAffected = null
        spreadRunning = false
        suppressSpreadToggle = true
        cbSpreadBeforeAfter.isChecked = false
        suppressSpreadToggle = false
        cbSpreadBeforeAfter.isEnabled = false
        cbSpreadBeforeAfter.isVisible = false
        setS7TriggerEnabled(btnSpread2Opt, FeatureFlags.S7_SPREAD2OPT && lastGreedy != null)
        setS7TriggerEnabled(btnFinalizeK, false)
        btnFinalizeK.isVisible = FeatureFlags.S7_KNEEDLE && lastSpread != null
        suppressResidualToggle = true
        cbShowResidual.isChecked = false
        suppressResidualToggle = false
        cbShowResidual.isEnabled = false
        cbShowResidual.isVisible = false
        lastKneedle = null
        viewModel.updateKstarReady(false)
        resetIndexState()
    }

    private fun formatAnchors(init: S7InitResult): String {
        if (init.anchors.isEmpty()) return "нет"
        return init.anchors.entries.sortedBy { it.value }.joinToString(separator = ", ") { "${it.key}→${it.value}" }
    }

    private fun formatHistogram(roi: Map<S7SamplingSpec.Zone, Int>): String {
        return S7SamplingSpec.Zone.entries.joinToString(separator = " ") { zone ->
            val value = roi[zone] ?: 0
            "${zone.name.lowercase(Locale.ROOT)}=$value"
        }
    }

    private fun createSpreadHeatmapBitmap(size: Size, sampling: S7SamplingResult, values: FloatArray, before: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        val radius = max(size.width, size.height) / 90f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val samples = sampling.samples
        val limit = if (before) values.maxOrNull()?.coerceAtLeast(1e-6f) ?: 1f else 1f
        val clamp = if (limit <= 0f) 1f else limit
        for (idx in samples.indices) {
            if (idx >= values.size) break
            val value = values[idx]
            if (value <= 0f) continue
            val norm = if (before) (value / clamp).coerceIn(0f, 1f) else value.coerceIn(0f, 1f)
            val alpha = if (before) {
                (40f + 200f * norm).toInt().coerceIn(0, 255)
            } else {
                (70f + 180f * norm).toInt().coerceIn(0, 255)
            }
            val color = if (before) {
                Color.argb(alpha, 255, (120 + 80 * norm).toInt().coerceIn(0, 255), 32)
            } else {
                Color.argb(alpha, 64, (160 + 70 * norm).toInt().coerceIn(0, 255), 255)
            }
            paint.color = color
            val sample = samples[idx]
            canvas.drawCircle(sample.x.toFloat(), sample.y.toFloat(), radius, paint)
        }
        return bitmap
    }

    private fun createIndexPreviewBitmap(src: Bitmap, palette: List<S7InitColor>): Bitmap? {
        if (palette.isEmpty()) return null
        val width = src.width
        val height = src.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        val labs = palette.map { it.okLab }
        val colors = palette.map { it.sRGB }
        for (y in 0 until height) {
            src.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val lab = argbToOklab(row[x])
                var bestIdx = 0
                var bestDist = Float.POSITIVE_INFINITY
                for (i in labs.indices) {
                    val d = deltaE(lab, labs[i])
                    if (d < bestDist) {
                        bestDist = d
                        bestIdx = i
                    }
                }
                row[x] = colors[bestIdx]
            }
            out.setPixels(row, 0, width, 0, y, width, 1)
        }
        return out
    }

    private fun argbToOklab(color: Int): FloatArray {
        val r = srgbToLinear(Color.red(color) / 255f)
        val g = srgbToLinear(Color.green(color) / 255f)
        val b = srgbToLinear(Color.blue(color) / 255f)
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
        val lRoot = cbrt(l)
        val mRoot = cbrt(m)
        val sRoot = cbrt(s)
        val L = 0.2104542553f * lRoot + 0.7936177850f * mRoot - 0.0040720468f * sRoot
        val A = 1.9779984951f * lRoot - 2.4285922050f * mRoot + 0.4505937099f * sRoot
        val B = 0.0259040371f * lRoot + 0.7827717662f * mRoot - 0.8086757660f * sRoot
        return floatArrayOf(L, A, B)
    }

    private fun srgbToLinear(value: Float): Float {
        return if (value <= 0.04045f) {
            value / 12.92f
        } else {
            ((value + 0.055f) / 1.055f).pow(2.4f)
        }
    }

    private fun cbrt(value: Float): Float {
        return if (value <= 0f) 0f else kotlin.math.cbrt(value.toDouble()).toFloat()
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
        val h1p = hueAngleDouble(b1d, a1p)
        val h2p = hueAngleDouble(b2d, a2p)
        val deltaHp = 2.0 * sqrt(c1p * c2p) * sin((hueDeltaDouble(c1p, c2p, h1p, h2p)) / 2.0)
        val deltaLp = L2d - L1d
        val deltaCp = c2p - c1p
        val avgHp = meanHueDouble(h1p, h2p, c1p, c2p)
        val t = 1.0 - 0.17 * cos(avgHp - degToRadDouble(30.0)) + 0.24 * cos(2.0 * avgHp) +
            0.32 * cos(3.0 * avgHp + degToRadDouble(6.0)) - 0.20 * cos(4.0 * avgHp - degToRadDouble(63.0))
        val sl = 1.0 + (0.015 * (avgL - 50.0).pow(2.0)) / sqrt(20.0 + (avgL - 50.0).pow(2.0))
        val sc = 1.0 + 0.045 * avgCp
        val sh = 1.0 + 0.015 * avgCp * t
        val deltaTheta = degToRadDouble(30.0) * exp(-((avgHp - degToRadDouble(275.0)) / degToRadDouble(25.0)).pow(2.0))
        val rc = 2.0 * sqrt(avgCp.pow(7.0) / (avgCp.pow(7.0) + 25.0.pow(7.0)))
        val rt = -sin(2.0 * deltaTheta) * rc
        val termL = deltaLp / sl
        val termC = deltaCp / sc
        val termH = deltaHp / sh
        val deltaE = sqrt(termL * termL + termC * termC + termH * termH + rt * termC * termH)
        return deltaE.toFloat()
    }

    private fun hueAngleDouble(b: Double, ap: Double): Double {
        if (ap == 0.0 && b == 0.0) return 0.0
        var angle = kotlin.math.atan2(b, ap)
        if (angle < 0) angle += 2.0 * Math.PI
        return angle
    }

    private fun hueDeltaDouble(c1p: Double, c2p: Double, h1p: Double, h2p: Double): Double {
        if (c1p * c2p == 0.0) return 0.0
        val diff = h2p - h1p
        return when {
            kotlin.math.abs(diff) <= Math.PI -> diff
            diff > Math.PI -> diff - 2.0 * Math.PI
            diff < -Math.PI -> diff + 2.0 * Math.PI
            else -> diff
        }
    }

    private fun meanHueDouble(h1p: Double, h2p: Double, c1p: Double, c2p: Double): Double {
        if (c1p * c2p == 0.0) return h1p + h2p
        val diff = kotlin.math.abs(h1p - h2p)
        return when {
            diff <= Math.PI -> (h1p + h2p) * 0.5
            (h1p + h2p) < 2.0 * Math.PI -> (h1p + h2p + 2.0 * Math.PI) * 0.5
            else -> (h1p + h2p - 2.0 * Math.PI) * 0.5
        }
    }

    private fun degToRadDouble(value: Double): Double = value * Math.PI / 180.0

    private fun scheduleWarmupIfNeeded() {
        if (!FeatureFlags.S7_INDEX) return
        if (!WARMUP_SCHEDULED.compareAndSet(false, true)) return

        activityScope.launch(Dispatchers.Default) {
            var status = "ok"
            val start = SystemClock.elapsedRealtime()
            try {
                performWarmup(WARMUP_BITMAP_SIZE)
            } catch (cancelled: CancellationException) {
                status = "cancelled"
                throw cancelled
            } catch (t: Throwable) {
                status = "error"
                Logger.w(
                    "PALETTE",
                    "s7.warmup.fail",
                    mapOf("error" to (t.message ?: t.toString()))
                )
            } finally {
                val duration = SystemClock.elapsedRealtime() - start
                Logger.i(
                    "PALETTE",
                    "s7.warmup.finish",
                    mapOf("status" to status, "duration_ms" to duration)
                )
            }
        }
    }

    private fun performWarmup(size: Int) {
        val dummy = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        dummy.eraseColor(Color.rgb(0x7A, 0x88, 0x95))
        val maskBitmaps = Array(6) {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        }
        val masks = Masks(
            edge = maskBitmaps[0],
            flat = maskBitmaps[1],
            hiTexFine = maskBitmaps[2],
            hiTexCoarse = maskBitmaps[3],
            skin = maskBitmaps[4],
            sky = maskBitmaps[5]
        )
        val palette = buildWarmupPalette()
        val sampleCount = size * size
        val tilesPerSide = max(1, size / 8)
        val tileCount = max(1, tilesPerSide * tilesPerSide)

        PaletteQuantBuffers.acquire(sampleCount, palette.size, tileCount).use { workspace ->
            workspace.tileIndices.fill(0)
            workspace.owners.fill(-1)
            workspace.secondOwners.fill(-1)
            workspace.d2min.fill(0f)
            workspace.d2second.fill(0f)
            workspace.errorPerTile.fill(0f)
            workspace.weights.fill(0f)
            workspace.riskWeights.fill(0f)
            workspace.perColorImportance.fill(0.0)
            workspace.invalidTiles.fill(false)
        }
        DitherBuffers.acquireLineWorkspace(size + 2).use { workspace ->
            workspace.current.fill(0)
            workspace.next.fill(0)
        }
        DitherBuffers.acquirePlaneWorkspace(sampleCount).use { workspace ->
            workspace.errors.fill(0)
        }

        try {
            val tier = S7SamplingSpec.detectDeviceTier(this@ImportActivity).key
            val result = S7Indexer.run(
                ctx = applicationContext,
                preScaledImage = dummy,
                masks = masks,
                paletteK = palette,
                seed = S7SamplingSpec.DEFAULT_SEED,
                deviceTier = tier,
                mode = S7Indexer.Mode.PREVIEW,
                sourceWidth = size,
                sourceHeight = size
            )
            cleanupWarmupArtifacts(result)
        } finally {
            dummy.recycle()
            maskBitmaps.forEach { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun buildWarmupPalette(): List<S7InitColor> {
        val definitions = listOf(
            Color.rgb(240, 205, 180) to S7InitSpec.PaletteZone.SKIN,
            Color.rgb(140, 185, 255) to S7InitSpec.PaletteZone.SKY,
            Color.rgb(45, 45, 45) to S7InitSpec.PaletteZone.EDGE,
            Color.rgb(180, 120, 80) to S7InitSpec.PaletteZone.HITEX,
            Color.rgb(160, 160, 160) to S7InitSpec.PaletteZone.FLAT,
            Color.rgb(210, 210, 210) to S7InitSpec.PaletteZone.NEUTRAL,
            Color.rgb(110, 150, 110) to S7InitSpec.PaletteZone.HITEX,
            Color.rgb(220, 90, 90) to S7InitSpec.PaletteZone.EDGE
        )
        return definitions.map { (color, zone) ->
            val okLab = srgbToOkLab(color)
            S7InitColor(
                okLab = okLab,
                sRGB = color,
                protected = false,
                role = zone,
                spreadMin = 0f,
                clipped = false
            )
        }
    }

    private fun srgbToOkLab(color: Int): FloatArray {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val okLab = ColorMgmt.rgbLinearToOKLab(
            ColorMgmt.srgbToLinear(r),
            ColorMgmt.srgbToLinear(g),
            ColorMgmt.srgbToLinear(b)
        )
        return floatArrayOf(okLab.L, okLab.a, okLab.b)
    }

    private fun cleanupWarmupArtifacts(result: S7IndexResult) {
        safeDelete(result.indexPath)
        safeDelete(result.previewPath)
        safeDelete(result.legendCsvPath)
        safeDelete(result.costHeatmapPath)
    }

    private fun safeDelete(path: String?) {
        if (path.isNullOrEmpty()) return
        runCatching {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private fun violationIndices(violations: List<S7SpreadViolation>?): Set<Int>? {
        if (violations.isNullOrEmpty()) return null
        val set = mutableSetOf<Int>()
        for (violation in violations) {
            set.add(violation.i)
            set.add(violation.j)
        }
        return set
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeProgressSignals() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastDispatchAt = 0L
                progressSignals
                    .sample(150.milliseconds)
                    .distinctUntilChanged { old, new -> old.visible == new.visible }
                    .collect { signal ->
                        if (!this@ImportActivity::progress.isInitialized) return@collect
                        val now = SystemClock.elapsedRealtime()
                        val lag = (now - signal.emittedAt).coerceAtLeast(0L)
                        val sincePrev = if (lastDispatchAt == 0L) null else now - lastDispatchAt
                        progress.isVisible = signal.visible
                        lastDispatchAt = now
                        if (isStageBoundary(signal.reason)) {
                            val data = mutableMapOf<String, Any?>(
                                "stage" to signal.reason,
                                "visible" to signal.visible,
                                "lag_ms" to lag
                            )
                            sincePrev?.let { data["since_prev_ms"] = it }
                            Logger.i("UI", "progress.stage", data)
                        }
                    }
            }
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeIndexProgress() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.indexProgress
                    .sample(150.milliseconds)
                    .collect { value ->
                        if (!this@ImportActivity::progress.isInitialized) return@collect
                        val clamped = value.coerceIn(0, 100)
                        progress.isIndeterminate = clamped == 0 || clamped == 100
                        progress.progress = clamped
                    }
            }
        }
    }

    private fun isStageBoundary(reason: String): Boolean {
        if (!reason.startsWith("s7.")) return false
        return reason.endsWith(".start") ||
            reason.endsWith(".done") ||
            reason.endsWith(".fail") ||
            reason.endsWith(".finish") ||
            reason.endsWith("_ms")
    }

    private fun setProgressVisible(visible: Boolean, reason: String) {
        val signal = ProgressSignal(visible = visible, reason = reason, emittedAt = SystemClock.elapsedRealtime())
        if (!progressSignals.tryEmit(signal)) {
            Logger.w(
                "UI",
                "progress.flow.drop",
                mapOf("visible" to visible, "reason" to reason)
            )
        }
    }

    private data class ProgressSignal(
        val visible: Boolean,
        val reason: String,
        val emittedAt: Long,
    )

    /**
     * Автозапуск индекса S7, если всё готово.
     * Пишет подробный лог, почему пропустили запуск.
     */
    private fun autoStartIndexingIfReady(trigger: String) {
        val state = viewModel.uiState.value
        val kReady = state.isKstarReady
        val palReady = paletteColorsOrNull != null
        val preReady = state.preScale != null
        val flagOn = FeatureFlags.S7_INDEX
        val canRun = flagOn && !indexRunning && kReady && palReady && preReady
        val reason = when {
            canRun -> "ready"
            !flagOn -> "flag_off"
            indexRunning -> "index_running"
            !kReady -> "missing_kneedle"
            !palReady -> "missing_palette"
            !preReady -> "missing_prescale"
            else -> "unknown"
        }
        Logger.i(
            "PALETTE", "s7.autorun.check",
            mapOf(
                "trigger" to trigger,
                "indexRunning" to indexRunning,
                "flag" to flagOn,
                "kReady" to kReady,
                "palReady" to palReady,
                "preReady" to preReady,
                "ready" to canRun,
                "reason" to reason
            )
        )
        if (canRun) {
            startIndexingK()
        }
    }

    companion object {
        private const val TAG = "ImportActivity"
        private const val RC_OPEN_IMAGE = 1001
        private const val WARMUP_BITMAP_SIZE = 64
        private val WARMUP_SCHEDULED = AtomicBoolean(false)
        /** Частота UI-обновлений (throttle) для прогресса/палитры */
        private const val UI_THROTTLE_MS: Long = 100
        /** Порог «блокировки» главного потока для мониторинга */
        private const val MAIN_BLOCK_THRESHOLD_MS: Long = 700
        /** Интервал проверки блокировки главного потока */
        private const val MAIN_BLOCK_MONITOR_INTERVAL_MS: Long = 500
    }
}
