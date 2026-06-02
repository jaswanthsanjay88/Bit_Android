package com.dark.system_encryptor

/**
 * Native encryption engine for protecting user data.
 * Uses BoringSSL: AES-256-GCM for encryption, HKDF-SHA256 for key derivation.
 */
class SystemEncryptor {

    external fun nativeEncrypt(plaintext: ByteArray, key: ByteArray): ByteArray?
    external fun nativeDecrypt(sealedData: ByteArray, key: ByteArray): ByteArray?
    external fun nativeDeriveKey(masterKey: ByteArray, context: String): ByteArray?
    external fun nativeRandomBytes(size: Int): ByteArray?
    external fun nativeSecureWipe(data: ByteArray)
    private external fun nativePbkdf2(
        password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int
    ): ByteArray?

    external fun nativeHxsEncrypt(plaintext: ByteArray, masterKey: ByteArray, signerPubKey: ByteArray, signerPrivKey: ByteArray): ByteArray?
    external fun nativeHxsDecrypt(hxsBlock: ByteArray, masterKey: ByteArray): ByteArray?
    external fun nativePolicySetTrustedSigner(signerPubKey: ByteArray): Boolean
    external fun nativePolicyEvaluate(packageName: String, callerSignature: String, operation: Int, resourceId: String, authToken: String, authTokenSig: ByteArray): Boolean
    external fun nativePolicyGetAuditLogs(): String

    fun hxsEncrypt(plaintext: ByteArray, masterKey: ByteArray, signerPubKey: ByteArray, signerPrivKey: ByteArray): ByteArray {
        return nativeHxsEncrypt(plaintext, masterKey, signerPubKey, signerPrivKey)
            ?: throw SecurityException("HXS Encryption failed")
    }

    fun hxsDecrypt(hxsBlock: ByteArray, masterKey: ByteArray): ByteArray {
        return nativeHxsDecrypt(hxsBlock, masterKey)
            ?: throw SecurityException("HXS Decryption failed: invalid key, signature, or tampered data")
    }

    fun policySetTrustedSigner(signerPubKey: ByteArray): Boolean {
        return nativePolicySetTrustedSigner(signerPubKey)
    }

    fun policyEvaluate(packageName: String, callerSignature: String, operation: Int, resourceId: String, authToken: String, authTokenSig: ByteArray): Boolean {
        return nativePolicyEvaluate(packageName, callerSignature, operation, resourceId, authToken, authTokenSig)
    }

    fun policyGetAuditLogs(): String {
        return nativePolicyGetAuditLogs()
    }

    fun encryptData(plaintext: ByteArray, key: ByteArray): ByteArray {
        return nativeEncrypt(plaintext, key)
            ?: throw SecurityException("Encryption failed")
    }

    fun decryptData(sealedData: ByteArray, key: ByteArray): ByteArray {
        return nativeDecrypt(sealedData, key)
            ?: throw SecurityException("Decryption failed: invalid key or tampered data")
    }

    fun deriveKey(masterKey: ByteArray, context: String): ByteArray {
        return nativeDeriveKey(masterKey, context)
            ?: throw SecurityException("Key derivation failed")
    }

    fun randomBytes(size: Int): ByteArray {
        return nativeRandomBytes(size)
            ?: throw SecurityException("Random bytes generation failed")
    }

    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int = 600_000, keyLength: Int = 32): ByteArray =
        nativePbkdf2(password, salt, iterations, keyLength)
            ?: throw SecurityException("PBKDF2 derivation failed")

    fun secureWipe(data: ByteArray) {
        nativeSecureWipe(data)
    }

    companion object {
        init {
            System.loadLibrary("system_encryptor")
        }
    }
}
