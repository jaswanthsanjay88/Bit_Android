package com.bit.utils

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val TAG = "FontFileManager"

data class CustomFontItem(
    val path: String,
    val name: String,
    val sizeBytes: Long
)

class FontFileManager(private val context: Context) {

    private val fontsDir: File = context.filesDir.resolve("custom_fonts").also { it.mkdirs() }

    suspend fun importFont(uri: Uri, displayName: String): String? = withContext(Dispatchers.IO) {
        try {
            val sanitized = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val ext = if (displayName.endsWith(".otf", ignoreCase = true)) "otf" else "ttf"
            val fileName = "${System.currentTimeMillis()}_$sanitized.$ext"
            val destFile = File(fontsDir, fileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null

            // Validate typeface creation
            try {
                val tf = Typeface.createFromFile(destFile)
                if (tf == null) {
                    destFile.delete()
                    return@withContext null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create typeface from font file", e)
                destFile.delete()
                return@withContext null
            }

            Log.d(TAG, "Imported font: ${destFile.absolutePath}")
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import font", e)
            null
        }
    }

    fun deleteFont(internalPath: String): Boolean {
        return try {
            File(internalPath).delete()
        } catch (e: Exception) {
            false
        }
    }

    fun listCustomFonts(): List<CustomFontItem> {
        return fontsDir.listFiles()?.mapNotNull { file ->
            try {
                val tf = Typeface.createFromFile(file)
                if (tf != null) {
                    val rawName = file.name.substringAfter('_').substringBeforeLast('.')
                    CustomFontItem(
                        path = file.absolutePath,
                        name = rawName.replace('_', ' ').ifBlank { file.name },
                        sizeBytes = file.length()
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()
    }
}
