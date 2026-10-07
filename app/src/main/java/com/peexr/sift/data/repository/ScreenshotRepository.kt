package com.peexr.sift.data.repository

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.peexr.sift.data.api.JevApiClient
import com.peexr.sift.data.local.AppDatabase
import com.peexr.sift.data.local.CollectionEntity
import com.peexr.sift.data.local.ScreenshotCollectionCrossRef
import com.peexr.sift.data.local.ScreenshotEntity
import com.peexr.sift.data.model.JevQuestion
import com.peexr.sift.data.model.JevRequest
import com.peexr.sift.data.model.ScreenshotItem
import com.peexr.sift.data.perception.PerceptionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
    private val jevApiClient: JevApiClient
) {
    private val dao = database.screenshotDao()
    private val contentResolver: ContentResolver = context.contentResolver

    // Flow of all screenshots
    val allScreenshotsFlow: Flow<List<ScreenshotItem>> = dao.getAllScreenshotsFlow().map { entities ->
        entities.map { it.toDomainModel() }
    }

    // Flow of user collections
    val allCollectionsFlow: Flow<List<CollectionEntity>> = dao.getAllCollectionsFlow()

    // Flow of junk screenshots for Cleanup tab
    val junkScreenshotsFlow: Flow<List<ScreenshotItem>> = dao.getJunkScreenshotsFlow().map { entities ->
        entities.map { it.toDomainModel() }
    }

    val junkCountFlow: Flow<Int> = dao.getJunkCountFlow()
    val recoverableBytesFlow: Flow<Long> = dao.getRecoverableBytesFlow().map { it ?: 0L }

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

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "screenshot_$id.png"
                val date = cursor.getLong(dateColumn)
                val size = cursor.getLong(sizeColumn)
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
            dao.insertScreenshots(screenshots)
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

    suspend fun createCollection(name: String, description: String, colorHex: String) = withContext(Dispatchers.IO) {
        val id = name.lowercase().replace("\\s+".toRegex(), "_") + "_" + System.currentTimeMillis()
        dao.insertCollection(CollectionEntity(id, name, description, colorHex))
    }

    suspend fun deleteCollection(id: String) = withContext(Dispatchers.IO) {
        dao.deleteCollection(id)
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

    private suspend fun ScreenshotEntity.toDomainModel(): ScreenshotItem {
        val collections = dao.getCollectionsForScreenshot(id).map { it.name }
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
}
