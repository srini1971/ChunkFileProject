package com.example.chunkplugin

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class UploadState { PENDING, UPLOADED, FAILED }

data class ChunkUpload(
    val chunk: ChunkResult,
    val state: UploadState
)

/**
 * Uploads encrypted chunks to a server endpoint with resume-after-failure.
 *
 * Progress is tracked per-file in SharedPreferences: once a chunk index is
 * marked UPLOADED it is skipped on subsequent runs, so an interrupted upload
 * can be resumed simply by calling [uploadChunks] again with the same
 * [fileId]. Failed chunks are retried [maxRetries] times with backoff.
 *
 * Expected server endpoint: POST {baseUrl}/chunks
 *   multipart fields: fileId, index, sha256, iv, salt, chunk (ciphertext base64)
 *   Returns HTTP 2xx on success.
 */
class ChunkUploader(
    private val context: Context,
    private val baseUrl: String,
    private val maxRetries: Int = 3
) {
    private val prefs = context.getSharedPreferences("chunk_upload_state", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun prefKey(fileId: String, index: Int) = "$fileId|$index"

    /** Returns indices already uploaded for [fileId]. */
    fun uploadedIndices(fileId: String): Set<Int> =
        prefs.all.keys
            .filter { it.startsWith("$fileId|") && prefs.getBoolean(it, false) }
            .mapNotNull { it.removePrefix("$fileId|").toIntOrNull() }
            .toSet()

    /** Clears upload state for [fileId] so it can be re-uploaded from scratch. */
    fun reset(fileId: String) {
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("$fileId|") }.forEach { remove(it) }
            commit()
        }
    }

    /**
     * Uploads [chunks] for [fileId]. Skips already-uploaded indices (resume).
     * Returns a per-chunk list with state; call again to retry failed chunks.
     */
    suspend fun uploadChunks(fileId: String, chunks: List<ChunkResult>): List<ChunkUpload> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<ChunkUpload>(chunks.size)
            for (chunk in chunks) {
                if (prefs.getBoolean(prefKey(fileId, chunk.index), false)) {
                    out.add(ChunkUpload(chunk, UploadState.UPLOADED))
                    continue
                }
                val ok = uploadWithRetry(fileId, chunk)
                if (ok) {
                    prefs.edit().putBoolean(prefKey(fileId, chunk.index), true).commit()
                    out.add(ChunkUpload(chunk, UploadState.UPLOADED))
                } else {
                    out.add(ChunkUpload(chunk, UploadState.FAILED))
                }
            }
            out
        }

    private suspend fun uploadWithRetry(fileId: String, chunk: ChunkResult): Boolean {
        var delayMs = 1_000L
        repeat(maxRetries + 1) { attempt ->
            if (tryUpload(fileId, chunk)) return true
            if (attempt < maxRetries) {
                delay(delayMs)
                delayMs *= 2
            }
        }
        return false
    }

    private fun tryUpload(fileId: String, chunk: ChunkResult): Boolean {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("fileId", fileId)
                .addFormDataPart("index", chunk.index.toString())
                .addFormDataPart("sha256", chunk.sha256Hex)
                .addFormDataPart("iv", Base64.encodeToString(chunk.iv, Base64.NO_WRAP))
                .addFormDataPart("salt", Base64.encodeToString(chunk.salt, Base64.NO_WRAP))
                .addFormDataPart("chunk", chunk.ciphertextBase64)
                .build()

            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chunks")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: IOException) {
            false
        }
    }
}