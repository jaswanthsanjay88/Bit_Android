package com.bit.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.bit.models.table_schema.Model
import com.bit.models.table_schema.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogCollector {

    private const val TAG = "CrashLogCollector"

    /**
     * Builds a comprehensive diagnostic crash report containing device specs,
     * model state, exception stack trace (if any), and recent system logcat output.
     */
    suspend fun captureCrashReport(
        context: Context?,
        model: Model? = null,
        config: ModelConfig? = null,
        customMessage: String? = null,
        throwable: Throwable? = null
    ): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val timestamp = dateFormat.format(Date())

        sb.append("=== BIT INFERENCE DIAGNOSTIC REPORT ===\n")
        sb.append("Timestamp: $timestamp\n")
        sb.append("App Version: ")
        try {
            val pInfo = context?.packageManager?.getPackageInfo(context.packageName, 0)
            sb.append("${pInfo?.versionName} (Build ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo?.longVersionCode else @Suppress("DEPRECATION") pInfo?.versionCode})\n")
        } catch (_: Exception) {
            sb.append("Unknown\n")
        }

        // Device Specs
        sb.append("\n[DEVICE & HARDWARE]\n")
        sb.append("Manufacturer: ${Build.MANUFACTURER}\n")
        sb.append("Model: ${Build.MODEL} (${Build.DEVICE})\n")
        sb.append("Hardware / Board: ${Build.HARDWARE} / ${Build.BOARD}\n")
        sb.append("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")

        // Memory State
        context?.let { ctx ->
            try {
                val actManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                actManager?.getMemoryInfo(memInfo)
                val freeMb = memInfo.availMem / (1024 * 1024)
                val totalMb = memInfo.totalMem / (1024 * 1024)
                val usedMb = totalMb - freeMb
                sb.append("RAM Status: ${usedMb}MB used / ${totalMb}MB total (${freeMb}MB free, lowMem=${memInfo.lowMemory})\n")
            } catch (e: Exception) {
                sb.append("RAM Status: Unable to query (${e.message})\n")
            }
        }

        // Model & Engine Configuration
        sb.append("\n[MODEL CONFIGURATION]\n")
        if (model != null) {
            sb.append("Model Name: ${model.modelName}\n")
            sb.append("Model Path: ${model.modelPath}\n")
            sb.append("Provider: ${model.providerType}\n")
            model.fileSize?.let { sb.append("File Size: ${it / (1024 * 1024)} MB\n") }
        } else {
            sb.append("Model: None (or unloaded)\n")
        }

        if (config != null) {
            sb.append("Loading Params: ${config.modelLoadingParams ?: "default"}\n")
            sb.append("Inference Params: ${config.modelInferenceParams ?: "default"}\n")
        }

        // Error / Message
        if (!customMessage.isNullOrBlank()) {
            sb.append("\n[REASON / SYMPTOM]\n")
            sb.append(customMessage.trim()).append("\n")
        }

        // Exception Stack Trace (if present)
        if (throwable != null) {
            sb.append("\n[EXCEPTION STACK TRACE]\n")
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            sb.append(sw.toString().trim()).append("\n")
        }

        // Native / Logcat Trace
        sb.append("\n[LOGCAT CRASH & TOMBSTONE TAIL]\n")
        val logcatOutput = readRecentLogcat()
        if (logcatOutput.isNotBlank()) {
            sb.append(logcatOutput)
        } else {
            sb.append("No relevant logcat entries retrieved.\n")
        }

        sb.append("\n=== END OF REPORT ===")
        sb.toString()
    }

    /**
     * Reads recent logcat entries focused on crash signals, tombstones, and inference process logs.
     */
    private fun readRecentLogcat(): String {
        return try {
            val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "160")
                .redirectErrorStream(true)
                .start()

            val lines = mutableListOf<String>()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    lines.add(line)
                    line = reader.readLine()
                }
            }
            process.waitFor()

            if (lines.isEmpty()) {
                return ""
            }

            // Find crash markers like Fatal signal, SIGBUS, SIGSEGV, DEBUG, abort, tombstone
            val crashKeywords = listOf(
                "Fatal signal",
                "SIGBUS",
                "SIGSEGV",
                "SIGFPE",
                "SIGABRT",
                "DEBUG   :",
                "crash_dump",
                "backtrace:",
                "bit.agent:inference",
                "com.bit.agent",
                "gguf_lib",
                "LLMService",
                "LlmModelWorker",
                "AndroidRuntime: FATAL"
            )

            val relevantLines = mutableListOf<String>()
            var foundCrashMarker = false

            for (l in lines) {
                if (crashKeywords.any { l.contains(it, ignoreCase = true) }) {
                    foundCrashMarker = true
                    relevantLines.add(l)
                } else if (foundCrashMarker && (l.startsWith("    #") || l.contains("pc ") || l.contains("lib"))) {
                    // Capture following backtrace lines
                    relevantLines.add(l)
                }
            }

            if (relevantLines.size >= 5) {
                relevantLines.takeLast(100).joinToString("\n")
            } else {
                // If filter didn't catch enough specifics, return the last 60 lines directly
                lines.takeLast(60).joinToString("\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read logcat", e)
            "Error executing logcat: ${e.message}"
        }
    }
}
