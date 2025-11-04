package com.example.opdslibrary.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The Room database for the application
 */
@Database(
    entities = [OpdsCatalog::class, OpdsFeedCache::class, FavoriteEntry::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun feedCacheDao(): OpdsFeedCacheDao
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "opds_library_database"
                )
                    .fallbackToDestructiveMigration()
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
            val defaultCatalogs = listOf(
                Triple("http://flibusta.is/opds", "Flibusta", "http://flibusta.is/favicon.ico"),
                Triple("https://m.gutenberg.org/ebooks.opds/", "Project Gutenberg", "https://www.gutenberg.org/gutenberg/favicon.ico"),
                Triple("https://manybooks.net/opds/index.php", "Manybooks", "https://manybooks.net/sites/default/files/favicon_3.ico"),
                Triple("https://www.smashwords.com/lexcycle/feed", "Smashwords", "https://www.smashwords.com/favicon.ico")
            )

            defaultCatalogs.forEachIndexed { index, (url, name, icon) ->
                if (catalogDao.catalogExists(url) == 0) {
                    catalogDao.insert(
                        OpdsCatalog(
                            url = url,
                            customName = null,
                            opdsName = name,
                            iconUrl = icon,
                            iconUpdated = null,
                            isDefault = (index == 0) // Only Flibusta is default
                        )
                    )
                }
            }
        }

        private suspend fun addDefaultCatalogs(catalogDao: CatalogDao) {
            // Flibusta (Russian books)
            catalogDao.insert(
                OpdsCatalog(
                    url = "http://flibusta.is/opds",
                    customName = null,
                    opdsName = "Flibusta",
                    iconUrl = "http://flibusta.is/favicon.ico",
                    iconUpdated = null,
                    isDefault = true
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

        private suspend fun addFlibustaCatalog(catalogDao: CatalogDao) {
            catalogDao.insert(
                OpdsCatalog(
                    url = "http://flibusta.is/opds",
                    customName = null,
                    opdsName = "Flibusta",
                    iconUrl = "http://flibusta.is/favicon.ico",
                    iconUpdated = null,
                    isDefault = true
                )
            )
        }
    }
}
