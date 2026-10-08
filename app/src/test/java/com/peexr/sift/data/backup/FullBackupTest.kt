package com.peexr.sift.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class FullBackupTest {

    @Test
    fun roundTrip_gzipped_encodesAndDecodesCorrectly() {
        val originalFloats = FloatArray(128) { i -> (i * 0.05f) - 3.2f }
        val embeddingB64 = embeddingToBase64(originalFloats)

        val original = FullBackup(
            version = 1,
            createdAtEpochSec = 1700000000L,
            appVersion = "1.0.0",
            settings = BackupSettings(
                autoFillSensitivity = "BALANCED",
                indexingQuality = "AUTO",
                autoIndexNewScreenshots = true,
                measuredSecPerImageAt280 = 8.5
            ),
            collections = listOf(
                FullCollection(
                    key = "col_receipts",
                    name = "Receipts",
                    description = "Invoices and bills",
                    colorHex = "#FFD8EC",
                    autoFill = true
                )
            ),
            screenshots = listOf(
                FullScreenshot(
                    displayName = "screenshot_1.png",
                    dateAdded = 1600000000L,
                    sizeBytes = 204800L,
                    ocrText = "Total $45.00",
                    visualLabels = "document,receipt",
                    isProcessed = true,
                    embeddingB64 = embeddingB64,
                    memberships = listOf(
                        FullMembership(
                            collectionKey = "col_receipts",
                            source = "AUTO",
                            score = 2.4f
                        )
                    )
                )
            )
        )

        val bytes = encodeFullBackup(original)
        assertTrue("Encoded bytes should not be empty", bytes.isNotEmpty())

        val decodeResult = decodeFullBackup(bytes)
        assertTrue("Decoding gzipped backup should succeed", decodeResult.isSuccess)

        val decoded = decodeResult.getOrThrow()
        assertEquals(original, decoded)
    }

    @Test
    fun roundTrip_plainJson_decodesCorrectly() {
        val originalFloats = FloatArray(128) { i -> i * 0.1f }
        val original = FullBackup(
            version = 1,
            createdAtEpochSec = 1700000000L,
            appVersion = "1.0.0",
            settings = BackupSettings(
                autoFillSensitivity = "STRICT",
                indexingQuality = "BEST",
                autoIndexNewScreenshots = false,
                measuredSecPerImageAt280 = null
            ),
            collections = listOf(
                FullCollection(
                    key = "col_code",
                    name = "Code",
                    description = "Programming snippets",
                    colorHex = "#CFE5FF",
                    autoFill = false
                )
            ),
            screenshots = listOf(
                FullScreenshot(
                    displayName = "code_snip.png",
                    dateAdded = 1610000000L,
                    sizeBytes = 102400L,
                    ocrText = "fun main() {}",
                    visualLabels = "code",
                    isProcessed = true,
                    embeddingB64 = embeddingToBase64(originalFloats),
                    memberships = emptyList()
                )
            )
        )

        val jsonString = kotlinx.serialization.json.Json.encodeToString(FullBackup.serializer(), original)
        val plainBytes = jsonString.toByteArray(Charsets.UTF_8)

        val decodeResult = decodeFullBackup(plainBytes)
        assertTrue("Decoding plain JSON backup should succeed", decodeResult.isSuccess)

        val decoded = decodeResult.getOrThrow()
        assertEquals(original, decoded)
    }

    @Test
    fun embeddingBase64_roundTrip() {
        val floats = FloatArray(128) { i -> (i * 0.25f) - 16.0f }
        val b64 = embeddingToBase64(floats)
        val decoded = embeddingFromBase64(b64)

        assertNotNull("Decoded float array should not be null", decoded)
        assertEquals(128, decoded!!.size)
        for (i in 0 until 128) {
            assertEquals(floats[i], decoded[i], 1e-6f)
        }
    }

    @Test
    fun embeddingBase64_rejectsWrongLength() {
        try {
            embeddingToBase64(FloatArray(127))
            fail("Expected IllegalArgumentException for 127 floats")
        } catch (_: IllegalArgumentException) {}

        try {
            embeddingToBase64(FloatArray(129))
            fail("Expected IllegalArgumentException for 129 floats")
        } catch (_: IllegalArgumentException) {}

        // Base64 bytes of 127 floats = 508 bytes
        val shortBytes = ByteArray(127 * 4)
        val shortB64 = Base64.getEncoder().encodeToString(shortBytes)
        assertNull("Decode should return null for 127 floats", embeddingFromBase64(shortB64))

        // Base64 bytes of 129 floats = 516 bytes
        val longBytes = ByteArray(129 * 4)
        val longB64 = Base64.getEncoder().encodeToString(longBytes)
        assertNull("Decode should return null for 129 floats", embeddingFromBase64(longB64))

        // Empty string
        assertNull("Decode should return null for empty string", embeddingFromBase64(""))

        // Malformed base64
        assertNull("Decode should return null for invalid base64", embeddingFromBase64("!@#$%^&*"))
    }

    @Test
    fun embeddingBase64_rejectsNaNAndInfinity() {
        val nanArray = FloatArray(128) { 1.0f }
        nanArray[42] = Float.NaN
        try {
            embeddingToBase64(nanArray)
            fail("Expected IllegalArgumentException for Float.NaN")
        } catch (_: IllegalArgumentException) {}

        val infArray = FloatArray(128) { 1.0f }
        infArray[10] = Float.POSITIVE_INFINITY
        try {
            embeddingToBase64(infArray)
            fail("Expected IllegalArgumentException for Float.POSITIVE_INFINITY")
        } catch (_: IllegalArgumentException) {}

        // Manually build Base64 with NaN float
        val bb = ByteBuffer.allocate(128 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 128) {
            bb.putFloat(if (i == 5) Float.NaN else 1.0f)
        }
        val nanB64 = Base64.getEncoder().encodeToString(bb.array())
        assertNull("Decode should return null when containing Float.NaN", embeddingFromBase64(nanB64))

        // Manually build Base64 with negative infinity float
        val bbInf = ByteBuffer.allocate(128 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 128) {
            bbInf.putFloat(if (i == 7) Float.NEGATIVE_INFINITY else 1.0f)
        }
        val infB64 = Base64.getEncoder().encodeToString(bbInf.array())
        assertNull("Decode should return null when containing -Infinity", embeddingFromBase64(infB64))
    }

    @Test
    fun decodeFullBackup_malformedGzipOrJson_returnsFailureWithClearMessage() {
        // Corrupted gzip magic bytes
        val corruptGzip = byteArrayOf(0x1F, 0x8B.toByte(), 0x00, 0x01, 0x02, 0x03)
        val gzipResult = decodeFullBackup(corruptGzip)
        assertTrue("Corrupted gzip should fail", gzipResult.isFailure)
        assertEquals("That file isn't a Sift backup", gzipResult.exceptionOrNull()?.message)

        // Corrupted plain JSON
        val corruptJson = "{ invalid json: [[[ ".toByteArray(Charsets.UTF_8)
        val jsonResult = decodeFullBackup(corruptJson)
        assertTrue("Corrupted json should fail", jsonResult.isFailure)
        assertEquals("That file isn't a Sift backup", jsonResult.exceptionOrNull()?.message)
    }

    @Test
    fun decodeFullBackup_newerVersion_returnsFailureWithClearMessage() {
        val futureVersionJson = """
            {
                "version": 2,
                "createdAtEpochSec": 1700000000,
                "appVersion": "2.0.0",
                "settings": {
                    "autoFillSensitivity": "BALANCED",
                    "indexingQuality": "AUTO",
                    "autoIndexNewScreenshots": true,
                    "measuredSecPerImageAt280": null
                },
                "collections": [],
                "screenshots": []
            }
        """.trimIndent()

        val result = decodeFullBackup(futureVersionJson.toByteArray(Charsets.UTF_8))
        assertTrue("Version 2 should fail", result.isFailure)
        assertEquals("That file isn't a Sift backup", result.exceptionOrNull()?.message)
    }

    @Test
    fun decodeFullBackup_unknownKeysIgnored_succeeds() {
        val jsonWithUnknownKeys = """
            {
                "version": 1,
                "extraRootProperty": "unexpected",
                "createdAtEpochSec": 1700000000,
                "appVersion": "1.0.0",
                "settings": {
                    "autoFillSensitivity": "BALANCED",
                    "indexingQuality": "AUTO",
                    "autoIndexNewScreenshots": true,
                    "measuredSecPerImageAt280": null,
                    "extraSettingKey": 42
                },
                "collections": [
                    {
                        "key": "c1",
                        "name": "Receipts",
                        "description": "Tax docs",
                        "colorHex": "#FFFFFF",
                        "autoFill": true,
                        "extraColField": "ignore"
                    }
                ],
                "screenshots": [
                    {
                        "displayName": "receipt1.png",
                        "dateAdded": 1000,
                        "sizeBytes": 2000,
                        "ocrText": "Receipt",
                        "visualLabels": "paper",
                        "isProcessed": true,
                        "embeddingB64": null,
                        "memberships": [
                            {
                                "collectionKey": "c1",
                                "source": "MANUAL",
                                "score": null,
                                "unknownMemberKey": false
                            }
                        ],
                        "unknownShotKey": 123
                    }
                ]
            }
        """.trimIndent()

        val result = decodeFullBackup(jsonWithUnknownKeys.toByteArray(Charsets.UTF_8))
        assertTrue("Unknown keys should be ignored", result.isSuccess)
        val backup = result.getOrThrow()
        assertEquals(1, backup.collections.size)
        assertEquals("Receipts", backup.collections[0].name)
        assertEquals(1, backup.screenshots.size)
        assertEquals("receipt1.png", backup.screenshots[0].displayName)
        assertEquals(1, backup.screenshots[0].memberships.size)
        assertEquals("MANUAL", backup.screenshots[0].memberships[0].source)
    }

    @Test
    fun restoreMatches_exactMatches_returnsIdsInOrder() {
        val candidates = listOf(
            Candidate(id = 101L, displayName = "shot_a.png", dateAdded = 100L, sizeBytes = 1000L),
            Candidate(id = 102L, displayName = "shot_b.png", dateAdded = 200L, sizeBytes = 2000L),
            Candidate(id = 103L, displayName = "shot_c.png", dateAdded = 300L, sizeBytes = 3000L)
        )

        val refs = listOf(
            RefKey(displayName = "shot_c.png", dateAdded = 300L, sizeBytes = 3000L),
            RefKey(displayName = "shot_a.png", dateAdded = 100L, sizeBytes = 1000L)
        )

        val matchedIds = restoreMatches(refs, candidates)
        assertEquals(listOf(103L, 101L), matchedIds)
    }

    @Test
    fun restoreMatches_missingCandidates_returnsNull() {
        val candidates = listOf(
            Candidate(id = 101L, displayName = "shot_a.png", dateAdded = 100L, sizeBytes = 1000L)
        )

        val refs = listOf(
            RefKey(displayName = "shot_a.png", dateAdded = 100L, sizeBytes = 1000L),
            RefKey(displayName = "shot_b.png", dateAdded = 200L, sizeBytes = 2000L),
            RefKey(displayName = "shot_a.png", dateAdded = 100L, sizeBytes = 9999L) // Mismatched size
        )

        val matchedIds = restoreMatches(refs, candidates)
        assertEquals(listOf(101L, null, null), matchedIds)
    }

    @Test
    fun restoreMatches_duplicates_mapsOneToOneInOrder() {
        val candidates = listOf(
            Candidate(id = 10L, displayName = "shot_dup.png", dateAdded = 500L, sizeBytes = 4000L),
            Candidate(id = 20L, displayName = "shot_dup.png", dateAdded = 500L, sizeBytes = 4000L),
            Candidate(id = 30L, displayName = "shot_dup.png", dateAdded = 500L, sizeBytes = 4000L)
        )

        val refs = listOf(
            RefKey(displayName = "shot_dup.png", dateAdded = 500L, sizeBytes = 4000L),
            RefKey(displayName = "shot_dup.png", dateAdded = 500L, sizeBytes = 4000L),
            RefKey(displayName = "shot_dup.png", dateAdded = 500L, sizeBytes = 4000L),
            RefKey(displayName = "shot_dup.png", dateAdded = 500L, sizeBytes = 4000L) // 4th has no candidate left
        )

        val matchedIds = restoreMatches(refs, candidates)
        assertEquals(listOf(10L, 20L, 30L, null), matchedIds)
    }

    @Test
    fun backupReminderText_logic() {
        val now = 10_000_000L

        // Under 100 screenshots: never reminds
        assertNull(backupReminderText(lastBackupEpochSec = null, nowEpochSec = now, indexedCount = 0))
        assertNull(backupReminderText(lastBackupEpochSec = null, nowEpochSec = now, indexedCount = 99))
        assertNull(backupReminderText(lastBackupEpochSec = now - (60 * 86400L), nowEpochSec = now, indexedCount = 50))

        // 100+ screenshots with no backup: reminds
        assertEquals(
            "No recent backup. Indexing took a while — keep a copy.",
            backupReminderText(lastBackupEpochSec = null, nowEpochSec = now, indexedCount = 100)
        )
        assertEquals(
            "No recent backup. Indexing took a while — keep a copy.",
            backupReminderText(lastBackupEpochSec = null, nowEpochSec = now, indexedCount = 500)
        )

        // 100+ screenshots with recent backup (<= 30 days): no reminder
        assertNull(backupReminderText(lastBackupEpochSec = now - (10 * 86400L), nowEpochSec = now, indexedCount = 150))
        assertNull(backupReminderText(lastBackupEpochSec = now - (30 * 86400L), nowEpochSec = now, indexedCount = 150))

        // 100+ screenshots with backup older than 30 days: reminds
        assertEquals(
            "No recent backup. Indexing took a while — keep a copy.",
            backupReminderText(lastBackupEpochSec = now - (31 * 86400L), nowEpochSec = now, indexedCount = 150)
        )
    }
}
