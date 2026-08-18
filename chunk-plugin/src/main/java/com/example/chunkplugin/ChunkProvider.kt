package com.example.chunkplugin

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okio.buffer
import okio.source
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Result produced for each encrypted chunk. [salt] is populated only by the
 * password/PBKDF2 variant (empty for the Keystore variant).
 */
data class ChunkResult(
    val index: Int,
    val sha256Hex: String,
    val iv: ByteArray,
    val ciphertextBase64: String,
    val plainSize: Long,
    val salt: ByteArray = ByteArray(0)
)

/**
 * A redisbutable Android library that:
 *   1. Streams a file with Okio in fixed-size chunks (background thread).
 *   2. SHA-256-hashes each chunk.
 *   3. Encrypts each chunk with AES-256-GCM.
 *
 * Two key-source variants are provided:
 *   – Android Keystore-backed key (keys never leave the device).
 *   – Password-derived key via PBKDF2 (portable; salt stored alongside chunks).
 */
object ChunkProvider {

    private const val KEY_ALIAS = "chunk_file_enc_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_TAG_BITS = 128
    private const val IV_SIZE = 12
    private const val PBKDF2_ITERATIONS = 120_000
    private const val PBKDF2_SALT_SIZE = 16

    /**
     * Returns the Keystore-backed AES key, generating it if it does not exist.
     */
    @Synchronized
    fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (keyStore.containsAlias(KEY_ALIAS)) {
            return keyStore.getKey(KEY_ALIAS, null) as SecretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    /** SHA-256 hash of [data], returned as a lowercase hex string. */
    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()

    /**
     * Streams [file], chopping it into [chunkSizeMb] MiB chunks. Each chunk is
     * hashed and encrypted with an Android Keystore-backed key, and emitted as
     * a [ChunkResult]. The final chunk may be smaller than [chunkSizeMb].
     */
    fun chunkAndEncrypt(file: File, chunkSizeMb: Int): Flow<ChunkResult> = flow {
        require(chunkSizeMb in 1..512) { "chunkSizeMb must be between 1 and 512" }
        val key = getOrCreateKey()
        processChunks(file, chunkSizeMb) { chunk ->
            encrypt(chunk, key, ByteArray(0))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Password + PBKDF2 variant. Derives a fresh 256-bit key per file from
     * [password] using a random 16-byte salt (120k iterations of PBKDF2WithHmacSHA256).
     * The salt is returned in each [ChunkResult] so it can be stored alongside
     * the ciphertext for later decryption.
     */
    fun chunkAndEncryptWithPassword(
        file: File,
        chunkSizeMb: Int,
        password: String
    ): Flow<ChunkResult> = flow {
        require(chunkSizeMb in 1..512) { "chunkSizeMb must be between 1 and 512" }
        require(password.isNotBlank()) { "password must not be blank" }
        val salt = ByteArray(PBKDF2_SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        processChunks(file, chunkSizeMb) { chunk ->
            encrypt(chunk, key, salt)
        }
    }.flowOn(Dispatchers.IO)

    /** Derives a 256-bit AES key from [password] + [salt] using PBKDF2. */
    fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** Shared chunking pipeline: reads chunks and hands each to [transform]. */
    private suspend fun FlowCollector<ChunkResult>.processChunks(
        file: File,
        chunkSizeMb: Int,
        transform: suspend (ByteArray) -> ChunkResult
    ) {
        val random = SecureRandom()
        val chunkSize = chunkSizeMb.toLong() * 1024 * 1024

        file.source().buffer().use { source ->
            var index = 0
            val buffer = ByteArray(64 * 1024) // 64 KiB read buffer

            while (true) {
                val chunk = readUpTo(source, chunkSize, buffer) ?: break
                emit(transform(chunk).copy(index = index, sha256Hex = sha256Hex(chunk)))
                index++
            }
        }
    }

    /** Encrypts [data] with AES-256-GCM and returns a [ChunkResult]. */
    private fun encrypt(data: ByteArray, key: SecretKey, salt: ByteArray): ChunkResult {
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(data)
        return ChunkResult(
            index = 0,
            sha256Hex = "",
            iv = iv,
            ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            plainSize = data.size.toLong(),
            salt = salt
        )
    }

    /** Reads up to [byteCount] bytes; returns null at EOF. */
    private fun readUpTo(
        source: okio.BufferedSource,
        byteCount: Long,
        scratch: ByteArray
    ): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var remaining = byteCount
        while (remaining > 0) {
            val wanted = minOf(remaining, scratch.size.toLong()).toInt()
            val read = source.read(scratch, 0, wanted)
            if (read == -1) break
            out.write(scratch, 0, read)
            remaining -= read
        }
        val bytes = out.toByteArray()
        return if (bytes.isEmpty()) null else bytes
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }