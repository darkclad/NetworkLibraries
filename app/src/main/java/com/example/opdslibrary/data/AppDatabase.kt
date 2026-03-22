package com.example.opdslibrary.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.opdslibrary.data.library.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The Room database for the application
 */
@Database(
    entities = [
        // OPDS entities
        OpdsCatalog::class,
        OpdsFeedCache::class,
        FavoriteEntry::class,
        // Library entities
        Book::class,
        Author::class,
        BookAuthor::class,
        Series::class,
        Genre::class,
        BookGenre::class,
        ScanFolder::class,
        // FTS entities
        BookSearchIndex::class,
        BookSearchFts::class,
        // Search history
        SearchHistory::class,
        // Last visited authors
        LastVisitedAuthor::class
    ],
    version = 18,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    // OPDS DAOs
    abstract fun catalogDao(): CatalogDao
    abstract fun feedCacheDao(): OpdsFeedCacheDao
    abstract fun favoriteDao(): FavoriteDao

    // Library DAOs
    abstract fun bookDao(): BookDao
    abstract fun authorDao(): AuthorDao
    abstract fun seriesDao(): SeriesDao
    abstract fun genreDao(): GenreDao
    abstract fun scanFolderDao(): ScanFolderDao
    abstract fun bookFtsDao(): BookFtsDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun lastVisitedAuthorDao(): LastVisitedAuthorDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration from version 6 to 7: Add Local Library tables
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create series table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS series (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_series_name ON series(name)")

                // Create books table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS books (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        filePath TEXT NOT NULL,
                        fileHash TEXT NOT NULL,
                        fileSize INTEGER NOT NULL,
                        fileModified INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        titleSort TEXT NOT NULL,
                        lang TEXT,
                        year INTEGER,
                        isbn TEXT,
                        description TEXT,
                        seriesId INTEGER,
                        seriesNumber REAL,
                        metadataSource TEXT NOT NULL,
                        indexedAt INTEGER NOT NULL,
                        needsReindex INTEGER NOT NULL DEFAULT 0,
                        originalPath TEXT,
                        coverPath TEXT,
                        addedAt INTEGER NOT NULL,
                        downloadedViaApp INTEGER NOT NULL DEFAULT 0,
                        catalogId INTEGER,
                        FOREIGN KEY(seriesId) REFERENCES series(id) ON DELETE SET NULL
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_books_filePath ON books(filePath)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_books_fileHash ON books(fileHash)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_books_seriesId ON books(seriesId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_books_needsReindex ON books(needsReindex)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_books_titleSort ON books(titleSort)")

                // Create authors table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS authors (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        firstName TEXT,
                        middleName TEXT,
                        lastName TEXT,
                        nickname TEXT,
                        sortName TEXT NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_authors_sortName ON authors(sortName)")

                // Create book_authors junction table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS book_authors (
                        bookId INTEGER NOT NULL,
                        authorId INTEGER NOT NULL,
                        role TEXT NOT NULL DEFAULT 'author',
                        PRIMARY KEY(bookId, authorId),
                        FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE,
                        FOREIGN KEY(authorId) REFERENCES authors(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_book_authors_authorId ON book_authors(authorId)")

                // Create genres table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS genres (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        parentId INTEGER,
                        fb2Code TEXT,
                        FOREIGN KEY(parentId) REFERENCES genres(id) ON DELETE SET NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_genres_name ON genres(name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_genres_fb2Code ON genres(fb2Code)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_genres_parentId ON genres(parentId)")

                // Create book_genres junction table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS book_genres (
                        bookId INTEGER NOT NULL,
                        genreId INTEGER NOT NULL,
                        PRIMARY KEY(bookId, genreId),
                        FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE,
                        FOREIGN KEY(genreId) REFERENCES genres(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_book_genres_genreId ON book_genres(genreId)")

                // Create scan_folders table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS scan_folders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        path TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        lastScan INTEGER,
                        fileCount INTEGER NOT NULL DEFAULT 0,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        addedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_scan_folders_path ON scan_folders(path)")
            }
        }

        /**
         * Migration from version 7 to 8: Add FTS search tables
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create book_search_index content table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS book_search_index (
                        bookId INTEGER PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        authors TEXT NOT NULL,
                        series TEXT NOT NULL,
                        description TEXT NOT NULL,
                        genres TEXT NOT NULL
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_book_search_index_bookId ON book_search_index(bookId)")

                // Create FTS4 virtual table - column order and content format must match Room's expectations
                db.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS book_search_fts
                    USING fts4(
                        title,
                        authors,
                        series,
                        description,
                        genres,
                        content=`book_search_index`
                    )
                """)
            }
        }

        /**
         * Migration from version 8 to 9: Recreate FTS table with correct format
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Drop and recreate FTS table with correct format
                db.execSQL("DROP TABLE IF EXISTS book_search_fts")
                db.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS book_search_fts
                    USING fts4(
                        title,
                        authors,
                        series,
                        description,
                        genres,
                        content=`book_search_index`
                    )
                """)
            }
        }

        /**
         * Migration from version 9 to 10: Add search history table
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS search_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        catalogId INTEGER NOT NULL,
                        query TEXT NOT NULL,
                        searchedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_search_history_catalogId ON search_history(catalogId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_search_history_searchedAt ON search_history(searchedAt)")
            }
        }

        /**
         * Migration from version 10 to 11: Add OPDS entry ID to books table
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add opdsEntryId column to books table
                db.execSQL("ALTER TABLE books ADD COLUMN opdsEntryId TEXT")
                // Create indexes for OPDS entry lookup
                db.execSQL("CREATE INDEX IF NOT EXISTS index_books_opdsEntryId ON books(opdsEntryId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_books_catalogId ON books(catalogId)")
            }
        }

        /**
         * Migration from version 11 to 12: Add OPDS updated timestamp to books table
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add opdsUpdated column to books table
                db.execSQL("ALTER TABLE books ADD COLUMN opdsUpdated INTEGER")
            }
        }

        /**
         * Migration from version 12 to 13: Add local icon path to catalogs table
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add iconLocalPath column to opds_catalogs table
                db.execSQL("ALTER TABLE opds_catalogs ADD COLUMN iconLocalPath TEXT")
            }
        }

        /**
         * Migration from version 13 to 14: Add OPDS related links to books table
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add opdsRelLinks column to books table (JSON string of related links)
                db.execSQL("ALTER TABLE books ADD COLUMN opdsRelLinks TEXT")
            }
        }

        /**
         * Migration from version 14 to 15: Add OPDS navigation history to books table
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add opdsNavigationHistory column to books table (JSON string of navigation history)
                db.execSQL("ALTER TABLE books ADD COLUMN opdsNavigationHistory TEXT")
            }
        }

        /**
         * Migration from version 15 to 16: Add scanFolderId to books table
         * Links books to their source scan folder for filtering disabled folders
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add scanFolderId column to books table
                db.execSQL("ALTER TABLE books ADD COLUMN scanFolderId INTEGER")
                // Create index for efficient filtering
                db.execSQL("CREATE INDEX IF NOT EXISTS index_books_scanFolderId ON books(scanFolderId)")
            }
        }

        /**
         * Migration from version 16 to 17: Add last visited authors table
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS last_visited_authors (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        catalogId INTEGER NOT NULL,
                        authorName TEXT NOT NULL,
                        url TEXT NOT NULL,
                        feedTitle TEXT NOT NULL,
                        navigationHistory TEXT NOT NULL,
                        visitedAt INTEGER NOT NULL
                    )
                """)
            }
        }

        /**
         * Migration from version 17 to 18: Add unique index on (opdsEntryId, catalogId)
         * and clean up duplicate book records
         */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Delete duplicate books keeping the one with the highest id per (opdsEntryId, catalogId)
                db.execSQL("""
                    DELETE FROM books
                    WHERE opdsEntryId IS NOT NULL
                      AND catalogId IS NOT NULL
                      AND id NOT IN (
                          SELECT MAX(id) FROM books
                          WHERE opdsEntryId IS NOT NULL AND catalogId IS NOT NULL
                          GROUP BY opdsEntryId, catalogId
                      )
                """)
                // Create unique index (NULLs are exempt from uniqueness in SQLite)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_books_opdsEntryId_catalogId ON books(opdsEntryId, catalogId)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "opds_library_database"
                )
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18)
                    .fallbackToDestructiveMigration(dropAllTables = true)  // Keep as fallback for older versions
                    .addCallback(DatabaseCallback())
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

    /**
     * Callback to populate the database with default data on first launch
     */
    private class DatabaseCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                CoroutineScope(Dispatchers.IO).launch {
                    populateDatabase(database.catalogDao())
                }
            }
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            // Ensure all default catalogs exist even after migrations
            INSTANCE?.let { database ->
                CoroutineScope(Dispatchers.IO).launch {
                    ensureDefaultCatalogsExist(database.catalogDao())
                }
            }
        }

        suspend fun populateDatabase(catalogDao: CatalogDao) {
            // Add default free OPDS catalogs
            addDefaultCatalogs(catalogDao)
        }

        suspend fun ensureDefaultCatalogsExist(catalogDao: CatalogDao) {
            // Ensure all 4 default catalogs exist
            // Format: url, name, icon, alternateUrl (null if none)
            data class CatalogInfo(val url: String, val name: String, val icon: String, val alternateUrl: String?)
            val defaultCatalogs = listOf(
                CatalogInfo("http://flibusta.is/opds", "Flibusta", "http://flibusta.is/favicon.ico", "http://flibusta.net/opds"),
                CatalogInfo("https://m.gutenberg.org/ebooks.opds/", "Project Gutenberg", "https://www.gutenberg.org/gutenberg/favicon.ico", null),
                CatalogInfo("https://manybooks.net/opds/index.php", "Manybooks", "https://manybooks.net/sites/default/files/favicon_3.ico", null),
                CatalogInfo("https://www.smashwords.com/lexcycle/feed", "Smashwords", "https://www.smashwords.com/favicon.ico", null)
            )

            defaultCatalogs.forEachIndexed { index, catalog ->
                if (catalogDao.catalogExists(catalog.url) == 0) {
                    catalogDao.insert(
                        OpdsCatalog(
                            url = catalog.url,
                            customName = null,
                            opdsName = catalog.name,
                            iconUrl = catalog.icon,
                            iconUpdated = null,
                            isDefault = (index == 0), // Only Flibusta is default
                            alternateUrl = catalog.alternateUrl
                        )
                    )
                } else if (catalog.alternateUrl != null) {
                    // Update alternate URL for existing catalogs if not set
                    val existingCatalog = catalogDao.getCatalogByUrl(catalog.url)
                    if (existingCatalog != null && existingCatalog.alternateUrl == null) {
                        catalogDao.updateAlternateUrl(catalog.url, catalog.alternateUrl)
                    }
                }
            }
        }

        private suspend fun addDefaultCatalogs(catalogDao: CatalogDao) {
            // Flibusta (Russian books)
            if (catalogDao.catalogExists("http://flibusta.is/opds") > 0) return
            catalogDao.insert(
                OpdsCatalog(
                    url = "http://flibusta.is/opds",
                    customName = null,
                    opdsName = "Flibusta",
                    iconUrl = "http://flibusta.is/favicon.ico",
                    iconUpdated = null,
                    isDefault = true,
                    alternateUrl = "http://flibusta.net/opds"
                )
            )

            // Project Gutenberg
            catalogDao.insert(
                OpdsCatalog(
                    url = "https://m.gutenberg.org/ebooks.opds/",
                    customName = null,
                    opdsName = "Project Gutenberg",
                    iconUrl = "https://www.gutenberg.org/gutenberg/favicon.ico",
                    iconUpdated = null,
                    isDefault = false
                )
            )

            // Manybooks
            catalogDao.insert(
                OpdsCatalog(
                    url = "https://manybooks.net/opds/index.php",
                    customName = null,
                    opdsName = "Manybooks",
                    iconUrl = "https://manybooks.net/sites/default/files/favicon_3.ico",
                    iconUpdated = null,
                    isDefault = false
                )
            )

            // Smashwords
            catalogDao.insert(
                OpdsCatalog(
                    url = "https://www.smashwords.com/lexcycle/feed",
                    customName = null,
                    opdsName = "Smashwords",
                    iconUrl = "https://www.smashwords.com/favicon.ico",
                    iconUpdated = null,
                    isDefault = false
                )
            )
        }

    }
}
