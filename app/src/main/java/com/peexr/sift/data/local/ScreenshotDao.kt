package com.peexr.sift.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

import androidx.room.SkipQueryVerification

@Dao
interface ScreenshotDao {

    @Query("SELECT * FROM screenshots ORDER BY dateAdded DESC")
    fun getAllScreenshotsFlow(): Flow<List<ScreenshotEntity>>

    @Query("SELECT COUNT(*) FROM screenshots WHERE isProcessed = 0 OR embedding IS NULL")
    fun countNeedingIndexingFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM screenshots")
    fun countAllFlow(): Flow<Int>

    @Query("SELECT * FROM screenshots WHERE id = :id")
    suspend fun getScreenshotById(id: Long): ScreenshotEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewScreenshots(screenshots: List<ScreenshotEntity>)

    @Query("SELECT id FROM screenshots")
    suspend fun getAllScreenshotIds(): List<Long>

    @Query("UPDATE screenshots SET embedding = :embedding WHERE id = :id")
    suspend fun updateEmbedding(id: Long, embedding: ByteArray)

    @Query("SELECT id, embedding FROM screenshots WHERE embedding IS NOT NULL")
    suspend fun getAllEmbeddings(): List<EmbeddingRow>

    @Query("SELECT id, uri FROM screenshots WHERE embedding IS NULL")
    suspend fun getScreenshotsNeedingEmbedding(): List<ScreenshotUriRow>

    @Query("SELECT id, uri, isProcessed, (embedding IS NULL) AS needsEmbedding FROM screenshots WHERE isProcessed = 0 OR embedding IS NULL ORDER BY dateAdded DESC")
    suspend fun getScreenshotsNeedingIndexing(): List<IndexingRow>

    @Query("SELECT * FROM screenshots WHERE id IN (:ids)")
    suspend fun getScreenshotsByIds(ids: List<Long>): List<ScreenshotEntity>

    @SkipQueryVerification
    @Query("SELECT rowid FROM screenshots_fts WHERE screenshots_fts MATCH :query ORDER BY rank LIMIT :limit")
    suspend fun searchOcrIds(query: String, limit: Int = 100): List<Long>

    @Query("UPDATE screenshots SET ocrText = :ocrText, visualLabels = :visualLabels, isProcessed = 1 WHERE id = :id")
    suspend fun updatePerception(id: Long, ocrText: String, visualLabels: String)

    @Query("UPDATE screenshots SET isJunk = :isJunk, junkConfidence = :confidence, junkRationale = :rationale WHERE id = :id")
    suspend fun updateJunkStatus(id: Long, isJunk: Boolean, confidence: Double, rationale: String)

    @Query("DELETE FROM screenshots WHERE id IN (:ids)")
    suspend fun deleteScreenshots(ids: List<Long>)

    // Collections
    @Query("SELECT * FROM collections ORDER BY name ASC")
    fun getAllCollectionsFlow(): Flow<List<CollectionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: CollectionEntity)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteCollection(id: String)

    @Query("SELECT * FROM collections WHERE autoFill = 1")
    suspend fun getAutoFillCollections(): List<CollectionEntity>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun getCollectionById(id: String): CollectionEntity?

    // Cross reference
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assignToCollection(crossRef: ScreenshotCollectionCrossRef)

    @Query("DELETE FROM screenshot_collections WHERE screenshotId = :screenshotId AND collectionId = :collectionId")
    suspend fun removeFromCollection(screenshotId: Long, collectionId: String)

    @Query("DELETE FROM screenshot_collections WHERE collectionId = :collectionId AND source = 'AUTO'")
    suspend fun clearAutoMembers(collectionId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemberships(rows: List<ScreenshotCollectionCrossRef>)

    @Transaction
    suspend fun replaceAutoMembers(collectionId: String, rows: List<ScreenshotCollectionCrossRef>) {
        clearAutoMembers(collectionId)
        insertMemberships(rows)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembership(row: ScreenshotCollectionCrossRef)

    @Query("""
        SELECT c.* FROM collections c
        INNER JOIN screenshot_collections sc ON c.id = sc.collectionId
        WHERE sc.screenshotId = :screenshotId AND sc.source != 'EXCLUDED'
    """)
    suspend fun getCollectionsForScreenshot(screenshotId: Long): List<CollectionEntity>

    @Query("""
        SELECT s.* FROM screenshots s
        INNER JOIN screenshot_collections sc ON s.id = sc.screenshotId
        WHERE sc.collectionId = :collectionId AND sc.source != 'EXCLUDED'
        ORDER BY s.dateAdded DESC
    """)
    fun getScreenshotsForCollectionFlow(collectionId: String): Flow<List<ScreenshotEntity>>

    @Query("""
        SELECT s.*, sc.source AS membershipSource, sc.score AS membershipScore
        FROM screenshots s
        INNER JOIN screenshot_collections sc ON s.id = sc.screenshotId
        WHERE sc.collectionId = :collectionId AND sc.source != 'EXCLUDED'
        ORDER BY (sc.score IS NULL), sc.score ASC, s.dateAdded DESC
    """)
    suspend fun getReviewQueue(collectionId: String): List<CollectionMemberRow>

    @Query("SELECT * FROM screenshots WHERE isJunk = 1 ORDER BY dateAdded DESC")
    fun getJunkScreenshotsFlow(): Flow<List<ScreenshotEntity>>

    @Query("SELECT COUNT(*) FROM screenshots WHERE isJunk = 1")
    fun getJunkCountFlow(): Flow<Int>

    @Query("SELECT SUM(sizeBytes) FROM screenshots WHERE isJunk = 1")
    fun getRecoverableBytesFlow(): Flow<Long?>
}
