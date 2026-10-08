package com.peexr.sift.ui.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardImageTest {

    // --- cardImageMode tests ---

    @Test
    fun cardImageMode_nullAspect_returnsFit() {
        assertEquals(CardImageMode.FIT, cardImageMode(null))
    }

    @Test
    fun cardImageMode_standardPhoneAspect_returnsFit() {
        assertEquals(CardImageMode.FIT, cardImageMode(0.46f))
    }

    @Test
    fun cardImageMode_exactThresholdAspect_returnsFit() {
        assertEquals(CardImageMode.FIT, cardImageMode(0.35f))
    }

    @Test
    fun cardImageMode_tallScrollingCapture_returnsTallTop() {
        assertEquals(CardImageMode.TALL_TOP, cardImageMode(0.2f))
    }

    @Test
    fun cardImageMode_squareAspect_returnsFit() {
        assertEquals(CardImageMode.FIT, cardImageMode(1.0f))
    }

    @Test
    fun cardImageMode_landscapeAspect_returnsFit() {
        assertEquals(CardImageMode.FIT, cardImageMode(2.0f))
    }

    // --- ocrSnippet tests ---

    @Test
    fun ocrSnippet_blankAndWhitespace_returnsEmptyString() {
        assertEquals("", ocrSnippet(""))
        assertEquals("", ocrSnippet("   "))
        assertEquals("", ocrSnippet("\t\n  \r\n "))
    }

    @Test
    fun ocrSnippet_newlinesAndMultipleSpacesCollapsed() {
        val input = "Order #12345\n\nTotal: $42.00\r\nDate:    Oct 8\t2026"
        val expected = "Order #12345 Total: $42.00 Date: Oct 8 2026"
        assertEquals(expected, ocrSnippet(input))
    }

    @Test
    fun ocrSnippet_shortTextUnchanged() {
        val text = "Invoice #98765 paid"
        assertEquals(text, ocrSnippet(text, maxChars = 80))
        assertEquals("Short", ocrSnippet("Short", maxChars = 10))
        assertEquals("Exact", ocrSnippet("Exact", maxChars = 5))
    }

    @Test
    fun ocrSnippet_longTextTruncatedAtWordBoundaryWithEllipsis() {
        val text = "The quick brown fox jumps over the lazy dog"
        val snippet = ocrSnippet(text, maxChars = 20)
        // First 20 chars: "The quick brown fox " -> last space before or at boundary trims to "The quick brown fox"
        assertEquals("The quick brown fox…", snippet)
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun ocrSnippet_longTextTruncatedWhenNextCharIsSpace() {
        val text = "The quick brown fox jumps"
        val snippet = ocrSnippet(text, maxChars = 19)
        // First 19 chars: "The quick brown fox", next char is ' '
        assertEquals("The quick brown fox…", snippet)
    }

    @Test
    fun ocrSnippet_singleVeryLongWordWithNoSpaces_hardTruncatedWithEllipsis() {
        val longWord = "Supercalifragilisticexpialidocious"
        val snippet = ocrSnippet(longWord, maxChars = 10)
        assertEquals("Supercalif…", snippet)
    }

    @Test
    fun ocrSnippet_resultLengthNeverExceedsMaxCharsPlusOne() {
        val sampleTexts = listOf(
            "Short",
            "This is a sample sentence designed for testing snippet truncation boundaries.",
            "Supercalifragilisticexpialidocious",
            "A B C D E F G H I J K L M N O P Q R S T U V W X Y Z",
            "Another    test\nwith\r\nmany\t\tbreaks   and words."
        )
        val limits = listOf(1, 5, 10, 15, 20, 25, 40, 80)

        for (text in sampleTexts) {
            for (limit in limits) {
                val snippet = ocrSnippet(text, maxChars = limit)
                assertTrue(
                    "Snippet length (${snippet.length}) exceeds limit + 1 (${limit + 1}) for snippet: '$snippet'",
                    snippet.length <= limit + 1
                )
            }
        }
    }
}
