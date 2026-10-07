package com.peexr.sift.data.embedding

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.File
import java.security.MessageDigest

class ModelDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var filesDir: File

    @Before
    fun setup() {
        mockContext = mock(Context::class.java)
        filesDir = tempFolder.newFolder("filesDir")
        `when`(mockContext.filesDir).thenReturn(filesDir)
    }

    @Test
    fun testManifestHas5EntriesAndNoAudio() {
        val manifest = ModelStore.MANIFEST
        assertEquals(5, manifest.size)
        assertTrue(manifest.none { it.name.contains("audio") })
        assertTrue(manifest.any { it.name == "onnx/model_q4.onnx" })
        assertTrue(manifest.any { it.name == "onnx/model_q4.onnx_data" })
        assertTrue(manifest.any { it.name == "onnx/vision_encoder_q4.onnx" })
        assertTrue(manifest.any { it.name == "onnx/vision_encoder_q4.onnx_data" })
        assertTrue(manifest.any { it.name == "tokenizer.json" })
    }

    @Test
    fun testHashVerificationHelper() {
        val testFile = File(tempFolder.root, "test.txt")
        val content = "hello world"
        testFile.writeText(content)
        val digest = MessageDigest.getInstance("SHA-256")
        val expectedHash = digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }

        assertTrue(FileHashUtils.verifyFileHash(testFile, expectedHash))
        assertFalse(FileHashUtils.verifyFileHash(testFile, "wronghash"))
    }

    @Test
    fun testDownloadResumeAndHash() = runBlocking {
        val expectedContent = "mock_file_content_for_testing".repeat(100).toByteArray()
        val digest = MessageDigest.getInstance("SHA-256")
        val expectedHash = digest.digest(expectedContent).joinToString("") { "%02x".format(it) }

        val fakeManifest = listOf(
            ModelFileInfo("test_model.bin", "http://fake.url/test_model.bin", expectedContent.size.toLong(), expectedHash)
        )

        val modelStore = ModelStore(mockContext, fakeManifest)

        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val range = request.header("Range")
            
            var startByte = 0
            if (range != null && range.startsWith("bytes=")) {
                startByte = range.substringAfter("bytes=").substringBefore("-").toInt()
            }
            
            val remainingData = expectedContent.copyOfRange(startByte, expectedContent.size)
            
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (startByte > 0) 206 else 200)
                .message(if (startByte > 0) "Partial Content" else "OK")
                .body(remainingData.toResponseBody("application/octet-stream".toMediaTypeOrNull()))
                .build()
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        val downloader = ModelDownloader(modelStore, client)

        // 1. Partial download
        val modelDir = modelStore.getModelDir()
        val partFile = File(modelDir, "test_model.bin.part")
        partFile.writeBytes(expectedContent.copyOfRange(0, 10))

        // 2. Resume download
        downloader.downloadModels()

        assertTrue(modelStore.isInstalled())
        val finalFile = File(modelDir, "test_model.bin")
        assertTrue(finalFile.exists())
        assertEquals(expectedContent.size.toLong(), finalFile.length())
        assertFalse(partFile.exists())
    }
}
