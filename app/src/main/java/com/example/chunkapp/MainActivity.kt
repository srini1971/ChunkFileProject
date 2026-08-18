package com.example.chunkapp

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.chunkplugin.ChunkProvider
import com.example.chunkplugin.ChunkResult
import com.example.chunkplugin.ChunkUploader
import com.example.chunkplugin.UploadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}

private enum class EncryptionMode { KEYSTORE, PASSWORD }

@Composable
private fun MainScreen() {
    val context = LocalContext.current

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var cachedFile by remember { mutableStateOf<File?>(null) }
    var chunkSizeMb by remember { mutableStateOf("4") }
    var mode by remember { mutableStateOf(EncryptionMode.KEYSTORE) }
    var password by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("http://10.0.2.2:8080") }
    var results by remember { mutableStateOf<List<ChunkResult>>(emptyList()) }
    var uploadStates by remember { mutableStateOf<Map<Int, UploadState>>(emptyMap()) }
    var isWorking by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedName = uri.lastPathSegment
            cachedFile = null
            results = emptyList()
            uploadStates = emptyMap()
        }
    }

    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("File Chunker", style = MaterialTheme.typography.headlineMedium)

        Button(onClick = { filePicker.launch(arrayOf("*/*")) }) {
            Text("Select File")
        }

        selectedName?.let { Text("Selected: $it") }

        OutlinedTextField(
            value = chunkSizeMb,
            onValueChange = { chunkSizeMb = it.filter { c -> c.isDigit() }.take(3) },
            label = { Text("Chunk size (MB)") },
            modifier = Modifier.fillMaxWidth()
        )

        // Mode switcher
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { mode = EncryptionMode.KEYSTORE }) {
                Text("Keystore", color = if (mode == EncryptionMode.KEYSTORE)
                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            TextButton(onClick = { mode = EncryptionMode.PASSWORD }) {
                Text("Password (PBKDF2)", color = if (mode == EncryptionMode.PASSWORD)
                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
        }

        if (mode == EncryptionMode.PASSWORD) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Button(
            onClick = {
                val uri = selectedUri ?: return@Button
                val size = chunkSizeMb.toIntOrNull() ?: return@Button
                error = null
                message = null
                isWorking = true
                scope.launch {
                    try {
                        val file = copyUriToCache(context, uri)
                        val chunks = ArrayList<ChunkResult>()
                        val flow = if (mode == EncryptionMode.PASSWORD) {
                            ChunkProvider.chunkAndEncryptWithPassword(file, size, password)
                        } else {
                            ChunkProvider.chunkAndEncrypt(file, size)
                        }
                        flow.collect { chunks.add(it) }
                        cachedFile = file
                        results = chunks
                        uploadStates = emptyMap()
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        isWorking = false
                    }
                }
            },
            enabled = selectedUri != null && !isWorking &&
                (mode != EncryptionMode.PASSWORD || password.isNotBlank()),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isWorking) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
            } else {
                Text("Chunk & Encrypt")
            }
        }

        // Upload section
        if (results.isNotEmpty()) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Upload server URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            val uploaded = uploadStates.values.count { it == UploadState.UPLOADED }
            LinearProgressIndicator(
                progress = { if (results.isEmpty()) 0f else uploaded / results.size.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )
            Text("Uploaded $uploaded / ${results.size} chunks")

            Button(
                onClick = {
                    val file = cachedFile ?: return@Button
                    val fileId = file.name
                    error = null
                    message = null
                    isUploading = true
                    scope.launch {
                        try {
                            val uploader = ChunkUploader(context, serverUrl)
                            val res = uploader.uploadChunks(fileId, results)
                            uploadStates = res.associate { it.chunk.index to it.state }
                            message = "Upload finished"
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            isUploading = false
                        }
                    }
                },
                enabled = !isUploading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isUploading) "Uploading…" else "Upload (resumable)")
            }
        }

        error?.let {
            Text("Error: $it", color = MaterialTheme.colorScheme.error)
        }
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary)
        }
        if (isUploading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Uploading chunks…")
            }
        }

        Text("${results.size} chunks generated", style = MaterialTheme.typography.titleMedium)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(results, key = { it.index }) { result ->
                ChunkCard(result, uploadStates[result.index])
            }
        }
    }
}

@Composable
private fun ChunkCard(result: ChunkResult, state: UploadState?) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            val stateText = when (state) {
                UploadState.UPLOADED -> " • uploaded"
                UploadState.FAILED -> " • FAILED"
                else -> ""
            }
            Text("Chunk ${result.index + 1}  (${result.plainSize} bytes)$stateText",
                style = MaterialTheme.typography.titleSmall)
            Text("SHA-256: ${result.sha256Hex}", style = MaterialTheme.typography.bodySmall)
            Text("Ciphertext: ${result.ciphertextBase64.take(40)}…",
                style = MaterialTheme.typography.bodySmall)
            if (result.salt.isNotEmpty()) {
                Text("Salt: ${result.salt.joinToString("") { "%02x".format(it) }}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private suspend fun copyUriToCache(context: android.content.Context, uri: Uri): File =
    withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "selected_${System.currentTimeMillis()}.bin")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw Exception("Cannot open selected file")
        tmp
    }