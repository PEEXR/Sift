package com.peexr.sift.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.peexr.sift.SiftApplication
import com.peexr.sift.data.local.MembershipSource
import kotlinx.coroutines.launch

class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DEBUG) {
            return
        }

        val app = context.applicationContext as? SiftApplication ?: run {
            Log.e(TAG, "Application context is not SiftApplication")
            return
        }

        val cmd = intent.getStringExtra("cmd")
        Log.i(TAG, "Received debug command: cmd=$cmd")

        val pendingResult = goAsync()
        app.appScope.launch {
            try {
                val repository = app.repository
                val dao = app.database.screenshotDao()

                when (cmd) {
                    "create" -> {
                        val name = intent.getStringExtra("name").orEmpty()
                        val desc = intent.getStringExtra("desc").orEmpty()
                        val filesRaw = intent.getStringExtra("files").orEmpty()
                        val fileNames = filesRaw.split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }

                        val collectionId = repository.createCollection(name, desc, DEFAULT_PALETTE_COLOR)

                        val matchingScreenshots = if (fileNames.isNotEmpty()) {
                            dao.getScreenshotsByDisplayNames(fileNames)
                        } else {
                            emptyList()
                        }

                        for (screenshot in matchingScreenshots) {
                            repository.addToCollection(screenshot.id, collectionId)
                        }

                        repository.refreshAutoCollections()
                        Log.i(
                            TAG,
                            "Command 'create' finished for '$name' ($collectionId): ${matchingScreenshots.size} / ${fileNames.size} files matched"
                        )
                    }
                    "refresh" -> {
                        repository.refreshAutoCollections()
                        Log.i(TAG, "Command 'refresh' finished")
                    }
                    "status" -> {
                        val totalScreenshots = dao.countAll()
                        val withEmbeddings = dao.countWithEmbedding()
                        val collections = dao.getAllCollections()
                        val allMemberships = dao.getAllMemberships()
                        val membershipsByCollection = allMemberships.groupBy { it.collectionId }

                        Log.i(
                            TAG,
                            "Status: screenshots=$totalScreenshots, withEmbeddings=$withEmbeddings, collections=${collections.size}"
                        )
                        for (col in collections) {
                            val list = membershipsByCollection[col.id] ?: emptyList()
                            val manual = list.count { it.source == MembershipSource.MANUAL }
                            val auto = list.count { it.source == MembershipSource.AUTO }
                            val excluded = list.count { it.source == MembershipSource.EXCLUDED }
                            Log.i(
                                TAG,
                                "Collection '${col.name}' (${col.id}): MANUAL=$manual, AUTO=$auto, EXCLUDED=$excluded"
                            )
                        }
                    }
                    else -> {
                        Log.w(TAG, "Unknown debug command: '$cmd'")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error executing debug command '$cmd'", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_DEBUG = "com.peexr.sift.DEBUG"
        private const val TAG = "Sift.Debug"
        private const val DEFAULT_PALETTE_COLOR = "#6750A4"
    }
}
