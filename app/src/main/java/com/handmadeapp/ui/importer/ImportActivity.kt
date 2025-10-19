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
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import com.appforcross.editor.analysis.Stage3Analyze
import com.appforcross.editor.preset.Stage4Runner
import com.appforcross.editor.prescale.PreScaleRunner
import com.handmadeapp.R
import java.io.File
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

    private var baseBitmap: Bitmap? = null
    private var currentUri: Uri? = null

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
        Thread {
            try {
                val bmp = decodePreview(uri, maxDim = 2048)
                runOnUiThread {
                    baseBitmap = bmp
                    image.setImageBitmap(bmp)
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

    companion object {
        private const val TAG = "ImportActivity"
        private const val RC_OPEN_IMAGE = 1001
    }
}
