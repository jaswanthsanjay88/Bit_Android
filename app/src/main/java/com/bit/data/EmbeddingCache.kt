package com.bit.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.bit.global.AppPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmbeddingCache @Inject constructor(
    @ApplicationContext private val context: Context
) : SQLiteOpenHelper(context, AppPaths.vaultRoot(context).absolutePath + File.separator + "embeddings_cache.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS embeddings (" +
                    "hash_key TEXT PRIMARY KEY, " +
                    "vector BLOB" +
                    ")"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS embeddings")
        onCreate(db)
    }

    fun getEmbedding(text: String): FloatArray? {
        val hash = hashText(text)
        readableDatabase.rawQuery("SELECT vector FROM embeddings WHERE hash_key = ?", arrayOf(hash)).use { cursor ->
            if (cursor.moveToFirst()) {
                val blob = cursor.getBlob(0)
                return bytesToFloatArray(blob)
            }
        }
        return null
    }

    fun putEmbedding(text: String, vector: FloatArray) {
        val hash = hashText(text)
        val blob = floatArrayToBytes(vector)
        
        val values = ContentValues().apply {
            put("hash_key", hash)
            put("vector", blob)
        }
        
        writableDatabase.insertWithOnConflict("embeddings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun hashText(text: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun floatArrayToBytes(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.nativeOrder())
        for (f in floats) {
            buffer.putFloat(f)
        }
        return buffer.array()
    }

    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            floats[i] = buffer.float
        }
        return floats
    }
}
