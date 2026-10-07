package com.peexr.sift.data.embedding

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.sqrt

data class PatchBatch(
    val pixelValues: FloatArray,
    val positions: LongArray,
    val numPatches: Int,
    val softTokens: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PatchBatch
        if (!pixelValues.contentEquals(other.pixelValues)) return false
        if (!positions.contentEquals(other.positions)) return false
        if (numPatches != other.numPatches) return false
        if (softTokens != other.softTokens) return false
        return true
    }

    override fun hashCode(): Int {
        var result = pixelValues.contentHashCode()
        result = 31 * result + positions.contentHashCode()
        result = 31 * result + numPatches
        result = 31 * result + softTokens
        return result
    }
}

object ImagePatchifier {

    const val DEFAULT_MAX_SOFT_TOKENS = 280
    val SUPPORTED_BUDGETS = setOf(70, 140, 280, 560, 1120)

    /**
     * Calculates the target image dimensions (tW, tH) for EmbeddingGemma vision encoder.
     * Dimensions are always multiples of 48 (pool * patch = 3 * 16).
     * Returns Pair(targetWidth, targetHeight), or null if the image should be skipped.
     */
    fun calculateTargetSize(
        origW: Int,
        origH: Int,
        maxSoftTokens: Int = DEFAULT_MAX_SOFT_TOKENS
    ): Pair<Int, Int>? {
        require(maxSoftTokens in SUPPORTED_BUDGETS) {
            "Unsupported token budget: $maxSoftTokens. Supported: $SUPPORTED_BUDGETS"
        }
        if (origW <= 0 || origH <= 0) return null
        val maxPatches = maxSoftTokens * 9
        val targetPixels = maxPatches * 256.0
        val factor = sqrt(targetPixels / (origH.toDouble() * origW.toDouble()))
        var tH = (floor(factor * origH / 48.0) * 48).toInt()
        var tW = (floor(factor * origW / 48.0) * 48).toInt()

        if ((tH == 0) xor (tW == 0)) {
            val longSide = maxOf(origH, origW)
            val shortSide = maxOf(1, minOf(origH, origW))
            val other = minOf(
                (floor(longSide.toDouble() / shortSide) * 48).toInt(),
                (maxPatches / 9) * 48
            )
            if (tH == 0) {
                tH = 48
                tW = other
            } else {
                tW = 48
                tH = other
            }
        }
        if (tH == 0 && tW == 0) return null
        return tW to tH
    }

    /**
     * Converts a raw pixel array (ARGB_8888 formatted ints) of size tW * tH into
     * PatchBatch without any dependency on android.graphics (plain Kotlin JUnit testable).
     */
    fun processPixels(
        pixels: IntArray,
        tW: Int,
        tH: Int
    ): PatchBatch {
        val gh = tH / 16
        val gw = tW / 16
        val P = gh * gw

        val pixelValues = FloatArray(P * 768)
        val positions = LongArray(P * 2)

        for (py in 0 until gh) {
            for (px in 0 until gw) {
                val pIdx = py * gw + px
                positions[pIdx * 2] = px.toLong()
                positions[pIdx * 2 + 1] = py.toLong()

                val base = pIdx * 768
                for (y in 0 until 16) {
                    val iy = py * 16 + y
                    for (x in 0 until 16) {
                        val ix = px * 16 + x
                        val pixel = pixels[iy * tW + ix]
                        val r = ((pixel shr 16) and 0xFF) / 255f
                        val g = ((pixel shr 8) and 0xFF) / 255f
                        val b = (pixel and 0xFF) / 255f

                        val chIdx = (y * 16 + x) * 3
                        pixelValues[base + chIdx] = r
                        pixelValues[base + chIdx + 1] = g
                        pixelValues[base + chIdx + 2] = b
                    }
                }
            }
        }

        return PatchBatch(pixelValues, positions, P, P / 9)
    }

    /**
     * Resizes and patchifies an Android Bitmap into PatchBatch.
     */
    fun processBitmap(
        bitmap: Bitmap,
        maxSoftTokens: Int = DEFAULT_MAX_SOFT_TOKENS
    ): PatchBatch? {
        require(maxSoftTokens in SUPPORTED_BUDGETS) {
            "Unsupported token budget: $maxSoftTokens. Supported: $SUPPORTED_BUDGETS"
        }
        if (bitmap.isRecycled) return null
        val size = calculateTargetSize(bitmap.width, bitmap.height, maxSoftTokens) ?: return null
        val (tW, tH) = size
        val scaled = Bitmap.createScaledBitmap(bitmap, tW, tH, true)
        val pixels = IntArray(tW * tH)
        scaled.getPixels(pixels, 0, tW, 0, 0, tW, tH)
        if (scaled !== bitmap) {
            scaled.recycle()
        }
        return processPixels(pixels, tW, tH)
    }
}
