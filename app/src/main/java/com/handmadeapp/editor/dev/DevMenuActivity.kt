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
import com.handmadeapp.editor.dev.DevPrefs
import com.handmadeapp.logging.LogLevel
import com.handmadeapp.logging.Logger
import com.appforcross.editor.io.ImagePrep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val REQ_PICK_IMAGE = 1001

class DevMenuActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dev_menu)

        findViewById<TextView>(R.id.tvTitle).text = "Developer Options"
        val swDebug = findViewById<Switch>(R.id.swDebugLogs)
        val btnExport = findViewById<Button>(R.id.btnExportDiag)
        val btnStage2 = findViewById<Button>(R.id.btnPickRunStage2)

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
}
