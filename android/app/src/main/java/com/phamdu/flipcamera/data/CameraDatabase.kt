package com.phamdu.flipcamera.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Metadata survives upgrades: no destructive fallback is configured.
 *
 * Version 1 contained only segments. Version 2 retains every segment column/type and adds gaps
 * and query indexes. Version 3 adds completed motion events. Exported schemas belong in
 * app/schemas; future versions must provide and
 * register a real migration, validated against those schemas before release. Do not register
 * an empty migration as a placeholder. Migration never claims to verify media files.
 *
 * MIGRATION_1_2 targets the source-defined v1 schema that preceded this change: @Database version
 * 1, SegmentEntity only, exportSchema=false, index_segments_session_id on session_id. Its TEXT
 * columns id/session_id/codec/internal_name/path/status were non-null; start_utc/duration/width/
 * height/audio/bytes/protected/revision were non-null INTEGER; end_utc was nullable INTEGER;
 * id was the primary key. Kotlin defaults were not SQL defaults. Enum status still stores TEXT.
 * No exported v1 schema or on-device database has been validated here. Any deployed schema that
 * differs needs its own verified migration; Room must fail closed rather than destroy metadata.
 */
@Database(
    entities = [SegmentEntity::class, GapEntity::class, MotionEventEntity::class],
    version = 3,
    exportSchema = true
)
@TypeConverters(SegmentConverters::class, MotionEventConverters::class)
abstract class CameraDatabase : RoomDatabase() {
    abstract fun segmentDao(): SegmentDao
    abstract fun motionEventDao(): MotionEventDao

    companion object {
        @Volatile
        private var INSTANCE: CameraDatabase? = null

        private const val DATABASE_NAME = "flip_camera.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_segments_start_utc ON segments(start_utc)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_segments_status_start_utc ON segments(status, start_utc)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_segments_protected_end_utc ON segments(protected, end_utc)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS gaps (
                        id TEXT NOT NULL,
                        session_id TEXT,
                        start_utc INTEGER NOT NULL,
                        end_utc INTEGER NOT NULL,
                        reason TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_gaps_start_utc ON gaps(start_utc)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_gaps_session_id ON gaps(session_id)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS events (
                        id TEXT NOT NULL,
                        session_id TEXT,
                        segment_id TEXT,
                        start_utc INTEGER NOT NULL,
                        end_utc INTEGER NOT NULL,
                        event_type TEXT NOT NULL,
                        status TEXT NOT NULL,
                        peak_score REAL NOT NULL,
                        thumbnail_name TEXT,
                        revision INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_events_start_utc ON events(start_utc)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_events_session_id_start_utc ON events(session_id, start_utc)")
            }
        }

        fun getInstance(context: Context): CameraDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                CameraDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { INSTANCE = it }
        }
    }
}
