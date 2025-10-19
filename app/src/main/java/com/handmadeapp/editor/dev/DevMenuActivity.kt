package com.handmadeapp.editor.dev

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Bitmap
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import com.handmadeapp.R
import com.handmadeapp.diagnostics.DiagnosticsManager
import com.handmadeapp.logging.LogLevel
import com.handmadeapp.logging.Logger
import com.handmadeapp.io.ImagePrep
import com.handmadeapp.analysis.Stage3Analyze
import com.handmadeapp.preset.Stage4Runner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val REQ_PICK_IMAGE = 1001
private const val REQ_PICK_ANALYZE = 1002
private const val REQ_PICK_PRESET = 1003

class DevMenuActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dev_menu)

        findViewById<TextView>(R.id.tvTitle).text = "Developer Options"
        val swDebug = findViewById<Switch>(R.id.swDebugLogs)
        val btnExport = findViewById<Button>(R.id.btnExportDiag)
        val btnStage2 = findViewById<Button>(R.id.btnPickRunStage2)
        val btnStage3 = findViewById<Button>(R.id.btnPickAnalyzeStage3)
        val btnStage4 = findViewById<Button>(R.id.btnPickPresetStage4)

        scope.launch {
            swDebug.isChecked = DevPrefs.isDebug(this@DevMenuActivity).first()
        }
        swDebug.setOnCheckedChangeListener { _, checked ->
            scope.launch {
                DevPrefs.setDebug(this@DevMenuActivity, checked)
                Logger.setMinLevel(if (checked) LogLevel.DEBUG else LogLevel.INFO)
                Toast.makeText(this@DevMenuActivity, "DEBUG logs: $checked", Toast.LENGTH_SHORT).show()
            }
        }

        btnExport.setOnClickListener {
            scope.launch {
                try {
                    val zip = DiagnosticsManager.exportZip(this@DevMenuActivity)
                    Logger.i("IO", "diag.export.ui", mapOf("zip" to zip.absolutePath))
                    val uri = Uri.fromFile(zip)
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, "Share diagnostics"))
                } catch (e: Exception) {
                    Logger.e("IO", "diag.export.fail", err = e)
                    Toast.makeText(this@DevMenuActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        btnStage2.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(intent, REQ_PICK_IMAGE)
        }

        btnStage3.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(intent, REQ_PICK_ANALYZE)
        }

        btnStage4.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(intent, REQ_PICK_PRESET)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
                // Ignore — best effort only.
            }
            runStage2(uri)
        }

        if (requestCode == REQ_PICK_ANALYZE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val rawFlags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            val flags = if (rawFlags != 0) rawFlags else Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
                // Ignore — best effort only.
            }
            runStage3(uri)
        }

        if (requestCode == REQ_PICK_PRESET && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val rawFlags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            val flags = if (rawFlags != 0) rawFlags else Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
                // Ignore — best effort only.
            }
            runStage4(uri)
        }
    }

    private fun runStage2(uri: Uri) {
        scope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    ImagePrep.prepare(this@DevMenuActivity, uri)
                }
                val out = File(cacheDir, "stage2_result.png")
                withContext(Dispatchers.IO) {
                    FileOutputStream(out).use { fos ->
                        res.linearF16.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                }
                Logger.i(
                    "IO",
                    "prep.result",
                    mapOf(
                        "uri" to uri.toString(),
                        "hdr" to res.wasHdrTonemapped,
                        "cs" to (res.srcColorSpace?.name ?: "unknown"),
                        "blk_mean" to res.blockiness.mean,
                        "halo" to res.haloScore,
                        "out" to out.absolutePath,
                        "bytes" to out.length()
                    )
                )
                Toast.makeText(this@DevMenuActivity, "Stage-2 OK → ${out.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.e("IO", "prep.fail", err = e, data = mapOf("uri" to uri.toString()))
                Toast.makeText(this@DevMenuActivity, "Stage-2 error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runStage3(uri: Uri) {
        scope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    Stage3Analyze.run(this@DevMenuActivity, uri)
                }
                val out = File(cacheDir, "stage3_preview.png")
                withContext(Dispatchers.IO) {
                    FileOutputStream(out).use { fos ->
                        res.preview.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                }
                Logger.i(
                    "ANALYZE",
                    "stage3.result",
                    mapOf(
                        "uri" to uri.toString(),
                        "kind" to res.decision.kind.name,
                        "subtype" to (res.decision.subtype ?: "-"),
                        "confidence" to "%.2f".format(res.decision.confidence),
                        "preview" to out.absolutePath
                    )
                )
                Toast.makeText(
                    this@DevMenuActivity,
                    "Stage-3: ${res.decision.kind} (${res.decision.subtype ?: "-"})",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Logger.e(
                    "ANALYZE",
                    "stage3.fail",
                    data = mapOf("uri" to uri.toString()),
                    err = e
                )
                Toast.makeText(this@DevMenuActivity, "Stage-3 error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runStage4(uri: Uri) {
        scope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    Stage4Runner.run(this@DevMenuActivity, uri, targetWst = 240)
                }
                Logger.i(
                    "PGATE",
                    "stage4.result",
                    mapOf(
                        "uri" to uri.toString(),
                        "preset" to res.gate.spec.id,
                        "addons" to res.gate.spec.addons.joinToString(","),
                        "r" to "%.3f".format(res.gate.r),
                        "json" to res.jsonPath
                    )
                )
                Toast.makeText(
                    this@DevMenuActivity,
                    "Stage-4: ${res.gate.spec.id}",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Logger.e(
                    "PGATE",
                    "stage4.fail",
                    data = mapOf("uri" to uri.toString()),
                    err = e
                )
                Toast.makeText(this@DevMenuActivity, "Stage-4 error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
