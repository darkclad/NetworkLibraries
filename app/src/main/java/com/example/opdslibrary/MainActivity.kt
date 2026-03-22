package com.example.opdslibrary

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.opdslibrary.ui.AppSettingsScreen
import com.example.opdslibrary.ui.CatalogScreen
import com.example.opdslibrary.ui.MainScreen
import com.example.opdslibrary.ui.StartScreen
import com.example.opdslibrary.ui.library.BookDetailScreen
import com.example.opdslibrary.ui.library.LibraryScreen
import com.example.opdslibrary.ui.theme.OpdsLibraryTheme
import com.example.opdslibrary.viewmodel.AppSettingsViewModel
import com.example.opdslibrary.viewmodel.CatalogViewModel
import com.example.opdslibrary.viewmodel.LibraryViewModel
import com.example.opdslibrary.viewmodel.StartScreenViewModel
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Fix for Samsung devices that set animator duration scale to 0
        // This ensures CircularProgressIndicator animations work
        ensureAnimationsEnabled()

        setContent {
            OpdsLibraryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(
                        onExit = { finish() }
                    )
                }
            }
        }
    }

    /**
     * Check if system animator duration scale is 0 and log a warning.
     * We can't change the system setting, but we can detect it.
     */
    private fun ensureAnimationsEnabled() {
        try {
            val scale = Settings.Global.getFloat(
                contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            if (scale == 0f) {
                android.util.Log.w(
                    "MainActivity",
                    "System animator duration scale is 0 - animations will be disabled. " +
                    "This is often caused by Samsung's Battery Guardian. " +
                    "Go to Developer Options and set Animator duration scale to 1x to fix."
                )
            }
        } catch (e: Exception) {
            // Ignore - settings may not be accessible
        }
    }
}

