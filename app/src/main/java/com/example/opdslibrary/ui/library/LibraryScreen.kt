package com.example.opdslibrary.ui.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.opdslibrary.data.library.*
import com.example.opdslibrary.data.AppPreferences
import com.example.opdslibrary.ui.CustomSpinner
import com.example.opdslibrary.utils.BookOpener
import com.example.opdslibrary.viewmodel.LibraryViewModel
import com.example.opdslibrary.viewmodel.LibraryViewModel.BrowseMode
import com.example.opdslibrary.viewmodel.LibraryViewModel.SortOrder
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
    onBookClick: (Long) -> Unit
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

    var searchQuery by remember { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }
    var showBrowseMenu by remember { mutableStateOf(false) }

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
                                leadingIcon = { Icon(Icons.Default.List, null) }
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
                                leadingIcon = { Icon(Icons.Default.List, null) }
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

                    // Sort order
                    if (uiState.browseMode == BrowseMode.ALL_BOOKS) {
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Sort")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Title A-Z") },
                                    onClick = {
                                        viewModel.setSortOrder(SortOrder.TITLE_ASC)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Title Z-A") },
                                    onClick = {
                                        viewModel.setSortOrder(SortOrder.TITLE_DESC)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Author A-Z") },
                                    onClick = {
                                        viewModel.setSortOrder(SortOrder.AUTHOR_ASC)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Date Added (New)") },
                                    onClick = {
                                        viewModel.setSortOrder(SortOrder.DATE_ADDED_DESC)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
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
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search books...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            viewModel.setBrowseMode(BrowseMode.ALL_BOOKS)
                        }) {
                            Icon(Icons.Default.Clear, "Clear")
                        }
                    }
                },
                singleLine = true
            )

            // Search button
            if (searchQuery.isNotEmpty()) {
                Button(
                    onClick = { viewModel.searchBooks(searchQuery) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Text("Search")
                }
                Spacer(modifier = Modifier.height(8.dp))
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
                        hasMore = if (isRecentMode) uiState.hasMoreRecentBooks else false,
                        isLoadingMore = if (isRecentMode) uiState.isLoadingMoreRecent else false,
                        onLoadMore = if (isRecentMode) {{ viewModel.loadMoreRecentBooks() }} else null
                    )
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

@Composable
private fun BooksGrid(
    books: List<BookWithDetails>,
    onBookClick: (BookWithDetails) -> Unit,
    onOpenBook: (BookWithDetails) -> Unit,
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    onLoadMore: (() -> Unit)? = null
) {
    // Remove duplicates by book ID (keep first occurrence)
    val uniqueBooks = remember(books) {
        books.distinctBy { it.book.id }
    }

    val gridState = rememberLazyGridState()

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
            BookCard(
                bookWithDetails = bookWithDetails,
                onClick = { onBookClick(bookWithDetails) },
                onLongClick = { onOpenBook(bookWithDetails) }
            )
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
                    Icon(Icons.Default.List, contentDescription = null)
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

private fun getMimeType(path: String): String {
    val lower = path.lowercase()
    return when {
        lower.endsWith(".fb2.zip") -> "application/zip"
        lower.endsWith(".fb2") -> "application/x-fictionbook+xml"
        lower.endsWith(".epub") -> "application/epub+zip"
        lower.endsWith(".pdf") -> "application/pdf"
        lower.endsWith(".mobi") -> "application/x-mobipocket-ebook"
        else -> "application/octet-stream"
    }
}
