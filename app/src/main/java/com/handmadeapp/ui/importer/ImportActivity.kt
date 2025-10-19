package com.handmadeapp.ui.importer

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import com.appforcross.editor.config.FeatureFlags
import com.appforcross.editor.palette.S7Sampler
import com.appforcross.editor.palette.S7SamplingIo
import com.appforcross.editor.palette.S7SamplingResult
import com.appforcross.editor.palette.S7SamplingSpec
import com.handmadeapp.R
import com.handmadeapp.analysis.Masks
import com.handmadeapp.analysis.Stage3Analyze
import com.handmadeapp.diagnostics.DiagnosticsManager
import com.handmadeapp.logging.Logger
import com.handmadeapp.preset.Stage4Runner
import com.handmadeapp.prescale.PreScaleRunner
import java.io.File
import java.util.Locale
import kotlin.math.max

/**
 * ImportActivity: выбор изображения, предпросмотр и «живые» правки (яркость/контраст/насыщенность).
 * Реализовано без зависимостей на Activity Result API (для совместимости) — используем onActivityResult.
 */
class ImportActivity : AppCompatActivity() {

    private lateinit var image: ImageView
    private lateinit var pickBtn: Button
    private lateinit var sbBrightness: SeekBar
    private lateinit var sbContrast: SeekBar
    private lateinit var sbSaturation: SeekBar
    private lateinit var progress: ProgressBar
    private lateinit var fileNameView: TextView
    private lateinit var btnProcess: Button
    private lateinit var tvStatus: TextView
    private lateinit var cbSampling: CheckBox
    private lateinit var overlay: QuantOverlayView

