package com.peexr.sift.data.embedding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive

class ModelDownloader(
    private val modelStore: ModelStore,
    private val client: OkHttpClient
) {
    suspend fun downloadModels(wifiOnly: Boolean = true) {
        withContext(Dispatchers.IO) {
            try {
                if (modelStore.isInstalled()) {
                    modelStore.updateState(ModelState.Ready)
                    return@withContext
                }

                val manifest = modelStore.manifest
                var totalBytes = manifest.sumOf { it.sizeBytes }
                var downloadedBytes = 0L

                // Calculate already downloaded/installed bytes to report correct progress
                for (fileInfo in manifest) {
                    val finalFile = modelStore.getFile(fileInfo)
                    if (finalFile.exists() && finalFile.length() == fileInfo.sizeBytes) {
                        downloadedBytes += fileInfo.sizeBytes
                    } else {
                        val partFile = File(finalFile.parentFile, finalFile.name + ".part")
                        if (partFile.exists()) {
                            downloadedBytes += partFile.length()
                        }
                    }
                }

                modelStore.updateState(ModelState.Downloading(downloadedBytes, totalBytes, ""))

                for (fileInfo in manifest) {
                    if (!coroutineContext.isActive) throw CancellationException()

                    val finalFile = modelStore.getFile(fileInfo)
                    if (finalFile.exists() && finalFile.length() == fileInfo.sizeBytes) {
                        if (FileHashUtils.verifyFileHash(finalFile, fileInfo.sha256)) {
                            continue
                        } else {
                            finalFile.delete() // Corrupted, re-download
                            downloadedBytes -= fileInfo.sizeBytes
                        }
                    }

                    modelStore.updateState(ModelState.Downloading(downloadedBytes, totalBytes, fileInfo.name))

                    val partFile = File(finalFile.parentFile, finalFile.name + ".part")
                    val existingSize = if (partFile.exists()) partFile.length() else 0L

                    if (existingSize > fileInfo.sizeBytes) {
                        partFile.delete() // Too large or corrupted part
                    }

                    val startByte = if (partFile.exists()) partFile.length() else 0L

                    // Resume-capable transfer with retries: a stalled CDN connection must not fail a 300 MB download.
                    var attempt = 0
                    while (true) {
                        val start = if (partFile.exists()) partFile.length() else 0L
                        if (start >= fileInfo.sizeBytes) break
                        try {
                            // Fixed-size ranges: a connection that goes quiet only costs one chunk, not the whole file.
                            val end = minOf(start + CHUNK_BYTES - 1, fileInfo.sizeBytes - 1)
                            val requestBuilder = Request.Builder().url(fileInfo.url)
                                .addHeader("Range", "bytes=$start-$end")
                            client.newCall(requestBuilder.build()).execute().use { response ->
                                if (!response.isSuccessful) {
                                    val message = "Failed to download ${fileInfo.name}: ${response.code}"
                                    // Server errors are transient (retry); client errors are not.
                                    if (response.code >= 500) throw IOException(message) else throw Exception(message)
                                }
                                // A server that ignores Range answers 200 with the whole file: restart the part file.
                                val resumed = start > 0 && response.code == 206
                                if (start > 0 && response.code != 206) {
                                    downloadedBytes -= start
                                    partFile.delete()
                                }
                                val body = response.body ?: throw IOException("Empty body for ${fileInfo.name}")
                                var lastReported = downloadedBytes
                                body.byteStream().use { inputStream ->
                                    FileOutputStream(partFile, resumed).use { outputStream ->
                                        val buffer = ByteArray(64 * 1024)
                                        var read: Int
                                        while (inputStream.read(buffer).also { read = it } != -1) {
                                            if (!coroutineContext.isActive) throw CancellationException()
                                            outputStream.write(buffer, 0, read)
                                            downloadedBytes += read
                                            if (downloadedBytes - lastReported >= PROGRESS_STEP_BYTES) {
                                                lastReported = downloadedBytes
                                                modelStore.updateState(ModelState.Downloading(downloadedBytes, totalBytes, fileInfo.name))
                                            }
                                        }
                                    }
                                }
                                modelStore.updateState(ModelState.Downloading(downloadedBytes, totalBytes, fileInfo.name))
                            }
                            attempt = 0 // chunk finished: only consecutive failures count against the limit
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IOException) {
                            attempt++
                            if (attempt >= MAX_ATTEMPTS) throw e
                            // Re-sync progress with what is actually on disk, then back off and resume.
                            delay(RETRY_BASE_DELAY_MS * attempt)
                        }
                    }

                    // Verify hash
                    if (!FileHashUtils.verifyFileHash(partFile, fileInfo.sha256)) {
                        partFile.delete()
                        throw Exception("Checksum mismatch for ${fileInfo.name}")
                    }

                    // Atomically rename
                    if (!partFile.renameTo(finalFile)) {
                        throw Exception("Failed to rename ${partFile.name} to ${finalFile.name}")
                    }
                }

                modelStore.updateState(ModelState.Ready)
            } catch (e: CancellationException) {
                modelStore.updateState(ModelState.NotInstalled) // Or keep current state
                throw e
            } catch (e: Exception) {
                modelStore.updateState(ModelState.Failed(e.message ?: "Unknown error"))
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 6
        const val RETRY_BASE_DELAY_MS = 2_000L
        const val PROGRESS_STEP_BYTES = 256L * 1024L
        const val CHUNK_BYTES = 16L * 1024L * 1024L
    }
}
