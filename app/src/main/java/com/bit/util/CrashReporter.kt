package com.bit.util

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val CRASH_FILE_NAME = "crash_reports.json"

    fun init(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrash(context, thread, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save crash report", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrash(context: Context, thread: Thread, throwable: Throwable) {
        val file = File(context.filesDir, CRASH_FILE_NAME)
        val array = if (file.exists()) {
            try {
                JSONArray(file.readText())
            } catch (e: Exception) {
                JSONArray()
            }
        } else {
            JSONArray()
        }

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val timestamp = sdf.format(Date())

        val crashObj = JSONObject().apply {
            put("timestamp", timestamp)
            put("thread", thread.name)
            put("exception", throwable.javaClass.name)
            put("message", throwable.message ?: "No message")
            put("stackTrace", stackTrace)
        }

        val newArray = JSONArray()
        newArray.put(crashObj)
        for (i in 0 until minOf(array.length(), 9)) {
            newArray.put(array.get(i))
        }

        file.writeText(newArray.toString(2))
    }

    fun getCrashReports(context: Context): String {
        val file = File(context.filesDir, CRASH_FILE_NAME)
        if (!file.exists()) return "[]"
        return try {
            file.readText()
        } catch (e: Exception) {
            "[]"
        }
    }

    fun clearCrashReports(context: Context) {
        val file = File(context.filesDir, CRASH_FILE_NAME)
        if (file.exists()) {
            file.delete()
        }
    }
}
