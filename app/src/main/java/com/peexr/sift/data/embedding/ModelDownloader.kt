package com.peexr.sift.data.embedding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
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

                    if (startByte < fileInfo.sizeBytes) {
                        val requestBuilder = Request.Builder().url(fileInfo.url)
                        if (startByte > 0) {
                            requestBuilder.addHeader("Range", "bytes=$startByte-")
                        }
                        val request = requestBuilder.build()

                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                throw Exception("Failed to download ${fileInfo.name}: ${response.code}")
                            }

                            val body = response.body ?: throw Exception("Empty body for ${fileInfo.name}")
                            body.byteStream().use { inputStream ->
                                FileOutputStream(partFile, startByte > 0).use { outputStream ->
                                    val buffer = ByteArray(8192)
                                    var read: Int
                                    while (inputStream.read(buffer).also { read = it } != -1) {
                                        if (!coroutineContext.isActive) throw CancellationException()
                                        outputStream.write(buffer, 0, read)
                                        downloadedBytes += read
                                        modelStore.updateState(ModelState.Downloading(downloadedBytes, totalBytes, fileInfo.name))
                                    }
                                }
                            }
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
}
