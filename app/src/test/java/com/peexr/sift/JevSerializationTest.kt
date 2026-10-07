package com.peexr.sift

import com.peexr.sift.data.model.JevAnswer
import com.peexr.sift.data.model.JevQuestion
import com.peexr.sift.data.model.JevRequest
import com.peexr.sift.data.model.JevResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JevSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Test
    fun testJevRequestSerialization() {
        val state = buildJsonObject {
            put("ocr_text", JsonPrimitive("Motivational quote: Never give up."))
            put("visual_concepts", JsonArray(listOf(JsonPrimitive("text"), JsonPrimitive("quote"))))
        }

        val questions = mapOf(
            "is_motivational_quote" to JevQuestion(
                type = "noul",
                instructions = "Is this a motivational quote?"
            ),
            "collection" to JevQuestion(
                type = "choice",
                instructions = "Pick a collection",
                criteria = buildJsonObject {
                    put("memes", JsonPrimitive("Funny memes"))
                    put("quotes", JsonPrimitive("Inspirational quotes"))
                }
            )
        )

        val request = JevRequest(
            model = "jev-latest",
            state = state,
            questions = questions
        )

        val serialized = json.encodeToString(JevRequest.serializer(), request)
        assertNotNull(serialized)
        assertTrue(serialized.contains("jev-latest"))
        assertTrue(serialized.contains("is_motivational_quote"))
        assertTrue(serialized.contains("noul"))
    }

    @Test
    fun testJevResponseDeserialization() {
        val responseJson = """
            {
                "answers": {
                    "is_motivational_quote": {
                        "noul": true,
                        "confidence": 0.96,
                        "rationale": "Clear inspirational quote text detected"
                    },
                    "collection": {
                        "choice": "quotes",
                        "confidence": 0.92
                    }
                }
            }
        """.trimIndent()

        val response = json.decodeFromString(JevResponse.serializer(), responseJson)
        assertEquals(true, response.answers["is_motivational_quote"]?.noul)
        assertEquals(0.96, response.answers["is_motivational_quote"]?.confidence ?: 0.0, 0.001)
        assertEquals("quotes", response.answers["collection"]?.choice)
    }
}