    private var baseBitmap: Bitmap? = null
    private var currentUri: Uri? = null
    private var lastSampling: S7SamplingResult? = null
    private var overlayImageSize: Size? = null
    private var cachedMasks: Masks? = null
    private var cachedMasksSize: Size? = null
    private var samplingRunning = false
    private var overlayPending = false
    private var suppressSamplingToggle = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import)

        image = findViewById(R.id.previewImage)
        pickBtn = findViewById(R.id.btnPick)
        sbBrightness = findViewById(R.id.sbBrightness)
        sbContrast = findViewById(R.id.sbContrast)
        sbSaturation = findViewById(R.id.sbSaturation)
        progress = findViewById(R.id.progress)
        fileNameView = findViewById(R.id.tvFileName)
        btnProcess = findViewById(R.id.btnProcess)
        tvStatus = findViewById(R.id.tvStatus)
        cbSampling = findViewById(R.id.cbSampling)
        overlay = findViewById(R.id.quantOverlay)

        FeatureFlags.logFlagsOnce()
        cbSampling.isEnabled = false
        cbSampling.isChecked = false
        overlay.isVisible = false
        cbSampling.isVisible = FeatureFlags.S7_SAMPLING || FeatureFlags.S7_OVERLAY

        cbSampling.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSamplingToggle) return@setOnCheckedChangeListener
            if (!FeatureFlags.S7_OVERLAY && !FeatureFlags.S7_SAMPLING) {
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                val result = lastSampling
                if (result == null) {
                    overlayPending = true
                    startSamplingInBackground(showOverlayWhenDone = true)
                } else {
                    showOverlay(result)
                }
            } else {
                overlay.isVisible = false
                overlayImageSize?.let {
                    overlay.setData(it, null, heat = false, points = false)
                }
            }
        }

        pickBtn.setOnClickListener {
            openImagePicker()
        }

        // Центр — отсутствие изменений
        sbBrightness.max = 200
        sbBrightness.progress = 100
        sbContrast.max = 200
        sbContrast.progress = 100
        sbSaturation.max = 200
        sbSaturation.progress = 100

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = applyAdjustments()
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
        progress.isVisible = true
        image.setImageDrawable(ColorDrawable(0xFF222222.toInt()))
        fileNameView.text = queryDisplayName(uri) ?: "Выбран файл"
        tvStatus.text = "Загружаем превью…"
        currentUri = uri
        resetSamplingState()
        cbSampling.isEnabled = false
        Thread {
            try {
                val bmp = decodePreview(uri, maxDim = 2048)
                runOnUiThread {
                    baseBitmap = bmp
                    image.setImageBitmap(bmp)
                    overlayImageSize = Size(bmp.width, bmp.height)
                    resetSamplingState()
                    cbSampling.isEnabled = FeatureFlags.S7_SAMPLING
                    progress.isVisible = false
                    applyAdjustments()
                    tvStatus.text = "Предпросмотр готов. Можно запускать конвейер."
                    Log.i(TAG, "preview.built w=${bmp.width} h=${bmp.height}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "import.decode.fail: ${t.message}", t)
                runOnUiThread {
                    progress.isVisible = false
                    tvStatus.text = "Ошибка загрузки: ${t.message}"
                    Toast.makeText(this, "Ошибка загрузки: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Запуск основного конвейера: Stage3 → Stage4 → PreScale. Результат показываем в превью. */
    private fun runPipeline(uri: Uri, targetWst: Int) {
        tvStatus.text = "Запуск конвейера…"
        progress.isVisible = true
        btnProcess.isEnabled = false

        Thread {
            try {
                // 1) Stage3 — анализ превью (маски, метрики, классификация)
                val analyze = Stage3Analyze.run(this, uri)

                // 2) Stage4 — выбор пресета/параметров
                val stage4 = Stage4Runner.run(this, uri, targetWst = targetWst)

                // 3) PreScale — полный прогон и PNG-выход
                val pre = PreScaleRunner.run(
                    ctx = this,
                    uri = uri,
                    analyze = analyze,
                    gate = stage4.gate,
                    targetWst = targetWst
                )

                val png = File(pre.pngPath)
                val outBmp = BitmapFactory.decodeFile(png.absolutePath)

                runOnUiThread {
                    if (outBmp != null) {
                        image.colorFilter = null   // сбросить локальные правки предпросмотра
                        image.setImageBitmap(outBmp)
                        tvStatus.text = "Готово: ${png.name} (${outBmp.width}×${outBmp.height})"
                    } else {
                        tvStatus.text = "Готово, но не удалось отобразить результат"
                    }
                    progress.isVisible = false
                    btnProcess.isEnabled = true
                    Toast.makeText(this, "Конвейер завершён", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "pipeline.fail: ${t.message}", t)
                runOnUiThread {
                    progress.isVisible = false
                    btnProcess.isEnabled = true
                    tvStatus.text = "Ошибка конвейера: ${t.message}"
                    Toast.makeText(this, "Ошибка: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Реал-тайм предпросмотр с ColorMatrix (без пересборки битмапа). */
    private fun applyAdjustments() {
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
        overlayPending = false
        samplingRunning = false
        cachedMasks = null
        cachedMasksSize = null
        overlayImageSize = null
        suppressSamplingToggle = true
        cbSampling.isChecked = false
        suppressSamplingToggle = false
        overlay.isVisible = false
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
            tvStatus.text = "S7.1: расчёт уже выполняется…"
            return
        }
        overlayPending = showOverlayWhenDone
        samplingRunning = true
        cbSampling.isEnabled = false
        progress.isVisible = true
        tvStatus.text = "S7.1: считаем выборку…"

        Thread {
            try {
                val masks = ensureMasksFor(bmp, uri)
                val tier = S7SamplingSpec.detectDeviceTier(this).key
                val seed = S7SamplingSpec.DEFAULT_SEED
                val sampling = S7Sampler.run(bmp, masks, tier, seed)
                DiagnosticsManager.currentSessionDir(this)?.let { dir ->
                    try {
                        S7SamplingIo.writeJson(dir, sampling)
                        S7SamplingIo.writeRoiHistogramPng(dir, sampling, bmp.width, bmp.height)
                    } catch (io: Throwable) {
                        Logger.w("PALETTE", "sampling.io.fail", mapOf("error" to (io.message ?: "io")))
                    }
                }
                runOnUiThread {
                    samplingRunning = false
                    lastSampling = sampling
                    overlayImageSize = Size(bmp.width, bmp.height)
                    val coverageOk = (sampling.params["coverage_ok"] as? Boolean) ?: true
                    val coverageState = if (coverageOk) "OK" else "низкое"
                    val hist = formatHistogram(sampling.roiHist)
                    tvStatus.text = "S7.1: ${sampling.samples.size} семплов • coverage=$coverageState • $hist • готово к S7.2"
                    progress.isVisible = false
                    cbSampling.isEnabled = FeatureFlags.S7_SAMPLING
                    if (overlayPending && FeatureFlags.S7_OVERLAY) {
                        suppressSamplingToggle = true
                        cbSampling.isChecked = true
                        suppressSamplingToggle = false
                        showOverlay(sampling)
                    } else {
                        overlay.isVisible = false
                    }
                    overlayPending = false
                }
            } catch (t: Throwable) {
                Logger.e("PALETTE", "sampling.fail", mapOf("error" to (t.message ?: t.toString())), err = t)
                runOnUiThread {
                    samplingRunning = false
                    overlayPending = false
                    progress.isVisible = false
                    cbSampling.isEnabled = FeatureFlags.S7_SAMPLING
                    suppressSamplingToggle = true
                    cbSampling.isChecked = false
                    suppressSamplingToggle = false
                    overlay.isVisible = false
                    tvStatus.text = "S7.1 ошибка: ${t.message}"
                    Toast.makeText(this, "S7.1 ошибка: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun ensureMasksFor(bmp: Bitmap, uri: Uri): Masks {
        val cached = cachedMasks
        val size = cachedMasksSize
        if (cached != null && size != null && size.width == bmp.width && size.height == bmp.height) {
            return cached
        }
        Logger.i("PALETTE", "sampling.masks.build", mapOf("w" to bmp.width, "h" to bmp.height))
        val analyze = Stage3Analyze.run(this, uri)
        val scaled = scaleMasks(analyze.masks, bmp.width, bmp.height)
        cachedMasks = scaled
        cachedMasksSize = Size(bmp.width, bmp.height)
        return scaled
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
            sky = Bitmap.createScaledBitmap(masks.sky, targetW, targetH, filter)
        )
    }

    private fun showOverlay(result: S7SamplingResult) {
        val size = overlayImageSize ?: baseBitmap?.let { Size(it.width, it.height).also { s -> overlayImageSize = s } }
        if (size == null) {
            overlay.isVisible = false
            return
        }
        overlay.setData(size, result, heat = true, points = true)
        overlay.isVisible = true
    }

    private fun formatHistogram(roi: Map<S7SamplingSpec.Zone, Int>): String {
        return S7SamplingSpec.Zone.entries.joinToString(separator = " ") { zone ->
            val value = roi[zone] ?: 0
            "${zone.name.lowercase(Locale.ROOT)}=$value"
        }
    }

    companion object {
        private const val TAG = "ImportActivity"
        private const val RC_OPEN_IMAGE = 1001
    }
}
