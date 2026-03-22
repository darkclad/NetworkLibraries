package com.example.opdslibrary.ui.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.opdslibrary.data.library.*
import com.example.opdslibrary.data.AppPreferences
import com.example.opdslibrary.ui.CustomSpinner
import com.example.opdslibrary.utils.BookOpener
import com.example.opdslibrary.viewmodel.LibraryViewModel
import com.example.opdslibrary.viewmodel.LibraryViewModel.BrowseMode
import com.example.opdslibrary.viewmodel.LibraryViewModel.SortOrder
import com.example.opdslibrary.viewmodel.LibraryViewModel.SortType
import java.io.File

/**
 * Main Library screen for browsing local books
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onBookClick: (Long) -> Unit,
    isNavigatedFilter: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsState()
    val books by viewModel.books.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val authors by viewModel.authors.collectAsState()
    val series by viewModel.series.collectAsState()
    val genres by viewModel.genres.collectAsState()
    val totalBooks by viewModel.totalBooks.collectAsState()
    val totalAuthors by viewModel.totalAuthors.collectAsState()
    val totalSeries by viewModel.totalSeries.collectAsState()

    // Handle system back button for internal browse navigation
    val needsInternalBack = !isNavigatedFilter && (
        uiState.browseMode != BrowseMode.ALL_BOOKS ||
        uiState.selectedAuthorId != null ||
        uiState.selectedSeriesId != null ||
        uiState.selectedGenreId != null
    )
    BackHandler(enabled = needsInternalBack) {
        when (uiState.browseMode) {
            BrowseMode.BY_AUTHOR, BrowseMode.BY_SERIES,
            BrowseMode.BY_GENRE, BrowseMode.RECENT -> {
                if (uiState.selectedAuthorId != null ||
                    uiState.selectedSeriesId != null ||
                    uiState.selectedGenreId != null) {
                    viewModel.setBrowseMode(uiState.browseMode)
                } else {
                    viewModel.setBrowseMode(BrowseMode.ALL_BOOKS)
                }
            }
            BrowseMode.SEARCH_RESULTS -> viewModel.setBrowseMode(BrowseMode.ALL_BOOKS)
            else -> {}
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showBrowseMenu by remember { mutableStateOf(false) }
    val searchHistory by viewModel.searchHistory.collectAsState()
    val suggestedHistory = remember(searchQuery, searchHistory) {
        if (searchQuery.isEmpty()) searchHistory
        else searchHistory.filter { it.contains(searchQuery, ignoreCase = true) }
    }

    // Live search: debounce 300ms so we don't hammer the search on every keystroke
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            viewModel.searchBooks("")
        } else {
            delay(300)
            viewModel.searchBooks(searchQuery)
        }
    }

    val context = LocalContext.current

    // Preferred reader app
    val appPreferences = remember { AppPreferences(context) }
    val preferredReaderPackage by appPreferences.preferredReaderPackage.collectAsState(initial = null)

    // Display list based on mode
    val displayBooks = when (uiState.browseMode) {
        BrowseMode.SEARCH_RESULTS -> searchResults
        else -> books
    }

    // Get selected item names for title
    val selectedAuthorName = remember(uiState.selectedAuthorId, authors) {
        uiState.selectedAuthorId?.let { id ->
            authors.find { it.author.id == id }?.author?.getDisplayNameLastFirst()
        }
    }
    val selectedSeriesName = remember(uiState.selectedSeriesId, series) {
        uiState.selectedSeriesId?.let { id ->
            series.find { it.series.id == id }?.series?.name
        }
    }
    val selectedGenreName = remember(uiState.selectedGenreId, genres) {
        uiState.selectedGenreId?.let { id ->
            genres.find { it.genre.id == id }?.genre?.name
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when (uiState.browseMode) {
                        BrowseMode.ALL_BOOKS -> Text("Library ($totalBooks)")
                        BrowseMode.BY_AUTHOR -> {
                            if (selectedAuthorName != null) {
                                Text(selectedAuthorName)
                            } else {
                                Text("Authors ($totalAuthors)")
                            }
                        }
                        BrowseMode.BY_SERIES -> {
                            if (selectedSeriesName != null) {
                                Text(selectedSeriesName)
                            } else {
                                Text("Series ($totalSeries)")
                            }
                        }
                        BrowseMode.BY_GENRE -> {
                            if (selectedGenreName != null) {
                                Text(selectedGenreName)
                            } else {
                                Text("Genres")
                            }
                        }
                        BrowseMode.RECENT -> Text("Recent")
                        BrowseMode.SEARCH_RESULTS -> Text("Search Results")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isNavigatedFilter) {
                            onBack()
                        } else {
                            when (uiState.browseMode) {
                                BrowseMode.ALL_BOOKS -> onBack()
                                BrowseMode.BY_AUTHOR, BrowseMode.BY_SERIES,
                                BrowseMode.BY_GENRE, BrowseMode.RECENT -> {
                                    if (uiState.selectedAuthorId != null ||
                                        uiState.selectedSeriesId != null ||
                                        uiState.selectedGenreId != null) {
                                        viewModel.setBrowseMode(uiState.browseMode)
                                    } else {
                                        viewModel.setBrowseMode(BrowseMode.ALL_BOOKS)
                                    }
                                }
                                BrowseMode.SEARCH_RESULTS -> viewModel.setBrowseMode(BrowseMode.ALL_BOOKS)
                            }
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Browse mode selector
                    Box {
                        IconButton(onClick = { showBrowseMenu = true }) {
                            Icon(Icons.Default.Menu, contentDescription = "Browse Mode")
                        }
                        DropdownMenu(
                            expanded = showBrowseMenu,
                            onDismissRequest = { showBrowseMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("All Books") },
                                onClick = {
                                    viewModel.setBrowseMode(BrowseMode.ALL_BOOKS)
                                    showBrowseMenu = false
                                },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("By Author ($totalAuthors)") },
                                onClick = {
                                    viewModel.setBrowseMode(BrowseMode.BY_AUTHOR)
                                    showBrowseMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Person, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("By Series ($totalSeries)") },
                                onClick = {
                                    viewModel.setBrowseMode(BrowseMode.BY_SERIES)
                                    showBrowseMenu = false
                                },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("By Genre") },
                                onClick = {
                                    viewModel.setBrowseMode(BrowseMode.BY_GENRE)
                                    showBrowseMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Info, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Recent") },
                                onClick = {
                                    viewModel.setBrowseMode(BrowseMode.RECENT)
                                    showBrowseMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.DateRange, null) }
                            )
                        }
                    }

                    // Sort type dropdown (always visible)
                    val availableSortTypes = viewModel.getAvailableSortTypes()
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Sort by")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            if (SortType.TITLE in availableSortTypes) {
                                DropdownMenuItem(
                                    text = { Text("Sort by Title") },
                                    onClick = {
                                        viewModel.setSortType(SortType.TITLE)
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (viewModel.getCurrentSortType() == SortType.TITLE) {
                                            Icon(Icons.Default.Check, null)
                                        }
                                    }
                                )
                            }
                            if (SortType.AUTHOR in availableSortTypes) {
                                DropdownMenuItem(
                                    text = { Text("Sort by Author") },
                                    onClick = {
                                        viewModel.setSortType(SortType.AUTHOR)
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (viewModel.getCurrentSortType() == SortType.AUTHOR) {
                                            Icon(Icons.Default.Check, null)
                                        }
                                    }
                                )
                            }
                            if (SortType.DATE_ADDED in availableSortTypes) {
                                DropdownMenuItem(
                                    text = { Text("Sort by Date Added") },
                                    onClick = {
                                        viewModel.setSortType(SortType.DATE_ADDED)
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (viewModel.getCurrentSortType() == SortType.DATE_ADDED) {
                                            Icon(Icons.Default.Check, null)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Ascending/Descending toggle (always visible)
                    IconButton(onClick = { viewModel.toggleSortDirection() }) {
                        Icon(
                            imageVector = if (viewModel.isSortAscending())
                                Icons.Default.KeyboardArrowUp
                            else
                                Icons.Default.KeyboardArrowDown,
                            contentDescription = if (viewModel.isSortAscending())
                                "Ascending"
                            else
                                "Descending"
                        )
                    }

                    // Settings
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search combo box
            @Suppress("DEPRECATION")
            ExposedDropdownMenuBox(
                expanded = dropdownExpanded && suggestedHistory.isNotEmpty(),
                onExpandedChange = { dropdownExpanded = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        dropdownExpanded = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                        .onFocusChanged { if (it.isFocused) dropdownExpanded = true },
                    placeholder = { Text("Search books...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, "Clear")
                            }
                        }
                    },
                    singleLine = true
                )
                ExposedDropdownMenu(
                    expanded = dropdownExpanded && suggestedHistory.isNotEmpty(),
                    onDismissRequest = { dropdownExpanded = false }
                ) {
                    suggestedHistory.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                searchQuery = item
                                dropdownExpanded = false
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { viewModel.removeSearchHistoryItem(item) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Close, "Remove", modifier = Modifier.size(16.dp))
                                }
                            }
                        )
                    }
                    if (suggestedHistory.size > 1) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Clear history",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                viewModel.clearSearchHistory()
                                dropdownExpanded = false
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete, null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }
            }

            // Content based on browse mode
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CustomSpinner()
                    }
                }
                uiState.browseMode == BrowseMode.BY_AUTHOR && uiState.selectedAuthorId == null -> {
                    // Show authors list
                    AuthorsList(
                        authors = authors,
                        onAuthorClick = { viewModel.selectAuthor(it.author.id) }
                    )
                }
                uiState.browseMode == BrowseMode.BY_SERIES && uiState.selectedSeriesId == null -> {
                    // Show series list
                    SeriesList(
                        seriesList = series,
                        onSeriesClick = { viewModel.selectSeries(it.series.id) }
                    )
                }
                uiState.browseMode == BrowseMode.BY_GENRE && uiState.selectedGenreId == null -> {
                    // Show genres list
                    GenresList(
                        genres = genres,
                        onGenreClick = { viewModel.selectGenre(it.genre.id) }
                    )
                }
                displayBooks.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "No books found",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.outline
                            )
                            if (uiState.browseMode == BrowseMode.ALL_BOOKS) {
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(onClick = onSettings) {
                                    Text("Add folders to scan")
                                }
                            }
                        }
                    }
                }
                else -> {
                    // Show books grid with pagination for RECENT mode
                    val isRecentMode = uiState.browseMode == BrowseMode.RECENT
                    Box(modifier = Modifier.fillMaxSize()) {
                        BooksGrid(
                            books = displayBooks,
                            onBookClick = { onBookClick(it.book.id) },
                            onOpenBook = { book ->
                                // Open book in external reader
                                BookOpener.openBook(
                                    context = context,
                                    filePath = book.book.filePath,
                                    preferredPackage = preferredReaderPackage
                                )
                            },
                            onDeleteBook = { book, deleteFile ->
                                viewModel.deleteBook(book.book.id, deleteFile)
                            },
                            hasMore = if (isRecentMode) uiState.hasMoreRecentBooks else false,
                            isLoadingMore = if (isRecentMode) uiState.isLoadingMoreRecent else false,
                            onLoadMore = if (isRecentMode) {{ viewModel.loadMoreRecentBooks() }} else null
                        )
                    }
                }
            }
        }
    }

    // Error snackbar
    uiState.errorMessage?.let { error ->
        LaunchedEffect(error) {
            // Show error and clear
            viewModel.clearError()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BooksGrid(
    books: List<BookWithDetails>,
    onBookClick: (BookWithDetails) -> Unit,
    onOpenBook: (BookWithDetails) -> Unit,
    onDeleteBook: (BookWithDetails, Boolean) -> Unit,
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    onLoadMore: (() -> Unit)? = null
) {
    // Remove duplicates by book ID (keep first occurrence)
    val uniqueBooks = remember(books) {
        books.distinctBy { it.book.id }
    }

    val gridState = rememberLazyGridState()

    // Build letter index map (letter/digit -> first book index with that character)
    // Includes letters from any alphabet, digits, and groups symbols as '#'
    val letterIndexMap = remember(uniqueBooks) {
        uniqueBooks.mapIndexedNotNull { index, book ->
            val firstChar = book.book.titleSort.firstOrNull()?.uppercaseChar()
            when {
                firstChar == null -> null
                firstChar.isLetter() -> firstChar to index  // Any alphabet letter
                firstChar.isDigit() -> firstChar to index   // Numbers 0-9
                else -> '#' to index  // Group all symbols as '#'
            }
        }.groupBy({ it.first }, { it.second })
            .mapValues { it.value.first() } // Take first occurrence of each character
    }

    // Detect when user scrolls near the bottom
    LaunchedEffect(gridState) {
        snapshotFlow {
            val lastVisibleItem = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = gridState.layoutInfo.totalItemsCount
            lastVisibleItem >= totalItems - 4 // Load more when 4 items from end
        }.collect { shouldLoadMore ->
            if (shouldLoadMore && hasMore && !isLoadingMore && onLoadMore != null) {
                onLoadMore()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        items(
            items = uniqueBooks,
            key = { it.book.id }
        ) { bookWithDetails ->
            var showDeleteDialog by remember { mutableStateOf(false) }

            BookCard(
                bookWithDetails = bookWithDetails,
                onClick = { onBookClick(bookWithDetails) },
                onLongClick = { showDeleteDialog = true }
            )

            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = { Text(bookWithDetails.book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    text = { Text("What would you like to do?") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onDeleteBook(bookWithDetails, true)
                                showDeleteDialog = false
                            }
                        ) {
                            Text("Delete with File", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = { showDeleteDialog = false }) {
                                Text("Cancel")
                            }
                            TextButton(
                                onClick = {
                                    onDeleteBook(bookWithDetails, false)
                                    showDeleteDialog = false
                                }
                            ) {
                                Text("Remove from Library")
                            }
                        }
                    }
                )
            }
        }

        // Loading more indicator
        if (isLoadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CustomSpinner(size = 32, strokeWidth = 3f)
                }
            }
        }

        // Load more button as fallback
        if (hasMore && !isLoadingMore && onLoadMore != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(
                    onClick = onLoadMore,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text("Load More")
                }
            }
        }
    }

        // Letter index scroller on the right
        if (letterIndexMap.isNotEmpty()) {
            LetterIndexScroller(
                letterIndexMap = letterIndexMap,
                gridState = gridState,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookCard(
    bookWithDetails: BookWithDetails,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val book = bookWithDetails.book
    val authors = bookWithDetails.authors

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Cover image
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (book.coverPath != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(File(book.coverPath))
                            .crossfade(true)
                            .build(),
                        contentDescription = book.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Placeholder
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            // Book info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (authors.isNotEmpty()) {
                    Text(
                        text = authors.firstOrNull()?.getDisplayName() ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthorsList(
    authors: List<AuthorWithBookCount>,
    onAuthorClick: (AuthorWithBookCount) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(authors, key = { it.author.id }) { authorWithCount ->
            ListItem(
                headlineContent = { Text(authorWithCount.author.getDisplayNameLastFirst()) },
                supportingContent = { Text("${authorWithCount.bookCount} books") },
                leadingContent = {
                    Icon(Icons.Default.Person, contentDescription = null)
                },
                modifier = Modifier.clickable { onAuthorClick(authorWithCount) }
            )
        }
    }
}

@Composable
private fun SeriesList(
    seriesList: List<SeriesWithBookCount>,
    onSeriesClick: (SeriesWithBookCount) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(seriesList, key = { it.series.id }) { seriesWithCount ->
            ListItem(
                headlineContent = { Text(seriesWithCount.series.name) },
                supportingContent = { Text("${seriesWithCount.bookCount} books") },
                leadingContent = {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                },
                modifier = Modifier.clickable { onSeriesClick(seriesWithCount) }
            )
        }
    }
}

@Composable
private fun GenresList(
    genres: List<GenreWithBookCount>,
    onGenreClick: (GenreWithBookCount) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(genres, key = { it.genre.id }) { genreWithCount ->
            ListItem(
                headlineContent = { Text(genreWithCount.genre.name) },
                supportingContent = { Text("${genreWithCount.bookCount} books") },
                leadingContent = {
                    Icon(Icons.Default.Info, contentDescription = null)
                },
                modifier = Modifier.clickable { onGenreClick(genreWithCount) }
            )
        }
    }
}

/**
 * Letter index scroller - supports drag-to-select with zoom bubble.
 * Finger drag tracks the letter under the finger (zoomed in bubble shown to the left),
 * and jumps the grid to the corresponding section. Tap still works too.
 */
