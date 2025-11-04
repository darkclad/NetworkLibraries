package com.example.opdslibrary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.opdslibrary.ui.CatalogScreen
import com.example.opdslibrary.ui.StartScreen
import com.example.opdslibrary.ui.theme.OpdsLibraryTheme
import com.example.opdslibrary.viewmodel.CatalogViewModel
import com.example.opdslibrary.viewmodel.StartScreenViewModel
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
}

@Composable
fun AppNavigation(
    onExit: () -> Unit
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "start"
    ) {
        composable("start") {
            val startViewModel: StartScreenViewModel = viewModel()
            StartScreen(
                viewModel = startViewModel,
                onCatalogSelected = { catalog ->
                    navController.navigate("catalog/${catalog.id}")
                }
            )
        }

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
                }
            )
        }
    }
}
