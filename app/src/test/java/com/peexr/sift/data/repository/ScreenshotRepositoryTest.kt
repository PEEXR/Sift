package com.peexr.sift.data.repository

import android.content.Context
import com.peexr.sift.data.api.JevApiClient
import com.peexr.sift.data.embedding.EmbeddingEngine
import com.peexr.sift.data.embedding.ModelState
import com.peexr.sift.data.embedding.ModelStore
import com.peexr.sift.data.local.AppDatabase
import com.peexr.sift.data.local.EmbeddingRow
import com.peexr.sift.data.local.ScreenshotDao
import com.peexr.sift.data.local.ScreenshotEntity
import com.peexr.sift.data.perception.PerceptionEngine
import com.peexr.sift.data.search.toByteArray
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

class ScreenshotRepositoryTest {

    private lateinit var mockContext: Context
    private lateinit var mockDatabase: AppDatabase
    private lateinit var mockDao: ScreenshotDao
    private lateinit var mockPerceptionEngine: PerceptionEngine
    private lateinit var mockJevApiClient: JevApiClient
    private lateinit var mockModelStore: ModelStore
    private lateinit var mockEmbeddingEngine: EmbeddingEngine

    private fun createScreenshot(id: Long, name: String = "screenshot_$id.png"): ScreenshotEntity {
        return ScreenshotEntity(
            id = id,
            uri = "content://media/external/images/media/$id",
            displayName = name,
            dateAdded = 1000L + id,
            sizeBytes = 2048L,
            ocrText = "Sample OCR text for $id",
            visualLabels = "document,text",
            isProcessed = true
        )
    }

    private fun createEmbeddingRow(id: Long, vector: FloatArray): EmbeddingRow {
        return EmbeddingRow(id = id, embedding = vector.toByteArray())
    }

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockDatabase = mock(AppDatabase::class.java)
        mockDao = mock(ScreenshotDao::class.java)
        mockPerceptionEngine = mock(PerceptionEngine::class.java)
        mockJevApiClient = mock(JevApiClient::class.java)
        mockModelStore = mock(ModelStore::class.java)
        mockEmbeddingEngine = mock(EmbeddingEngine::class.java)

