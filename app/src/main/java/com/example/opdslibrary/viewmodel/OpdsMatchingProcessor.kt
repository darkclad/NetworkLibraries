package com.example.opdslibrary.viewmodel

import android.util.Log
import com.example.opdslibrary.data.AppDatabase
import com.example.opdslibrary.data.AppPreferences
import com.example.opdslibrary.data.OpdsEntry
import com.example.opdslibrary.data.library.Author
import com.example.opdslibrary.data.library.Book
import com.example.opdslibrary.data.library.BookAuthor
import com.example.opdslibrary.utils.FilenameUtils
import com.example.opdslibrary.utils.FormatSelector
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Result of filename-based OPDS matching
 */
data class MatchingResult(
    val entryId: String,
    val status: CatalogViewModel.BookLibraryStatus,
    val matchedBookId: Long? = null
)

/**
 * Queue entry status for tracking matching progress
 */
enum class QueueStatus {
    QUEUED,      // Entry is in queue, waiting to be processed
    PROCESSING,  // Entry is currently being matched
    COMPLETED    // Matching completed (found or not found)
}

/**
 * Processes OPDS entries in background to find filename-based matches
 * Uses queue-based processing with coroutines and semaphore for concurrency control
 * Supports coordination with downloads to avoid duplicates
 * Uses context-aware matching based on OPDS page type
 */
