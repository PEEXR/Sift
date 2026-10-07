package com.peexr.sift.data.embedding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader

/**
 * Byte-Pair Encoding (BPE) tokenizer for Gemma / EmbeddingGemma-2 models.
 * Replicates Hugging Face tokenizer.json semantics:
 * - Streaming JSON parsing (memory-efficient, no full AST tree)
 * - Atomic added/special token matching (e.g. <|image|>)
 * - SentencePiece normalizer: replaces ASCII space with U+2581 (Lower One Eighth Block)
 * - Optional pre-tokenizer delimiter splitting
 * - Character-level decomposition with UTF-8 byte fallback (<0xNN>)
 * - Greedy BPE merging based on merge-rank table
 * - Unknown token fusing (fuse_unk = true)
 * - Automatic <bos> and <eos> wrapping
 */
class GemmaTokenizer(
    val vocab: Map<String, Int>,
    val merges: Map<Pair<String, String>, Int>,
    val addedTokens: Map<String, Int> = emptyMap(),
    val bosId: Int = BOS_ID,
    val eosId: Int = EOS_ID,
    val unkId: Int = UNK_ID,
    val byteFallback: Boolean = true,
    val fuseUnk: Boolean = true,
    val splitPattern: String? = null,
    val splitBehavior: String? = null
) {
    private val unkTokenStr: String = vocab.entries.firstOrNull { it.value == unkId }?.key ?: "<unk>"

    private val addedTokenRegex: Regex? = if (addedTokens.isNotEmpty()) {
        val pattern = addedTokens.keys
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        Regex(pattern)
    } else null

    /**
     * Encodes the given input text into an array of token IDs.
     *
     * @param text The input string to tokenize.
     * @param addSpecial If true, prepends <bos> (2) and appends <eos> (1).
     * @return Array of integer token IDs.
     */
    fun encode(text: String, addSpecial: Boolean = true): IntArray {
        if (text.isEmpty()) {
            return if (addSpecial) intArrayOf(bosId, eosId) else intArrayOf()
        }

        val tokenIds = ArrayList<Int>()

        if (addedTokenRegex != null) {
            var lastIndex = 0
            val matches = addedTokenRegex.findAll(text)
            for (match in matches) {
                if (match.range.first > lastIndex) {
                    val slice = text.substring(lastIndex, match.range.first)
                    encodeSlice(slice, tokenIds)
                }
                val matchedContent = match.value
                val id = addedTokens[matchedContent] ?: vocab[matchedContent] ?: unkId
                tokenIds.add(id)
                lastIndex = match.range.last + 1
            }
            if (lastIndex < text.length) {
                val slice = text.substring(lastIndex)
                encodeSlice(slice, tokenIds)
            }
        } else {
            encodeSlice(text, tokenIds)
        }

        val result = ArrayList<Int>(tokenIds.size + if (addSpecial) 2 else 0)
        if (addSpecial) {
            result.add(bosId)
        }
        result.addAll(tokenIds)
        if (addSpecial) {
            result.add(eosId)
        }
        return result.toIntArray()
    }

    private fun encodeSlice(slice: String, outTokenIds: ArrayList<Int>) {
        if (slice.isEmpty()) return

        // Step 1: Normalizer - replace ASCII space with U+2581
        val normalized = slice.replace(" ", "\u2581")

        // Step 2: Pre-tokenizer (if configured)
        val chunks = if (splitPattern != null && splitBehavior != null) {
            preTokenize(normalized, splitPattern, splitBehavior)
        } else {
            listOf(normalized)
        }

        // Step 3: Tokenize each chunk with BPE
        for (chunk in chunks) {
            if (chunk.isNotEmpty()) {
                tokenizeChunk(chunk, outTokenIds)
            }
        }
    }

    private fun preTokenize(text: String, pattern: String, behavior: String): List<String> {
        if (pattern.isEmpty() || !text.contains(pattern)) {
            return listOf(text)
        }
        val result = ArrayList<String>()
        var start = 0
        var idx = text.indexOf(pattern, start)

        when (behavior) {
            "MergedWithPrevious" -> {
                while (idx != -1) {
                    val end = idx + pattern.length
                    result.add(text.substring(start, end))
                    start = end
                    idx = text.indexOf(pattern, start)
                }
                if (start < text.length) {
                    result.add(text.substring(start))
                }
            }
            "MergedWithNext" -> {
                while (idx != -1) {
                    if (idx > start) {
                        result.add(text.substring(start, idx))
                    }
                    start = idx
                    idx = text.indexOf(pattern, start + pattern.length)
                }
                if (start < text.length) {
                    result.add(text.substring(start))
                }
            }
            "Isolated" -> {
                while (idx != -1) {
                    if (idx > start) {
                        result.add(text.substring(start, idx))
                    }
                    result.add(pattern)
                    start = idx + pattern.length
                    idx = text.indexOf(pattern, start)
                }
                if (start < text.length) {
                    result.add(text.substring(start))
                }
            }
            "Removed" -> {
                while (idx != -1) {
                    if (idx > start) {
                        result.add(text.substring(start, idx))
                    }
                    start = idx + pattern.length
                    idx = text.indexOf(pattern, start)
                }
                if (start < text.length) {
                    result.add(text.substring(start))
                }
            }
            else -> result.add(text)
        }
        return result
    }

    private fun tokenizeChunk(chunk: String, outTokenIds: ArrayList<Int>) {
        var symbols = decomposeToSymbols(chunk)
        if (symbols.isEmpty()) return

        // BPE Merging Loop
        while (symbols.size >= 2) {
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1

            for (j in 0 until symbols.size - 1) {
                val rank = merges[Pair(symbols[j], symbols[j + 1])]
                if (rank != null && rank < bestRank) {
                    bestRank = rank
                    bestIdx = j
                }
            }

            if (bestIdx == -1) break

            val s1 = symbols[bestIdx]
            val s2 = symbols[bestIdx + 1]
            val merged = s1 + s2

            val nextSymbols = ArrayList<String>(symbols.size)
            var k = 0
            while (k < symbols.size) {
                if (k < symbols.size - 1 && symbols[k] == s1 && symbols[k + 1] == s2) {
                    nextSymbols.add(merged)
                    k += 2
                } else {
                    nextSymbols.add(symbols[k])
                    k += 1
                }
            }
            symbols = nextSymbols
        }

        // Map symbols to vocab token IDs
        for (sym in symbols) {
            val id = vocab[sym] ?: unkId
            if (fuseUnk && id == unkId && outTokenIds.isNotEmpty() && outTokenIds.last() == unkId) {
                continue
            }
            outTokenIds.add(id)
        }
    }

    private fun decomposeToSymbols(chunk: String): ArrayList<String> {
        val symbols = ArrayList<String>()
        var i = 0
        while (i < chunk.length) {
            val cp = chunk.codePointAt(i)
            val charCount = Character.charCount(cp)
            val charStr = chunk.substring(i, i + charCount)
            i += charCount

            if (vocab.containsKey(charStr)) {
                symbols.add(charStr)
            } else if (byteFallback) {
                val bytes = charStr.toByteArray(Charsets.UTF_8)
                for (b in bytes) {
                    val byteStr = "<0x%02X>".format(b.toInt() and 0xFF)
                    if (vocab.containsKey(byteStr)) {
                        symbols.add(byteStr)
                    } else {
                        symbols.add(unkTokenStr)
                    }
                }
            } else {
                symbols.add(unkTokenStr)
            }
        }
        return symbols
    }

    companion object {
        const val QUERY_PREFIX = "task: search result | query: "
        const val PAD_ID = 0
        const val EOS_ID = 1
        const val BOS_ID = 2
        const val UNK_ID = 3

        @Volatile
        private var instance: GemmaTokenizer? = null

        /**
         * Asynchronously loads or returns the singleton instance of GemmaTokenizer on Dispatchers.IO.
         */
        suspend fun getInstance(file: File): GemmaTokenizer = withContext(Dispatchers.IO) {
            instance ?: synchronized(this) {
                instance ?: load(file).also { instance = it }
            }
        }

        /**
         * Asynchronously loads or returns the singleton instance of GemmaTokenizer on Dispatchers.IO.
         */
        suspend fun getInstance(inputStream: InputStream): GemmaTokenizer = withContext(Dispatchers.IO) {
            instance ?: synchronized(this) {
                instance ?: load(inputStream).also { instance = it }
            }
        }

        fun load(file: File): GemmaTokenizer {
            return file.inputStream().buffered().use { load(it) }
        }

        fun load(inputStream: InputStream): GemmaTokenizer {
            val reader = BufferedReader(InputStreamReader(inputStream.buffered(), Charsets.UTF_8))
            return StreamingTokenizerParser(reader).parse()
        }

        fun resetInstance() {
            instance = null
        }
    }
}

