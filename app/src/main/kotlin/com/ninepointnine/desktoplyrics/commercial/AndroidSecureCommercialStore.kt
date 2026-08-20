package com.ninepointnine.desktoplyrics.commercial

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidSecureCommercialStore(
    context: Context,
    private val keyAlias: String = STORAGE_KEY_ALIAS,
    private val filePrefix: String = FILE_PREFIX
) : SecureCommercialStore {
    private val appContext = context.applicationContext
    private val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    @Synchronized
    override fun read(record: SecureCommercialRecord): SecureStoreReadResult {
        val atomicFile = atomicFile(record)
        if (!atomicFile.baseFile.exists()) return SecureStoreReadResult.Missing
        return runCatching {
            val encoded = atomicFile.openRead().use { it.readBytes() }
            SecureStoreReadResult.Value(decrypt(record, encoded))
        }.getOrElse { SecureStoreReadResult.Failure }
    }

    @Synchronized
    override fun write(record: SecureCommercialRecord, bytes: ByteArray): Boolean = runCatching {
        val atomicFile = atomicFile(record)
        val stream = atomicFile.startWrite()
        try {
            stream.write(encrypt(record, bytes))
            stream.fd.sync()
            atomicFile.finishWrite(stream)
        } catch (error: Exception) {
            atomicFile.failWrite(stream)
            throw error
        }
        true
    }.getOrDefault(false)

    @Synchronized
    override fun delete(record: SecureCommercialRecord): Boolean = runCatching {
        atomicFile(record).delete()
        true
    }.getOrDefault(false)

    internal fun storageFile(record: SecureCommercialRecord) = atomicFile(record).baseFile

    private fun encrypt(record: SecureCommercialRecord, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(record.storageKey.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plaintext)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeByte(FORMAT_VERSION)
            data.writeByte(cipher.iv.size)
            data.write(cipher.iv)
            data.writeInt(encrypted.size)
            data.write(encrypted)
        }
        return output.toByteArray()
    }

    private fun decrypt(record: SecureCommercialRecord, encoded: ByteArray): ByteArray {
        DataInputStream(ByteArrayInputStream(encoded)).use { data ->
            require(data.readUnsignedByte() == FORMAT_VERSION)
            val ivSize = data.readUnsignedByte()
            require(ivSize in 12..32)
            val iv = ByteArray(ivSize).also(data::readFully)
            val encryptedSize = data.readInt()
            require(encryptedSize in 16..MAX_RECORD_BYTES)
            val encrypted = ByteArray(encryptedSize).also(data::readFully)
            require(data.available() == 0)
            return Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                updateAAD(record.storageKey.toByteArray(Charsets.UTF_8))
                doFinal(encrypted)
            }
        }
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generateKey()
        }
    }

    private fun atomicFile(record: SecureCommercialRecord): AtomicFile = AtomicFile(
        appContext.noBackupFilesDir.resolve("$filePrefix${record.storageKey}.bin")
    )

    companion object {
        const val STORAGE_KEY_ALIAS = "03lyrics_commercial_storage_key_v1"
        private const val FILE_PREFIX = "commercial_secure_v1_"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION = 1
        private const val GCM_TAG_BITS = 128
        private const val MAX_RECORD_BYTES = 512 * 1024
    }
}
