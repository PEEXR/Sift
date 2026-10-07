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

    @Query("SELECT * FROM screenshots WHERE id = :id")
    suspend fun getScreenshotById(id: Long): ScreenshotEntity?

    // Relies on PRAGMA recursive_triggers = ON for FTS sync on REPLACE conflict
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScreenshots(screenshots: List<ScreenshotEntity>)

    @Query("UPDATE screenshots SET embedding = :embedding WHERE id = :id")
    suspend fun updateEmbedding(id: Long, embedding: ByteArray)

    @Query("SELECT id, embedding FROM screenshots WHERE embedding IS NOT NULL")
    suspend fun getAllEmbeddings(): List<EmbeddingRow>

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

    // Cross reference
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assignToCollection(crossRef: ScreenshotCollectionCrossRef)

    @Query("DELETE FROM screenshot_collections WHERE screenshotId = :screenshotId AND collectionId = :collectionId")
    suspend fun removeFromCollection(screenshotId: Long, collectionId: String)

    @Query("""
        SELECT c.* FROM collections c
        INNER JOIN screenshot_collections sc ON c.id = sc.collectionId
        WHERE sc.screenshotId = :screenshotId
    """)
    suspend fun getCollectionsForScreenshot(screenshotId: Long): List<CollectionEntity>

    @Query("""
        SELECT s.* FROM screenshots s
        INNER JOIN screenshot_collections sc ON s.id = sc.screenshotId
        WHERE sc.collectionId = :collectionId
        ORDER BY s.dateAdded DESC
    """)
    fun getScreenshotsForCollectionFlow(collectionId: String): Flow<List<ScreenshotEntity>>

    @Query("SELECT * FROM screenshots WHERE isJunk = 1 ORDER BY dateAdded DESC")
    fun getJunkScreenshotsFlow(): Flow<List<ScreenshotEntity>>

    @Query("SELECT COUNT(*) FROM screenshots WHERE isJunk = 1")
    fun getJunkCountFlow(): Flow<Int>

    @Query("SELECT SUM(sizeBytes) FROM screenshots WHERE isJunk = 1")
    fun getRecoverableBytesFlow(): Flow<Long?>
}
