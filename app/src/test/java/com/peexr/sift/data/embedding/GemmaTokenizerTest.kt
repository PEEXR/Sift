package com.peexr.sift.data.embedding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Test
import java.io.File

class GemmaTokenizerTest {

    private val syntheticTokenizerJson = """
    {
      "version": "1.0",
      "added_tokens": [
        {
          "id": 0,
          "content": "<pad>",
          "special": true
        },
        {
          "id": 1,
          "content": "<eos>",
          "special": true
        },
        {
          "id": 2,
          "content": "<bos>",
          "special": true
        },
        {
          "id": 3,
          "content": "<unk>",
          "special": true
        },
        {
          "id": 20,
          "content": "<|image|>",
          "special": true
        },
        {
          "id": 21,
          "content": "<special>",
          "special": true
        }
      ],
      "normalizer": {
        "type": "Replace",
        "pattern": {
          "String": " "
        },
        "content": "▁"
      },
      "pre_tokenizer": {
        "type": "Split",
        "pattern": {
          "String": " "
        },
        "behavior": "MergedWithPrevious"
      },
      "model": {
        "type": "BPE",
        "byte_fallback": true,
        "fuse_unk": true,
        "vocab": {
          "<pad>": 0,
          "<eos>": 1,
          "<bos>": 2,
          "<unk>": 3,
          "▁": 4,
          "h": 5,
          "e": 6,
          "l": 7,
          "o": 8,
          "w": 9,
          "r": 10,
          "d": 11,
          "he": 12,
          "ll": 13,
          "hello": 14,
          "▁hello": 15,
          "world": 16,
          "▁world": 17,
          "<0x61>": 18,
          "<0x62>": 19
        },
        "merges": [
          "h e",
          "l l",
          "he ll",
          "hell o",
          "▁ hello",
          "w o",
          "wo r",
          "wor l",
          "worl d",
          "▁ world"
        ]
      }
    }
    """.trimIndent()

    @Test
    fun testSyntheticTokenizerFixture() {
        val tokenizer = GemmaTokenizer.load(syntheticTokenizerJson.byteInputStream())

        assertEquals(2, tokenizer.bosId)
        assertEquals(1, tokenizer.eosId)
        assertEquals(3, tokenizer.unkId)

        // 1. Normalization & word merging
        // "hello world" -> normalized "hello world" -> [hello,  world]
        // hello -> 14,  world -> 17
        val helloWorld = tokenizer.encode("hello world", addSpecial = true)
        assertArrayEquals(intArrayOf(2, 14, 17, 1), helloWorld)

        // 2. Leading space " hello" -> " hello" -> 15
        val leadingHello = tokenizer.encode(" hello", addSpecial = true)
        assertArrayEquals(intArrayOf(2, 15, 1), leadingHello)

        // 3. Without special tokens (addSpecial = false)
        val plainHello = tokenizer.encode("hello", addSpecial = false)
        assertArrayEquals(intArrayOf(14), plainHello)

        // 4. Byte fallback: 'a' (0x61) -> 18, 'b' (0x62) -> 19
        val ab = tokenizer.encode("ab", addSpecial = true)
        assertArrayEquals(intArrayOf(2, 18, 19, 1), ab)

        // 5. Special/added token atomic matching: <|image|> -> 20
        val imageToken = tokenizer.encode("<|image|>", addSpecial = true)
        assertArrayEquals(intArrayOf(2, 20, 1), imageToken)

        // Mixed with regular text
        val mixedSpecial = tokenizer.encode("hello<special>world", addSpecial = true)
        assertArrayEquals(intArrayOf(2, 14, 21, 16, 1), mixedSpecial)

        // 6. Unknown tokens and fuse_unk
        // Character 'z' and 'y' (not in vocab, no byte fallback in this synthetic vocab)
        // Consecutive unk tokens should be fused into a single unk (3)
        val unkTokens = tokenizer.encode("zy", addSpecial = false)
        assertArrayEquals(intArrayOf(3), unkTokens)

        // 7. Empty string
        assertArrayEquals(intArrayOf(2, 1), tokenizer.encode("", addSpecial = true))
        assertArrayEquals(intArrayOf(), tokenizer.encode("", addSpecial = false))
    }

    @Test
    fun testGoldenQueriesWithRealTokenizer() {
        // Locate tokenizer.json from env var or standard paths
        val envPath = System.getenv("SIFT_TOKENIZER_JSON")
        val sysProp = System.getProperty("SIFT_TOKENIZER_JSON")
        val candidatePaths = listOfNotNull(
            envPath,
            sysProp,
            "tokenizer.json",
            "../tokenizer.json",
            "models/tokenizer.json"
        )

        val tokenizerFile = candidatePaths
            .map { File(it) }
            .firstOrNull { it.exists() && it.isFile && it.length() > 0 }

        Assume.assumeNotNull("SIFT_TOKENIZER_JSON not found, skipping golden test", tokenizerFile)
        Assume.assumeTrue("Tokenizer file missing or empty", tokenizerFile != null && tokenizerFile.exists())

        val tokenizer = GemmaTokenizer.load(tokenizerFile!!)

        // Expected golden token IDs from golden.json:
        val goldenCases = mapOf(
            "coffee receipt" to intArrayOf(
                2, 8071, 236787, 3927, 1354, 1109, 7609, 236787, 7681, 19971, 1
            ),
            "flight boarding pass" to intArrayOf(
                2, 8071, 236787, 3927, 1354, 1109, 7609, 236787, 10224, 54788, 1786, 1
            ),
            "funny cat meme" to intArrayOf(
                2, 8071, 236787, 3927, 1354, 1109, 7609, 236787, 12274, 5866, 40741, 1
            ),
            "wifi password" to intArrayOf(
                2, 8071, 236787, 3927, 1354, 1109, 7609, 236787, 47802, 8918, 1
            ),
            "recipe for pasta" to intArrayOf(
                2, 8071, 236787, 3927, 1354, 1109, 7609, 236787, 15028, 573, 25021, 1
            )
        )

        for ((query, expectedIds) in goldenCases) {
            val fullQuery = GemmaTokenizer.QUERY_PREFIX + query
            val actualIds = tokenizer.encode(fullQuery, addSpecial = true)
            assertArrayEquals("Token ID mismatch for query '$query'", expectedIds, actualIds)
        }
    }
}
