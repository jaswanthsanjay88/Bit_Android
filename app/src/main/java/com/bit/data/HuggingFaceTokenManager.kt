package com.bit.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages HuggingFace API access token with encrypted on-device storage.
 *
 * Uses EncryptedSharedPreferences (AES-256-GCM via AndroidKeyStore) to ensure
 * the token is never stored as plaintext. The token is injected automatically
 * into HuggingFace API requests via [com.bit.network.HuggingFaceAuthInterceptor].
 *
 * Security notes:
 * - Token is encrypted at rest using hardware-backed KeyStore when available
 * - Token is never logged (only presence is checked)
 * - Graceful fallback on KeyStore corruption (clear and recreate)
 */
@Singleton
class HuggingFaceTokenManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "HfTokenManager"
        private const val PREFS_FILE = "hf_secure_prefs"
        private const val KEY_TOKEN = "hf_access_token"
    }

    private val prefs: SharedPreferences by lazy { createEncryptedPrefs() }

    private fun createEncryptedPrefs(): SharedPreferences {
        return try {
            buildEncryptedPrefs()
        } catch (e: Exception) {
            // KeyStore corruption — delete and rebuild
            Log.e(TAG, "EncryptedSharedPreferences init failed, resetting", e)
            context.deleteSharedPreferences(PREFS_FILE)
            buildEncryptedPrefs()
        }
    }

    private fun buildEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Save the HuggingFace access token (encrypted). */
    fun saveToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isBlank()) return
        prefs.edit().putString(KEY_TOKEN, trimmed).apply()
        Log.d(TAG, "HF token saved (encrypted)")
    }

    /** Retrieve the decrypted token, or null if not set. */
    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    /** Remove the stored token. */
    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
        Log.d(TAG, "HF token cleared")
    }

    /** Fast check — does a token exist? */
    fun hasToken(): Boolean = !getToken().isNullOrBlank()

    /** Returns "Bearer <token>" for use in Authorization header, or null. */
    fun getBearerHeader(): String? {
        val token = getToken() ?: return null
        return "Bearer $token"
    }
}
