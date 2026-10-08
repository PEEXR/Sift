package com.peexr.sift.data.repository

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.peexr.sift.SiftApplication
import com.peexr.sift.data.api.JevApiClient
import com.peexr.sift.data.collections.CollectionMatcher
import com.peexr.sift.data.embedding.EmbeddingEngine
import com.peexr.sift.data.embedding.ModelState
import com.peexr.sift.data.embedding.ModelStore
import com.peexr.sift.data.local.AppDatabase
import com.peexr.sift.data.local.CollectionEntity
import com.peexr.sift.data.local.CollectionMemberRow
import com.peexr.sift.data.local.MembershipRow
import com.peexr.sift.data.local.MembershipSource
import com.peexr.sift.data.local.ScreenshotCollectionCrossRef
import com.peexr.sift.data.local.ScreenshotDetailRow
import com.peexr.sift.data.local.ScreenshotEntity
import com.peexr.sift.data.model.CollectionMemberItem
import com.peexr.sift.data.model.CollectionSummary
import com.peexr.sift.data.model.JevQuestion
import com.peexr.sift.data.model.JevRequest
import com.peexr.sift.data.cleanup.CodeKind
import com.peexr.sift.data.cleanup.EmbeddedRef
import com.peexr.sift.data.cleanup.burstDeletionCandidates
import com.peexr.sift.data.cleanup.findBursts
import com.peexr.sift.data.cleanup.looksLikeCode
import com.peexr.sift.data.model.ScreenshotDetail
import com.peexr.sift.data.model.ScreenshotItem
import com.peexr.sift.data.perception.PerceptionEngine
import com.peexr.sift.data.search.EMBEDDING_DIM
import com.peexr.sift.data.search.reciprocalRankFusion
import com.peexr.sift.data.search.sanitizeFtsQuery
import com.peexr.sift.data.search.toFloatArray
import com.peexr.sift.data.search.topKBySimilarity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.peexr.sift.data.backup.BackupCollection
import com.peexr.sift.data.backup.BackupFile
import com.peexr.sift.data.backup.Candidate
import com.peexr.sift.data.backup.ScreenshotRef
import com.peexr.sift.data.backup.decodeBackup
import com.peexr.sift.data.backup.encodeBackup
import com.peexr.sift.data.backup.matchRefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ScreenshotRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val perceptionEngine: PerceptionEngine,
    private val jevApiClient: JevApiClient,
    private val modelStoreProvider: () -> ModelStore? = { (context.applicationContext as? SiftApplication)?.modelStore },
    private val embeddingEngineProvider: () -> EmbeddingEngine? = { (context.applicationContext as? SiftApplication)?.embeddingEngine },
    private val zThresholdProvider: () -> Float = { CollectionMatcher.DEFAULT_Z_THRESHOLD }
) {
    private val dao = database.screenshotDao()
    private val contentResolver: ContentResolver = context.contentResolver

    private val textEmbeddingCacheMutex = Mutex()
    private val textEmbeddingCache = mutableMapOf<String, FloatArray>()

    private suspend fun getOrEmbedText(engine: EmbeddingEngine, text: String): FloatArray {
        textEmbeddingCacheMutex.withLock {
            textEmbeddingCache[text]?.let { return it }
        }
        val vector = engine.embedText(text)
        textEmbeddingCacheMutex.withLock {
            textEmbeddingCache[text] = vector
        }
        return vector
    }

    // Lightweight flow of library screenshot items (no collections, no OCR)
    val libraryItemsFlow: Flow<List<ScreenshotItem>> = dao.getLibraryRowsFlow().map { rows ->
        rows.map { row ->
            ScreenshotItem(
                id = row.id,
                uri = row.uri,
                displayName = row.displayName,
                dateAdded = row.dateAdded,
                sizeBytes = row.sizeBytes
            )
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    // Flow of all screenshots with collection names combined in one pass
    val allScreenshotsFlow: Flow<List<ScreenshotItem>> = combine(
        dao.getAllScreenshotsFlow(),
        dao.getAllMembershipNamesFlow()
    ) { entities, membershipRows ->
        val namesByScreenshot = membershipRows.groupBy({ it.screenshotId }, { it.name })
        entities.map { entity ->
            entity.toDomainModel(namesByScreenshot[entity.id] ?: emptyList())
        }
    }.flowOn(Dispatchers.Default)

    // Flow of user collections
    val allCollectionsFlow: Flow<List<CollectionEntity>> = dao.getAllCollectionsFlow()

    // Flow of collection summaries with up to 3 preview URIs
    val collectionSummariesFlow: Flow<List<CollectionSummary>> = combine(
        dao.getCollectionSummariesFlow(),
        dao.getCollectionPreviewsFlow()
    ) { summaryRows, previewRows ->
        val previewsByCollection = previewRows.groupBy({ it.collectionId }, { it.uri })
        summaryRows.map { row ->
            CollectionSummary(
                id = row.id,
                name = row.name,
                description = row.description,
                colorHex = row.colorHex,
                memberCount = row.memberCount,
                reviewCount = row.reviewCount,
                totalBytes = row.totalBytes,
                previewUris = previewsByCollection[row.id]?.take(3) ?: emptyList()
            )
        }
    }.flowOn(Dispatchers.Default)

    // Flow of junk screenshots for Cleanup tab
    val junkScreenshotsFlow: Flow<List<ScreenshotItem>> = dao.getJunkScreenshotsFlow().map { entities ->
        entities.map { it.toDomainModel() }
    }

    val junkCountFlow: Flow<Int> = dao.getJunkCountFlow()
    val recoverableBytesFlow: Flow<Long> = dao.getRecoverableBytesFlow().map { it ?: 0L }

    val countNeedingIndexingFlow: Flow<Int> = dao.countNeedingIndexingFlow()
    val countAllFlow: Flow<Int> = dao.countAllFlow()

    suspend fun syncMediaStoreScreenshots(): Int = withContext(Dispatchers.IO) {
        val screenshots = mutableListOf<ScreenshotEntity>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATA
        )

        // Query screenshots directory or images matching screenshot pattern
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.DATA} LIKE ?"
        val selectionArgs = arrayOf("%screenshot%", "%Screenshots%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        ) ?: return@withContext 0

        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val name = it.getString(nameColumn) ?: "screenshot_$id.png"
                val date = it.getLong(dateColumn)
                val size = it.getLong(sizeColumn)
                val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                screenshots.add(
                    ScreenshotEntity(
                        id = id,
                        uri = contentUri.toString(),
                        displayName = name,
                        dateAdded = date,
                        sizeBytes = size
                    )
                )
            }
        }

        if (screenshots.isNotEmpty()) {
            dao.insertNewScreenshots(screenshots)
        }

        val dbIds = dao.getAllScreenshotIds()
        val mediaIds = screenshots.map { it.id }
        val staleIds = computeStaleIds(dbIds, mediaIds)
        // An empty result usually means lost/partial media access, not that every screenshot was deleted;
        // never wipe the whole index (OCR + embeddings) on that basis
        if (staleIds.isNotEmpty() && mediaIds.isNotEmpty()) {
            staleIds.chunked(500).forEach { chunk ->
                dao.deleteScreenshots(chunk)
            }
        }

        screenshots.size
    }

    suspend fun processScreenshotWithPerception(id: Long, uriString: Uri): Boolean = withContext(Dispatchers.IO) {
        val result = perceptionEngine.analyzeImage(uriString)
        if (result.isSuccess) {
            val perception = result.getOrNull()!!
            val labelsJson = perception.visualLabels.joinToString(",")
            dao.updatePerception(id, perception.ocrText, labelsJson)
            true
        } else {
            false
        }
    }

    suspend fun classifyScreenshotWithJev(screenshotId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val entity = dao.getScreenshotById(screenshotId) ?: return@withContext Result.failure(Exception("Not found"))
        val collections = dao.getAllCollectionsFlow().first()

        if (collections.isEmpty()) return@withContext Result.success(Unit)

        val labelsList = entity.visualLabels.split(",").filter { it.isNotBlank() }
        val stateJson = buildJsonObject {
            put("ocr_text", JsonPrimitive(entity.ocrText.take(1500)))
            put("visual_concepts", JsonArray(labelsList.map { JsonPrimitive(it) }))
            put("display_name", JsonPrimitive(entity.displayName))
        }

        val questions = mutableMapOf<String, JevQuestion>()
        collections.forEach { collection ->
            questions["col_${collection.id}"] = JevQuestion(
                type = "noul",
                instructions = "Does this image belong in the '${collection.name}' collection? Collection description/criteria: ${collection.description}"
            )
        }

        val jevRequest = JevRequest(
            model = "jev-latest",
            state = stateJson,
            questions = questions
        )

        val responseResult = jevApiClient.executeSystemOne(jevRequest)
        if (responseResult.isSuccess) {
            val response = responseResult.getOrNull()!!
            response.answers.forEach { (qKey, answer) ->
                if (answer.noul == true) {
                    val collectionId = qKey.removePrefix("col_")
                    dao.assignToCollection(ScreenshotCollectionCrossRef(screenshotId, collectionId))
                }
            }
            Result.success(Unit)
        } else {
            Result.failure(responseResult.exceptionOrNull()!!)
        }
    }

    suspend fun evaluateCustomPurgeDirective(
        directive: String,
        onProgress: (current: Int, total: Int) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        val allEntities = dao.getAllScreenshotsFlow().first()
        var matchCount = 0

        allEntities.forEachIndexed { index, entity ->
            onProgress(index + 1, allEntities.size)

            // Ensure perception is run first
            var ocr = entity.ocrText
            var labels = entity.visualLabels
            if (!entity.isProcessed) {
                val pResult = perceptionEngine.analyzeImage(Uri.parse(entity.uri))
                if (pResult.isSuccess) {
                    val p = pResult.getOrNull()!!
                    ocr = p.ocrText
                    labels = p.visualLabels.joinToString(",")
                    dao.updatePerception(entity.id, ocr, labels)
                }
            }

            val labelsList = labels.split(",").filter { it.isNotBlank() }
            val stateJson = buildJsonObject {
                put("ocr_text", JsonPrimitive(ocr.take(1500)))
                put("visual_concepts", JsonArray(labelsList.map { JsonPrimitive(it) }))
                put("display_name", JsonPrimitive(entity.displayName))
                put("file_size_bytes", JsonPrimitive(entity.sizeBytes))
            }

            val questions = mapOf(
                "is_match" to JevQuestion(
                    type = "noul",
                    instructions = "Evaluate if this screenshot matches the user's purge directive: '$directive'. Return true if it matches and should be deleted."
                ),
                "confidence" to JevQuestion(
                    type = "score",
                    instructions = "Confidence that this screenshot should be purged according to the directive",
                    criteria = JsonArray(listOf(JsonPrimitive("Not a match"), JsonPrimitive("Likely match"), JsonPrimitive("Definite match")))
                )
            )

            val jevResult = jevApiClient.executeSystemOne(JevRequest(state = stateJson, questions = questions))
            if (jevResult.isSuccess) {
                val answers = jevResult.getOrNull()?.answers ?: emptyMap()
                val isMatch = answers["is_match"]?.noul == true
                val confidence = answers["confidence"]?.score?.toDouble() ?: 0.0
                if (isMatch) {
                    matchCount++
                    dao.updateJunkStatus(
                        id = entity.id,
                        isJunk = true,
                        confidence = confidence,
                        rationale = "Matches directive: '$directive'"
                    )
                } else {
                    dao.updateJunkStatus(entity.id, false, 0.0, "")
                }
            }
        }
        Result.success(matchCount)
    }

    suspend fun refreshAutoCollections(onlyCollectionId: String? = null) {
        val modelStore = modelStoreProvider()
        val embeddingEngine = embeddingEngineProvider()
        val isModelReady = modelStore != null && embeddingEngine != null &&
            (modelStore.state.value is ModelState.Ready || modelStore.isInstalled())
        if (!isModelReady || embeddingEngine == null) {
            return
        }

        try {
            val (targetCollections, rawEmbeddings, feedbackByCollection) = withContext(Dispatchers.IO) {
                val collections = if (onlyCollectionId != null) {
                    val col = dao.getCollectionById(onlyCollectionId)
                    if (col != null && col.autoFill) listOf(col) else emptyList()
                } else {
                    dao.getAutoFillCollections()
                }
                val embeddings = dao.getAllEmbeddings()
                val feedback = collections.associate { col ->
                    val kept = dao.getFeedbackEmbeddings(col.id, MembershipSource.MANUAL)
                    val excluded = dao.getFeedbackEmbeddings(col.id, MembershipSource.EXCLUDED)
                    col.id to Pair(kept, excluded)
                }
                Triple(collections, embeddings, feedback)
            }

            if (targetCollections.isEmpty()) {
                return
            }

            val backgroundVectors = CollectionMatcher.BACKGROUND_DESCRIPTIONS.map { desc ->
                getOrEmbedText(embeddingEngine, desc)
            }

            val collectionVectors = targetCollections.map { col ->
                val text = if (col.description.isNotBlank()) col.description else col.name
                Pair(col, getOrEmbedText(embeddingEngine, text))
            }

            val collectionMatches = withContext(Dispatchers.Default) {
                val candidateScreenshots = rawEmbeddings.mapNotNull { row ->
                    try {
                        val vec = row.embedding.toFloatArray()
                        if (vec.size == 128) Pair(row.id, vec) else null
                    } catch (_: Exception) {
                        null
                    }
                }

                collectionVectors.map { (col, textVec) ->
                    val (keptRows, excludedRows) = feedbackByCollection[col.id] ?: Pair(emptyList(), emptyList())
                    val keptVectors = keptRows.mapNotNull { row ->
                        try {
                            val vec = row.embedding.toFloatArray()
                            if (vec.size == 128) vec else null
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val excludedVectors = excludedRows.mapNotNull { row ->
                        try {
                            val vec = row.embedding.toFloatArray()
                            if (vec.size == 128) vec else null
                        } catch (_: Exception) {
                            null
                        }
                    }

                    val refinedVector = CollectionMatcher.refineQuery(
                        base = textVec,
                        positives = keptVectors,
                        negatives = excludedVectors
                    )

                    val matches = CollectionMatcher.match(
                        collection = refinedVector,
                        screenshots = candidateScreenshots,
                        background = backgroundVectors,
                        threshold = zThresholdProvider()
                    )
                    Pair(col.id, matches)
                }
            }

            withContext(Dispatchers.IO) {
                for ((colId, matches) in collectionMatches) {
                    val rows = matches.map { (screenshotId, z) ->
                        ScreenshotCollectionCrossRef(
                            screenshotId = screenshotId,
                            collectionId = colId,
                            source = MembershipSource.AUTO,
                            score = z
                        )
                    }
                    dao.replaceAutoMembers(colId, rows)
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    suspend fun createCollection(name: String, description: String, colorHex: String): String = withContext(Dispatchers.IO) {
        val id = name.lowercase().replace("\\s+".toRegex(), "_") + "_" + System.currentTimeMillis()
        dao.insertCollection(CollectionEntity(id, name, description, colorHex))
        try {
            refreshAutoCollections(onlyCollectionId = id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                Log.e("ScreenshotRepository", "Failed to refresh auto collection $id after creation", e)
            } catch (_: Throwable) {}
        }
        id
    }

    suspend fun setMembership(screenshotId: Long, collectionId: String, member: Boolean) = withContext(Dispatchers.IO) {
        if (member) {
            keepInCollection(screenshotId, collectionId)
        } else {
            removeFromCollection(screenshotId, collectionId)
        }
    }

    suspend fun removeFromCollection(screenshotId: Long, collectionId: String) = withContext(Dispatchers.IO) {
        dao.upsertMembership(
            ScreenshotCollectionCrossRef(
                screenshotId = screenshotId,
                collectionId = collectionId,
                source = MembershipSource.EXCLUDED,
                score = null
            )
        )
    }

    suspend fun keepInCollection(screenshotId: Long, collectionId: String) = withContext(Dispatchers.IO) {
        dao.upsertMembership(
            ScreenshotCollectionCrossRef(
                screenshotId = screenshotId,
                collectionId = collectionId,
                source = MembershipSource.MANUAL,
                score = null
            )
        )
    }

    suspend fun addToCollection(screenshotId: Long, collectionId: String) = withContext(Dispatchers.IO) {
        dao.upsertMembership(
            ScreenshotCollectionCrossRef(
                screenshotId = screenshotId,
                collectionId = collectionId,
                source = MembershipSource.MANUAL,
                score = null
            )
        )
    }

    suspend fun getReviewQueue(collectionId: String): List<CollectionMemberRow> = withContext(Dispatchers.IO) {
        dao.getReviewQueue(collectionId)
    }

    suspend fun deleteCollection(id: String) = withContext(Dispatchers.IO) {
        dao.deleteCollection(id)
    }

    fun collectionMembersFlow(collectionId: String): Flow<List<CollectionMemberItem>> =
        dao.getCollectionMembersFlow(collectionId).map { rows ->
            rows.map { row ->
                CollectionMemberItem(
                    item = ScreenshotItem(
                        id = row.id,
                        uri = row.uri,
                        displayName = row.displayName,
                        dateAdded = row.dateAdded,
                        sizeBytes = row.sizeBytes
                    ),
                    kept = row.source == MembershipSource.MANUAL
                )
            }
        }.flowOn(Dispatchers.Default)

    fun screenshotDetailFlow(id: Long): Flow<ScreenshotDetail?> =
        dao.getScreenshotDetailFlow(id).map { row ->
            if (row != null) {
                ScreenshotDetail(
                    item = ScreenshotItem(
                        id = row.id,
                        uri = row.uri,
                        displayName = row.displayName,
                        dateAdded = row.dateAdded,
                        sizeBytes = row.sizeBytes,
                        ocrText = row.ocrText,
                        isProcessed = row.isProcessed
                    ),
                    hasEmbedding = row.hasEmbedding
                )
            } else {
                null
            }
        }.flowOn(Dispatchers.Default)

    fun collectionMembershipFlow(id: Long): Flow<Map<String, String>> =
        dao.getMembershipsForScreenshotFlow(id).map { rows ->
            rows.associate { it.collectionId to it.source }
        }.flowOn(Dispatchers.Default)

    fun collectionFlow(collectionId: String): Flow<CollectionSummary?> =
        collectionSummariesFlow.map { summaries ->
            summaries.firstOrNull { it.id == collectionId }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun updateCollectionDescription(id: String, description: String) = withContext(Dispatchers.IO) {
        dao.updateCollectionDescription(id, description)
        refreshAutoCollections(onlyCollectionId = id)
    }

    suspend fun restoreAutoMembership(screenshotId: Long, collectionId: String, score: Float?) = withContext(Dispatchers.IO) {
        dao.upsertMembership(
            ScreenshotCollectionCrossRef(
                screenshotId = screenshotId,
                collectionId = collectionId,
                source = MembershipSource.AUTO,
                score = score
            )
        )
    }


    fun createDeletePendingIntent(uris: List<Uri>): PendingIntent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createDeleteRequest(contentResolver, uris)
        } else {
            null
        }
    }

    suspend fun removeDeletedScreenshots(ids: List<Long>) = withContext(Dispatchers.IO) {
        dao.deleteScreenshots(ids)
    }

    /**
     * Executes an instant hybrid search combining sparse lexical matching (SQLite FTS5 on OCR text)
     * and dense semantic matching (128-d cosine similarity on EmbeddingGemma 2 vectors)
     * fused using Reciprocal Rank Fusion (RRF).
     *
     * Degrades gracefully to lexical-only search if the embedding model is absent or not ready.
     *
     * @param query Search query from user.
     * @param limit Maximum number of results to return (defaults to 50).
     * @return List of [ScreenshotEntity] matching the query, ordered by fused ranking.
     */
    suspend fun hybridSearch(query: String, limit: Int = 50): List<ScreenshotEntity> = withContext(Dispatchers.IO) {
        if (query.isBlank() || limit <= 0) {
            return@withContext emptyList()
        }

        // 1. Sparse lexical search via FTS5
        val sanitizedQuery = sanitizeFtsQuery(query)
        val lexicalIds: List<Long> = if (sanitizedQuery.isBlank()) {
            emptyList()
        } else {
            try {
                dao.searchOcrIds(sanitizedQuery, limit)
            } catch (_: android.database.sqlite.SQLiteException) {
                emptyList()
            } catch (e: Exception) {
                if (e.javaClass.simpleName.contains("SQLiteException")) {
                    emptyList()
                } else {
                    throw e
                }
            }
        }

        // 2. Dense semantic search via EmbeddingGemma 2 (if model is Ready)
        val modelStore = modelStoreProvider()
        val embeddingEngine = embeddingEngineProvider()
        val isModelReady = modelStore != null && embeddingEngine != null &&
            (modelStore.state.value is ModelState.Ready || modelStore.isInstalled())

        val denseIds: List<Long> = if (isModelReady && embeddingEngine != null) {
            try {
                val queryEmbedding = embeddingEngine.embedText(query)
                val embeddingRows = dao.getAllEmbeddings()
                if (embeddingRows.isNotEmpty()) {
                    val candidates = embeddingRows.mapNotNull { row ->
                        try {
                            Pair(row.id, row.embedding.toFloatArray())
                        } catch (_: Exception) {
                            null
                        }
                    }
                    topKBySimilarity(queryEmbedding, candidates, limit).map { it.first }
                } else {
                    emptyList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                try {
                    Log.w("ScreenshotRepository", "Dense embedding search failed; falling back to lexical", e)
                } catch (_: Throwable) {}
                emptyList()
            }
        } else {
            emptyList()
        }

        // 3. Reciprocal Rank Fusion (RRF)
        val fusedIds = reciprocalRankFusion(
            rankings = listOf(lexicalIds, denseIds),
            limit = limit
        )

        if (fusedIds.isEmpty()) {
            return@withContext emptyList()
        }

        // 4. Map IDs back to entities, strictly preserving fused order
        val entities = dao.getScreenshotsByIds(fusedIds)
        val entityMap = entities.associateBy { it.id }
        fusedIds.mapNotNull { id -> entityMap[id] }
    }

    private fun ScreenshotEntity.toDomainModel(collections: List<String> = emptyList()): ScreenshotItem {
        return ScreenshotItem(
            id = id,
            uri = uri,
            displayName = displayName,
            dateAdded = dateAdded,
            sizeBytes = sizeBytes,
            ocrText = ocrText,
            visualLabels = visualLabels.split(",").filter { it.isNotBlank() },
            collections = collections,
            isProcessed = isProcessed,
            isJunk = isJunk,
            junkConfidence = junkConfidence,
            junkRationale = junkRationale
        )
    }

    suspend fun findCodeScreenshots(): List<CodeMatch> = withContext(Dispatchers.IO) {
        val ocrRows = dao.getOcrTexts()
        if (ocrRows.isEmpty()) return@withContext emptyList()

        val matchesWithKind = withContext(Dispatchers.Default) {
            ocrRows.mapNotNull { row ->
                val kind = looksLikeCode(row.ocrText)
                if (kind != null) row.id to kind else null
            }
        }

        if (matchesWithKind.isEmpty()) return@withContext emptyList()

        val ids = matchesWithKind.map { it.first }
        val entities = ids.chunked(500).flatMap { chunk ->
            dao.getScreenshotsByIds(chunk)
        }
        val entityMap = entities.associateBy { it.id }
        matchesWithKind.mapNotNull { (id, kind) ->
            entityMap[id]?.let { CodeMatch(entity = it, kind = kind) }
        }
    }

    suspend fun findDuplicateScreenshots(): List<ScreenshotEntity> = withContext(Dispatchers.IO) {
        val embeddedRows = dao.getEmbeddedRefs()
        if (embeddedRows.isEmpty()) return@withContext emptyList()

        val candidateIds = withContext(Dispatchers.Default) {
            val refs = embeddedRows.mapNotNull { row ->
                try {
                    val floats = row.embedding.toFloatArray()
                    if (floats.size == EMBEDDING_DIM) {
                        EmbeddedRef(id = row.id, dateAdded = row.dateAdded, embedding = floats)
                    } else null
                } catch (_: Exception) {
                    null
                }
            }
            val bursts = findBursts(refs)
            burstDeletionCandidates(bursts)
        }

        if (candidateIds.isEmpty()) return@withContext emptyList()

        val entities = candidateIds.chunked(500).flatMap { chunk ->
            dao.getScreenshotsByIds(chunk)
        }
        entities.sortedBy { it.dateAdded }
    }

    suspend fun mapToDomainModel(entities: List<ScreenshotEntity>): List<ScreenshotItem> = withContext(Dispatchers.IO) {
        if (entities.isEmpty()) return@withContext emptyList()
        val ids = entities.map { it.id }
        val membershipRows = ids.chunked(500).flatMap { chunk ->
            dao.getMembershipNamesForScreenshots(chunk)
        }
        val namesByScreenshot = membershipRows.groupBy({ it.screenshotId }, { it.name })
        entities.map { entity ->
            entity.toDomainModel(namesByScreenshot[entity.id] ?: emptyList())
        }
    }

    suspend fun exportCollections(): String = withContext(Dispatchers.IO) {
        val collections = dao.getAllCollections()
        val manualRows = dao.getManualBackupMemberships()
        val excludedRows = dao.getExcludedBackupMemberships()

        val keptByCollection = manualRows.groupBy({ it.collectionId }) {
            ScreenshotRef(
                displayName = it.displayName,
                dateAdded = it.dateAdded,
                sizeBytes = it.sizeBytes
            )
        }

        val excludedByCollection = excludedRows.groupBy({ it.collectionId }) {
            ScreenshotRef(
                displayName = it.displayName,
                dateAdded = it.dateAdded,
                sizeBytes = it.sizeBytes
            )
        }

        val backupCollections = collections.map { col ->
            BackupCollection(
                name = col.name,
                description = col.description,
                colorHex = col.colorHex,
                autoFill = col.autoFill,
                kept = keptByCollection[col.id] ?: emptyList(),
                excluded = excludedByCollection[col.id] ?: emptyList()
            )
        }

        encodeBackup(BackupFile(version = 1, collections = backupCollections))
    }

    suspend fun importCollections(json: String): Result<ImportSummary> = withContext(Dispatchers.IO) {
        val backup = decodeBackup(json).getOrElse { return@withContext Result.failure(it) }
        val existingCollections = dao.getAllCollections()
        val existingNames = existingCollections.map { it.name.trim().lowercase() }.toMutableSet()

        val candidateRows = dao.getAllCandidatesForBackup()
        val candidates = candidateRows.map {
            Candidate(
                id = it.id,
                displayName = it.displayName,
                dateAdded = it.dateAdded,
                sizeBytes = it.sizeBytes
            )
        }

        var collectionsCreated = 0
        var collectionsSkipped = 0
        var membershipsApplied = 0
        val autoFillIdsToRefresh = mutableListOf<String>()

        for (col in backup.collections) {
            val normalizedName = col.name.trim().lowercase()
            if (normalizedName in existingNames) {
                collectionsSkipped++
                continue
            }

            val newId = col.name.lowercase().replace("\\s+".toRegex(), "_") + "_" + System.currentTimeMillis() + "_" + (collectionsCreated + 1)
            dao.insertCollection(
                CollectionEntity(
                    id = newId,
                    name = col.name,
                    description = col.description,
                    colorHex = col.colorHex,
                    autoFill = col.autoFill
                )
            )
            collectionsCreated++
            existingNames.add(normalizedName)

            val keptIds = matchRefs(col.kept, candidates)
            for (id in keptIds) {
                keepInCollection(id, newId)
                membershipsApplied++
            }

            val excludedIds = matchRefs(col.excluded, candidates)
            for (id in excludedIds) {
                removeFromCollection(id, newId)
                membershipsApplied++
            }

            if (col.autoFill) {
                autoFillIdsToRefresh.add(newId)
            }
        }

        for (id in autoFillIdsToRefresh) {
            try {
                refreshAutoCollections(onlyCollectionId = id)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("ScreenshotRepository", "Failed to refresh auto collection $id during import", t)
            }
        }

        Result.success(
            ImportSummary(
                collectionsCreated = collectionsCreated,
                collectionsSkipped = collectionsSkipped,
                membershipsApplied = membershipsApplied
            )
        )
    }

    suspend fun reindexEverything() = withContext(Dispatchers.IO) {
        dao.resetAllScreenshotsForReindexing()
        dao.clearAllAutoMemberships()
    }

    suspend fun libraryStats(): LibraryStats = withContext(Dispatchers.IO) {
        LibraryStats(
            total = dao.countAll(),
            indexed = dao.countIndexed()
        )
    }
}

data class ImportSummary(
    val collectionsCreated: Int,
    val collectionsSkipped: Int,
    val membershipsApplied: Int
)

data class LibraryStats(
    val total: Int,
    val indexed: Int
)

data class CodeMatch(
    val entity: ScreenshotEntity,
    val kind: CodeKind
)

/**
 * Computes the list of database screenshot IDs that are no longer present in MediaStore.
 *
 * @param dbIds Current screenshot IDs existing in the local database.
 * @param mediaIds Screenshot IDs currently discovered in MediaStore.
 * @return List of stale IDs that should be removed from the local database.
 */
fun computeStaleIds(dbIds: Collection<Long>, mediaIds: Collection<Long>): List<Long> {
    val mediaSet = if (mediaIds is Set) mediaIds else mediaIds.toHashSet()
    return dbIds.filter { it !in mediaSet }.distinct()
}
