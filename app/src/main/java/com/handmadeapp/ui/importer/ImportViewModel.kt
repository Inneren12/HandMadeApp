package com.handmadeapp.ui.importer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appforcross.editor.palette.S7IndexResult
import com.appforcross.editor.palette.S7Indexer
import com.appforcross.editor.palette.S7InitColor
import com.handmadeapp.analysis.Masks
import com.handmadeapp.analysis.Stage3Analyze
import com.handmadeapp.logging.Logger
import com.handmadeapp.prescale.PreScaleRunner
import com.handmadeapp.runtime.S7Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val previewBitmap: Bitmap? = null,
        val costHeatmap: Bitmap? = null,
        val masks: Masks? = null,
        val paletteColors: List<S7InitColor> = emptyList(),
        val isKstarReady: Boolean = false,
        val preScale: PreScaleRunner.Output? = null,
        val indexResult: S7IndexResult? = null,
        val isIndexRunning: Boolean = false,
        val indexError: String? = null,
    )

    private data class IndexRequest(
        val uri: Uri,
        val preScale: PreScaleRunner.Output,
        val palette: List<S7InitColor>,
        val kStar: Int,
        val seed: Long,
        val tier: String,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val indexRequests = MutableSharedFlow<IndexRequest>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val _indexProgress = MutableSharedFlow<Int>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val indexProgress = _indexProgress.asSharedFlow()

    private var lastProgressValue = -1
    private var lastProgressAt = 0L

    private var cachedMasks: Masks? = null
    private var cachedMaskSize: Size? = null
    private var cachedMaskUri: Uri? = null

    private var currentPreview: Bitmap? = null
    private var currentCost: Bitmap? = null

    init {
        viewModelScope.launch(S7Dispatchers.preview) {
            indexRequests.collectLatest { request ->
                runIndexRequest(request)
            }
        }
    }

    fun updatePreScale(output: PreScaleRunner.Output?) {
        _uiState.update { it.copy(preScale = output) }
    }

    fun updatePaletteColors(colors: List<S7InitColor>) {
        _uiState.update { it.copy(paletteColors = colors) }
    }

    fun updateKstarReady(ready: Boolean) {
        _uiState.update { it.copy(isKstarReady = ready) }
    }

    fun clearIndexResult() {
        replacePreview(null)
        replaceCost(null)
        _uiState.update { it.copy(previewBitmap = null, costHeatmap = null, indexResult = null, indexError = null, isIndexRunning = false) }
    }

    fun clearMasks() {
        val old = cachedMasks
        cachedMasks = null
        cachedMaskSize = null
        cachedMaskUri = null
        old?.let { recycleMasks(it) }
        _uiState.update { it.copy(masks = null) }
    }

    fun submitIndex(
        uri: Uri,
        preScale: PreScaleRunner.Output,
        palette: List<S7InitColor>,
        kStar: Int,
        seed: Long,
        tier: String,
    ) {
        viewModelScope.launch {
            indexRequests.emit(IndexRequest(uri, preScale, palette, kStar, seed, tier))
        }
    }

    suspend fun ensureScaledMasks(bitmap: Bitmap, uri: Uri): Masks {
        val size = Size(bitmap.width, bitmap.height)
        val cached = cachedMasks
        val cachedSize = cachedMaskSize
        val cachedUri = cachedMaskUri
        if (cached != null && cachedSize == size && cachedUri == uri) {
            return cached
        }
        Logger.i("PALETTE", "sampling.masks.build", mapOf("w" to size.width, "h" to size.height))
        val analyze = withContext(S7Dispatchers.preview) {
            Stage3Analyze.run(getApplication(), uri)
        }
        val scaled = scaleMasks(analyze.masks, size.width, size.height)
        updateMaskCache(scaled, uri, size)
        return scaled
    }

    override fun onCleared() {
        super.onCleared()
        replacePreview(null)
        replaceCost(null)
        cachedMasks?.let { recycleMasks(it) }
        cachedMasks = null
        cachedMaskSize = null
        cachedMaskUri = null
    }

    private suspend fun runIndexRequest(request: IndexRequest) {
        _uiState.update { it.copy(isIndexRunning = true, indexError = null) }
        resetProgressThrottle()
        emitProgress(0)
        var preBitmap: Bitmap? = null
        var previewBitmap: Bitmap? = null
        var costBitmap: Bitmap? = null
        var progressCompleted = false
        try {
            preBitmap = BitmapFactory.decodeFile(request.preScale.pngPath)
            if (preBitmap == null) {
                throw IllegalStateException("Не удалось открыть preScaled PNG")
            }
            val masks = ensureScaledMasks(preBitmap, request.uri)
            val kStar = request.kStar
            val palette = if (kStar in 1..request.palette.size) {
                request.palette.take(kStar)
            } else {
                request.palette
            }
            val result = S7Indexer.run(
                ctx = getApplication(),
                preScaledImage = preBitmap,
                masks = masks,
                paletteK = palette,
                seed = request.seed,
                deviceTier = request.tier,
                progressListener = ::emitProgress,
            )
            previewBitmap = BitmapFactory.decodeFile(result.previewPath)
                ?: throw IllegalStateException("Не удалось открыть индекс-превью")
            costBitmap = result.costHeatmapPath?.let { BitmapFactory.decodeFile(it) }
            publishIndexSuccess(result, previewBitmap, costBitmap)
            previewBitmap = null
            costBitmap = null
            progressCompleted = true
        } catch (cancelled: CancellationException) {
            _uiState.update { it.copy(isIndexRunning = false) }
            throw cancelled
        } catch (t: Throwable) {
            Logger.e(
                "PALETTE",
                "index.fail",
                mapOf("stage" to "viewmodel", "error" to (t.message ?: t.toString())),
                err = t,
            )
            emitProgress(100)
            progressCompleted = true
            _uiState.update { it.copy(isIndexRunning = false, indexError = t.message, indexResult = null) }
        } finally {
            if (!progressCompleted) {
                emitProgress(100)
            }
            preBitmap?.recycle()
            previewBitmap?.recycle()
            costBitmap?.recycle()
        }
    }

    private fun publishIndexSuccess(result: S7IndexResult, preview: Bitmap, cost: Bitmap?) {
        val previewForUi = replacePreview(preview)
        val costForUi = replaceCost(cost)
        _uiState.update {
            it.copy(
                previewBitmap = previewForUi,
                costHeatmap = costForUi,
                indexResult = result,
                isIndexRunning = false,
                indexError = null,
            )
        }
    }

    private fun replacePreview(new: Bitmap?): Bitmap? {
        val old = currentPreview
        if (old != null && old !== new && !old.isRecycled) {
            old.recycle()
        }
        currentPreview = new
        return new
    }

    private fun replaceCost(new: Bitmap?): Bitmap? {
        val old = currentCost
        if (old != null && old !== new && !old.isRecycled) {
            old.recycle()
        }
        currentCost = new
        return new
    }

    private fun updateMaskCache(masks: Masks, uri: Uri, size: Size) {
        cachedMasks?.let { recycleMasks(it) }
        cachedMasks = masks
        cachedMaskSize = size
        cachedMaskUri = uri
        _uiState.update { it.copy(masks = masks) }
    }

    private fun recycleMasks(masks: Masks) {
        if (!masks.edge.isRecycled) masks.edge.recycle()
        if (!masks.flat.isRecycled) masks.flat.recycle()
        if (!masks.hiTexFine.isRecycled) masks.hiTexFine.recycle()
        if (!masks.hiTexCoarse.isRecycled) masks.hiTexCoarse.recycle()
        if (!masks.skin.isRecycled) masks.skin.recycle()
        if (!masks.sky.isRecycled) masks.sky.recycle()
    }

    private fun scaleMasks(masks: Masks, targetW: Int, targetH: Int): Masks {
        if (masks.edge.width == targetW && masks.edge.height == targetH) return masks
        val filter = true
        return Masks(
            edge = Bitmap.createScaledBitmap(masks.edge, targetW, targetH, filter),
            flat = Bitmap.createScaledBitmap(masks.flat, targetW, targetH, filter),
            hiTexFine = Bitmap.createScaledBitmap(masks.hiTexFine, targetW, targetH, filter),
            hiTexCoarse = Bitmap.createScaledBitmap(masks.hiTexCoarse, targetW, targetH, filter),
            skin = Bitmap.createScaledBitmap(masks.skin, targetW, targetH, filter),
            sky = Bitmap.createScaledBitmap(masks.sky, targetW, targetH, filter),
        )
    }

    private fun emitProgress(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        val now = SystemClock.elapsedRealtime()
        val delta = if (lastProgressValue < 0) Int.MAX_VALUE else clamped - lastProgressValue
        val elapsed = now - lastProgressAt
        val shouldEmit = lastProgressValue < 0 || clamped == 0 || clamped == 100 ||
            delta >= MIN_PROGRESS_STEP || elapsed >= MIN_PROGRESS_INTERVAL_MS
        if (!shouldEmit) return
        lastProgressValue = clamped
        lastProgressAt = now
        _indexProgress.tryEmit(clamped)
    }

    private fun resetProgressThrottle() {
        lastProgressValue = -1
        lastProgressAt = 0L
    }

    private companion object {
        private const val MIN_PROGRESS_STEP = 2
        private const val MIN_PROGRESS_INTERVAL_MS = 100L
    }
}