internal class StreamingTokenizerParser(private val reader: Reader) {
    private val buffer = CharArray(8192)
    private var bufPos = 0
    private var bufLimit = 0
    private var peekedChar: Int = -1

    private fun nextChar(): Int {
        if (peekedChar != -1) {
            val c = peekedChar
            peekedChar = -1
            return c
        }
        if (bufPos >= bufLimit) {
            bufLimit = reader.read(buffer)
            bufPos = 0
            if (bufLimit <= 0) return -1
        }
        return buffer[bufPos++].code
    }

    private fun unreadChar(c: Int) {
        peekedChar = c
    }

    private fun peekChar(): Int {
        if (peekedChar != -1) return peekedChar
        val c = nextChar()
        peekedChar = c
        return c
    }

    private fun skipWhitespace(): Int {
        while (true) {
            val c = nextChar()
            if (c == -1) return -1
            if (c != ' '.code && c != '\t'.code && c != '\n'.code && c != '\r'.code) {
                return c
            }
        }
    }

    private fun hexVal(c: Int): Int = when (c.toChar()) {
        in '0'..'9' -> c - '0'.code
        in 'a'..'f' -> c - 'a'.code + 10
        in 'A'..'F' -> c - 'A'.code + 10
        else -> 0
    }

    fun readString(): String {
        val c = skipWhitespace()
        if (c != '"'.code) {
            throw IllegalStateException("Expected '\"' but got '${if (c != -1) c.toChar() else "EOF"}' ($c)")
        }
        val sb = StringBuilder()
        while (true) {
            val ch = nextChar()
            if (ch == -1) throw EOFException("Unexpected EOF inside string")
            if (ch == '"'.code) break
            if (ch == '\\'.code) {
                val esc = nextChar()
                when (esc) {
                    '"'.code -> sb.append('"')
                    '\\'.code -> sb.append('\\')
                    '/'.code -> sb.append('/')
                    'b'.code -> sb.append('\b')
                    'f'.code -> sb.append('\u000C')
                    'n'.code -> sb.append('\n')
                    'r'.code -> sb.append('\r')
                    't'.code -> sb.append('\t')
                    'u'.code -> {
                        val h1 = nextChar()
                        val h2 = nextChar()
                        val h3 = nextChar()
                        val h4 = nextChar()
                        val cp = (hexVal(h1) shl 12) or (hexVal(h2) shl 8) or (hexVal(h3) shl 4) or hexVal(h4)
                        sb.append(cp.toChar())
                    }
                    else -> sb.append(esc.toChar())
                }
            } else {
                sb.append(ch.toChar())
            }
        }
        return sb.toString()
    }