@Composable
fun AppNavigation(
    onExit: () -> Unit
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "main"
    ) {
        // Main screen with Library and Network Libraries entries
        composable("main") {
            MainScreen(
                onNetworkLibrariesClick = {
                    navController.navigate("network_libraries")
                },
                onLibraryClick = {
                    navController.navigate("library")
                },
                onSettingsClick = {
                    navController.navigate("app_settings")
                }
            )
        }

        // Application Settings screen
        composable("app_settings") {
            val appSettingsViewModel: AppSettingsViewModel = viewModel()
            AppSettingsScreen(
                viewModel = appSettingsViewModel,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        // Library screen
        composable("library") {
            val libraryViewModel: LibraryViewModel = viewModel()
            LibraryScreen(
                viewModel = libraryViewModel,
                onBack = {
                    navController.popBackStack()
                },
                onSettings = {
                    navController.navigate("app_settings")
                },
                onBookClick = { bookId ->
                    navController.navigate("book_detail/$bookId")
                }
            )
        }

        // Library screen with author filter
        composable("library/author/{authorId}") { backStackEntry ->
            val authorId = backStackEntry.arguments?.getString("authorId")?.toLongOrNull() ?: return@composable
            val libraryViewModel: LibraryViewModel = viewModel()

            LaunchedEffect(authorId) {
                libraryViewModel.selectAuthor(authorId)
            }

            LibraryScreen(
                viewModel = libraryViewModel,
                onBack = {
                    navController.popBackStack()
                },
                onSettings = {
                    navController.navigate("app_settings")
                },
                onBookClick = { bookId ->
                    navController.navigate("book_detail/$bookId")
                },
                isNavigatedFilter = true
            )
        }

        // Library screen with series filter
        composable("library/series/{seriesId}") { backStackEntry ->
            val seriesId = backStackEntry.arguments?.getString("seriesId")?.toLongOrNull() ?: return@composable
            val libraryViewModel: LibraryViewModel = viewModel()

            LaunchedEffect(seriesId) {
                libraryViewModel.selectSeries(seriesId)
            }

            LibraryScreen(
                viewModel = libraryViewModel,
                onBack = {
                    navController.popBackStack()
                },
                onSettings = {
                    navController.navigate("app_settings")
                },
                onBookClick = { bookId ->
                    navController.navigate("book_detail/$bookId")
                },
                isNavigatedFilter = true
            )
        }

        // Book Detail screen
        composable("book_detail/{bookId}") { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId")?.toLongOrNull() ?: return@composable
            val libraryViewModel: LibraryViewModel = viewModel()
            BookDetailScreen(
                viewModel = libraryViewModel,
                bookId = bookId,
                onBack = {
                    navController.popBackStack()
                },
                onNavigateToCatalog = { catalogId, url ->
                    val encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
                    navController.navigate("catalog_with_url/$catalogId/$encodedUrl")
                },
                onViewInCatalog = { catalogId, navHistoryJson ->
                    val encodedHistory = URLEncoder.encode(navHistoryJson, StandardCharsets.UTF_8.toString())
                    navController.navigate("catalog_with_history/$catalogId/$encodedHistory")
                },
                onShowAuthorBooks = { authorId ->
                    navController.navigate("library/author/$authorId")
                },
                onShowSeriesBooks = { seriesId ->
                    navController.navigate("library/series/$seriesId")
                }
            )
        }

        // Network Libraries screen (list of OPDS catalogs)
        composable("network_libraries") {
            val startViewModel: StartScreenViewModel = viewModel()
            StartScreen(
                viewModel = startViewModel,
                onCatalogSelected = { catalog ->
                    navController.navigate("catalog/${catalog.id}")
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        // Catalog browsing screen
        composable("catalog/{catalogId}") { backStackEntry ->
            val catalogId = backStackEntry.arguments?.getString("catalogId")?.toLongOrNull() ?: return@composable
            val catalogViewModel: CatalogViewModel = viewModel()

            // Initialize the catalog with the catalog ID
            catalogViewModel.initializeCatalogById(catalogId)

            CatalogScreen(
                viewModel = catalogViewModel,
                onBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else {
                        onExit()
                    }
                },
                onNavigateToLibrary = { authorId ->
                    navController.navigate("library/author/$authorId")
                }
            )
        }

        // Catalog with initial URL (for navigating from library book details to related OPDS links)
        composable("catalog_with_url/{catalogId}/{initialUrl}") { backStackEntry ->
            val catalogId = backStackEntry.arguments?.getString("catalogId")?.toLongOrNull() ?: return@composable
            val encodedUrl = backStackEntry.arguments?.getString("initialUrl") ?: return@composable
            val initialUrl = URLDecoder.decode(encodedUrl, StandardCharsets.UTF_8.toString())
            val catalogViewModel: CatalogViewModel = viewModel()

            // Initialize the catalog and navigate to the initial URL
            catalogViewModel.initializeCatalogByIdWithUrl(catalogId, initialUrl)

            CatalogScreen(
                viewModel = catalogViewModel,
                onBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else {
                        onExit()
                    }
                },
                onNavigateToLibrary = { authorId ->
                    navController.navigate("library/author/$authorId")
                }
            )
        }

        // Catalog with navigation history (for "View in Catalog" from library book details)
        composable("catalog_with_history/{catalogId}/{navHistory}") { backStackEntry ->
            val catalogId = backStackEntry.arguments?.getString("catalogId")?.toLongOrNull() ?: return@composable
            val encodedHistory = backStackEntry.arguments?.getString("navHistory") ?: return@composable
            val navHistoryJson = URLDecoder.decode(encodedHistory, StandardCharsets.UTF_8.toString())
            val catalogViewModel: CatalogViewModel = viewModel()

            // Initialize the catalog and restore navigation history
            catalogViewModel.initializeCatalogByIdWithHistory(catalogId, navHistoryJson)

            CatalogScreen(
                viewModel = catalogViewModel,
                onBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else {
                        onExit()
                    }
                },
                onNavigateToLibrary = { authorId ->
                    navController.navigate("library/author/$authorId")
                }
            )
        }
    }
}
