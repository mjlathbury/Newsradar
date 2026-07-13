package com.newsradar.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Article::class, KeywordWeight::class, OutletState::class,
        EntityAffinity::class, ArticleEntity::class, ExplicitDislike::class,
        ReadHistory::class
    ],
    version = 6,
    exportSchema = false
)
abstract class NewsDatabase : RoomDatabase() {
    abstract fun newsDao(): NewsDao

    companion object {
        @Volatile private var INSTANCE: NewsDatabase? = null

        /** Additive migration 4 -> 5: create the read_history table (no data loss). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS read_history (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "articleId TEXT NOT NULL, " +
                    "title TEXT NOT NULL, " +
                    "summary TEXT NOT NULL, " +
                    "outletName TEXT NOT NULL, " +
                    "link TEXT NOT NULL, " +
                    "imageUrl TEXT, " +
                    "readAt INTEGER NOT NULL, " +
                    "UNIQUE(articleId))"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "index_read_history_articleId ON read_history(articleId)"
                )
            }
        }

        /** Additive migration 5 -> 6: add the user's per-provider read-quality
         *  rating column to outlet_state (no data loss; defaults to ""). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outlet_state ADD COLUMN readQuality TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): NewsDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NewsDatabase::class.java,
                    "newsradar.db"
                ).fallbackToDestructiveMigration()
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6).build().also { INSTANCE = it }
            }
    }
}