class OpdsMatchingProcessor(
    private val database: AppDatabase,
    private val appPreferences: AppPreferences,
    private val catalogId: Long,
    private val scope: CoroutineScope,
    private val browsingContext: CatalogViewModel.OpdsBrowsingContext
) {
    companion object {
        private const val TAG = "OpdsMatchingProcessor"
        private const val MAX_CONCURRENT_QUERIES = 3
    }

    private val bookDao = database.bookDao()
    private val authorDao = database.authorDao()
    private val semaphore = Semaphore(MAX_CONCURRENT_QUERIES)
    private val processingQueue = Channel<Pair<OpdsEntry, Long?>>(Channel.UNLIMITED)

    private val _results = MutableStateFlow<Map<String, MatchingResult>>(emptyMap())
    val results: StateFlow<Map<String, MatchingResult>> = _results.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    // Track queue status for each entry
    private val queueStatus = mutableMapOf<String, QueueStatus>()
    private val queueStatusLock = Any()

    private var processingJob: Job? = null
    private var totalEntries = 0
    private var processedEntries = 0

    /**
     * Start background processing of queued entries
     */
    fun start() {
        if (processingJob?.isActive == true) return

        totalEntries = 0
        processedEntries = 0
        _isProcessing.value = false

        processingJob = scope.launch(Dispatchers.IO) {
            try {
                for ((entry, opdsUpdated) in processingQueue) {
                    _isProcessing.value = true

                    // Check if entry was cancelled while in queue
                    val status = synchronized(queueStatusLock) { queueStatus[entry.id] }
                    if (status == null) {
                        Log.d(TAG, "Entry ${entry.id} was cancelled, skipping")
                        continue
                    }

                    // Mark as processing
                    synchronized(queueStatusLock) {
                        queueStatus[entry.id] = QueueStatus.PROCESSING
                    }

                    // Process entries with concurrency limit
                    semaphore.withPermit {
                        processEntry(entry, opdsUpdated)
                        processedEntries++
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Processing job error", e)
                }
            } finally {
                _isProcessing.value = false
                Log.d(TAG, "Processing complete: $processedEntries entries")
            }
        }
        Log.d(TAG, "Started background processing")
    }

    /**
     * Queue an entry for background processing
     * Also performs quick check for existing OPDS ID match
     */
    fun queueEntry(entry: OpdsEntry, opdsUpdated: Long?) {
        totalEntries++
        Log.d(TAG, "=== QUEUE ENTRY START: ${entry.title} (id=${entry.id}) ===")
        scope.launch(Dispatchers.IO) {
            try {
                // Quick check - does this entry already have an OPDS ID match?
                Log.d(TAG, "  Checking OPDS ID match: entryId=${entry.id}, catalogId=$catalogId")
                val existsByOpdsId = bookDao.existsByOpdsEntry(entry.id, catalogId)
                Log.d(TAG, "  OPDS ID exists in DB: $existsByOpdsId")

                if (existsByOpdsId) {
                    // Fast path - already matched by OPDS ID
                    val book = bookDao.getBookByOpdsEntry(entry.id, catalogId)
                    val status = determineStatusForExistingBook(entry.id, opdsUpdated)
                    emitResult(entry.id, status, book?.id)
                    synchronized(queueStatusLock) {
                        queueStatus[entry.id] = QueueStatus.COMPLETED
                    }
                    Log.i(TAG, "  ✓ FAST PATH: entry=${entry.id}, bookId=${book?.id}, status=$status")
                } else {
                    // Mark as queued and send to processing queue
                    synchronized(queueStatusLock) {
                        queueStatus[entry.id] = QueueStatus.QUEUED
                    }
                    processingQueue.send(entry to opdsUpdated)
                    Log.i(TAG, "  → QUEUED for filename matching: entry=${entry.id}, title='${entry.title}'")
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "  ✗ ERROR queuing entry ${entry.id}", e)
                    emitResult(entry.id, CatalogViewModel.BookLibraryStatus.NOT_IN_LIBRARY)
                }
            }
        }
    }

    /**
     * Check if entry is queued or being processed
     * Used by downloader to coordinate with matcher
     */
    fun getQueueStatus(entryId: String): QueueStatus? {
        return synchronized(queueStatusLock) {
            queueStatus[entryId]
        }
    }

    /**
     * Remove entry from queue (called when download completes before matching)
     * Returns true if entry was removed, false if already processing or completed
     */
    fun cancelQueuedEntry(entryId: String): Boolean {
        synchronized(queueStatusLock) {
            val status = queueStatus[entryId]
            Log.i(TAG, "=== CANCEL QUEUE ENTRY: $entryId (current status: $status) ===")
            if (status == QueueStatus.QUEUED) {
                queueStatus.remove(entryId)
                Log.i(TAG, "  ✓ Cancelled (user initiated download)")
                return true
            }
            Log.d(TAG, "  → Cannot cancel (status: $status)")
            return false
        }
    }

    /**
     * Get matching result if available (for download coordination)
     */
    fun getMatchingResult(entryId: String): MatchingResult? {
        return _results.value[entryId]
    }

    /**
     * Process a single entry - attempt filename-based matching
     */
    private suspend fun processEntry(entry: OpdsEntry, opdsUpdated: Long?) {
        Log.d(TAG, "=== PROCESS ENTRY START: ${entry.title} (id=${entry.id}) ===")
        try {
            // Check if filename matching is enabled
            val matchingEnabled = appPreferences.getEnableFilenameMatchingOnce()
            Log.d(TAG, "  Filename matching enabled: $matchingEnabled")
            if (!matchingEnabled) {
                Log.d(TAG, "  ✗ SKIPPED: Matching disabled in settings")
                emitResult(entry.id, CatalogViewModel.BookLibraryStatus.NOT_IN_LIBRARY)
                synchronized(queueStatusLock) { queueStatus[entry.id] = QueueStatus.COMPLETED }
                return
            }

            // Get format priority for selecting best link
            val formatPriority = appPreferences.getFormatPriorityOnce()
            Log.d(TAG, "  Format priority: $formatPriority")

            // Select best acquisition link based on format preference
            val bestLink = FormatSelector.selectBestLink(entry.links, formatPriority)
            Log.d(TAG, "  Acquisition links: ${entry.links.size}, best link: ${bestLink?.href}")
            if (bestLink == null) {
                Log.d(TAG, "  ✗ NO ACQUISITION LINK for entry: ${entry.id}")
                emitResult(entry.id, CatalogViewModel.BookLibraryStatus.NOT_IN_LIBRARY)
                synchronized(queueStatusLock) { queueStatus[entry.id] = QueueStatus.COMPLETED }
                return
            }

            // Generate expected filename from entry title + format
            // This matches what the download code would create
            val format = FormatSelector.getFormatName(bestLink)
            val filename = FilenameUtils.generateExpectedFilename(entry, format)
            Log.d(TAG, "  Generated filename from entry: '$filename' (format=$format)")

            // Also try extracting from URL as fallback
            val filenameFromUrl = FilenameUtils.extractFilenameFromUrl(bestLink.href)
            if (filenameFromUrl != null) {
                Log.d(TAG, "  Also have filename from URL: '$filenameFromUrl'")
            }

            // Normalize OPDS entry title and author for matching
            val entryTitle = normalizeTitle(entry.title)
            // Use entry's own author; fall back to page-level context author (e.g. author page)
            val entryAuthor = entry.author?.name?.let { normalizeTitle(it) }
                ?: browsingContext.authorName?.let { normalizeTitle(it) }
                ?: ""
            Log.d(TAG, "  Entry: title='$entryTitle', author='$entryAuthor'")

            // Track which books matched by which criteria
            val candidateBooks = mutableListOf<Book>()
            val authorMatchedIds = mutableSetOf<Long>()
            val titleMatchedIds = mutableSetOf<Long>()
            val filenameMatchedIds = mutableSetOf<Long>()
            var authorNotFoundInDb = false

            // ── UNIFIED MATCHING STRATEGY ──────────────────────────────────────────
            // 1. Author known → find author's books, filter by title (strict) or filename.
            //    Author found but no title/filename overlap → reject (prevents wrong matches).
            // 2. Author absent / not in DB → global title + filename search.
            //    When author WAS specified but absent: require title AND filename to agree.
            // ──────────────────────────────────────────────────────────────────────

            Log.i(TAG, "  --- MATCHING ---")
            Log.i(TAG, "  ║ Entry: '${entry.title}'")
            Log.i(TAG, "  ║ Author: '$entryAuthor'")
            Log.i(TAG, "  ║ Title (norm): '$entryTitle'")
            Log.i(TAG, "  ║ Filename: '$filename' / URL: '$filenameFromUrl'")

            if (entryAuthor.isNotEmpty()) {
                val authorBooks = queryBooksByAuthor(entryAuthor)
                Log.d(TAG, "  Author query '$entryAuthor': ${authorBooks.size} books")

                if (authorBooks.isEmpty()) {
                    authorNotFoundInDb = true
                    Log.w(TAG, "  Author not in DB → falling through to global title/filename search")
                } else {
                    // Step 1a: strict title filter within author's books
                    if (entryTitle.isNotEmpty()) {
                        val titleMatches = authorBooks.filter { book ->
                            val similar = titlesSimilar(normalizeTitle(book.title), entryTitle, strictMode = true)
                            if (similar) Log.d(TAG, "    ✓ Title match: '${book.title}' (id=${book.id})")
                            similar
                        }
                        candidateBooks.addAll(titleMatches)
                        titleMatches.forEach { authorMatchedIds.add(it.id); titleMatchedIds.add(it.id) }
                        Log.i(TAG, "  → Author+Title matches: ${titleMatches.size}")
                    }

                    // Step 1b: filename filter within author's books (only when title gave nothing)
                    if (candidateBooks.isEmpty() && (filename.isNotEmpty() || filenameFromUrl != null)) {
                        val filenameMatches = authorBooks.filter { book ->
                            val normFn = FilenameUtils.normalizeBookFilename(book.filePath)
                            val mGen = filename.isNotEmpty() && FilenameUtils.normalizedFilenameMatches(normFn, filename)
                            val mUrl = filenameFromUrl != null && FilenameUtils.normalizedFilenameMatches(normFn, filenameFromUrl)
                            if (mGen || mUrl) Log.d(TAG, "    ✓ Filename match: '${book.filePath}' (id=${book.id})")
                            mGen || mUrl
                        }
                        candidateBooks.addAll(filenameMatches)
                        filenameMatches.forEach { authorMatchedIds.add(it.id); filenameMatchedIds.add(it.id) }
                        Log.i(TAG, "  → Author+Filename matches: ${filenameMatches.size}")
                    }

                    // Author found but nothing matched → reject to prevent wrong-author match
                    if (candidateBooks.isEmpty()) {
                        Log.w(TAG, "  ✗ Author '${entry.author?.name}' found in DB but no title/filename match → reject")
                        Log.w(TAG, "     Author books: ${authorBooks.take(5).joinToString { "'${it.title}'" }}")
                        emitResult(entry.id, CatalogViewModel.BookLibraryStatus.NOT_IN_LIBRARY)
                        synchronized(queueStatusLock) { queueStatus[entry.id] = QueueStatus.COMPLETED }
                        return
                    }
                }
            }

            // Global title + filename search (when no author candidates found above)
            if (candidateBooks.isEmpty()) {
                if (entryTitle.isNotEmpty()) {
                    val rawTitle = bookDao.getBooksByTitlePattern(entryTitle)
                    val titleMatches = rawTitle.filter { titlesSimilar(normalizeTitle(it.title), entryTitle) }
                    candidateBooks.addAll(titleMatches)
                    titleMatches.forEach { titleMatchedIds.add(it.id) }
                    Log.d(TAG, "  Global title search: ${titleMatches.size} matches")
                }
                if (filename.isNotEmpty()) {
                    val rawFn = bookDao.getBooksByFilenamePattern(filename)
                    val fnMatches = rawFn.filter {
                        FilenameUtils.normalizedFilenameMatches(FilenameUtils.normalizeBookFilename(it.filePath), filename)
                    }
                    candidateBooks.addAll(fnMatches)
                    fnMatches.forEach { filenameMatchedIds.add(it.id) }
                    Log.d(TAG, "  Global generated-filename search: ${fnMatches.size} matches")
                }
                if (filenameFromUrl != null) {
                    val rawUrl = bookDao.getBooksByFilenamePattern(filenameFromUrl)
                    val urlMatches = rawUrl.filter {
                        FilenameUtils.normalizedFilenameMatches(FilenameUtils.normalizeBookFilename(it.filePath), filenameFromUrl)
                    }
                    candidateBooks.addAll(urlMatches)
                    urlMatches.forEach { filenameMatchedIds.add(it.id) }
                    Log.d(TAG, "  Global URL-filename search: ${urlMatches.size} matches")
                }

                // Author was specified but not in DB: require title AND filename to agree
                if (authorNotFoundInDb) {
                    val overlap = titleMatchedIds.intersect(filenameMatchedIds)
                    if (overlap.isEmpty()) {
                        Log.w(TAG, "  ✗ Author absent from DB and title+filename don't overlap → reject")
                        emitResult(entry.id, CatalogViewModel.BookLibraryStatus.NOT_IN_LIBRARY)
                        synchronized(queueStatusLock) { queueStatus[entry.id] = QueueStatus.COMPLETED }
                        return
                    }
                    candidateBooks.removeIf { it.id !in overlap }
                    Log.d(TAG, "  ✓ Author-absent strict filter: ${overlap.size} book(s)")
                }
            }

            if (candidateBooks.isEmpty()) {
                Log.w(TAG, "  ✗ NO MATCHES for: filename='$filename', title='${entry.title}', author='$entryAuthor'")
                emitResult(entry.id, CatalogViewModel.BookLibraryStatus.NOT_IN_LIBRARY)
                synchronized(queueStatusLock) { queueStatus[entry.id] = QueueStatus.COMPLETED }
                return
            }

            // Score and pick best match
            val uniqueBooks = candidateBooks.distinctBy { it.id }
            Log.d(TAG, "  Candidates: ${uniqueBooks.size} (author=${authorMatchedIds.size}, title=${titleMatchedIds.size}, filename=${filenameMatchedIds.size})")

            val bestMatch = findBestMatch(
                uniqueBooks,
                entryTitle,
                filename,
                authorMatchedIds,
                titleMatchedIds,
                filenameMatchedIds
            )

            if (bestMatch == null) {
                Log.w(TAG, "  ✗ NO SUITABLE MATCH after scoring (entry: ${entry.id})")
                emitResult(entry.id, CatalogViewModel.BookLibraryStatus.NOT_IN_LIBRARY)
                synchronized(queueStatusLock) { queueStatus[entry.id] = QueueStatus.COMPLETED }
                return
            }

            Log.i(TAG, "  ✓✓✓ MATCH FOUND! ✓✓✓")
            Log.i(TAG, "    Entry: ${entry.id} - '${entry.title}'")
            Log.i(TAG, "    Book: ${bestMatch.id} - '${bestMatch.title}'")
            Log.i(TAG, "    File: ${bestMatch.filePath}")

            // Update book's OPDS fields to link it to this catalog entry
            updateBookOpdsFields(bestMatch, entry)

            // Determine status based on opdsUpdated timestamp
            val status = if (opdsUpdated == null) {
                CatalogViewModel.BookLibraryStatus.CURRENT
            } else {
                when (bookDao.isBookOutdated(entry.id, catalogId, opdsUpdated)) {
                    1 -> CatalogViewModel.BookLibraryStatus.OUTDATED
                    else -> CatalogViewModel.BookLibraryStatus.CURRENT
                }
            }

            Log.i(TAG, "    Status: $status")
            emitResult(entry.id, status, bestMatch.id)

        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(TAG, "Error processing entry ${entry.id}", e)
                emitResult(entry.id, CatalogViewModel.BookLibraryStatus.NOT_IN_LIBRARY)
            }
        } finally {
            // Mark as completed regardless of outcome
            synchronized(queueStatusLock) {
                queueStatus[entry.id] = QueueStatus.COMPLETED
            }
        }
    }

    /**
     * Update book's OPDS fields in database after a match.
     * Also updates the book's author metadata if the book has no real author (Unknown).
     */
    private suspend fun updateBookOpdsFields(book: Book, entry: OpdsEntry) {
        try {
            // Link this book to the OPDS entry
            bookDao.updateOpdsEntryId(book.id, entry.id, catalogId)

            val opdsUpdated = parseOpdsTimestamp(entry.updated)
            if (opdsUpdated != null) {
                bookDao.updateOpdsUpdated(book.id, opdsUpdated)
            }

            // Store related OPDS navigation links
            val relLinks = entry.links
                .filter { link ->
                    link.rel?.contains("author") == true ||
                    link.rel?.contains("series") == true ||
                    link.rel == "related" ||
                    link.rel == "http://opds-spec.org/facet"
                }
                .map { link ->
                    Book.OpdsRelLink(href = link.href, title = link.title, type = link.type, rel = link.rel)
                }
            if (relLinks.isNotEmpty()) {
                bookDao.updateOpdsRelLinks(book.id, Book.serializeOpdsRelLinks(relLinks))
            }

            // Update author from OPDS if the book has no real author stored
            val opdsAuthorName = entry.author?.name?.trim()
            if (!opdsAuthorName.isNullOrEmpty()) {
                val existingAuthors = authorDao.getAuthorsForBook(book.id)
                val hasRealAuthor = existingAuthors.any { author ->
                    !author.firstName.isNullOrEmpty() || !author.lastName.isNullOrEmpty() ||
                    (!author.nickname.isNullOrEmpty() && author.nickname != "Unknown")
                }
                if (!hasRealAuthor) {
                    val newAuthor = Author.fromFullName(opdsAuthorName)
                    // Find existing author by name, or insert new one
                    val existingAuthor = authorDao.findByNameParts(newAuthor.lastName, newAuthor.firstName, newAuthor.nickname)
                    val authorId = if (existingAuthor != null) {
                        existingAuthor.id
                    } else {
                        val inserted = authorDao.insert(newAuthor)
                        if (inserted == -1L) {
                            // IGNORE conflict — try to find by sortName
                            authorDao.findBySortName(newAuthor.sortName)?.id ?: return
                        } else {
                            inserted
                        }
                    }
                    // Replace old author links with the one from OPDS
                    authorDao.deleteBookAuthors(book.id)
                    authorDao.insertBookAuthor(BookAuthor(bookId = book.id, authorId = authorId))
                    Log.i(TAG, "  Updated author for book ${book.id} ('${book.title}') → '$opdsAuthorName' (authorId=$authorId)")
                }
            }

            Log.i(TAG, "Updated OPDS fields: bookId=${book.id}, entryId=${entry.id}, catalogId=$catalogId")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update OPDS fields for book ${book.id}", e)
        }
    }

    /**
     * Normalize title for matching (lowercase, remove extra spaces, remove common articles)
     */
    private fun normalizeTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("\\s+"), " ") // Replace multiple spaces with single space
            .trim()
            .removePrefix("the ")
            .removePrefix("an ")
        // "a " is intentionally NOT removed: it is a common first word in non-English
        // titles where it is not an article, causing false matches if stripped.
    }

    /**
     * Query books by author name, handling both "Last First" (Russian/Eastern) and
     * "First Last" (Western) name formats without needing to identify which word is the surname.
     *
     * Strategy: query each word of the name separately against all author DB fields, then
     * intersect the result sets. A book only survives if its author fields contain ALL words.
     *
     * Examples:
     *   "John Smith"          → intersect(books with "john") ∩ (books with "smith")
     *   "Иванов Антон"        → intersect(books with "иванов") ∩ (books with "антон")
     *   "J.R.R. Tolkien"      → intersect(books with "j.r.r.") ∩ (books with "tolkien")
     *
     * Falls back to last-word-only query when the intersection is empty (handles the case
     * where the first name is not stored in the DB but the surname is).
     */
    private suspend fun queryBooksByAuthor(authorName: String): List<Book> {
        if (authorName.isEmpty()) return emptyList()

        val parts = normalizeTitle(authorName).split(" ").filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()

        if (parts.size == 1) {
            Log.d(TAG, "  Querying by single author part: '${parts[0]}'")
            return bookDao.getBooksByAuthorPattern(parts[0])
        }

        // Query each name part separately, then intersect by book ID
        Log.d(TAG, "  Querying by all author parts: $parts")
        val candidateSets = parts.map { part -> bookDao.getBooksByAuthorPattern(part) }

        val fullIntersection = intersectBookSets(candidateSets)
        if (fullIntersection.isNotEmpty()) {
            Log.d(TAG, "    Intersection of ${parts.size} parts → ${fullIntersection.size} book(s)")
            return fullIntersection
        }

        // For 3+ parts, try dropping the last part — handles Russian "Last First Patronymic"
        // where the patronymic is not stored in the DB (e.g. "Денисов Константин Владимирович"
        // → try "Денисов Константин", which matches DB entry "Константин Денисов")
        if (parts.size >= 3) {
            val withoutLast = intersectBookSets(candidateSets.dropLast(1))
            if (withoutLast.isNotEmpty()) {
                Log.d(TAG, "    Intersection without patronymic (${parts.size - 1} parts) → ${withoutLast.size} book(s)")
                return withoutLast
            }
        }

        // Fall back to last word (likely surname in "First Last" Western format)
        val lastPart = parts.last()
        Log.d(TAG, "    No intersection, falling back to last-word query: '$lastPart'")
        return bookDao.getBooksByAuthorPattern(lastPart)
    }

    private fun intersectBookSets(sets: List<List<Book>>): List<Book> {
        if (sets.isEmpty()) return emptyList()
        val ids = sets.drop(1).fold(
            sets[0].map { it.id }.toSet()
        ) { acc, books -> acc.intersect(books.map { it.id }.toSet()) }
        return sets[0].filter { it.id in ids }
    }

    /**
     * Check if two titles are similar enough to be considered a match
     * @param strictMode If true, requires higher similarity (90%+) for book detail pages
     */
    private fun titlesSimilar(title1: String, title2: String, strictMode: Boolean = false): Boolean {
        // Exact match after normalization
        if (title1 == title2) return true

        // In strict mode: if both titles end in a number and those numbers differ,
        // they are different volumes in a series — reject immediately.
        // e.g. "ревизор 51" vs "ревизор 52" must NOT match.
        if (strictMode) {
            val num1 = title1.trimEnd().substringAfterLast(" ").toIntOrNull()
            val num2 = title2.trimEnd().substringAfterLast(" ").toIntOrNull()
            if (num1 != null && num2 != null && num1 != num2) return false
        }

        // Check if one contains the other (for cases like "Book" vs "Book: Subtitle").
        // Require the shorter title to be at least 8 characters to prevent short/numeric
        // tokens (e.g. "1", "II", "run") from falsely matching unrelated longer titles.
        val shorter = if (title1.length <= title2.length) title1 else title2
        if (shorter.length >= 8 && (title1.contains(title2) || title2.contains(title1))) return true

        // Check Levenshtein distance for typos/minor differences
        val distance = levenshteinDistance(title1, title2)
        val maxLength = maxOf(title1.length, title2.length)
        val similarity = 1.0 - (distance.toDouble() / maxLength)

        // Use stricter threshold for book detail pages, more lenient for collections
        val threshold = if (strictMode) 0.90 else 0.80
        return similarity >= threshold
    }

    /**
     * Calculate Levenshtein distance between two strings
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }

        return dp[len1][len2]
    }

    /**
     * Find the best match from candidate books based on author, title, and filename similarity
     * Prioritizes: Author (highest) → Title → Filename (lowest)
     * Prefers books without existing OPDS linkage
     */
    private fun findBestMatch(
        candidates: List<Book>,
        targetTitle: String,
        targetFilename: String?,
        authorMatchedIds: Set<Long>,
        titleMatchedIds: Set<Long>,
        filenameMatchedIds: Set<Long>
    ): Book? {
        if (candidates.isEmpty()) {
            Log.d(TAG, "      findBestMatch: No candidates")
            return null
        }
        if (candidates.size == 1) {
            Log.d(TAG, "      findBestMatch: Only one candidate, auto-selecting")
            return candidates.first()
        }

        // Score each candidate
        data class ScoredBook(val book: Book, val score: Int, val breakdown: String)
        Log.d(TAG, "      Scoring ${candidates.size} candidates...")

        val scored = candidates.map { book ->
            val scoreBreakdown = mutableListOf<String>()
            var score = 0

            // Author matching: highest priority (+200 points)
            if (book.id in authorMatchedIds) {
                score += 200
                scoreBreakdown.add("Author+200")
            }

            // Title similarity: +100 points for exact match, +50 for partial
            val bookTitle = normalizeTitle(book.title)
            when {
                bookTitle == targetTitle -> {
                    score += 100
                    scoreBreakdown.add("TitleExact+100")
                }
                bookTitle.contains(targetTitle) || targetTitle.contains(bookTitle) -> {
                    score += 50
                    scoreBreakdown.add("TitlePartial+50")
                }
                book.id in titleMatchedIds -> {
                    score += 25
                    scoreBreakdown.add("TitleQuery+25")
                }
            }

            // Filename exact match: +75 points
            if (targetFilename != null) {
                val bookFilename = FilenameUtils.normalizeBookFilename(book.filePath)
                if (bookFilename == targetFilename) {
                    score += 75
                    scoreBreakdown.add("FilenameExact+75")
                } else if (book.id in filenameMatchedIds) {
                    score += 25
                    scoreBreakdown.add("FilenameQuery+25")
                }
            }

            // Prefer books without existing OPDS linkage: +20 points
            if (book.opdsEntryId == null && book.catalogId == null) {
                score += 20
                scoreBreakdown.add("NoLink+20")
            }

            ScoredBook(book, score, scoreBreakdown.joinToString(", "))
        }

        // Log all scores
        scored.sortedByDescending { it.score }.take(5).forEach { scoredBook ->
            Log.d(TAG, "        Book ${scoredBook.book.id}: ${scoredBook.score} pts [${scoredBook.breakdown}] - '${scoredBook.book.title}'")
        }

        // Return book with highest score (must have at least some points)
        val best = scored.filter { it.score > 0 }
            .maxByOrNull { it.score }

        if (best != null) {
            Log.i(TAG, "      → WINNER: Book ${best.book.id} with ${best.score} pts")
        } else {
            Log.w(TAG, "      → NO WINNER: All scores were 0")
        }

        return best?.book
    }

    /**
     * Determine status for book that already exists by OPDS ID
     */
    private suspend fun determineStatusForExistingBook(
        entryId: String,
        opdsUpdated: Long?
    ): CatalogViewModel.BookLibraryStatus {
        if (opdsUpdated == null) {
            return CatalogViewModel.BookLibraryStatus.CURRENT
        }
        val dbResult = bookDao.isBookOutdated(entryId, catalogId, opdsUpdated)
        val book = bookDao.getBookByOpdsEntry(entryId, catalogId)
        Log.d(TAG, "  determineStatus: entryId=$entryId, opdsUpdated=$opdsUpdated, dbOpdsUpdated=${book?.opdsUpdated}, dbResult=$dbResult")
        return when (dbResult) {
            null -> CatalogViewModel.BookLibraryStatus.NOT_IN_LIBRARY
            1 -> CatalogViewModel.BookLibraryStatus.OUTDATED
            else -> CatalogViewModel.BookLibraryStatus.CURRENT
        }
    }

    /**
     * Parse OPDS timestamp (RFC 3339 format) to milliseconds
     */
    private fun parseOpdsTimestamp(timestamp: String?): Long? {
        if (timestamp.isNullOrBlank()) return null
        return try {
            java.time.Instant.parse(timestamp).toEpochMilli()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse timestamp: $timestamp", e)
            null
        }
    }

    /**
     * Emit result to StateFlow (thread-safe)
     */
    private fun emitResult(entryId: String, status: CatalogViewModel.BookLibraryStatus, bookId: Long? = null) {
        _results.value = _results.value + (entryId to MatchingResult(entryId, status, bookId))
    }

    /**
     * Re-check a specific entry (e.g., after download completes)
     * This immediately checks if the book now exists by OPDS ID
     */
    fun recheckEntry(entryId: String, opdsUpdated: Long?) {
        scope.launch(Dispatchers.IO) {
            try {
                val existsByOpdsId = bookDao.existsByOpdsEntry(entryId, catalogId)
                if (existsByOpdsId) {
                    val status = determineStatusForExistingBook(entryId, opdsUpdated)
                    val book = bookDao.getBookByOpdsEntry(entryId, catalogId)
                    emitResult(entryId, status, book?.id)
                    Log.d(TAG, "Recheck: entry=$entryId now in library, status=$status")
                } else {
                    emitResult(entryId, CatalogViewModel.BookLibraryStatus.NOT_IN_LIBRARY)
                    Log.d(TAG, "Recheck: entry=$entryId still not in library")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error rechecking entry $entryId", e)
            }
        }
    }

    /**
     * Stop processing and clear queue
     */
    fun stop() {
        processingJob?.cancel()
        processingQueue.cancel()
        _results.value = emptyMap()
        _isProcessing.value = false
        synchronized(queueStatusLock) {
            queueStatus.clear()
        }
        totalEntries = 0
        processedEntries = 0
        Log.d(TAG, "Stopped background processing")
    }

    /**
     * Clear results (when navigating to new feed)
     */
    fun clearResults() {
        _results.value = emptyMap()
        _isProcessing.value = false
        synchronized(queueStatusLock) {
            queueStatus.clear()
        }
        totalEntries = 0
        processedEntries = 0
        Log.d(TAG, "Cleared matching results")
    }
}
