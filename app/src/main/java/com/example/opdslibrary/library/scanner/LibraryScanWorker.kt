package com.example.opdslibrary.library.scanner

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.opdslibrary.data.AppDatabase
import com.example.opdslibrary.data.AppPreferences
import com.example.opdslibrary.data.library.*
import com.example.opdslibrary.library.organizer.BookOrganizer
import com.example.opdslibrary.library.organizer.OrganizeResult
import com.example.opdslibrary.library.parser.BookParserFactory
import com.example.opdslibrary.library.search.BookIndexData
import com.example.opdslibrary.library.search.BookSearchManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background worker for scanning library folders
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryScanWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "LibraryScanWorker"

        // Input keys
        const val KEY_FOLDER_ID = "folder_id"
        const val KEY_FULL_SCAN = "full_scan"
        const val KEY_SINGLE_FILE_URI = "single_file_uri"
        const val KEY_ORGANIZE_FILES = "organize_files"
        const val KEY_OPDS_ENTRY_ID = "opds_entry_id"
        const val KEY_OPDS_CATALOG_ID = "opds_catalog_id"
        const val KEY_OPDS_REL_LINKS = "opds_rel_links"
        const val KEY_OPDS_NAV_HISTORY = "opds_nav_history"

        // Output/Progress keys
        const val KEY_FOLDER_NAME = "folder_name"
        const val KEY_PROCESSED_COUNT = "processed_count"
        const val KEY_TOTAL_COUNT = "total_count"
        const val KEY_CURRENT_FILE = "current_file"
        const val KEY_STATUS = "status"

        // Status values
        const val STATUS_SCANNING = "scanning"
        const val STATUS_INDEXING = "indexing"
        const val STATUS_COMPLETE = "complete"
        const val STATUS_ERROR = "error"
    }

    private val database = AppDatabase.getDatabase(context)
    private val bookDao = database.bookDao()
    private val authorDao = database.authorDao()
    private val seriesDao = database.seriesDao()
    private val genreDao = database.genreDao()
    private val scanFolderDao = database.scanFolderDao()
    private val searchManager = BookSearchManager(context)
    private val appPreferences = AppPreferences(context)
    private val bookOrganizer = BookOrganizer(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Initialize search index
            searchManager.initialize()

            val folderId = inputData.getLong(KEY_FOLDER_ID, -1)
            val fullScan = inputData.getBoolean(KEY_FULL_SCAN, false)
            val singleFileUri = inputData.getString(KEY_SINGLE_FILE_URI)

            when {
                singleFileUri != null -> {
                    // Process a single file (e.g., newly downloaded book)
                    processSingleFile(Uri.parse(singleFileUri))
                }
                folderId > 0 -> {
                    // Scan specific folder
                    scanFolder(folderId, fullScan)
                }
                else -> {
                    // Scan all enabled folders
                    scanAllFolders(fullScan)
                }
            }

            // Commit search index
            searchManager.commit()

            Result.success(workDataOf(KEY_STATUS to STATUS_COMPLETE))
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed", e)
            Result.failure(workDataOf(
                KEY_STATUS to STATUS_ERROR,
                "error" to e.message
            ))
        }
    }

    private suspend fun scanAllFolders(fullScan: Boolean) {
        val folders = scanFolderDao.getEnabledFoldersOnce()
        Log.d(TAG, "Scanning ${folders.size} folders")

        folders.forEach { folder ->
            scanFolder(folder.id, fullScan)
        }
    }

    private suspend fun scanFolder(folderId: Long, fullScan: Boolean) {
        val folder = scanFolderDao.getFolderById(folderId) ?: return
        Log.d(TAG, "Scanning folder: ${folder.displayName}")

        setProgress(workDataOf(
            KEY_FOLDER_NAME to folder.displayName,
            KEY_STATUS to STATUS_SCANNING,
            KEY_PROCESSED_COUNT to 0,
            KEY_TOTAL_COUNT to 0
        ))

        val folderUri = Uri.parse(folder.path)
        val documentFile = DocumentFile.fromTreeUri(context, folderUri)

        if (documentFile == null || !documentFile.exists()) {
            Log.w(TAG, "Folder not accessible: ${folder.path}")
            return
        }

        // Get number of parallel workers from preferences
        val parallelWorkers = appPreferences.getScanParallelWorkersOnce()
        Log.d(TAG, "Using $parallelWorkers parallel workers for scanning")

        // Use atomic counters for thread-safe progress tracking
        val discoveredCount = AtomicInteger(0)
        val processedCount = AtomicInteger(0)

        // Mutex for database operations to prevent conflicts
        val dbMutex = Mutex()

        // Channel for producer-consumer pattern
        // Buffer size allows directory scanning to get ahead of processing
        val fileChannel = Channel<DocumentFile>(capacity = parallelWorkers * 4)

        coroutineScope {
            // Producer: Parallel directory traversal
            val producerJob = launch(Dispatchers.IO) {
                try {
                    collectFilesParallel(documentFile, fileChannel, discoveredCount, parallelWorkers)
                } finally {
                    fileChannel.close()
                    Log.d(TAG, "Directory scan complete. Found ${discoveredCount.get()} files")
                }
            }

            // Consumers: Process files as they arrive
            val consumers = List(parallelWorkers) { workerId ->
                launch(Dispatchers.IO) {
                    for (file in fileChannel) {
                        try {
                            processFileParallel(file, file.uri, folder.id, fullScan, dbMutex)
                            val currentCount = processedCount.incrementAndGet()
                            val totalFound = discoveredCount.get()

                            // Update progress every 5 files
                            if (currentCount % 5 == 0) {
                                setProgress(workDataOf(
                                    KEY_FOLDER_NAME to folder.displayName,
                                    KEY_PROCESSED_COUNT to currentCount,
                                    KEY_TOTAL_COUNT to totalFound,
                                    KEY_CURRENT_FILE to (file.name ?: ""),
                                    KEY_STATUS to STATUS_SCANNING
                                ))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Worker $workerId error processing file: ${file.name}", e)
                        }
                    }
                }
            }

            // Wait for producer to finish
            producerJob.join()

            // Wait for all consumers to finish
            consumers.forEach { it.join() }
        }

        val finalCount = processedCount.get()

        // Final progress update
        setProgress(workDataOf(
            KEY_FOLDER_NAME to folder.displayName,
            KEY_PROCESSED_COUNT to finalCount,
            KEY_TOTAL_COUNT to finalCount,
            KEY_STATUS to STATUS_SCANNING
        ))

        // Update folder stats
        scanFolderDao.updateScanStats(
            folderId = folder.id,
            lastScan = System.currentTimeMillis(),
            fileCount = finalCount
        )

        Log.d(TAG, "Folder scan complete: ${folder.displayName}, processed $finalCount files")
    }

    /**
     * Process a file in parallel-safe manner
     */
    private suspend fun processFileParallel(
        file: DocumentFile,
        uri: Uri,
        folderId: Long,
        fullScan: Boolean,
        dbMutex: Mutex
    ) {
        val path = uri.toString()
        val filename = file.name ?: return

        // Check if already indexed (read operation - safe without mutex)
        val existing = bookDao.getBookByPath(path)
        if (existing != null && !fullScan) {
            // Check if file was modified
            val lastModified = file.lastModified()
            if (lastModified <= existing.fileModified) {
                return  // No changes
            }
        }

        // Calculate file hash (CPU-bound, no mutex needed)
        val hash = FileHashCalculator.calculateHash(uri, context)

        // Check for duplicates by hash
        if (existing == null) {
            val duplicates = bookDao.getBooksByHash(hash)
            if (duplicates.isNotEmpty()) {
                Log.d(TAG, "Duplicate detected (by hash): $filename")
            }
        }

        // Parse metadata (CPU-bound, no mutex needed)
        val metadata = BookParserFactory.parseBook(uri, context)

        // Save to database (use mutex for write operations)
        val bookId = dbMutex.withLock {
            saveBookToDatabase(
                uri = uri,
                hash = hash,
                fileSize = file.length(),
                fileModified = file.lastModified(),
                metadata = metadata,
                existingId = existing?.id,
                scanFolderId = folderId
            )
        }

        // Get related data for indexing
        val book = bookDao.getBookById(bookId) ?: return
        val authors = authorDao.getAuthorsForBook(bookId)
        val series = book.seriesId?.let { seriesDao.getSeriesById(it) }
        val genres = genreDao.getGenresForBook(bookId)

        // Add to search index (mutex for thread safety)
        dbMutex.withLock {
            searchManager.indexBook(book, authors, series, genres)
        }
    }

    /**
     * Parallel directory traversal using coroutines.
     * Scans directories concurrently and streams files to the channel.
     */
    private suspend fun collectFilesParallel(
        rootDir: DocumentFile,
        fileChannel: Channel<DocumentFile>,
        discoveredCount: AtomicInteger,
        parallelism: Int
    ) {
        // Channel for directories to scan
        val dirChannel = Channel<DocumentFile>(capacity = Channel.UNLIMITED)

        // Semaphore to limit concurrent directory scans
        val dirSemaphore = Semaphore(parallelism)

        // Track active directory scan jobs
        val activeJobs = AtomicInteger(1) // Start with 1 for root dir

        coroutineScope {
            // Seed with root directory
            dirChannel.send(rootDir)

            // Directory scanner workers
            val scanners = List(parallelism) { workerId ->
                launch(Dispatchers.IO) {
                    while (true) {
                        val dir = try {
                            dirChannel.tryReceive().getOrNull()
                        } catch (e: Exception) {
                            null
                        }

                        if (dir == null) {
                            // No directory available, check if we should exit
                            if (activeJobs.get() == 0 && dirChannel.isEmpty) {
                                break
                            }
                            // Brief yield to allow other coroutines to add directories
                            yield()
                            continue
                        }

                        dirSemaphore.withPermit {
                            try {
                                val files = dir.listFiles()
                                for (file in files) {
                                    if (file.isDirectory) {
                                        // Add subdirectory to queue
                                        activeJobs.incrementAndGet()
                                        dirChannel.send(file)
                                    } else if (file.name != null && BookParserFactory.isSupported(file.name!!)) {
                                        // Send file to processing channel
                                        discoveredCount.incrementAndGet()
                                        fileChannel.send(file)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Error scanning directory: ${dir.name}", e)
                            } finally {
                                activeJobs.decrementAndGet()
                            }
                        }
                    }
                }
            }

            // Wait for all scanners to complete
            scanners.forEach { it.join() }
            dirChannel.close()
        }
    }

    /**
     * Legacy sequential file collection (kept for compatibility)
     */
    private fun collectFiles(dir: DocumentFile, files: MutableList<DocumentFile>) {
        dir.listFiles().forEach { file ->
            if (file.isDirectory) {
                collectFiles(file, files)
            } else if (file.name != null && BookParserFactory.isSupported(file.name!!)) {
                files.add(file)
            }
        }
    }

    private suspend fun processFile(
        file: DocumentFile,
        uri: Uri,
        folderId: Long,
        fullScan: Boolean
    ) {
        val path = uri.toString()
        val filename = file.name ?: return

        // Check if already indexed
        val existing = bookDao.getBookByPath(path)
        if (existing != null && !fullScan) {
            // Check if file was modified
            val lastModified = file.lastModified()
            if (lastModified <= existing.fileModified) {
                return  // No changes
            }
        }

        // Calculate file hash
        val hash = FileHashCalculator.calculateHash(uri, context)

        // Check for duplicates by hash
        if (existing == null) {
            val duplicates = bookDao.getBooksByHash(hash)
            if (duplicates.isNotEmpty()) {
                Log.d(TAG, "Duplicate detected (by hash): $filename")
                // Could add to duplicates table or just skip
            }
        }

        // Parse metadata
        val metadata = BookParserFactory.parseBook(uri, context)

        // Save to database
        val bookId = saveBookToDatabase(
            uri = uri,
            hash = hash,
            fileSize = file.length(),
            fileModified = file.lastModified(),
            metadata = metadata,
            existingId = existing?.id,
            scanFolderId = folderId
        )

        // Get related data for indexing
        val book = bookDao.getBookById(bookId) ?: return
        val authors = authorDao.getAuthorsForBook(bookId)
        val series = book.seriesId?.let { seriesDao.getSeriesById(it) }
        val genres = genreDao.getGenresForBook(bookId)

        // Add to search index
        searchManager.indexBook(book, authors, series, genres)
    }

    private suspend fun processSingleFile(uri: Uri) {
        Log.d(TAG, "Processing single file: $uri")

        // Get OPDS entry info if provided
        val opdsEntryId = inputData.getString(KEY_OPDS_ENTRY_ID)
        val catalogId = inputData.getLong(KEY_OPDS_CATALOG_ID, -1).takeIf { it > 0 }
        val opdsRelLinks = inputData.getString(KEY_OPDS_REL_LINKS)
        val opdsNavHistory = inputData.getString(KEY_OPDS_NAV_HISTORY)

        Log.d(TAG, "OPDS entry info: entryId=$opdsEntryId, catalogId=$catalogId, hasRelLinks=${opdsRelLinks != null}, hasNavHistory=${opdsNavHistory != null}")

        setProgress(workDataOf(
            KEY_STATUS to STATUS_SCANNING,
            KEY_CURRENT_FILE to uri.lastPathSegment
        ))

        val hash = FileHashCalculator.calculateHash(uri, context)

        // Get file info
        val fileSize: Long
        val fileModified: Long

        val documentFile = DocumentFile.fromSingleUri(context, uri)
        if (documentFile != null && documentFile.exists()) {
            fileSize = documentFile.length()
            fileModified = documentFile.lastModified()
        } else {
            // Try as regular file
            val file = uri.path?.let { File(it) }
            if (file != null && file.exists()) {
                fileSize = file.length()
                fileModified = file.lastModified()
            } else {
                fileSize = 0
                fileModified = System.currentTimeMillis()
            }
        }

        // Parse metadata
        val metadata = BookParserFactory.parseBook(uri, context)

        // Save to database
        val bookId = saveBookToDatabase(
            uri = uri,
            hash = hash,
            fileSize = fileSize,
            fileModified = fileModified,
            metadata = metadata,
            downloadedViaApp = true,
            opdsEntryId = opdsEntryId,
            catalogId = catalogId,
            opdsRelLinks = opdsRelLinks,
            opdsNavigationHistory = opdsNavHistory
        )

        // Get related data for indexing and organization
        val book = bookDao.getBookById(bookId) ?: return
        Log.d(TAG, "Book saved: id=$bookId, catalogId=${book.catalogId}, hasNavHistory=${book.opdsNavigationHistory != null}, navHistoryLen=${book.opdsNavigationHistory?.length ?: 0}")
        val authors = authorDao.getAuthorsForBook(bookId)
        val series = book.seriesId?.let { seriesDao.getSeriesById(it) }
        val genres = genreDao.getGenresForBook(bookId)

        // Add to search index
        searchManager.indexBook(book, authors, series, genres)

        // Organize the downloaded book into the library structure
        val organizeFiles = inputData.getBoolean(KEY_ORGANIZE_FILES, true)
        if (organizeFiles) {
            // Get the library root folder
            val libraryRootUri = appPreferences.getDownloadFolderUriOnce()
            if (libraryRootUri != null && libraryRootUri.startsWith("content://")) {
                val libraryRoot = DocumentFile.fromTreeUri(context, Uri.parse(libraryRootUri))
                if (libraryRoot != null) {
                    Log.d(TAG, "Organizing book: ${book.title}")
                    val organizeResult = bookOrganizer.organizeBook(book, authors, series, libraryRoot)

                    when (organizeResult) {
                        is OrganizeResult.Success -> {
                            if (!organizeResult.alreadyOrganized) {
                                Log.d(TAG, "Book organized: ${organizeResult.oldPath} -> ${organizeResult.newPath}")
                            } else {
                                Log.d(TAG, "Book already organized: ${organizeResult.oldPath}")
                            }
                        }
                        is OrganizeResult.Error -> {
                            Log.e(TAG, "Failed to organize book: ${organizeResult.message}")
                        }
                    }
                } else {
                    Log.w(TAG, "Cannot access library root folder for organization")
                }
            } else {
                Log.d(TAG, "No SAF library folder configured, skipping organization")
            }
        }

        setProgress(workDataOf(
            KEY_STATUS to STATUS_COMPLETE,
            KEY_PROCESSED_COUNT to 1
        ))
    }

    private suspend fun saveBookToDatabase(
        uri: Uri,
        hash: String,
        fileSize: Long,
        fileModified: Long,
        metadata: com.example.opdslibrary.library.parser.BookMetadata,
        existingId: Long? = null,
        scanFolderId: Long? = null,
        downloadedViaApp: Boolean = false,
        opdsEntryId: String? = null,
        catalogId: Long? = null,
        opdsRelLinks: String? = null,
        opdsNavigationHistory: String? = null
    ): Long {
        // Handle series
        val seriesId = metadata.series?.let { seriesInfo ->
            val existing = seriesDao.findByName(seriesInfo.name)
            existing?.id ?: seriesDao.insert(Series(name = seriesInfo.name))
        }

        // Create or update book
        val book = Book(
            id = existingId ?: 0,
            filePath = uri.toString(),
            fileHash = hash,
            fileSize = fileSize,
            fileModified = fileModified,
            title = metadata.title,
            titleSort = metadata.getTitleSort(),
            lang = metadata.language,
            year = metadata.year,
            isbn = metadata.isbn,
            description = metadata.description,
            seriesId = seriesId,
            seriesNumber = metadata.series?.number,
            scanFolderId = scanFolderId,
            metadataSource = BookParserFactory.getMetadataSource(uri.lastPathSegment ?: ""),
            indexedAt = System.currentTimeMillis(),
            needsReindex = false,
            downloadedViaApp = downloadedViaApp,
            catalogId = catalogId,
            opdsEntryId = opdsEntryId,
            opdsRelLinks = opdsRelLinks,
            opdsNavigationHistory = opdsNavigationHistory
        )

        val bookId = bookDao.insert(book)

        // Handle authors
        if (existingId != null) {
            authorDao.deleteBookAuthors(existingId)
        }

        metadata.authors.forEach { authorInfo ->
            val sortName = authorInfo.getSortName()
            val existingAuthor = authorDao.findBySortName(sortName)
            val authorId = existingAuthor?.id ?: authorDao.insert(
                Author(
                    firstName = authorInfo.firstName,
                    middleName = authorInfo.middleName,
                    lastName = authorInfo.lastName,
                    nickname = authorInfo.nickname,
                    sortName = sortName
                )
            )
            authorDao.insertBookAuthor(BookAuthor(
                bookId = bookId,
                authorId = authorId,
                role = authorInfo.role
            ))
        }

        // Handle genres
        if (existingId != null) {
            genreDao.deleteBookGenres(existingId)
        }

        metadata.genres.forEach { genreName ->
            val genreId = genreDao.findOrCreateByName(genreName)
            genreDao.insertBookGenre(BookGenre(bookId = bookId, genreId = genreId))
        }

        // Extract and save cover if available
        if (metadata.coverData != null) {
            saveCoverImage(bookId, metadata.coverData)
        }

        return bookId
    }

    private suspend fun saveCoverImage(bookId: Long, coverData: ByteArray) {
        try {
            val coversDir = File(context.filesDir, "covers").also { it.mkdirs() }
            val coverFile = File(coversDir, "cover_$bookId.jpg")
            coverFile.writeBytes(coverData)
            bookDao.updateCoverPath(bookId, coverFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cover for book $bookId", e)
        }
    }
}