    fun readInt(): Int {
        val c = skipWhitespace()
        val sb = StringBuilder()
        sb.append(c.toChar())
        while (true) {
            val peek = peekChar()
            if ((peek in '0'.code..'9'.code) || peek == '-'.code) {
                sb.append(nextChar().toChar())
            } else {
                break
            }
        }
        return sb.toString().toInt()
    }

    fun readBoolean(): Boolean {
        val c = skipWhitespace()
        unreadChar(c)
        val sb = StringBuilder()
        while (true) {
            val peek = peekChar()
            if (peek in 'a'.code..'z'.code || peek in 'A'.code..'Z'.code) {
                sb.append(nextChar().toChar())
            } else {
                break
            }
        }
        return sb.toString().equals("true", ignoreCase = true)
    }

    fun readColon() {
        val c = skipWhitespace()
        if (c != ':'.code) {
            throw IllegalStateException("Expected ':' but got '${if (c != -1) c.toChar() else "EOF"}'")
        }
    }

    private fun readComma() {
        val c = skipWhitespace()
        if (c != ','.code) {
            throw IllegalStateException("Expected ',' but got '${if (c != -1) c.toChar() else "EOF"}'")
        }
    }

    /** Consumes up to and including the closing ']' of the array whose items were already read. */
    private fun skipToArrayEnd() {
        val c = skipWhitespace()
        if (c != ']'.code) {
            throw IllegalStateException("Expected ']' but got '${if (c != -1) c.toChar() else "EOF"}'")
        }
    }

