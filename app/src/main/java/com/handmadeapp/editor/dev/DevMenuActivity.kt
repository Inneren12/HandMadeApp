package com.handmadeapp.editor.dev

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.handmadeapp.R
import com.handmadeapp.diagnostics.DiagnosticsManager
import com.handmadeapp.editor.dev.DevPrefs
import com.handmadeapp.logging.LogLevel
import com.handmadeapp.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DevMenuActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dev_menu)

        findViewById<TextView>(R.id.tvTitle).text = "Developer Options"
        val swDebug = findViewById<Switch>(R.id.swDebugLogs)
        val btnExport = findViewById<Button>(R.id.btnExportDiag)

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
    }
}