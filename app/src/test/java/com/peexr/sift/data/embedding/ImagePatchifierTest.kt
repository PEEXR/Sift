package com.peexr.sift.data.embedding

import org.junit.Assert.*
import org.junit.Test

class ImagePatchifierTest {

    @Test
    fun testCalculateTargetSizeStandardImages() {
        // Golden vision image size: 2400 tall x 1080 wide
        val size = ImagePatchifier.calculateTargetSize(1080, 2400, maxSoftTokens = 280)
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

        // Default call without explicit maxSoftTokens must match budget 280
        val defaultSize = ImagePatchifier.calculateTargetSize(1080, 2400)
        assertEquals(size, defaultSize)

        // Landscape version: 1080 tall x 2400 wide
        val landscape = ImagePatchifier.calculateTargetSize(2400, 1080, maxSoftTokens = 280)
        assertNotNull(landscape)
        assertEquals(1152, landscape!!.first)
        assertEquals(528, landscape.second)

        // Square image 1000x1000
        val square = ImagePatchifier.calculateTargetSize(1000, 1000, maxSoftTokens = 280)
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
        val tall = ImagePatchifier.calculateTargetSize(1, 1000, maxSoftTokens = 280)
        assertNotNull(tall)
        val (tallW, tallH) = tall!!
        assertEquals(48, tallW)
        assertEquals(13440, tallH)
        val tallPatches = (tallW / 16) * (tallH / 16)
        assertEquals(2520, tallPatches)
        assertEquals(280, tallPatches / 9)

        // Extreme wide image: triggers tW == 0 xor tH == 0 rule
        val wide = ImagePatchifier.calculateTargetSize(1000, 1, maxSoftTokens = 280)
        assertNotNull(wide)
        val (wideW, wideH) = wide!!
        assertEquals(13440, wideW)
        assertEquals(48, wideH)
        val widePatches = (wideW / 16) * (wideH / 16)
        assertEquals(2520, widePatches)
        assertEquals(280, widePatches / 9)
    }

    @Test
    fun testCalculateTargetSizeBudget140() {
        // Explicit budget 140: 1080 wide x 2400 tall
        // Derived from formula:
        // maxPatches = 140 * 9 = 1260
        // factor = sqrt(1260 * 256 / (2400 * 1080)) = sqrt(322560 / 2592000) ≈ 0.3527668
        // tH = floor(factor * 2400 / 48) * 48 = floor(17.63834) * 48 = 17 * 48 = 816 (51 patches)
        // tW = floor(factor * 1080 / 48) * 48 = floor(7.93725) * 48 = 7 * 48 = 336 (21 patches)
        val size140 = ImagePatchifier.calculateTargetSize(1080, 2400, maxSoftTokens = 140)
        assertNotNull(size140)
        val (tW, tH) = size140!!
        assertEquals(336, tW)
        assertEquals(816, tH)
        assertEquals(0, tW % 48)
        assertEquals(0, tH % 48)

        val gw = tW / 16
        val gh = tH / 16
        assertEquals(21, gw)
        assertEquals(51, gh)

        val numPatches = gw * gh
        assertEquals(1071, numPatches)
        assertTrue("Patch count $numPatches must be <= 1260", numPatches <= 1260)
        assertEquals(119, numPatches / 9)

        // Landscape version: 1080 tall x 2400 wide
        val landscape = ImagePatchifier.calculateTargetSize(2400, 1080, maxSoftTokens = 140)
        assertNotNull(landscape)
        assertEquals(816, landscape!!.first)
        assertEquals(336, landscape.second)

        // Square image 1000x1000
        val square = ImagePatchifier.calculateTargetSize(1000, 1000, maxSoftTokens = 140)
        assertNotNull(square)
        assertEquals(528, square!!.first)
        assertEquals(528, square.second)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCalculateTargetSizeUnsupportedBudgetThrows() {
        ImagePatchifier.calculateTargetSize(1080, 2400, maxSoftTokens = 100)
    }

    @Test
    fun testCalculateTargetSizeExtremeAspectRatiosBudget140() {
        // Extreme tall image (1 x 1000) with budget 140:
        // other = minOf(floor(1000/1)*48, (1260/9)*48) = minOf(48000, 6720) = 6720
        val tall = ImagePatchifier.calculateTargetSize(1, 1000, maxSoftTokens = 140)
        assertNotNull(tall)
        val (tallW, tallH) = tall!!
        assertEquals(48, tallW)
        assertEquals(6720, tallH)
        assertEquals(0, tallW % 48)
        assertEquals(0, tallH % 48)
        val tallPatches = (tallW / 16) * (tallH / 16)
        assertEquals(1260, tallPatches)
        assertTrue(tallPatches <= 140 * 9)
        assertEquals(140, tallPatches / 9)

        // Extreme wide image (1000 x 1) with budget 140:
        val wide = ImagePatchifier.calculateTargetSize(1000, 1, maxSoftTokens = 140)
        assertNotNull(wide)
        val (wideW, wideH) = wide!!
        assertEquals(6720, wideW)
        assertEquals(48, wideH)
        assertEquals(0, wideW % 48)
        assertEquals(0, wideH % 48)
        val widePatches = (wideW / 16) * (wideH / 16)
        assertEquals(1260, widePatches)
        assertTrue(widePatches <= 140 * 9)
        assertEquals(140, widePatches / 9)
    }

    @Test
    fun testCalculateTargetSizeExtremeAspectRatiosAllSupportedBudgets() {
        for (budget in ImagePatchifier.SUPPORTED_BUDGETS) {
            val maxPatches = budget * 9

            val tall = ImagePatchifier.calculateTargetSize(1, 1000, maxSoftTokens = budget)
            assertNotNull(tall)
            val (tallW, tallH) = tall!!
            val tallPatches = (tallW / 16) * (tallH / 16)
            assertTrue("Tall patches $tallPatches must be <= $maxPatches for budget $budget", tallPatches <= maxPatches)
            assertEquals(0, tallW % 48)
            assertEquals(0, tallH % 48)

            val wide = ImagePatchifier.calculateTargetSize(1000, 1, maxSoftTokens = budget)
            assertNotNull(wide)
            val (wideW, wideH) = wide!!
            val widePatches = (wideW / 16) * (wideH / 16)
            assertTrue("Wide patches $widePatches must be <= $maxPatches for budget $budget", widePatches <= maxPatches)
            assertEquals(0, wideW % 48)
            assertEquals(0, wideH % 48)
        }
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