    fun hasNextObjectEntry(): Boolean {
        val c = skipWhitespace()
        if (c == '}'.code) {
            return false
        }
        if (c == ','.code) {
            val next = skipWhitespace()
            if (next == '}'.code) return false
            unreadChar(next)
            return true
        }
        unreadChar(c)
        return true
    }

    fun hasNextArrayEntry(): Boolean {
        val c = skipWhitespace()
        if (c == ']'.code) {
            return false
        }
        if (c == ','.code) {
            val next = skipWhitespace()
            if (next == ']'.code) return false
            unreadChar(next)
            return true
        }
        unreadChar(c)
        return true
    }

    fun skipValue() {
        val c = skipWhitespace()
        when (c) {
            '{'.code -> skipObject()
            '['.code -> skipArray()
            '"'.code -> skipStringBody()
            else -> skipLiteral()
        }
    }

    private fun skipObject() {
        var depth = 1
        while (depth > 0) {
            val c = nextChar()
            if (c == -1) break
            if (c == '"'.code) {
                skipStringBody()
            } else if (c == '{'.code) {
                depth++
            } else if (c == '}'.code) {
                depth--
            }
        }
    }

    private fun skipArray() {
        var depth = 1
        while (depth > 0) {
            val c = nextChar()
            if (c == -1) break
            if (c == '"'.code) {
                skipStringBody()
            } else if (c == '['.code) {
                depth++
            } else if (c == ']'.code) {
                depth--
            }
        }
    }

    private fun skipStringBody() {
        while (true) {
            val c = nextChar()
            if (c == -1 || c == '"'.code) break
            if (c == '\\'.code) {
                nextChar()
            }
        }
    }

    private fun skipLiteral() {
        while (true) {
            val c = peekChar()
            if (c == -1 || c == ','.code || c == '}'.code || c == ']'.code ||
                c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code) {
                break
            }
            nextChar()
        }
    }

