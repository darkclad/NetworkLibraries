package com.example.opdslibrary.data.library

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for genres
 */
@Dao
interface GenreDao {

    // === Query Methods ===

    @Query("SELECT * FROM genres ORDER BY name ASC")
    fun getAllGenres(): Flow<List<Genre>>

    @Query("SELECT * FROM genres WHERE parentId IS NULL ORDER BY name ASC")
    fun getTopLevelGenres(): Flow<List<Genre>>

    @Query("SELECT * FROM genres WHERE parentId = :parentId ORDER BY name ASC")
    fun getChildGenres(parentId: Long): Flow<List<Genre>>

    @Query("""
        SELECT g.*, COUNT(bg.bookId) as bookCount
        FROM genres g
        INNER JOIN book_genres bg ON g.id = bg.genreId
        INNER JOIN books b ON bg.bookId = b.id
        LEFT JOIN scan_folders sf ON b.scanFolderId = sf.id
        WHERE b.scanFolderId IS NULL OR sf.enabled = 1
        GROUP BY g.id
        HAVING bookCount > 0
        ORDER BY g.name ASC
    """)
    fun getAllGenresWithBookCount(): Flow<List<GenreWithBookCount>>

    @Query("""
        SELECT g.*, COUNT(bg.bookId) as bookCount
        FROM genres g
        INNER JOIN book_genres bg ON g.id = bg.genreId
        INNER JOIN books b ON bg.bookId = b.id
        LEFT JOIN scan_folders sf ON b.scanFolderId = sf.id
        WHERE g.parentId IS NULL AND (b.scanFolderId IS NULL OR sf.enabled = 1)
        GROUP BY g.id
        HAVING bookCount > 0
        ORDER BY bookCount DESC
    """)
    fun getTopGenresWithBookCount(): Flow<List<GenreWithBookCount>>

    @Query("SELECT * FROM genres WHERE id = :genreId")
    suspend fun getGenreById(genreId: Long): Genre?

    @Query("SELECT * FROM genres WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): Genre?

    @Query("SELECT * FROM genres WHERE fb2Code = :code LIMIT 1")
    suspend fun findByFb2Code(code: String): Genre?

    @Query("""
        SELECT g.* FROM genres g
        INNER JOIN book_genres bg ON g.id = bg.genreId
        WHERE bg.bookId = :bookId
        ORDER BY g.name ASC
    """)
    suspend fun getGenresForBook(bookId: Long): List<Genre>

    @Query("SELECT COUNT(*) FROM genres")
    suspend fun getGenreCount(): Int

    // === Insert/Update/Delete ===

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(genre: Genre): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(genres: List<Genre>): List<Long>

    @Update
    suspend fun update(genre: Genre)

    @Delete
    suspend fun delete(genre: Genre)

    @Query("DELETE FROM genres WHERE id = :genreId")
    suspend fun deleteById(genreId: Long)

    // === Book-Genre Junction ===

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookGenre(bookGenre: BookGenre)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookGenres(bookGenres: List<BookGenre>)

    @Query("DELETE FROM book_genres WHERE bookId = :bookId")
    suspend fun deleteBookGenres(bookId: Long)

    // === Find or Create ===

    @Transaction
    suspend fun findOrCreateByFb2Code(code: String): Long {
        val existing = findByFb2Code(code)
        if (existing != null) return existing.id

        val name = Genre.getNameForFb2Code(code)
        val existingByName = findByName(name)
        if (existingByName != null) return existingByName.id

        return insert(Genre(name = name, fb2Code = code))
    }

    @Transaction
    suspend fun findOrCreateByName(name: String): Long {
        val existing = findByName(name)
        return existing?.id ?: insert(Genre(name = name))
    }

    // === Cleanup Orphans ===

    @Query("""
        DELETE FROM genres WHERE id NOT IN (
            SELECT DISTINCT genreId FROM book_genres
        )
    """)
    suspend fun deleteOrphanedGenres(): Int

    @Query("DELETE FROM genres")
    suspend fun deleteAll()

    @Query("DELETE FROM book_genres")
    suspend fun deleteAllBookGenres()
}
