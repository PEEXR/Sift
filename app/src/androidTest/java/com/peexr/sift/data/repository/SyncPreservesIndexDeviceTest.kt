package com.peexr.sift.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.peexr.sift.SiftApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test: a MediaStore sync must not wipe existing OCR text or embeddings
 * (it previously re-inserted every row with REPLACE and default fields).
 */
@RunWith(AndroidJUnit4::class)
class SyncPreservesIndexDeviceTest {

    @Test
    fun sync_keepsOcrAndEmbeddings() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SiftApplication
        val dao = app.database.screenshotDao()
        val ids = dao.getAllScreenshotIds().take(3)
        Assume.assumeTrue("needs synced screenshots in the database", ids.size == 3)

        val marker = ByteArray(512) { (it % 7).toByte() }
        val originals = ids.map { dao.getScreenshotById(it)!! }
        ids.forEach { dao.updateEmbedding(it, marker) }
        dao.updatePerception(ids[0], "sync-regression-ocr", "label")

        try {
            app.repository.syncMediaStoreScreenshots()

            ids.forEach { assertArrayEquals("embedding wiped for $it", marker, dao.getScreenshotById(it)!!.embedding) }
            val first = dao.getScreenshotById(ids[0])!!
            assertEquals("sync-regression-ocr", first.ocrText)
            assertEquals(true, first.isProcessed)
        } finally {
            // Restore the rows' original OCR state; embeddings go back to whatever they were
            originals.forEach { o ->
                if (o.embedding != null) dao.updateEmbedding(o.id, o.embedding!!)
                dao.updatePerception(o.id, o.ocrText, o.visualLabels)
            }
            app.database.openHelper.writableDatabase.execSQL(
                "UPDATE screenshots SET embedding = NULL WHERE id IN (${originals.filter { it.embedding == null }.joinToString { it.id.toString() }.ifEmpty { "-1" }})"
            )
            app.database.openHelper.writableDatabase.execSQL(
                "UPDATE screenshots SET isProcessed = 0 WHERE id IN (${originals.filter { !it.isProcessed }.joinToString { it.id.toString() }.ifEmpty { "-1" }})"
            )
        }
    }
}