    fun parse(): GemmaTokenizer {
        val vocab = HashMap<String, Int>(270000)
        val merges = HashMap<Pair<String, String>, Int>(530000)
        val addedTokens = HashMap<String, Int>()
        var byteFallback = true
        var fuseUnk = true
        var splitPattern: String? = null
        var splitBehavior: String? = null

        val rootOpen = skipWhitespace()
        if (rootOpen != '{'.code) {
            throw IllegalStateException("Expected '{' at root of tokenizer.json")
        }

        while (hasNextObjectEntry()) {
            val key = readString()
            readColon()
            when (key) {
                "added_tokens" -> {
                    val arrOpen = skipWhitespace()
                    if (arrOpen == '['.code) {
                        while (hasNextArrayEntry()) {
                            val objOpen = skipWhitespace()
                            if (objOpen == '{'.code) {
                                var id = -1
                                var content: String? = null
                                while (hasNextObjectEntry()) {
                                    val fName = readString()
                                    readColon()
                                    when (fName) {
                                        "id" -> id = readInt()
                                        "content" -> content = readString()
                                        else -> skipValue()
                                    }
                                }
                                if (id != -1 && content != null) {
                                    addedTokens[content] = id
                                }
                            } else {
                                unreadChar(objOpen)
                                skipValue()
                            }
                        }
                    } else {
                        unreadChar(arrOpen)
                        skipValue()
                    }
                }
                "pre_tokenizer" -> {
                    val ptOpen = skipWhitespace()
                    if (ptOpen == '{'.code) {
                        while (hasNextObjectEntry()) {
                            val ptKey = readString()
                            readColon()
                            when (ptKey) {
                                "pattern" -> {
                                    val patOpen = skipWhitespace()
                                    if (patOpen == '{'.code) {
                                        while (hasNextObjectEntry()) {
                                            val patKey = readString()
                                            readColon()
                                            when (patKey) {
                                                "String", "Regex" -> splitPattern = readString()
                                                else -> skipValue()
                                            }
                                        }
                                    } else {
                                        unreadChar(patOpen)
                                        skipValue()
                                    }
                                }
                                "behavior" -> splitBehavior = readString()
                                else -> skipValue()
                            }
                        }
                    } else {
                        unreadChar(ptOpen)
                        skipValue()
                    }
                }
                "model" -> {
                    val mOpen = skipWhitespace()
                    if (mOpen == '{'.code) {
                        while (hasNextObjectEntry()) {
                            val mKey = readString()
                            readColon()
                            when (mKey) {
                                "vocab" -> {
                                    val vOpen = skipWhitespace()
                                    if (vOpen == '{'.code) {
                                        while (hasNextObjectEntry()) {
                                            val token = readString()
                                            readColon()
                                            val id = readInt()
                                            vocab[token] = id
                                        }
                                    } else {
                                        unreadChar(vOpen)
                                        skipValue()
                                    }
                                }
                                "merges" -> {
                                    val mrOpen = skipWhitespace()
                                    if (mrOpen == '['.code) {
                                        var rank = 0
                                        while (hasNextArrayEntry()) {
                                            // Two on-disk formats: ["a","b"] pairs (current HF) or "a b" strings (legacy).
                                            val entryStart = skipWhitespace()
                                            if (entryStart == '['.code) {
                                                val s1 = readString()
                                                readComma()
                                                val s2 = readString()
                                                skipToArrayEnd()
                                                merges[Pair(s1, s2)] = rank++
                                            } else {
                                                unreadChar(entryStart)
                                                val mergeStr = readString()
                                                val spaceIdx = mergeStr.indexOf(' ')
                                                if (spaceIdx > 0) {
                                                    val s1 = mergeStr.substring(0, spaceIdx)
                                                    val s2 = mergeStr.substring(spaceIdx + 1)
                                                    merges[Pair(s1, s2)] = rank++
                                                }
                                            }
                                        }
                                    } else {
                                        unreadChar(mrOpen)
                                        skipValue()
                                    }
                                }
                                "byte_fallback" -> byteFallback = readBoolean()
                                "fuse_unk" -> fuseUnk = readBoolean()
                                else -> skipValue()
                            }
                        }
                    } else {
                        unreadChar(mOpen)
                        skipValue()
                    }
                }
                else -> skipValue()
            }
        }

        val bosId = addedTokens["<bos>"] ?: vocab["<bos>"] ?: GemmaTokenizer.BOS_ID
        val eosId = addedTokens["<eos>"] ?: vocab["<eos>"] ?: GemmaTokenizer.EOS_ID
        val unkId = addedTokens["<unk>"] ?: vocab["<unk>"] ?: GemmaTokenizer.UNK_ID

        return GemmaTokenizer(
            vocab = vocab,
            merges = merges,
            addedTokens = addedTokens,
            bosId = bosId,
            eosId = eosId,
            unkId = unkId,
            byteFallback = byteFallback,
            fuseUnk = fuseUnk,
            splitPattern = splitPattern,
            splitBehavior = splitBehavior
        )
    }
}
