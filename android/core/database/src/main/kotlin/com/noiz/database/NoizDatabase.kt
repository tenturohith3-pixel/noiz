package com.noiz.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {

    @TypeConverter
    fun downloadStateToString(state: DownloadState): String = state.name

    @TypeConverter
    fun stringToDownloadState(value: String): DownloadState =
        runCatching { DownloadState.valueOf(value) }.getOrDefault(DownloadState.FAILED)

    /** Stored as an RFC 3339 UTC string so `ORDER BY` matches the server's ordering. */
    @TypeConverter
    fun hlcToString(value: Long): String = value.toString()

    @TypeConverter
    fun stringToHlc(value: String): Long = value.toLongOrNull() ?: 0L
}

@Database(
    entities = [
        TrackEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        RenditionEntity::class,
        DownloadEntity::class,
        OutboxEntity::class,
        PlayEventEntity::class,
        PlaybackStateEntity::class,
        FavoriteEntity::class,
        PaletteCacheEntity::class,
        SearchIndexEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NoizDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun albumDao(): AlbumDao
    abstract fun artistDao(): ArtistDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun downloadDao(): DownloadDao
    abstract fun outboxDao(): OutboxDao
    abstract fun playEventDao(): PlayEventDao
    abstract fun playbackStateDao(): PlaybackStateDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun paletteCacheDao(): PaletteCacheDao
    abstract fun searchDao(): SearchDao
    abstract fun mutationDao(): MutationDao

    // Phase 2 additions: whole-table reads for the index rebuild, rendition lookup and
    // non-paging observers. Declared here rather than folded into the original DAOs so
    // the Phase 1 files stay untouched.
    abstract fun trackExtraDao(): TrackExtraDao
    abstract fun albumExtraDao(): AlbumExtraDao
    abstract fun artistExtraDao(): ArtistExtraDao
    abstract fun playlistExtraDao(): PlaylistExtraDao
    abstract fun renditionDao(): RenditionDao

    companion object {
        const val NAME = "noiz.db"

        @Volatile
        private var instance: NoizDatabase? = null

        /**
         * The DB is process-wide singleton by design: playback, sync and the UI all
         * read the same instance so Room's invalidation tracker fans out one change.
         *
         * Opening two builders for the same file is legal but means two invalidation
         * trackers, two connection pools and two WAL writers; the UI could sit on a
         * stale snapshot while playback wrote through the other instance. Every
         * production caller goes through this accessor.
         */
        fun get(context: Context): NoizDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /** Creates a new instance. Tests use this; production uses [get]. */
        fun build(context: Context): NoizDatabase =
            Room.databaseBuilder(context, NoizDatabase::class.java, NAME)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // FTS4 is created by Room; nothing else to seed here.
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA foreign_keys = ON")
                        // Keeps the write-ahead log from growing unbounded on low storage.
                        db.execSQL("PRAGMA wal_autocheckpoint = 512")
                    }
                })
                // Downloads and outbox ops must survive a failed migration attempt.
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()

        /**
         * In-memory instance for Roborazzi/JVM tests. FTS4 needs `unicode61` with
         * `remove_diacritics=2`, which the bundled SQLite supports.
         */
        fun buildInMemory(context: Context): NoizDatabase =
            Room.inMemoryDatabaseBuilder(context, NoizDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
