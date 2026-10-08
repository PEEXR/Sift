package com.peexr.sift.data.backup

import com.peexr.sift.data.collections.AutoFillSensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionsBackupTest {

    @Test
    fun roundTrip_encodesAndDecodesCorrectly() {
        val original = BackupFile(
            version = 1,
            collections = listOf(
                BackupCollection(
                    name = "Memes",
                    description = "Funny screenshots",
                    colorHex = "#FFD8EC",
                    autoFill = true,
                    kept = listOf(
                        ScreenshotRef(displayName = "meme1.png", dateAdded = 1000L, sizeBytes = 50000L),
                        ScreenshotRef(displayName = "meme2.png", dateAdded = 2000L, sizeBytes = 75000L)
                    ),
                    excluded = listOf(
                        ScreenshotRef(displayName = "doc.png", dateAdded = 3000L, sizeBytes = 120000L)
                    )
                ),
                BackupCollection(
                    name = "Receipts",
                    description = "Payment confirmations",
                    colorHex = "#CFE5FF",
                    autoFill = false,
                    kept = emptyList(),
                    excluded = emptyList()
                )
            )
        )

        val json = encodeBackup(original)
        val decodedResult = decodeBackup(json)

        assertTrue("Expected decoding to succeed", decodedResult.isSuccess)
        val decoded = decodedResult.getOrThrow()
        assertEquals(original, decoded)
    }

    @Test
    fun decodeBackup_malformedInput_returnsFailure() {
        val malformedJson = "{ this is not valid json : [[}"
        val result = decodeBackup(malformedJson)

        assertTrue("Expected decoding malformed JSON to fail", result.isFailure)
    }

    @Test
    fun decodeBackup_newerVersion_returnsFailure() {
        val futureJson = """
            {
                "version": 2,
                "collections": []
            }
        """.trimIndent()

        val result = decodeBackup(futureJson)
        assertTrue("Expected decoding future version to fail", result.isFailure)
    }

    @Test
    fun decodeBackup_unknownKeysIgnored_succeeds() {
        val jsonWithExtraKeys = """
            {
                "version": 1,
                "extraTopLevelKey": "some value",
                "collections": [
                    {
                        "name": "Receipts",
                        "description": "Tax docs",
                        "colorHex": "#E4F7B5",
                        "autoFill": true,
                        "extraField": 12345,
                        "kept": [
                            {
                                "displayName": "receipt.jpg",
                                "dateAdded": 1234567,
                                "sizeBytes": 89012,
                                "unknownRefField": true
                            }
                        ],
                        "excluded": []
                    }
                ]
            }
        """.trimIndent()

        val result = decodeBackup(jsonWithExtraKeys)
        assertTrue("Expected unknown keys to be ignored", result.isSuccess)
        val file = result.getOrThrow()
        assertEquals(1, file.collections.size)
        assertEquals("Receipts", file.collections[0].name)
        assertEquals("receipt.jpg", file.collections[0].kept[0].displayName)
    }

    @Test
    fun matchRefs_exactTripleMatch_returnsIdsInRefOrder() {
        val candidates = listOf(
            Candidate(id = 101L, displayName = "shot_a.png", dateAdded = 100L, sizeBytes = 1000L),
            Candidate(id = 102L, displayName = "shot_b.png", dateAdded = 200L, sizeBytes = 2000L),
            Candidate(id = 103L, displayName = "shot_c.png", dateAdded = 300L, sizeBytes = 3000L)
        )

        // Refs specified in reverse order
        val refs = listOf(
            ScreenshotRef(displayName = "shot_c.png", dateAdded = 300L, sizeBytes = 3000L),
            ScreenshotRef(displayName = "shot_a.png", dateAdded = 100L, sizeBytes = 1000L)
        )

        val matchedIds = matchRefs(refs, candidates)
        assertEquals(listOf(103L, 101L), matchedIds)
    }

    @Test
    fun matchRefs_noMatch_skipsUnmatchedRefs() {
        val candidates = listOf(
            Candidate(id = 101L, displayName = "shot_a.png", dateAdded = 100L, sizeBytes = 1000L)
        )

        val refs = listOf(
            ScreenshotRef(displayName = "nonexistent.png", dateAdded = 999L, sizeBytes = 500L),
            ScreenshotRef(displayName = "shot_a.png", dateAdded = 100L, sizeBytes = 9999L), // Different size
            ScreenshotRef(displayName = "shot_a.png", dateAdded = 999L, sizeBytes = 1000L), // Different date
            ScreenshotRef(displayName = "shot_a.png", dateAdded = 100L, sizeBytes = 1000L)  // Match
        )

        val matchedIds = matchRefs(refs, candidates)
        assertEquals(listOf(101L), matchedIds)
    }

    @Test
    fun matchRefs_duplicates_handlesDuplicateCandidatesAndRefs() {
        // Multiple candidate entries with identical triple (e.g. library collision)
        val candidates = listOf(
            Candidate(id = 101L, displayName = "shot_dup.png", dateAdded = 500L, sizeBytes = 4000L),
            Candidate(id = 102L, displayName = "shot_dup.png", dateAdded = 500L, sizeBytes = 4000L),
            Candidate(id = 103L, displayName = "shot_other.png", dateAdded = 600L, sizeBytes = 5000L)
        )

        // Duplicate refs in requested list
        val refs = listOf(
            ScreenshotRef(displayName = "shot_dup.png", dateAdded = 500L, sizeBytes = 4000L),
            ScreenshotRef(displayName = "shot_other.png", dateAdded = 600L, sizeBytes = 5000L),
            ScreenshotRef(displayName = "shot_dup.png", dateAdded = 500L, sizeBytes = 4000L)
        )

        val matchedIds = matchRefs(refs, candidates)
        // First candidate ID (101L) is matched for each occurrence of shot_dup.png
        assertEquals(listOf(101L, 103L, 101L), matchedIds)
    }

    @Test
    fun autoFillSensitivity_thresholdsOrdering_strictGreaterThanBalancedGreaterThanLoose() {
        assertTrue(
            "STRICT threshold must be greater than BALANCED",
            AutoFillSensitivity.STRICT.zThreshold > AutoFillSensitivity.BALANCED.zThreshold
        )
        assertTrue(
            "BALANCED threshold must be greater than LOOSE",
            AutoFillSensitivity.BALANCED.zThreshold > AutoFillSensitivity.LOOSE.zThreshold
        )

        assertEquals(2.0f, AutoFillSensitivity.STRICT.zThreshold, 1e-4f)
        assertEquals(1.5f, AutoFillSensitivity.BALANCED.zThreshold, 1e-4f)
        assertEquals(1.0f, AutoFillSensitivity.LOOSE.zThreshold, 1e-4f)
    }
}
