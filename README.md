# ChunkFileProject — Secure File Chunker for Android

Dropbox-style file chunking with per-chunk **SHA-256 hashing** and **AES-256-GCM encryption**, built as a reusable Kotlin Android library plus a Jetpack Compose demo app.

Large files never rest on disk in plain form: each file is streamed, split into equal-size chunks, hashed, and individually encrypted before leaving the device — and uploads can be resumed after a network failure.

---

## ✨ Features

- **Okio streaming** — files are read as fixed-size chunks (default 4 MB) on a background thread, keeping memory bounded regardless of file size. The final chunk may be smaller.
- **SHA-256 per chunk** — each chunk's hash is computed and returned so integrity can be verified end-to-end.
- **AES-256-GCM encryption** with two key-source options:
  - **Android Keystore** — a 256-bit key is generated and stored in the hardware-backed Keystore; keys never leave the device.
  - **Password + PBKDF2** — derives a fresh 256-bit key per file from a user password (120,000 iterations, `PBKDF2WithHmacSHA256`), with a random 16-byte salt returned alongside each chunk for later decryption.
- **Resumable uploads** — chunks are POSTed over OkHttp as multipart form data. Failed chunks are retried with exponential backoff (1s → 2s → 4s); successfully uploaded chunks persist to `SharedPreferences`, so re-running the upload skips completed work and finishes the rest.
- **Kotlin Coroutines + Flow** — all I/O, hashing, and encryption run on `Dispatchers.IO` and stream results to the UI as `Flow<ChunkResult>`.

---

## 📦 Repository layout

```
ChunkFileProject/
├─ app/                  Jetpack Compose demo app (file picker, chunk size,
│                        encryption mode, server URL, progress, chunk list)
└─ chunk-plugin/         Reusable Android library (published as an AAR)
   ├─ ChunkProvider.kt   Streaming, SHA-256, AES-256-GCM (Keystore & PBKDF2)
   └─ ChunkUploader.kt   OkHttp multipart upload + resume-after-failure
```

The `chunk-plugin` module is the redistributable part — drop it into any Android project to get chunking, hashing, encryption, and resumable upload out of the box.

---

## 🛠️ Tech stack

| Concern        | Choice                                    |
|----------------|-------------------------------------------|
| Language       | Kotlin 2.0                                |
| UI             | Jetpack Compose (Material 3)              |
| Async          | kotlinx-coroutines / Flow                 |
| I/O streaming  | Okio 3                                    |
| HTTP / upload  | OkHttp 3                                  |
| Crypto         | `javax.crypto` (AES/GCM/NoPadding), PBKDF2, SHA-256 |
| Key storage    | Android Keystore                          |
| Build          | Gradle, AGP 8.7.3                         |

---

## 🚀 Getting started

1. Clone the repo and open it in **Android Studio** (or build from the CLI):
   ```bash
   git clone https://github.com/srini1971/ChunkFileProject.git
   cd ChunkFileProject
   ./gradlew :app:assembleDebug            # Windows: gradlew.bat :app:assembleDebug
   ```
2. Install the APK on a device/emulator (API 24+):
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. Open **Chunk Encrypt**, pick any file, set a chunk size, choose an encryption mode, and hit **Chunk & Encrypt**.

---

## 🔌 Using the library in your own Android project

Add the module (or later, a published AAR):

```kotlin
implementation(project(":chunk-plugin"))
// or, when published: implementation("com.example:chunk-plugin:1.0.0")
```

### Chunk + encrypt (Android Keystore)

```kotlin
val chunks = mutableListOf<ChunkResult>()
ChunkProvider.chunkAndEncrypt(file, chunkSizeMb = 4).collect { chunks.add(it) }
```

### Chunk + encrypt (password / PBKDF2)

```kotlin
val chunks = mutableListOf<ChunkResult>()
ChunkProvider.chunkAndEncryptWithPassword(file, 4, "your-password").collect { chunks.add(it) }
// each result carries `salt` + `iv` so chunks can be decrypted later
```

### Upload with resume

```kotlin
val uploader = ChunkUploader(context, baseUrl = "https://your-server.com")
val results = uploader.uploadChunks(fileId = file.name, chunks = chunks)
// UPLOADED chunks are skipped on the next call → resume after a network drop
```

---

## 🌐 Upload server contract

The library POSTs a `multipart/form-data` request per chunk to:

```
POST {baseUrl}/chunks
```

| Field     | Content                                                        |
|-----------|----------------------------------------------------------------|
| `fileId`  | stable file identifier (used to resume)                        |
| `index`   | 0-based chunk index                                            |
| `sha256`  | hex SHA-256 of the plaintext chunk                             |
| `iv`      | base64 12-byte GCM nonce                                       |
| `salt`    | base64 PBKDF2 salt (empty for Keystore mode)                   |
| `chunk`   | base64 AES-256-GCM ciphertext (includes the GCM tag)           |

Return **HTTP 2xx** to mark the chunk as uploaded. No server is bundled — spin up any endpoint (Node, Go, Ktor, etc.) that implements the contract.

---

## 📡 Example server (Node.js)

A minimal receiving endpoint to test the full pipeline:

```js
const express = require('express');
const multer = require('multer');
const app = express();
app.use(multer().none());
app.post('/chunks', (req, res) => {
  console.log(`chunk ${req.body.index} (${req.body.sha256})`);
  res.sendStatus(200);
});
app.listen(8080, () => console.log('chunk server on :8080'));
```

Point the app's **Upload server URL** at `http://<your-pc-ip>:8080` and tap **Upload (resumable)**.

---

## 🔒 Security notes

- Every chunk gets a **fresh 12-byte GCM nonce** — never reuse an IV with the same key.
- AES-256-GCM provides both confidentiality **and** authentication (128-bit tag).
- The PBKDF2 salt must be stored with the ciphertext; the key itself is *never* stored when using the password mode — it is re-derived from the password on demand.
- Android Keystore mode keeps keys in secure hardware and out of app memory where possible.
- GCM requires API 24+ (Keystore) — the app targets that consistently.

---

## 🗺️ Roadmap

- [ ] Server side (origin-verification of chunk hashes)
- [ ] Decryption/assembly tool for restoring the original file
- [ ] Parallel chunk upload with per-chunk bandwidth limiting
- [ ] Publish `chunk-plugin` to Maven Central / JitPack
- [ ] KMP/Wasm target so the same logic runs in React/Next.js

---

## 📄 License

MIT — use it as you wish. See [LICENSE](LICENSE).