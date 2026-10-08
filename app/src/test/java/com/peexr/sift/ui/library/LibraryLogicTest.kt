package com.peexr.sift.ui.library

import com.peexr.sift.data.embedding.ModelState
import com.peexr.sift.data.model.ScreenshotItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LibraryLogicTest {

    private val testZone: ZoneId = ZoneId.of("UTC")
    // Reference point: 2026-08-20T14:30:00Z
    private val fixedNow: ZonedDateTime = ZonedDateTime.of(2026, 8, 20, 14, 30, 0, 0, testZone)

    private fun createItem(id: Long, dateAddedSeconds: Long): ScreenshotItem {
        return ScreenshotItem(
            id = id,
            uri = "content://media/external/images/media/$id",
            displayName = "screenshot_$id.png",
            dateAdded = dateAddedSeconds,
            sizeBytes = 2048L
        )
    }

    @Test
    fun testFormatEtaLessThanMinute() {
        assertEquals("less than a minute", formatEta(0))
        assertEquals("less than a minute", formatEta(25))
        assertEquals("less than a minute", formatEta(59))
    }

    @Test
    fun testFormatEtaMinutesOnly() {
        assertEquals("about 1 min", formatEta(60))
        assertEquals("about 2 min", formatEta(120))
        assertEquals("about 12 min", formatEta(720))
        assertEquals("about 59 min", formatEta(3540))
    }

    @Test
    fun testFormatEtaExactHours() {
        assertEquals("about 1 h", formatEta(3600))
        assertEquals("about 2 h", formatEta(7200))
        assertEquals("about 5 h", formatEta(18000))
    }

    @Test
    fun testFormatEtaHoursAndMinutes() {
        assertEquals("about 1 h 1 min", formatEta(3660))
        assertEquals("about 1 h 40 min", formatEta(6000))
        assertEquals("about 1 h 40 min", formatEta(6017))
        assertEquals("about 2 h 15 min", formatEta(8100))
    }

    @Test
    fun testGroupByDateEmptyList() {
        val sections = groupByDate(emptyList(), fixedNow)
        assertTrue(sections.isEmpty())
    }

    @Test
    fun testGroupByDateToday() {
        val todaySec = fixedNow.toEpochSecond()
        val item = createItem(1L, todaySec)

        val sections = groupByDate(listOf(item), fixedNow)
        assertEquals(1, sections.size)
        assertEquals("Today", sections[0].title)
        assertEquals(listOf(item), sections[0].items)
    }

    @Test
    fun testGroupByDateYesterday() {
        val yesterdaySec = fixedNow.minusDays(1).toEpochSecond()
        val item = createItem(2L, yesterdaySec)

        val sections = groupByDate(listOf(item), fixedNow)
        assertEquals(1, sections.size)
        assertEquals("Yesterday", sections[0].title)
        assertEquals(listOf(item), sections[0].items)
    }

    @Test
    fun testGroupByDateThisWeek() {
        val day3 = createItem(3L, fixedNow.minusDays(3).toEpochSecond())
        val day7 = createItem(4L, fixedNow.minusDays(7).toEpochSecond())

        val sections = groupByDate(listOf(day3, day7), fixedNow)
        assertEquals(1, sections.size)
        assertEquals("This week", sections[0].title)
        assertEquals(listOf(day3, day7), sections[0].items)
    }

    @Test
    fun testGroupByDateLastWeek() {
        val day8 = createItem(5L, fixedNow.minusDays(8).toEpochSecond())
        val day14 = createItem(6L, fixedNow.minusDays(14).toEpochSecond())

        val sections = groupByDate(listOf(day8, day14), fixedNow)
        assertEquals(1, sections.size)
        assertEquals("Last week", sections[0].title)
        assertEquals(listOf(day8, day14), sections[0].items)
    }

    @Test
    fun testGroupByDateMonthBuckets() {
        // fixedNow is August 20, 2026.
        // 17 days ago is August 3, 2026 (> 14 days ago) -> "August 2026"
        val augItem = createItem(7L, fixedNow.minusDays(17).toEpochSecond())
        // 35 days ago is July 16, 2026 -> "July 2026"
        val julItem = createItem(8L, fixedNow.minusDays(35).toEpochSecond())
        // 65 days ago is June 16, 2026 -> "June 2026"
        val junItem = createItem(9L, fixedNow.minusDays(65).toEpochSecond())

        val sections = groupByDate(listOf(augItem, julItem, junItem), fixedNow)
        assertEquals(3, sections.size)
        assertEquals("August 2026", sections[0].title)
        assertEquals(listOf(augItem), sections[0].items)
        assertEquals("July 2026", sections[1].title)
        assertEquals(listOf(julItem), sections[1].items)
        assertEquals("June 2026", sections[2].title)
        assertEquals(listOf(junItem), sections[2].items)
    }

    @Test
    fun testGroupByDateFullOrdering() {
        val itemToday = createItem(1L, fixedNow.toEpochSecond())
        val itemYesterday = createItem(2L, fixedNow.minusDays(1).toEpochSecond())
        val itemThisWeek = createItem(3L, fixedNow.minusDays(4).toEpochSecond())
        val itemLastWeek = createItem(4L, fixedNow.minusDays(10).toEpochSecond())
        val itemAug = createItem(5L, fixedNow.minusDays(18).toEpochSecond())
        val itemJul = createItem(6L, fixedNow.minusDays(40).toEpochSecond())

        val allItems = listOf(itemToday, itemYesterday, itemThisWeek, itemLastWeek, itemAug, itemJul)
        val sections = groupByDate(allItems, fixedNow)

        assertEquals(6, sections.size)
        assertEquals("Today", sections[0].title)
        assertEquals("Yesterday", sections[1].title)
        assertEquals("This week", sections[2].title)
        assertEquals("Last week", sections[3].title)
        assertEquals("August 2026", sections[4].title)
        assertEquals("July 2026", sections[5].title)
    }

    @Test
    fun testComputeIndexingUiStates() {
        // Hidden when model is ready and 0 pending work
        val hiddenUi = computeIndexingUi(
            modelState = ModelState.Ready,
            countNeeding = 0,
            workInfos = emptyList(),
            isModelInstalled = true
        )
        assertEquals(IndexingUi.Hidden, hiddenUi)

        // Pending when model ready and work pending
        val pendingUi = computeIndexingUi(
            modelState = ModelState.Ready,
            countNeeding = 15,
            workInfos = emptyList(),
            isModelInstalled = true
        )
        assertEquals(IndexingUi.Pending(remaining = 15), pendingUi)

        // NeedsModel when not installed
        val needsModelUi = computeIndexingUi(
            modelState = ModelState.NotInstalled,
            countNeeding = 10,
            workInfos = emptyList(),
            isModelInstalled = false
        )
        assertEquals(IndexingUi.NeedsModel(sizeMb = 316), needsModelUi)

        // Downloading
        val downloadingUi = computeIndexingUi(
            modelState = ModelState.Downloading(bytesDone = 50L, bytesTotal = 100L, currentFile = "model.onnx"),
            countNeeding = 10,
            workInfos = emptyList(),
            isModelInstalled = false
        )
        assertTrue(downloadingUi is IndexingUi.Downloading)
        assertEquals(0.5f, (downloadingUi as IndexingUi.Downloading).fraction, 0.001f)

        // ModelFailed
        val failedUi = computeIndexingUi(
            modelState = ModelState.Failed("Disk full"),
            countNeeding = 10,
            workInfos = emptyList(),
            isModelInstalled = false
        )
        assertEquals(IndexingUi.ModelFailed("Disk full"), failedUi)
    }

    @Test
    fun testComputeIndexingUiWithCustomSecondsPerItem() {
        val pendingUi = computeIndexingUi(
            modelState = ModelState.Ready,
            countNeeding = 20,
            workInfos = emptyList(),
            isModelInstalled = true,
            secondsPerItem = 6L
        )
        assertEquals(IndexingUi.Pending(remaining = 20, secondsPerItem = 6L), pendingUi)
    }
}
