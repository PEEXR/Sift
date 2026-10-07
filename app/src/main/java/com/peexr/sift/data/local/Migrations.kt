package com.peexr.sift.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * SQL statements and Room migrations for AppDatabase v1 -> v2.
 *
 * Adds a 128-d embedding BLOB column to [ScreenshotEntity] and establishes an FTS5 external-content
 * virtual table over `ocrText` with automatic synchronization triggers.
 */

const val SQL_MIGRATION_1_2_ALTER_TABLE = "ALTER TABLE screenshots ADD COLUMN embedding BLOB"

const val SQL_CREATE_FTS_TABLE =
    "CREATE VIRTUAL TABLE IF NOT EXISTS screenshots_fts USING fts5(ocrText, content='screenshots', content_rowid='id', tokenize='unicode61 remove_diacritics 2')"

const val SQL_CREATE_TRIGGER_INSERT = """
CREATE TRIGGER IF NOT EXISTS screenshots_ai AFTER INSERT ON screenshots BEGIN
    INSERT INTO screenshots_fts(rowid, ocrText) VALUES (new.id, new.ocrText);
END
"""

const val SQL_CREATE_TRIGGER_DELETE = """
CREATE TRIGGER IF NOT EXISTS screenshots_ad AFTER DELETE ON screenshots BEGIN
    INSERT INTO screenshots_fts(screenshots_fts, rowid, ocrText) VALUES('delete', old.id, old.ocrText);
END
"""

const val SQL_CREATE_TRIGGER_UPDATE = """
CREATE TRIGGER IF NOT EXISTS screenshots_au AFTER UPDATE OF ocrText ON screenshots BEGIN
    INSERT INTO screenshots_fts(screenshots_fts, rowid, ocrText) VALUES('delete', old.id, old.ocrText);
    INSERT INTO screenshots_fts(rowid, ocrText) VALUES (new.id, new.ocrText);
END
"""

const val SQL_REBUILD_FTS_INDEX = "INSERT INTO screenshots_fts(screenshots_fts) VALUES('rebuild')"

/**
 * Creates the FTS5 virtual table and external-content sync triggers on the provided SQLite database.
 * Used during fresh database creation (Room callback) and during Room migration from v1 to v2.
 */
fun createFtsObjects(db: SupportSQLiteDatabase) {
    db.execSQL(SQL_CREATE_FTS_TABLE)
    db.execSQL(SQL_CREATE_TRIGGER_INSERT)
    db.execSQL(SQL_CREATE_TRIGGER_DELETE)
    db.execSQL(SQL_CREATE_TRIGGER_UPDATE)
}

/**
 * Migration from Database v1 to v2:
 * 1. Alter screenshots table to add the embedding BLOB column.
 * 2. Create the FTS5 virtual table and sync triggers.
 * 3. Backfill/rebuild existing rows into the FTS5 index.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(SQL_MIGRATION_1_2_ALTER_TABLE)
        createFtsObjects(db)
        db.execSQL(SQL_REBUILD_FTS_INDEX)
    }
}
