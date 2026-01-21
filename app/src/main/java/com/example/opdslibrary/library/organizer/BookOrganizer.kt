package com.example.opdslibrary.library.organizer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.opdslibrary.data.AppDatabase
import com.example.opdslibrary.data.library.Author
import com.example.opdslibrary.data.library.Book
import com.example.opdslibrary.data.library.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Organizes books into a structured folder hierarchy
 *
 * Structure:
 * Library Root/
 * ├── Authors/
 * │   ├── А/                          # Cyrillic first letter
 * │   │   └── Иванов Иван/
 * │   │       ├── Серия/
 * │   │       │   └── 01 - Книга.fb2.zip
 * │   │       └── Отдельная книга.fb2.zip
 * │   └── A/                          # Latin first letter
 * │       └── Author Name/
 * ├── Unknown/                        # Books with missing author
 * └── .booklib/                       # Database and index
 */
class BookOrganizer(private val context: Context) {

    companion object {
        private const val TAG = "BookOrganizer"
        private const val AUTHORS_DIR = "Authors"
        private const val UNKNOWN_DIR = "Unknown"
        private const val MAX_FILENAME_LENGTH = 200
    }

    private val database = AppDatabase.getDatabase(context)
    private val bookDao = database.bookDao()
    private val authorDao = database.authorDao()
    private val seriesDao = database.seriesDao()

    /**
     * Organize a book into the proper folder structure
     * @param book The book to organize
     * @param libraryRoot The root folder for the organized library (as DocumentFile from SAF)
     * @return Result containing the new path or error
     */
    suspend fun organizeBook(
        book: Book,
        libraryRoot: DocumentFile
    ): OrganizeResult = withContext(Dispatchers.IO) {
        try {
            // Get authors and series
            val authors = authorDao.getAuthorsForBook(book.id)
            val series = book.seriesId?.let { seriesDao.getSeriesById(it) }

            organizeBookInternal(book, authors, series, libraryRoot)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to organize book: ${book.title}", e)
            OrganizeResult.Error(book.filePath, e.message ?: "Unknown error")
        }
    }

    /**
     * Organize a book with pre-fetched metadata
     */
    suspend fun organizeBook(
        book: Book,
        authors: List<Author>,
        series: Series?,
        libraryRoot: DocumentFile
    ): OrganizeResult = withContext(Dispatchers.IO) {
        try {
            organizeBookInternal(book, authors, series, libraryRoot)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to organize book: ${book.title}", e)
            OrganizeResult.Error(book.filePath, e.message ?: "Unknown error")
        }
    }

    private suspend fun organizeBookInternal(
        book: Book,
        authors: List<Author>,
        series: Series?,
        libraryRoot: DocumentFile
    ): OrganizeResult {
        val primaryAuthor = authors.firstOrNull()

        // Build target folder path
        val targetFolderPath = buildTargetFolder(primaryAuthor, series)

        // Build target filename
        val targetFilename = buildFilename(book, series)

        // Create folder structure
        val targetDir = ensureDirectoryExists(libraryRoot, targetFolderPath)
            ?: return OrganizeResult.Error(book.filePath, "Failed to create directory: $targetFolderPath")

        // Check if target already exists
        val existingFile = targetDir.findFile(targetFilename)
        if (existingFile != null && existingFile.uri.toString() == book.filePath) {
            // Already in correct location
            return OrganizeResult.Success(book.filePath, book.filePath, alreadyOrganized = true)
        }

        // Handle duplicate filename
        val finalFilename = if (existingFile != null) {
            generateUniqueFilename(targetDir, targetFilename)
        } else {
            targetFilename
        }

        // Copy file to new location
        val sourceUri = Uri.parse(book.filePath)
        val mimeType = getMimeType(book.filePath)

        val newFile = targetDir.createFile(mimeType, finalFilename)
            ?: return OrganizeResult.Error(book.filePath, "Failed to create file: $finalFilename")

        // Copy content
        val copySuccess = copyFile(sourceUri, newFile.uri)
        if (!copySuccess) {
            newFile.delete()
            return OrganizeResult.Error(book.filePath, "Failed to copy file")
        }

        val newPath = newFile.uri.toString()

        // Update database
        bookDao.updateFilePathWithOriginal(
            bookId = book.id,
            newPath = newPath,
            originalPath = book.originalPath ?: book.filePath
        )

        // Try to delete original file
        try {
            val sourceDoc = DocumentFile.fromSingleUri(context, sourceUri)
            sourceDoc?.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete original file: ${book.filePath}", e)
        }

        Log.d(TAG, "Organized: ${book.title} -> $newPath")
        return OrganizeResult.Success(book.filePath, newPath)
    }