        `when`(mockDatabase.screenshotDao()).thenReturn(mockDao)
        // ScreenshotRepository reads context.contentResolver in its initializer
        `when`(mockContext.contentResolver).thenReturn(mock(android.content.ContentResolver::class.java))
    }

    private fun createRepository(): ScreenshotRepository {
        val repo = ScreenshotRepository(
            context = mockContext,
            database = mockDatabase,
            perceptionEngine = mockPerceptionEngine,
            jevApiClient = mockJevApiClient,
            modelStoreProvider = { mockModelStore },
            embeddingEngineProvider = { mockEmbeddingEngine }
        )
        // Drop interactions from the repository's own initializer so tests only see hybridSearch calls
        org.mockito.Mockito.clearInvocations(mockDao)
        return repo
    }

    @Test
    fun hybridSearch_blankQuery_returnsEmptyListWithoutDbCalls() = runBlocking {
        val repo = createRepository()

        val resultsEmpty = repo.hybridSearch("")
        val resultsBlank = repo.hybridSearch("    ")

        assertTrue(resultsEmpty.isEmpty())
        assertTrue(resultsBlank.isEmpty())
        verifyNoInteractions(mockDao)
    }

    @Test
    fun hybridSearch_invalidLimit_returnsEmptyListWithoutDbCalls() = runBlocking {
        val repo = createRepository()

        val resultsZero = repo.hybridSearch("query", limit = 0)
        val resultsNegative = repo.hybridSearch("query", limit = -1)

        assertTrue(resultsZero.isEmpty())
        assertTrue(resultsNegative.isEmpty())
        verifyNoInteractions(mockDao)
    }

    @Test
    fun hybridSearch_whenModelNotReady_returnsLexicalOnlyPreservingRank() = runBlocking {
        val repo = createRepository()

        `when`(mockModelStore.state).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(ModelState.NotInstalled))
        `when`(mockModelStore.isInstalled()).thenReturn(false)

        val entity1 = createScreenshot(1L)
        val entity2 = createScreenshot(2L)

        // Lexical hits: [2L, 1L]
        `when`(mockDao.searchOcrIds("\"receipt\"*", 50)).thenReturn(listOf(2L, 1L))
        `when`(mockDao.getScreenshotsByIds(listOf(2L, 1L))).thenReturn(listOf(entity1, entity2))

        val results = repo.hybridSearch("receipt", limit = 50)

        assertEquals(2, results.size)
        // Should preserve ranking order [2L, 1L]
        assertEquals(2L, results[0].id)
        assertEquals(1L, results[1].id)
    }

    @Test
    fun hybridSearch_whenModelReady_fusesLexicalAndDenseRankings() = runBlocking {
        val repo = createRepository()

        `when`(mockModelStore.state).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(ModelState.Ready))
        `when`(mockModelStore.isInstalled()).thenReturn(true)

        val queryVec = FloatArray(128) { 1f }
        val vec1 = FloatArray(128) { 1f }
        val vec2 = FloatArray(128) { 0.5f }
        val vec3 = FloatArray(128) { -1f }

        `when`(mockEmbeddingEngine.embedText("coffee")).thenReturn(queryVec)
        `when`(mockDao.getAllEmbeddings()).thenReturn(
            listOf(
                createEmbeddingRow(1L, vec1),
                createEmbeddingRow(2L, vec2),
                createEmbeddingRow(3L, vec3)
            )
        )

        // Lexical hits: [2L, 1L]
        `when`(mockDao.searchOcrIds("\"coffee\"*", 50)).thenReturn(listOf(2L, 1L))

        val entity1 = createScreenshot(1L)
        val entity2 = createScreenshot(2L)
        val entity3 = createScreenshot(3L)

        // Fused ids will include 1L, 2L, 3L
        `when`(mockDao.getScreenshotsByIds(org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(listOf(entity1, entity2, entity3))

        val results = repo.hybridSearch("coffee", limit = 50)

        assertTrue(results.isNotEmpty())
        val resultIds = results.map { it.id }
        assertTrue(resultIds.contains(1L))
        assertTrue(resultIds.contains(2L))
    }

    @Test
    fun hybridSearch_whenFtsFailsWithSQLiteException_returnsDenseOnly() = runBlocking {
        val repo = createRepository()

        `when`(mockModelStore.state).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(ModelState.Ready))
        `when`(mockModelStore.isInstalled()).thenReturn(true)

        // Simulate SQLiteException on FTS search
        class CustomSQLiteException : RuntimeException("FTS syntax error")
        `when`(mockDao.searchOcrIds("\"broken\"*", 50)).thenThrow(CustomSQLiteException())

        val queryVec = FloatArray(128) { 1f }
        val vec = FloatArray(128) { 1f }

        `when`(mockEmbeddingEngine.embedText("broken")).thenReturn(queryVec)
        `when`(mockDao.getAllEmbeddings()).thenReturn(listOf(createEmbeddingRow(42L, vec)))

        val entity42 = createScreenshot(42L)
        `when`(mockDao.getScreenshotsByIds(listOf(42L))).thenReturn(listOf(entity42))

        val results = repo.hybridSearch("broken", limit = 50)

        assertEquals(1, results.size)
        assertEquals(42L, results[0].id)
    }

    @Test
    fun hybridSearch_whenBothEmpty_returnsEmptyList() = runBlocking {
        val repo = createRepository()

        `when`(mockModelStore.state).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(ModelState.NotInstalled))
        `when`(mockModelStore.isInstalled()).thenReturn(false)
        `when`(mockDao.searchOcrIds("\"nothing\"*", 50)).thenReturn(emptyList())

        val results = repo.hybridSearch("nothing", limit = 50)

        assertTrue(results.isEmpty())
    }

    @Test
    fun computeStaleIds_emptyDb_returnsEmpty() {
        val staleWithMedia = computeStaleIds(dbIds = emptyList(), mediaIds = listOf(1L, 2L, 3L))
        assertTrue(staleWithMedia.isEmpty())

        val staleEmptyBoth = computeStaleIds(dbIds = emptyList(), mediaIds = emptyList())
        assertTrue(staleEmptyBoth.isEmpty())
    }

    @Test
    fun computeStaleIds_emptyMedia_returnsAllDbIds() {
        val dbIds = listOf(10L, 20L, 30L)
        val stale = computeStaleIds(dbIds = dbIds, mediaIds = emptyList())
        assertEquals(listOf(10L, 20L, 30L), stale)
    }

    @Test
    fun computeStaleIds_overlap_returnsOnlyStaleDbIds() {
        val dbIds = listOf(1L, 2L, 3L, 4L)
        val mediaIds = listOf(2L, 4L, 5L)
        val stale = computeStaleIds(dbIds = dbIds, mediaIds = mediaIds)
        assertEquals(listOf(1L, 3L), stale)

        // When all DB IDs exist in MediaStore, stale should be empty
        val allPresent = computeStaleIds(dbIds = listOf(1L, 2L), mediaIds = listOf(1L, 2L, 3L))
        assertTrue(allPresent.isEmpty())
    }

    @Test
    fun computeStaleIds_allStale_returnsAllDbIds() {
        val dbIds = listOf(100L, 200L, 300L)
        val mediaIds = listOf(400L, 500L)
        val stale = computeStaleIds(dbIds = dbIds, mediaIds = mediaIds)
        assertEquals(listOf(100L, 200L, 300L), stale)
    }

    @Test
    fun computeStaleIds_duplicates_handlesDuplicatesCorrectly() {
        // Duplicate MediaStore IDs should not affect stale determination
        val staleDupMedia = computeStaleIds(
            dbIds = listOf(1L, 2L, 3L),
            mediaIds = listOf(2L, 2L, 2L)
        )
        assertEquals(listOf(1L, 3L), staleDupMedia)

        // Duplicate DB IDs should be deduplicated
        val staleDupDb = computeStaleIds(
            dbIds = listOf(1L, 1L, 2L, 3L, 3L),
            mediaIds = listOf(2L)
        )
        assertEquals(listOf(1L, 3L), staleDupDb)

        // Duplicates on both sides
        val staleDupBoth = computeStaleIds(
            dbIds = listOf(1L, 1L, 2L, 2L, 3L),
            mediaIds = listOf(2L, 2L)
        )
        assertEquals(listOf(1L, 3L), staleDupBoth)
    }
}

