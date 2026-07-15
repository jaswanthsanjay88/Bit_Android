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
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
            var appKey = deriveAppKey(context)
            var ok = if (u.exists(path)) {
                u.openWithPassphrase(path, appKey, passphrase)
            } else {
                u.createWithPassphrase(path, appKey, passphrase)
            }
            if (!ok && u.exists(path)) {
                android.util.Log.w("VaultManager", "Failed to open vault (wrong key or corruption). Resetting database to start fresh.")
                try {
                    val file = java.io.File(path)
                    if (file.exists()) {
                        file.deleteRecursively()
                    }
                    val bf = bootstrapFile(context)
                    if (bf.exists()) {
                        bf.delete()
                    }
                    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    if (ks.containsAlias(KEYSTORE_ALIAS)) {
                        ks.deleteEntry(KEYSTORE_ALIAS)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VaultManager", "Failed to clear vault on reset fallback", e)
                }
                appKey = deriveAppKey(context)
                ok = u.createWithPassphrase(path, appKey, passphrase)
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

    private fun bootstrapFile(context: Context): java.io.File {
        val dir = java.io.File(context.filesDir, "app_bootstrap")
        dir.mkdirs()
        return java.io.File(dir, "k.bin")
    }

    fun deriveAppKey(context: Context): ByteArray {
        try {
            val pair = readDek(context)
            if (pair != null) {
                return unwrap(pair.first, pair.second)
            }
            val fresh = ByteArray(32)
            SecureRandom().nextBytes(fresh)
            val (iv, ct) = wrap(fresh)
            writeDek(context, iv, ct)
            return fresh
        } catch (e: Exception) {
            try {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (ks.containsAlias(KEYSTORE_ALIAS)) {
                    ks.deleteEntry(KEYSTORE_ALIAS)
                }
                val path = basePath(context)
                val file = java.io.File(path)
                if (file.exists()) {
                    file.deleteRecursively()
                }
                val bf = bootstrapFile(context)
                if (bf.exists()) {
                    bf.delete()
                }
            } catch (ignored: Exception) {}

            val fresh = ByteArray(32)
            SecureRandom().nextBytes(fresh)
            val (iv, ct) = wrap(fresh)
            writeDek(context, iv, ct)
            return fresh
        }
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(KEYSTORE_ALIAS)) {
            val entry = ks.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        return try {
            kg.init(buildKeySpec(preferStrongBox = true))
            kg.generateKey()
        } catch (e: Exception) {
            kg.init(buildKeySpec(preferStrongBox = false))
            kg.generateKey()
        }
    }

    private fun buildKeySpec(preferStrongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        if (preferStrongBox && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        return builder.build()
    }

    private fun wrap(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val key = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext)
        return iv to ct
    }

    private fun unwrap(iv: ByteArray, ct: ByteArray): ByteArray {
        val key = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    private fun writeDek(context: Context, iv: ByteArray, ct: ByteArray) {
        val file = bootstrapFile(context)
        val bos = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(bos)
        dos.writeInt(iv.size)
        dos.write(iv)
        dos.writeInt(ct.size)
        dos.write(ct)
        file.writeBytes(bos.toByteArray())
    }

    private fun readDek(context: Context): Pair<ByteArray, ByteArray>? {
        val file = bootstrapFile(context)
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
            val ivLen = dis.readInt()
            val iv = ByteArray(ivLen)
            dis.readFully(iv)
            val ctLen = dis.readInt()
            val ct = ByteArray(ctLen)
            dis.readFully(ct)
            iv to ct
        } catch (e: Exception) {
            null
        }
    }
}
