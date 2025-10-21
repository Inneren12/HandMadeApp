package com.handmadeapp.diagnostics

import android.content.Context
import android.os.Build
import com.handmadeapp.logging.Logger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

object DiagnosticsManager {
    private const val DIAG_DIR = "diag"
    private const val SESS_PREFIX = "session-"
    private const val MAX_SESSIONS = 5
    private const val S7_GATES_FILE = "s7_gates.json"

    data class S7Gate(val probability: Double, val target: Double) {
        fun toMap(): Map<String, Any> = linkedMapOf(
            "prob" to probability,
            "target" to target
        )
    }

    data class S7GateConfig(
        val aaMask: Int,
        val gates: Map<String, S7Gate>
    ) {
        fun gate(id: String): S7Gate = gates[id] ?: DEFAULT_S7_GATES.gates[id] ?: S7Gate(1.0, 1.0)

        fun toParamMap(): Map<String, Any> {
            val map = LinkedHashMap<String, Any>()
            map["aa_mask"] = aaMask
            val gatesMap = LinkedHashMap<String, Any>()
            gates.forEach { (id, gate) ->
                gatesMap[id] = gate.toMap()
            }
            map["gates"] = gatesMap
            return map
        }

        fun summary(): String {
            val builder = StringBuilder()
            builder.append("AA=")
            builder.append("0x")
            builder.append(aaMask.toUInt().toString(16).uppercase(Locale.US))
            val ordered = listOf("A", "B", "C", "D")
            for (key in ordered) {
                val gate = gates[key] ?: continue
                builder.append(", ")
                builder.append(key)
                builder.append("=")
                builder.append(String.format(Locale.US, "%.2f/%.2f", gate.probability, gate.target))
            }
            return builder.toString()
        }
    }

    private val DEFAULT_S7_GATES = S7GateConfig(
        aaMask = 0,
        gates = linkedMapOf(
            "A" to S7Gate(1.0, 1.0),
            "B" to S7Gate(1.0, 1.0),
            "C" to S7Gate(1.0, 1.0),
            "D" to S7Gate(1.0, 1.0)
        )
    )

    data class Session(val id: String, val dir: File)

    fun startSession(ctx: Context): Session {
        val root = File(ctx.filesDir, DIAG_DIR).apply { if (!exists()) mkdirs() }
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(root, "$SESS_PREFIX$id").apply { mkdirs() }
        rotate(root)
        Logger.i("IO", "io.session.start", mapOf(
            "diag_root" to root.absolutePath,
            "dir" to dir.absolutePath,
            "device" to "${Build.MANUFACTURER}/${Build.MODEL}",
            "sdk" to Build.VERSION.SDK_INT
        ))
        return Session(id, dir)
    }

    fun currentSessionDir(ctx: Context): File? {
        val root = File(ctx.filesDir, DIAG_DIR)
        val sessions = root.listFiles()?.filter { it.isDirectory && it.name.startsWith(SESS_PREFIX) }?.sorted()
        return sessions?.lastOrNull()
    }

    fun loadS7GateConfig(ctx: Context): S7GateConfig {
        val root = File(ctx.filesDir, DIAG_DIR)
        val file = File(root, S7_GATES_FILE)
        if (!file.exists()) {
            return DEFAULT_S7_GATES
        }
        val text = runCatching { file.readText() }.getOrElse {
            Logger.w("DIAG", "s7.gates.read.fail", mapOf("path" to file.absolutePath, "error" to (it.message ?: it.toString())))
            return DEFAULT_S7_GATES
        }
        return runCatching {
            val obj = JSONObject(text)
            val mask = obj.optInt("aa_mask", DEFAULT_S7_GATES.aaMask)
            val gatesObj = obj.optJSONObject("gates")
            val gates = LinkedHashMap<String, S7Gate>()
            val keys = listOf("A", "B", "C", "D")
            for (key in keys) {
                val gateObj = gatesObj?.optJSONObject(key)
                val defaultGate = DEFAULT_S7_GATES.gates[key]
                val prob = gateObj?.optDouble("prob", defaultGate?.probability ?: 1.0) ?: defaultGate?.probability ?: 1.0
                val target = gateObj?.optDouble("target", defaultGate?.target ?: 1.0) ?: defaultGate?.target ?: 1.0
                gates[key] = S7Gate(probability = prob, target = target)
            }
            S7GateConfig(mask, gates)
        }.getOrElse {
            Logger.w("DIAG", "s7.gates.parse.fail", mapOf("path" to file.absolutePath, "error" to (it.message ?: it.toString())))
            DEFAULT_S7_GATES
        }
    }

    fun exportZip(ctx: Context): File {
        val sessionDir = currentSessionDir(ctx) ?: throw IllegalStateException("No session found")
        val out = File(ctx.cacheDir, "${sessionDir.name}.zip")
        zipDir(sessionDir, out)
        Logger.i("IO", "diag.export", mapOf("zip_path" to out.absolutePath, "bytes" to out.length()))
        return out
    }

    private fun rotate(root: File) {
        val sessions = root.listFiles()?.filter { it.isDirectory && it.name.startsWith(SESS_PREFIX) }?.sorted() ?: return
        val excess = sessions.size - MAX_SESSIONS
        if (excess > 0) {
            sessions.take(excess).forEach {
                it.deleteRecursively()
                Logger.i("IO", "diag.rotate", mapOf("removed" to it.name))
            }
        }
    }

    private fun zipDir(srcDir: File, outZip: File) {
        ZipOutputStream(FileOutputStream(outZip)).use { zos ->
            fun add(file: File, base: String) {
                val name = base + file.name
                if (file.isDirectory) {
                    zos.putNextEntry(ZipEntry("$name/")); zos.closeEntry()
                    file.listFiles()?.forEach { add(it, "$name/") }
                } else {
                    FileInputStream(file).use { fis ->
                        zos.putNextEntry(ZipEntry(name))
                        fis.copyTo(zos)
                        zos.closeEntry()
                    }
                }
            }
            add(srcDir, "")
        }
    }
}