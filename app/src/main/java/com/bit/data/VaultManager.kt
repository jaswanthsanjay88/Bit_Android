package com.bit.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.bit.global.AppPaths
import com.bit.repo.ums.UmsChatRepository
import com.bit.repo.ums.UmsConfigRepository
import com.bit.repo.ums.UmsKnowledgeRepository
import com.bit.repo.ums.UmsMemoryRepository
import com.bit.repo.ums.UmsModelRepository
import com.bit.repo.ums.UmsPersonaRepository
import com.dark.ums.UnifiedMemorySystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object VaultManager {

    private var _ums: UnifiedMemorySystem? = null
    val ums: UnifiedMemorySystem get() = _ums ?: error("VaultManager not initialized")

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady

    var modelRepo: UmsModelRepository? = null; private set
    var configRepo: UmsConfigRepository? = null; private set
    var personaRepo: UmsPersonaRepository? = null; private set
    var memoryRepo: UmsMemoryRepository? = null; private set
    var knowledgeRepo: UmsKnowledgeRepository? = null; private set
    var chatRepo: UmsChatRepository? = null; private set

    fun basePath(context: Context): String =
        AppPaths.ums(context).absolutePath

    fun exists(context: Context): Boolean {
        val u = UnifiedMemorySystem()
        return u.exists(basePath(context))
    }

    fun initPlaintext(context: Context): Boolean {
        return initEncrypted(context, "BitSecurePassphrase2026")
    }

    fun initEncrypted(context: Context, passphrase: String): Boolean {
        synchronized(this) {
            if (_isReady.value) return true
            val u = UnifiedMemorySystem()
            val path = basePath(context)
            val appKey = deriveAppKey(context)
            val ok = if (u.exists(path)) {
                u.openWithPassphrase(path, appKey, passphrase)
            } else {
                u.createWithPassphrase(path, appKey, passphrase)
            }
            if (!ok) return false
            initRepos(u)
            return true
        }
    }

    private fun initRepos(u: UnifiedMemorySystem) {
        _ums = u
        modelRepo = UmsModelRepository(u).also { it.init() }
        configRepo = UmsConfigRepository(u).also { it.init() }
        personaRepo = UmsPersonaRepository(u).also { it.init() }
        memoryRepo = UmsMemoryRepository(u).also { it.init() }
        knowledgeRepo = UmsKnowledgeRepository(u).also { it.init() }
        chatRepo = UmsChatRepository(u).also { it.init() }
        _isReady.value = true
    }

    fun close() {
        _ums?.close()
        _ums = null
        modelRepo = null
        configRepo = null
        personaRepo = null
        memoryRepo = null
        knowledgeRepo = null
        chatRepo = null
        _isReady.value = false
    }

    private const val KEYSTORE_ALIAS = "ums_app_key"

    fun deriveAppKey(context: Context): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        try {
            val key: SecretKey = if (ks.containsAlias(KEYSTORE_ALIAS)) {
                (ks.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
            } else {
                generateNewKey()
            }
            return key.encoded ?: deriveFromHardwareKey(key)
        } catch (e: Exception) {
            // Delete the invalid key and try once more with the correct configuration
            try {
                if (ks.containsAlias(KEYSTORE_ALIAS)) {
                    ks.deleteEntry(KEYSTORE_ALIAS)
                }
                // Clear any partially created database to avoid passphrase mismatch
                val path = basePath(context)
                val file = java.io.File(path)
                if (file.exists()) {
                    file.deleteRecursively()
                }
            } catch (ignored: Exception) {}

            val key = generateNewKey()
            return key.encoded ?: deriveFromHardwareKey(key)
        }
    }

    private fun generateNewKey(): SecretKey {
        val gen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        gen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(false) // Required to allow caller-provided fixed IV for deterministic derivation
                .build()
        )
        return gen.generateKey()
    }

    private fun deriveFromHardwareKey(key: SecretKey): ByteArray {
        // Use GCM with a fixed IV to get deterministic output per device.
        // This is safe because: (1) only used once for key derivation, not encryption,
        // (2) the plaintext is a fixed constant, (3) we hash the result.
        val fixedIv = "BITKD_12B_IV".toByteArray() // 12 bytes for GCM
        val spec = javax.crypto.spec.GCMParameterSpec(128, fixedIv)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, spec)
        val ciphertext = cipher.doFinal("BIT-UMS-KeyDerivation".toByteArray())
        return java.security.MessageDigest.getInstance("SHA-256").digest(ciphertext)
    }
}
