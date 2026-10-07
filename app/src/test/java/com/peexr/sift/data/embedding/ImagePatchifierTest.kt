package com.peexr.sift.data.embedding

import org.junit.Assert.*
import org.junit.Test

class ImagePatchifierTest {

    @Test
    fun testCalculateTargetSizeStandardImages() {
        // Golden vision image size: 2400 tall x 1080 wide
        val size = ImagePatchifier.calculateTargetSize(1080, 2400)
        assertNotNull(size)
        val (tW, tH) = size!!
        assertEquals(528, tW)
        assertEquals(1152, tH)

        val gw = tW / 16
        val gh = tH / 16
        assertEquals(33, gw)
        assertEquals(72, gh)

        val numPatches = gw * gh
        assertEquals(2376, numPatches)
        assertEquals(264, numPatches / 9)

        // Landscape version: 1080 tall x 2400 wide
        val landscape = ImagePatchifier.calculateTargetSize(2400, 1080)
        assertNotNull(landscape)
        assertEquals(1152, landscape!!.first)
        assertEquals(528, landscape.second)

        // Square image 1000x1000
        val square = ImagePatchifier.calculateTargetSize(1000, 1000)
        assertNotNull(square)
        assertEquals(768, square!!.first)
        assertEquals(768, square.second)
    }

    @Test
    fun testCalculateTargetSizeInvalidDimensions() {
        assertNull(ImagePatchifier.calculateTargetSize(0, 100))
        assertNull(ImagePatchifier.calculateTargetSize(100, 0))
        assertNull(ImagePatchifier.calculateTargetSize(-10, 100))
        assertNull(ImagePatchifier.calculateTargetSize(100, -10))
    }

    @Test
    fun testCalculateTargetSizeExtremeAspectRatios() {
        // Extreme tall image: triggers tW == 0 xor tH == 0 rule
        val tall = ImagePatchifier.calculateTargetSize(1, 1000)
        assertNotNull(tall)
        val (tallW, tallH) = tall!!
        assertEquals(48, tallW)
        assertEquals(13440, tallH)
        val tallPatches = (tallW / 16) * (tallH / 16)
        assertEquals(2520, tallPatches)
        assertEquals(280, tallPatches / 9)

        // Extreme wide image: triggers tW == 0 xor tH == 0 rule
        val wide = ImagePatchifier.calculateTargetSize(1000, 1)
        assertNotNull(wide)
        val (wideW, wideH) = wide!!
        assertEquals(13440, wideW)
        assertEquals(48, wideH)
        val widePatches = (wideW / 16) * (wideH / 16)
        assertEquals(2520, widePatches)
        assertEquals(280, widePatches / 9)
    }

    @Test
    fun testGoldenVisionSyntheticDimensionsAndPositions() {
        // Golden dimensions for 2400x1080: tW = 528, tH = 1152
        val tW = 528
        val tH = 1152
        val dummyPixels = IntArray(tW * tH)

        val batch = ImagePatchifier.processPixels(dummyPixels, tW, tH)

        // Verified facts from golden_vision.json:
        // real_patches = 2376, soft_tokens = 264, last_pos_xy = [32, 71]
        assertEquals(2376, batch.numPatches)
        assertEquals(264, batch.softTokens)
        assertEquals(2376 * 768, batch.pixelValues.size)
        assertEquals(2376 * 2, batch.positions.size)

        // First patch position [0, 0]
        assertEquals(0L, batch.positions[0])
        assertEquals(0L, batch.positions[1])

        // Second patch position [1, 0]
        assertEquals(1L, batch.positions[2])
        assertEquals(0L, batch.positions[3])

        // Last patch position (pIdx = 2375): x=32, y=71
        val lastIdx = 2375
        assertEquals(32L, batch.positions[lastIdx * 2])
        assertEquals(71L, batch.positions[lastIdx * 2 + 1])
    }

    @Test
    fun testProcessPixelsExactOrderingAndScaling() {
        // Test with a 48x48 image (3 patches x 3 patches = 9 patches, 1 soft token)
        val w = 48
        val h = 48
        val pixels = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = (x * 4) and 0xFF
                val g = (y * 4) and 0xFF
                val b = ((x + y) * 2) and 0xFF
                val argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                pixels[y * w + x] = argb
            }
        }

        val batch = ImagePatchifier.processPixels(pixels, w, h)
        assertEquals(9, batch.numPatches)
        assertEquals(1, batch.softTokens)

        // Check patch (py = 1, px = 2):
        // pIdx = 1 * 3 + 2 = 5
        val pIdx = 5
        assertEquals(2L, batch.positions[pIdx * 2])     // px
        assertEquals(1L, batch.positions[pIdx * 2 + 1]) // py

        // Check pixel (iy = py*16 + y, ix = px*16 + x) inside patch
        val py = 1
        val px = 2
        val y = 7
        val x = 11
        val iy = py * 16 + y
        val ix = px * 16 + x
        val origPixel = pixels[iy * w + ix]
        val expectedR = ((origPixel shr 16) and 0xFF) / 255f
        val expectedG = ((origPixel shr 8) and 0xFF) / 255f
        val expectedB = (origPixel and 0xFF) / 255f

        val base = pIdx * 768
        val chIdx = (y * 16 + x) * 3
        assertEquals(expectedR, batch.pixelValues[base + chIdx], 1e-6f)
        assertEquals(expectedG, batch.pixelValues[base + chIdx + 1], 1e-6f)
        assertEquals(expectedB, batch.pixelValues[base + chIdx + 2], 1e-6f)
    }

    @Test
    fun testPatchBatchEquality() {
        val b1 = PatchBatch(floatArrayOf(1.0f, 2.0f), longArrayOf(0L, 1L), 1, 1)
        val b2 = PatchBatch(floatArrayOf(1.0f, 2.0f), longArrayOf(0L, 1L), 1, 1)
        val b3 = PatchBatch(floatArrayOf(1.0f, 3.0f), longArrayOf(0L, 1L), 1, 1)

        assertEquals(b1, b2)
        assertEquals(b1.hashCode(), b2.hashCode())
        assertNotEquals(b1, b3)
    }
}