@Composable
private fun LetterIndexScroller(
    letterIndexMap: Map<Char, Int>,
    gridState: LazyGridState,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val letters = remember(letterIndexMap) { letterIndexMap.keys.sorted() }
    if (letters.isEmpty()) return

    var draggingLetter by remember { mutableStateOf<Char?>(null) }
    var dragFraction by remember { mutableStateOf(0f) }
    var columnHeightPx by remember { mutableStateOf(0) }

    fun letterAt(fraction: Float): Char =
        letters[(fraction * letters.size).toInt().coerceIn(0, letters.size - 1)]

    fun jumpTo(letter: Char) {
        letterIndexMap[letter]?.let { idx ->
            coroutineScope.launch { gridState.scrollToItem(idx) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(28.dp)
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .onSizeChanged { columnHeightPx = it.height }
                .pointerInput(letters) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (columnHeightPx > 0) {
                            val f = (down.position.y / columnHeightPx).coerceIn(0f, 1f)
                            dragFraction = f
                            draggingLetter = letterAt(f)
                            jumpTo(draggingLetter!!)
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val ptr = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ptr.pressed) break
                            ptr.consume()
                            if (columnHeightPx > 0) {
                                val f = (ptr.position.y / columnHeightPx).coerceIn(0f, 1f)
                                dragFraction = f
                                val letter = letterAt(f)
                                if (letter != draggingLetter) {
                                    draggingLetter = letter
                                    jumpTo(letter)
                                }
                            }
                        }
                        draggingLetter = null
                    }
                },
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            letters.forEach { letter ->
                val isActive = letter == draggingLetter
                Text(
                    text = letter.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                )
            }
        }

        // Zoom bubble: floats to the left of the strip while dragging
        val activeLetter = draggingLetter
        if (activeLetter != null && columnHeightPx > 0) {
            val bubbleDp = 48.dp
            val bubblePx = with(density) { bubbleDp.toPx() }
            val rawY = dragFraction * columnHeightPx - bubblePx / 2
            val clampedY = rawY.coerceIn(0f, columnHeightPx - bubblePx)
            val yDp = with(density) { clampedY.toDp() }
            Box(
                modifier = Modifier
                    .size(bubbleDp)
                    .offset(x = (-54).dp, y = yDp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = activeLetter.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