    private fun buildTargetFolder(author: Author?, series: Series?): String {
        val authorFolder = if (author != null) {
            val firstLetter = getFirstLetter(author.sortName)
            val authorName = sanitizeFolderName(author.getFolderName())
            "$AUTHORS_DIR/$firstLetter/$authorName"
        } else {
            UNKNOWN_DIR
        }

        return if (series != null) {
            val seriesName = sanitizeFolderName(series.name)
            "$authorFolder/$seriesName"
        } else {
            authorFolder
        }
    }

    private fun buildFilename(book: Book, series: Series?): String {
        val extension = getExtension(book.filePath)

        val prefix = if (series != null && book.seriesNumber != null) {
            val num = book.seriesNumber.toInt()
            "%02d - ".format(num)
        } else {
            ""
        }

        val title = sanitizeFilename(book.title)
        val filename = "$prefix$title.$extension"

        return if (filename.length > MAX_FILENAME_LENGTH) {
            val maxTitleLength = MAX_FILENAME_LENGTH - prefix.length - extension.length - 1
            "$prefix${title.take(maxTitleLength)}.$extension"
        } else {
            filename
        }
    }

    private fun getFirstLetter(name: String): String {
        val firstChar = name.trim().firstOrNull()?.uppercaseChar() ?: 'U'
        return when {
            firstChar in 'А'..'Я' || firstChar == 'Ё' -> firstChar.toString()
            firstChar in 'A'..'Z' -> firstChar.toString()
            firstChar.isDigit() -> "0-9"
            else -> "#"
        }
    }

    private fun sanitizeFolderName(name: String): String {
        return name
            .replace(Regex("[<>:\"/\\\\|?*]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(100)
    }

    private fun sanitizeFilename(name: String): String {
        return name
            .replace(Regex("[<>:\"/\\\\|?*]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun getExtension(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".fb2.zip") -> "fb2.zip"
            else -> path.substringAfterLast(".", "fb2")
        }
    }

    private fun getMimeType(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".fb2.zip") -> "application/zip"
            lower.endsWith(".fb2") -> "application/x-fictionbook+xml"
            lower.endsWith(".epub") -> "application/epub+zip"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".mobi") -> "application/x-mobipocket-ebook"
            lower.endsWith(".azw3") -> "application/x-mobi8-ebook"
            else -> "application/octet-stream"
        }
    }

    private fun ensureDirectoryExists(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        val parts = path.split("/").filter { it.isNotBlank() }

        for (part in parts) {
            val existing = current.findFile(part)
            current = if (existing != null && existing.isDirectory) {
                existing
            } else {
                current.createDirectory(part) ?: return null
            }
        }

        return current
    }

    private fun generateUniqueFilename(dir: DocumentFile, baseFilename: String): String {
        val nameWithoutExt = baseFilename.substringBeforeLast(".")
        val ext = baseFilename.substringAfterLast(".", "")

        var counter = 1
        var newFilename = baseFilename

        while (dir.findFile(newFilename) != null) {
            newFilename = if (ext.isNotEmpty()) {
                "${nameWithoutExt}_$counter.$ext"
            } else {
                "${nameWithoutExt}_$counter"
            }
            counter++
        }

        return newFilename
    }

    private fun copyFile(sourceUri: Uri, destUri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(destUri)?.use { output ->
                    input.copyTo(output)
                    true
                } ?: false
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file", e)
            false
        }
    }

    /**
     * Undo organization - move book back to original location
     */
    suspend fun undoOrganize(book: Book): Boolean = withContext(Dispatchers.IO) {
        if (book.originalPath == null) {
            Log.w(TAG, "No original path stored for book: ${book.title}")
            return@withContext false
        }

        try {
            val sourceUri = Uri.parse(book.filePath)
            val destUri = Uri.parse(book.originalPath)

            // Copy back to original location
            val success = copyFile(sourceUri, destUri)
            if (success) {
                // Update database
                bookDao.updateFilePath(book.id, book.originalPath)

                // Try to delete organized file
                try {
                    val sourceDoc = DocumentFile.fromSingleUri(context, sourceUri)
                    sourceDoc?.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not delete organized file", e)
                }

                Log.d(TAG, "Undid organization for: ${book.title}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to undo organization for: ${book.title}", e)
            false
        }
    }
}

/**
 * Result of an organize operation
 */
sealed class OrganizeResult {
    data class Success(
        val oldPath: String,
        val newPath: String,
        val alreadyOrganized: Boolean = false
    ) : OrganizeResult()

    data class Error(
        val path: String,
        val message: String
    ) : OrganizeResult()
}
