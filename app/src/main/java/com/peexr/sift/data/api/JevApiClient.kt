package com.peexr.sift.data.api

import com.peexr.sift.data.model.JevRequest
import com.peexr.sift.data.model.JevResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class JevApiClient(
    private var apiKeyProvider: () -> String?
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun executeSystemOne(request: JevRequest): Result<JevResponse> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()?.trim()
        if (apiKey.isNullOrEmpty()) {
            return@withContext Result.failure(IllegalStateException("TypeSafe API Key is not configured. Please add your key in Settings."))
        }

        try {
            val isOpenRouter = apiKey.startsWith("sk-or-")
            val endpointUrl = if (isOpenRouter) {
                "https://openrouter.ai/api/alpha/decisions"
            } else {
                "https://api.typesafe.ai/v1/systemone"
            }
            val modelId = if (isOpenRouter) "typesafe/jev-latest" else "jev-latest"
            val adjustedRequest = request.copy(model = modelId)

            val requestBodyString = json.encodeToString(JevRequest.serializer(), adjustedRequest)
            val requestBuilder = Request.Builder()
                .url(endpointUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")

            if (isOpenRouter) {
                requestBuilder.addHeader("HTTP-Referer", "https://github.com/PEEXR/Sift")
                requestBuilder.addHeader("X-Title", "Sift Screenshot Triage")
            }

            val httpRequest = requestBuilder
                .post(requestBodyString.toRequestBody(mediaType))
                .build()

            client.newCall(httpRequest).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Jev API returned error code ${response.code}: $bodyString")
                    )
                }

                val jevResponse = json.decodeFromString(JevResponse.serializer(), bodyString)
                Result.success(jevResponse)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
