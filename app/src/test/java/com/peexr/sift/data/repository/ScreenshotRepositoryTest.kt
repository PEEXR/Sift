package com.peexr.sift.data.repository

import android.content.Context
import com.peexr.sift.data.api.JevApiClient
import com.peexr.sift.data.embedding.EmbeddingEngine
import com.peexr.sift.data.embedding.ModelState
import com.peexr.sift.data.embedding.ModelStore
import com.peexr.sift.data.local.AppDatabase
import com.peexr.sift.data.backup.BackupCollection
import com.peexr.sift.data.backup.BackupFile
import com.peexr.sift.data.backup.ScreenshotRef
import com.peexr.sift.data.backup.decodeBackup
import com.peexr.sift.data.backup.encodeBackup
import com.peexr.sift.data.local.BackupMembershipRow
import com.peexr.sift.data.local.CandidateScreenshotRow
import com.peexr.sift.data.local.CollectionEntity
import com.peexr.sift.data.local.CollectionMemberRow
import com.peexr.sift.data.local.EmbeddingRow
import com.peexr.sift.data.local.MembershipRow
import com.peexr.sift.data.local.MembershipSource
import com.peexr.sift.data.local.ScreenshotCollectionCrossRef
import com.peexr.sift.data.local.ScreenshotDao
import com.peexr.sift.data.local.ScreenshotDetailRow
import com.peexr.sift.data.local.ScreenshotEntity
import com.peexr.sift.data.perception.PerceptionEngine
import com.peexr.sift.data.search.toByteArray
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        `when`(mockDao.getLibraryRowsFlow()).thenReturn(kotlinx.coroutines.flow.emptyFlow())
        `when`(mockDao.getAllMembershipNamesFlow()).thenReturn(kotlinx.coroutines.flow.emptyFlow())
        `when`(mockDao.getAllScreenshotsFlow()).thenReturn(kotlinx.coroutines.flow.emptyFlow())
        `when`(mockDao.getCollectionSummariesFlow()).thenReturn(kotlinx.coroutines.flow.emptyFlow())
        `when`(mockDao.getCollectionPreviewsFlow()).thenReturn(kotlinx.coroutines.flow.emptyFlow())
        // ScreenshotRepository reads context.contentResolver in its initializer
        `when`(mockContext.contentResolver).thenReturn(mock(android.content.ContentResolver::class.java))
        runBlocking {
            `when`(mockDao.getFeedbackEmbeddings(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(emptyList())
        }
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

    @Test
    fun removeFromCollection_upsertsMembershipAsExcluded() = runBlocking {
        val repo = createRepository()

        repo.removeFromCollection(screenshotId = 123L, collectionId = "col_test")

        verify(mockDao).upsertMembership(
            ScreenshotCollectionCrossRef(
                screenshotId = 123L,
                collectionId = "col_test",
                source = MembershipSource.EXCLUDED,
                score = null
            )
        )
    }

    @Test
    fun keepInCollection_upsertsMembershipAsManual() = runBlocking {
        val repo = createRepository()

        repo.keepInCollection(screenshotId = 123L, collectionId = "col_test")

        verify(mockDao).upsertMembership(
            ScreenshotCollectionCrossRef(
                screenshotId = 123L,
                collectionId = "col_test",
                source = MembershipSource.MANUAL,
                score = null
            )
        )
    }

    @Test
    fun addToCollection_upsertsMembershipAsManual() = runBlocking {
        val repo = createRepository()

        repo.addToCollection(screenshotId = 123L, collectionId = "col_test")

        verify(mockDao).upsertMembership(
            ScreenshotCollectionCrossRef(
                screenshotId = 123L,
                collectionId = "col_test",
                source = MembershipSource.MANUAL,
                score = null
            )
        )
    }

    @Test
    fun setMembership_whenMemberIsTrue_upsertsAsManual() = runBlocking {
        val repo = createRepository()

        repo.setMembership(screenshotId = 555L, collectionId = "col_test", member = true)

        verify(mockDao).upsertMembership(
            ScreenshotCollectionCrossRef(
                screenshotId = 555L,
                collectionId = "col_test",
                source = MembershipSource.MANUAL,
                score = null
            )
        )
    }

    @Test
    fun setMembership_whenMemberIsFalse_upsertsAsExcluded() = runBlocking {
        val repo = createRepository()

        repo.setMembership(screenshotId = 555L, collectionId = "col_test", member = false)

        verify(mockDao).upsertMembership(
            ScreenshotCollectionCrossRef(
                screenshotId = 555L,
                collectionId = "col_test",
                source = MembershipSource.EXCLUDED,
                score = null
            )
        )
    }

    @Test
    fun createCollection_returnsGeneratedId() = runBlocking {
        val repo = createRepository()

        val id = repo.createCollection(name = "Wallpapers", description = "HD backgrounds", colorHex = "#CFE5FF")

        assertTrue("Generated id should start with wallpapers_", id.startsWith("wallpapers_"))
        verify(mockDao).insertCollection(
            CollectionEntity(
                id = id,
                name = "Wallpapers",
                description = "HD backgrounds",
                colorHex = "#CFE5FF"
            )
        )
    }

    @Test
    fun screenshotDetailFlow_transformsRowIntoDetail() = runBlocking {
        val repo = createRepository()

        val row = ScreenshotDetailRow(
            id = 42L,
            uri = "content://img/42",
            displayName = "shot42.png",
            dateAdded = 1000L,
            sizeBytes = 2048L,
            ocrText = "Detected text",
            isProcessed = true,
            hasEmbedding = true
        )
        `when`(mockDao.getScreenshotDetailFlow(42L)).thenReturn(flowOf(row))

        val detail = repo.screenshotDetailFlow(42L).first()

        assertNotNull(detail)
        assertEquals(42L, detail!!.item.id)
        assertEquals("content://img/42", detail.item.uri)
        assertEquals("Detected text", detail.item.ocrText)
        assertTrue(detail.item.isProcessed)
        assertTrue(detail.hasEmbedding)
    }

    @Test
    fun collectionMembershipFlow_transformsRowsIntoMap() = runBlocking {
        val repo = createRepository()

        val rows = listOf(
            MembershipRow(collectionId = "col_1", source = "MANUAL"),
            MembershipRow(collectionId = "col_2", source = "AUTO")
        )
        `when`(mockDao.getMembershipsForScreenshotFlow(42L)).thenReturn(flowOf(rows))

        val map = repo.collectionMembershipFlow(42L).first()

        assertEquals(2, map.size)
        assertEquals("MANUAL", map["col_1"])
        assertEquals("AUTO", map["col_2"])
    }


    @Test
    fun getReviewQueue_delegatesToDaoQuery(): Unit = runBlocking {
        val repo = createRepository()

        val expectedRows = listOf(
            CollectionMemberRow(
                screenshot = createScreenshot(1L),
                membershipSource = MembershipSource.AUTO,
                membershipScore = 1.6f
            )
        )
        `when`(mockDao.getReviewQueue("col_test")).thenReturn(expectedRows)

        val queue = repo.getReviewQueue("col_test")

        assertEquals(expectedRows, queue)
        verify(mockDao).getReviewQueue("col_test")
    }

    @Test
    fun refreshAutoCollections_whenModelNotReady_doesNothing() = runBlocking {
        val repo = createRepository()

        `when`(mockModelStore.state).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(ModelState.NotInstalled))
        `when`(mockModelStore.isInstalled()).thenReturn(false)

        repo.refreshAutoCollections()

        verifyNoInteractions(mockDao)
    }

    @Test
    fun refreshAutoCollections_whenModelReady_matchesAndReplacesAutoMembers() = runBlocking {
        val repo = createRepository()

        `when`(mockModelStore.state).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(ModelState.Ready))
        `when`(mockModelStore.isInstalled()).thenReturn(true)

        val col1 = CollectionEntity(
            id = "col_memes",
            name = "Memes",
            description = "funny meme images",
            autoFill = true
        )
        `when`(mockDao.getAutoFillCollections()).thenReturn(listOf(col1))

        val dummyVec = FloatArray(128) { 0.1f }
        dummyVec[0] = 1f
        `when`(mockEmbeddingEngine.embedText(org.mockito.ArgumentMatchers.anyString())).thenReturn(dummyVec)

        val imgVec = FloatArray(128) { 0.1f }
        imgVec[0] = 1f
        `when`(mockDao.getAllEmbeddings()).thenReturn(listOf(createEmbeddingRow(10L, imgVec)))

        repo.refreshAutoCollections()

        // Inspect the recorded call directly: Mockito's eq()/anyList() return null and break on Kotlin non-null params
        val replaceCalls = org.mockito.Mockito.mockingDetails(mockDao).invocations
            .filter { it.method.name == "replaceAutoMembers" }
        assertEquals(1, replaceCalls.size)
        assertEquals("col_memes", replaceCalls.single().arguments[0])
    }

    @Test
    fun refreshAutoCollections_withFeedbackEmbeddings_loadsFeedbackAndRefines() = runBlocking {
        val repo = createRepository()

        `when`(mockModelStore.state).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(ModelState.Ready))
        `when`(mockModelStore.isInstalled()).thenReturn(true)

        val col1 = CollectionEntity(
            id = "col_memes",
            name = "Memes",
            description = "funny meme images",
            autoFill = true
        )
        `when`(mockDao.getAutoFillCollections()).thenReturn(listOf(col1))

        val dummyVec = FloatArray(128) { 0.1f }
        dummyVec[0] = 1f
        `when`(mockEmbeddingEngine.embedText(org.mockito.ArgumentMatchers.anyString())).thenReturn(dummyVec)

        val keptVec = FloatArray(128) { 0.2f }
        val excludedVec = FloatArray(128) { 0.05f }
        `when`(mockDao.getFeedbackEmbeddings("col_memes", MembershipSource.MANUAL))
            .thenReturn(listOf(createEmbeddingRow(100L, keptVec)))
        `when`(mockDao.getFeedbackEmbeddings("col_memes", MembershipSource.EXCLUDED))
            .thenReturn(listOf(createEmbeddingRow(200L, excludedVec)))

        val imgVec = FloatArray(128) { 0.1f }
        imgVec[0] = 1f
        `when`(mockDao.getAllEmbeddings()).thenReturn(listOf(createEmbeddingRow(10L, imgVec)))

        repo.refreshAutoCollections()

        verify(mockDao).getFeedbackEmbeddings("col_memes", MembershipSource.MANUAL)
        verify(mockDao).getFeedbackEmbeddings("col_memes", MembershipSource.EXCLUDED)

        val replaceCalls = org.mockito.Mockito.mockingDetails(mockDao).invocations
            .filter { it.method.name == "replaceAutoMembers" }
        assertEquals(1, replaceCalls.size)
        assertEquals("col_memes", replaceCalls.single().arguments[0])
    }

    @Test
    fun updateCollectionDescription_updatesDaoAndRefreshesCollection() = runBlocking {
        val repo = createRepository()

        `when`(mockModelStore.state).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(ModelState.NotInstalled))
        `when`(mockModelStore.isInstalled()).thenReturn(false)

        repo.updateCollectionDescription("col_test", "new description")

        verify(mockDao).updateCollectionDescription("col_test", "new description")
    }

    @Test
    fun exportCollections_encodesExistingCollectionsAndMemberships() = runBlocking {
        val repo = createRepository()

        val col = CollectionEntity(
            id = "col_test",
            name = "Test Col",
            description = "testing export",
            colorHex = "#FFD8EC",
            autoFill = true
        )
        `when`(mockDao.getAllCollections()).thenReturn(listOf(col))
        `when`(mockDao.getManualBackupMemberships()).thenReturn(
            listOf(
                BackupMembershipRow(
                    collectionId = "col_test",
                    displayName = "shot1.png",
                    dateAdded = 12345L,
                    sizeBytes = 67890L
                )
            )
        )
        `when`(mockDao.getExcludedBackupMemberships()).thenReturn(
            listOf(
                BackupMembershipRow(
                    collectionId = "col_test",
                    displayName = "shot2.png",
                    dateAdded = 23456L,
                    sizeBytes = 78901L
                )
            )
        )

        val json = repo.exportCollections()
        val decoded = decodeBackup(json).getOrThrow()

        assertEquals(1, decoded.version)
        assertEquals(1, decoded.collections.size)
        val exportedCol = decoded.collections.first()
        assertEquals("Test Col", exportedCol.name)
        assertEquals("testing export", exportedCol.description)
        assertEquals(1, exportedCol.kept.size)
        assertEquals("shot1.png", exportedCol.kept.first().displayName)
        assertEquals(1, exportedCol.excluded.size)
        assertEquals("shot2.png", exportedCol.excluded.first().displayName)
    }

    @Test
    fun importCollections_skipsExistingAndCreatesNewWithMemberships() = runBlocking {
        val repo = createRepository()

        val existingCol = CollectionEntity(
            id = "col_existing",
            name = "Existing",
            description = "already exists",
            colorHex = "#CFE5FF",
            autoFill = false
        )
        `when`(mockDao.getAllCollections()).thenReturn(listOf(existingCol))
        `when`(mockDao.getAllCandidatesForBackup()).thenReturn(
            listOf(
                CandidateScreenshotRow(
                    id = 100L,
                    displayName = "imported_shot.png",
                    dateAdded = 1111L,
                    sizeBytes = 2222L
                )
            )
        )

        val backup = BackupFile(
            version = 1,
            collections = listOf(
                BackupCollection(
                    name = "EXISTING",
                    description = "should be skipped",
                    colorHex = "#CFE5FF",
                    autoFill = false
                ),
                BackupCollection(
                    name = "New Import",
                    description = "brand new",
                    colorHex = "#E4F7B5",
                    autoFill = false,
                    kept = listOf(
                        ScreenshotRef(
                            displayName = "imported_shot.png",
                            dateAdded = 1111L,
                            sizeBytes = 2222L
                        )
                    )
                )
            )
        )
        val json = encodeBackup(backup)

        val result = repo.importCollections(json)
        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()

        assertEquals(1, summary.collectionsCreated)
        assertEquals(1, summary.collectionsSkipped)
        assertEquals(1, summary.membershipsApplied)
    }

    @Test
    fun reindexEverything_resetsScreenshotsAndClearsAutoMemberships() = runBlocking {
        val repo = createRepository()

        repo.reindexEverything()

        verify(mockDao).resetAllScreenshotsForReindexing()
        verify(mockDao).clearAllAutoMemberships()
    }

    @Test
    fun libraryStats_queriesTotalAndIndexedFromDao() = runBlocking {
        val repo = createRepository()

        `when`(mockDao.countAll()).thenReturn(50)
        `when`(mockDao.countIndexed()).thenReturn(35)

        val stats = repo.libraryStats()
        assertEquals(50, stats.total)
        assertEquals(35, stats.indexed)
    }

    @Test
    fun deleteMembership_removesMembershipFromDao() = runBlocking {
        val repo = createRepository()

        repo.deleteMembership(screenshotId = 123L, collectionId = "col_test")

        verify(mockDao).removeFromCollection(123L, "col_test")
    }

    @Test
    fun getSuggestions_whenLessThanFiveManualExamples_returnsEmpty() = runBlocking {
        val repo = createRepository()

        val fourPositives = (1L..4L).map { id ->
            createEmbeddingRow(id, FloatArray(128) { 1f })
        }
        `when`(mockDao.getFeedbackEmbeddings("col_test", MembershipSource.MANUAL))
            .thenReturn(fourPositives)

        val suggestions = repo.getSuggestions("col_test")

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun getSuggestions_returnsOnlyNonMembersInThresholdRangeSortedAndLimited() = runBlocking {
        val repo = createRepository()

        // 5 positives with dimension 0 = 1.0f
        val positives = (1L..5L).map { id ->
            val vec = FloatArray(128) { 0f }
            vec[0] = 1.0f
            createEmbeddingRow(id, vec)
        }
        `when`(mockDao.getFeedbackEmbeddings("col_test", MembershipSource.MANUAL))
            .thenReturn(positives)

        // 5 negatives with dimension 0 = -1.0f
        val negatives = (6L..10L).map { id ->
            val vec = FloatArray(128) { 0f }
            vec[0] = -1.0f
            createEmbeddingRow(id, vec)
        }
        `when`(mockDao.getFeedbackEmbeddings("col_test", MembershipSource.EXCLUDED))
            .thenReturn(negatives)
        `when`(mockDao.getOtherManualEmbeddings("col_test"))
            .thenReturn(emptyList())

        // Members already in collection: positive IDs + screenshot 20L
        `when`(mockDao.getMemberScreenshotIds("col_test"))
            .thenReturn(listOf(1L, 2L, 3L, 4L, 5L, 20L))

        // Candidate embeddings:
        // 11L: strong match (dim 0 = 0.8f -> p >= 0.50, excluded)
        // 12L: near-miss high (dim 0 = -0.1f -> p ~ 0.36, included)
        // 13L: near-miss lower (dim 0 = -0.3f -> p ~ 0.29, included)
        // 14L: far negative (dim 0 = -2.0f -> p < 0.15, excluded)
        // 20L: near-miss score (dim 0 = -0.1f, but already member -> excluded)
        fun makeCandidate(id: Long, v0: Float): EmbeddingRow {
            val vec = FloatArray(128) { 0f }
            vec[0] = v0
            return createEmbeddingRow(id, vec)
        }

        val allEmbeddings = positives + negatives + listOf(
            makeCandidate(11L, 0.8f),
            makeCandidate(12L, -0.1f),
            makeCandidate(13L, -0.3f),
            makeCandidate(14L, -2.0f),
            makeCandidate(20L, -0.1f)
        )
        `when`(mockDao.getAllEmbeddings()).thenReturn(allEmbeddings)

        `when`(mockDao.getScreenshotsByIds(listOf(12L, 13L))).thenReturn(
            listOf(createScreenshot(12L), createScreenshot(13L))
        )
        `when`(mockDao.getScreenshotsByIds(listOf(12L))).thenReturn(
            listOf(createScreenshot(12L))
        )

        // Test with limit = 10 (returns both 12L and 13L sorted descending)
        val suggestions = repo.getSuggestions("col_test", limit = 10)

        assertEquals(2, suggestions.size)
        assertEquals(12L, suggestions[0].screenshot.id)
        assertEquals(13L, suggestions[1].screenshot.id)
        assertEquals(MembershipSource.AUTO, suggestions[0].membershipSource)
        assertEquals(MembershipSource.AUTO, suggestions[1].membershipSource)
        assertTrue(suggestions[0].membershipScore!! >= 0.15f)
        assertTrue(suggestions[0].membershipScore!! < 0.50f)
        assertTrue(suggestions[1].membershipScore!! >= 0.15f)
        assertTrue(suggestions[1].membershipScore!! < 0.50f)
        assertTrue(suggestions[0].membershipScore!! > suggestions[1].membershipScore!!)

        // Test limit = 1 respects the limit
        val singleSuggestion = repo.getSuggestions("col_test", limit = 1)
        assertEquals(1, singleSuggestion.size)
        assertEquals(12L, singleSuggestion[0].screenshot.id)
    }
}


