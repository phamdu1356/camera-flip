package com.phamdu.flipcamera.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CameraDatabaseMigrationTest {
    @Test
    fun migration1To2AddsGapsAndIndexesWithoutDroppingSegments() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "migration-test-${UUID.randomUUID()}.db"
        val segmentId = UUID.randomUUID().toString()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE segments (
                                id TEXT NOT NULL PRIMARY KEY,
                                session_id TEXT NOT NULL,
                                start_utc INTEGER NOT NULL,
                                end_utc INTEGER,
                                duration INTEGER NOT NULL,
                                codec TEXT NOT NULL,
                                width INTEGER NOT NULL,
                                height INTEGER NOT NULL,
                                audio INTEGER NOT NULL,
                                bytes INTEGER NOT NULL,
                                internal_name TEXT NOT NULL,
                                path TEXT NOT NULL,
                                status TEXT NOT NULL,
                                protected INTEGER NOT NULL,
                                revision INTEGER NOT NULL
                            )""".trimIndent()
                        )
                        db.execSQL("CREATE INDEX index_segments_session_id ON segments(session_id)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        try {
            val db = helper.writableDatabase
            db.execSQL(
                """INSERT INTO segments VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                arrayOf<Any?>(
                    segmentId, UUID.randomUUID().toString(), 1_000L, 2_000L, 1_000L,
                    "video/avc", 1280, 720, 0, 4_000L, "existing.mp4", "/private/existing.mp4",
                    "READY", 0, 2,
                ),
            )

            CameraDatabase.MIGRATION_1_2.migrate(db)

            db.query("SELECT id, status FROM segments WHERE id = ?", arrayOf(segmentId)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(segmentId, cursor.getString(0))
                assertEquals("READY", cursor.getString(1))
            }
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'gaps'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_segments_status_start_utc'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.query("PRAGMA table_info(gaps)", emptyArray()).use { cursor ->
                val columns = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.contains("start_utc"))
                assertTrue(!columns.contains("segment_id"))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migration2To3AddsMotionEventsWithoutChangingExistingRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "migration-test-${UUID.randomUUID()}.db"
        val segmentId = UUID.randomUUID().toString()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE segments (
                                id TEXT NOT NULL PRIMARY KEY,
                                session_id TEXT NOT NULL,
                                start_utc INTEGER NOT NULL,
                                end_utc INTEGER,
                                duration INTEGER NOT NULL,
                                codec TEXT NOT NULL,
                                width INTEGER NOT NULL,
                                height INTEGER NOT NULL,
                                audio INTEGER NOT NULL,
                                bytes INTEGER NOT NULL,
                                internal_name TEXT NOT NULL,
                                path TEXT NOT NULL,
                                status TEXT NOT NULL,
                                protected INTEGER NOT NULL,
                                revision INTEGER NOT NULL
                            )""".trimIndent()
                        )
                        db.execSQL("CREATE INDEX index_segments_session_id ON segments(session_id)")
                        db.execSQL("CREATE INDEX index_segments_start_utc ON segments(start_utc)")
                        db.execSQL("CREATE INDEX index_segments_status_start_utc ON segments(status, start_utc)")
                        db.execSQL("CREATE INDEX index_segments_protected_end_utc ON segments(protected, end_utc)")
                        db.execSQL(
                            """CREATE TABLE gaps (
                                id TEXT NOT NULL PRIMARY KEY,
                                session_id TEXT,
                                start_utc INTEGER NOT NULL,
                                end_utc INTEGER NOT NULL,
                                reason TEXT NOT NULL,
                                revision INTEGER NOT NULL
                            )""".trimIndent()
                        )
                        db.execSQL("CREATE INDEX index_gaps_start_utc ON gaps(start_utc)")
                        db.execSQL("CREATE INDEX index_gaps_session_id ON gaps(session_id)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        try {
            val db = helper.writableDatabase
            db.execSQL(
                """INSERT INTO segments VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                arrayOf<Any?>(
                    segmentId, UUID.randomUUID().toString(), 1_000L, 2_000L, 1_000L,
                    "video/avc", 1280, 720, 0, 4_000L, "existing.mp4", "/private/existing.mp4",
                    "READY", 0, 2,
                ),
            )

            CameraDatabase.MIGRATION_2_3.migrate(db)

            db.query("SELECT id, status FROM segments WHERE id = ?", arrayOf(segmentId)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(segmentId, cursor.getString(0))
                assertEquals("READY", cursor.getString(1))
            }
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'events'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_events_session_id_start_utc'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.query("PRAGMA table_info(events)", emptyArray()).use { cursor ->
                val columns = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.contains("segment_id"))
                assertTrue(columns.contains("status"))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }
}
