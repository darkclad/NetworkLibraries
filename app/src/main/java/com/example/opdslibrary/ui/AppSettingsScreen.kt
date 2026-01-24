package com.example.opdslibrary.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.example.opdslibrary.data.library.ScanFolder
import com.example.opdslibrary.viewmodel.AppSettingsViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * Application Settings screen for managing app-wide settings
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    viewModel: AppSettingsViewModel,
    onBack: () -> Unit
) {
    val downloadFolderName by viewModel.downloadFolderName.collectAsState()
    val parallelWorkers by viewModel.parallelWorkers.collectAsState()
    val maxParallelWorkers = viewModel.maxParallelWorkers
    val formatPriority by viewModel.formatPriority.collectAsState()
    val imageCacheSize by viewModel.imageCacheSize.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val preferredReaderName by viewModel.preferredReaderName.collectAsState()

    // Library settings states
    val scanFolders by viewModel.scanFolders.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()

    val context = LocalContext.current

    var showDownloadFolderPickerDialog by remember { mutableStateOf(false) }
    var showFormatPriorityDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showReaderPickerDialog by remember { mutableStateOf(false) }
    var showScanFolderPickerDialog by remember { mutableStateOf(false) }
    var showDeleteFolderDialog by remember { mutableStateOf<ScanFolder?>(null) }
    var showClearLibraryDialog by remember { mutableStateOf(false) }

    // Folder picker launcher for download folder
    val downloadFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Take persistent permission
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)

            // Get display name
            val documentFile = DocumentFile.fromTreeUri(context, it)
            val displayName = documentFile?.name ?: "Unknown Folder"

            viewModel.setDownloadFolder(it, displayName)
        }
    }

    // Folder picker launcher for scan folders
    val scanFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Take persistent permission
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)

            // Get display name
            val documentFile = DocumentFile.fromTreeUri(context, it)
            val displayName = documentFile?.name ?: "Unknown Folder"

            viewModel.addScanFolder(it, displayName)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ==================== Library Section ====================
            item {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Scan folders header with add button
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Scan Folders",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    IconButton(onClick = { showScanFolderPickerDialog = true }) {
                        Icon(Icons.Default.Add, "Add Folder")
                    }
                }
            }

            // Scan folders list
            if (scanFolders.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "No folders added",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Add folders to scan for books",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(onClick = { showScanFolderPickerDialog = true }) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add Folder")
                            }
                        }
                    }
                }
            } else {
                items(scanFolders, key = { it.id }) { folder ->
                    ScanFolderCard(
                        folder = folder,
                        onToggle = { viewModel.toggleFolderEnabled(folder) },
                        onScan = { viewModel.scanFolder(folder) },
                        onDelete = { showDeleteFolderDialog = folder }
                    )
                }
            }

            // Scan actions (only show if folders exist)
            if (scanFolders.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { viewModel.startScan(fullScan = false) },
                            modifier = Modifier.weight(1f),
                            enabled = !isScanning
                        ) {
                            if (isScanning) {
                                CustomSpinner(size = 16, strokeWidth = 2f)
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text("Quick Scan")
                        }

                        OutlinedButton(
                            onClick = { viewModel.startScan(fullScan = true) },
                            modifier = Modifier.weight(1f),
                            enabled = !isScanning
                        ) {
                            Text("Full Scan")
                        }
                    }
                }

                // Scan progress
                if (isScanning || scanProgress.isScanning) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        scanProgress.folderName?.let { folderName ->
                                            Text(
                                                text = folderName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }

                                        if (scanProgress.totalCount > 0) {
                                            Text(
                                                text = "Scanning: ${scanProgress.processedCount} / ${scanProgress.totalCount}",
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                        } else {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CustomSpinner(size = 16, strokeWidth = 2f)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Counting files...",
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                        }

                                        scanProgress.currentFile?.let { fileName ->
                                            Text(
                                                text = fileName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    TextButton(onClick = { viewModel.cancelScans() }) {
                                        Text("Cancel")
                                    }
                                }

                                if (scanProgress.totalCount > 0) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = { scanProgress.progressPercent },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ==================== Downloads Section ====================
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Downloads",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Download folder setting
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showDownloadFolderPickerDialog = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Download Folder",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = downloadFolderName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Where OPDS downloads are saved",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Change folder",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Format priority setting
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showFormatPriorityDialog = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Format Priority",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = formatPriority.take(3).joinToString(", ") + if (formatPriority.size > 3) "..." else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Preferred format order for downloads",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit priority",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // ==================== Reader Section ====================
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Reader",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Preferred reader app setting
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showReaderPickerDialog = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Default Reader App",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = preferredReaderName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "App used to open books",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Change reader",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // ==================== Performance Section ====================
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Performance",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Parallel workers slider
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Parallel Workers",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "$parallelWorkers",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Text(
                            text = "Number of files to process simultaneously during library scan",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Slider(
                            value = parallelWorkers.toFloat(),
                            onValueChange = { viewModel.setParallelWorkers(it.toInt()) },
                            valueRange = 1f..maxParallelWorkers.toFloat(),
                            steps = maxParallelWorkers - 2,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "1",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$maxParallelWorkers (max)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ==================== Cache Section ====================
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Cache",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Image cache
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Image Cache",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = formatCacheSize(imageCacheSize),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Cached catalog icons and book covers",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { showClearCacheDialog = true },
                            enabled = imageCacheSize > 0
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }

            // ==================== Danger Zone ====================
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Danger Zone",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }

            item {
                OutlinedButton(
                    onClick = { showClearLibraryDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear Library Database")
                }
            }

            // Bottom padding
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Storage location picker dialog for download folder
    if (showDownloadFolderPickerDialog) {
        StorageLocationPickerDialog(
            onDismiss = { showDownloadFolderPickerDialog = false },
            onLocationSelected = { uri ->
                showDownloadFolderPickerDialog = false
                downloadFolderPickerLauncher.launch(uri)
            }
        )
    }

    // Storage location picker dialog for scan folders
    if (showScanFolderPickerDialog) {
        StorageLocationPickerDialog(
            onDismiss = { showScanFolderPickerDialog = false },
            onLocationSelected = { uri ->
                showScanFolderPickerDialog = false
                scanFolderPickerLauncher.launch(uri)
            }
        )
    }

    // Format priority dialog
    if (showFormatPriorityDialog) {
        FormatPriorityDialog(
            currentPriority = formatPriority,
            onDismiss = { showFormatPriorityDialog = false },
            onSave = { newPriority ->
                viewModel.setFormatPriority(newPriority)
                showFormatPriorityDialog = false
            },
            onReset = {
                viewModel.resetFormatPriority()
                showFormatPriorityDialog = false
            }
        )
    }

    // Clear cache confirmation dialog
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear Image Cache") },
            text = {
                Text("This will delete all cached catalog icons and book covers. They will be re-downloaded when needed.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearImageCache()
                        showClearCacheDialog = false
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Reader picker dialog
    if (showReaderPickerDialog) {
        ReaderPickerDialog(
            onDismiss = { showReaderPickerDialog = false },
            onReaderSelected = { packageName, displayName ->
                viewModel.setPreferredReader(packageName, displayName)
                showReaderPickerDialog = false
            }
        )
    }

    // Delete folder confirmation
    showDeleteFolderDialog?.let { folder ->
        AlertDialog(
            onDismissRequest = { showDeleteFolderDialog = null },
            title = { Text("Remove Folder") },
            text = {
                Text("Remove \"${folder.displayName}\" from scan folders? Books from this folder will remain in the library.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeScanFolder(folder)
                    showDeleteFolderDialog = null
                }) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteFolderDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear library confirmation
    if (showClearLibraryDialog) {
        AlertDialog(
            onDismissRequest = { showClearLibraryDialog = false },
            title = { Text("Clear Library Database") },
            text = {
                Column {
                    Text(
                        "This will permanently remove all books from the library database.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "- All book metadata will be deleted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "- Your book files will NOT be deleted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "- Scan folders will be preserved",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Are you sure you want to continue?",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearLibrary()
                        showClearLibraryDialog = false
                    }
                ) {
                    Text("Clear Library", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearLibraryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Error snackbar
    errorMessage?.let { error ->
        LaunchedEffect(error) {
            viewModel.clearError()
        }
    }
}

/**
 * Card displaying a scan folder with controls
 */
@Composable
private fun ScanFolderCard(
    folder: ScanFolder,
    onToggle: () -> Unit,
    onScan: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )

                    if (folder.lastScan != null) {
                        Text(
                            text = "Last scan: ${formatDate(folder.lastScan)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (folder.fileCount > 0) {
                        Text(
                            text = "${folder.fileCount} books",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Switch(
                    checked = folder.enabled,
                    onCheckedChange = { onToggle() }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onScan,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Scan", style = MaterialTheme.typography.bodySmall)
                }

                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * Dialog that shows available storage locations to start browsing from
 */
@Composable
private fun StorageLocationPickerDialog(
    onDismiss: () -> Unit,
    onLocationSelected: (Uri?) -> Unit
) {
    val context = LocalContext.current

    // Get available storage locations
    val storageLocations = remember {
        buildList {
            // Internal storage common folders
            add(StorageLocation(
                name = "Downloads",
                path = "primary:Download",
                icon = Icons.Default.KeyboardArrowDown
            ))
            add(StorageLocation(
                name = "Documents",
                path = "primary:Documents",
                icon = Icons.Default.Create
            ))
            add(StorageLocation(
                name = "Internal Storage",
                path = "primary:",
                icon = Icons.Default.Home
            ))

            // Check for external SD card
            val externalDirs = context.getExternalFilesDirs(null)
            externalDirs.forEachIndexed { index, file ->
                if (file != null && index > 0) {
                    // This is likely an SD card
                    val path = file.absolutePath
                    // Extract the SD card ID from path like /storage/XXXX-XXXX/Android/...
                    val sdCardId = path.split("/").getOrNull(2)
                    if (sdCardId != null && sdCardId.contains("-")) {
                        add(StorageLocation(
                            name = "SD Card",
                            path = "$sdCardId:",
                            icon = Icons.Default.Info
                        ))
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Starting Location") },
        text = {
            Column {
                Text(
                    "Choose where to start browsing:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                storageLocations.forEach { location ->
                    ListItem(
                        headlineContent = { Text(location.name) },
                        leadingContent = {
                            Icon(location.icon, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            val uri = Uri.Builder()
                                .scheme("content")
                                .authority("com.android.externalstorage.documents")
                                .appendPath("document")
                                .appendPath(location.path)
                                .build()
                            onLocationSelected(uri)
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private data class StorageLocation(
    val name: String,
    val path: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

/**
 * Dialog for editing format priority order
 */
@Composable
private fun FormatPriorityDialog(
    currentPriority: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    onReset: () -> Unit
) {
    var formats by remember { mutableStateOf(currentPriority.toMutableList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Format Priority") },
        text = {
            Column {
                Text(
                    "Use arrows to reorder. Formats at the top are preferred when downloading.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                ) {
                    itemsIndexed(formats, key = { _, item -> item }) { index, format ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "${index + 1}.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = format.uppercase(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Row {
                                    // Move up button
                                    IconButton(
                                        onClick = {
                                            if (index > 0) {
                                                formats = formats.toMutableList().apply {
                                                    add(index - 1, removeAt(index))
                                                }
                                            }
                                        },
                                        enabled = index > 0
                                    ) {
                                        Icon(
                                            Icons.Default.KeyboardArrowUp,
                                            contentDescription = "Move up",
                                            tint = if (index > 0)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                        )
                                    }
                                    // Move down button
                                    IconButton(
                                        onClick = {
                                            if (index < formats.size - 1) {
                                                formats = formats.toMutableList().apply {
                                                    add(index + 1, removeAt(index))
                                                }
                                            }
                                        },
                                        enabled = index < formats.size - 1
                                    ) {
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            contentDescription = "Move down",
                                            tint = if (index < formats.size - 1)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(formats) }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) {
                    Text("Reset")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

/**
 * Dialog for selecting preferred reader app
 */
@Composable
private fun ReaderPickerDialog(
    onDismiss: () -> Unit,
    onReaderSelected: (packageName: String?, displayName: String) -> Unit
) {
    val context = LocalContext.current

    // Query available apps that can open e-books
    val readerApps = remember {
        val packageManager = context.packageManager

        // Packages to exclude (file managers, galleries, system apps that aren't readers)
        val excludedPackages = setOf(
            "com.google.android.apps.photos",
            "com.google.android.documentsui",
            "com.google.android.apps.nbu.files",
            "com.android.documentsui",
            "com.sec.android.gallery3d",
            "com.sec.android.app.myfiles",
            "com.mi.android.globalFileexplorer",
            "com.miui.gallery",
            "com.coloros.filemanager",
            "com.coloros.gallery3d",
            "com.oneplus.filemanager",
            "com.android.gallery3d",
            "com.android.htmlviewer",
            "com.android.chrome",
            "com.google.android.gm",
            "com.whatsapp",
            "org.telegram.messenger",
            context.packageName
        )

        // Book-specific MIME types
        val bookMimeTypes = listOf(
            "application/epub+zip",
            "application/x-fictionbook+xml",
            "application/x-fictionbook",
            "application/pdf",
            "application/x-mobipocket-ebook",
            "image/vnd.djvu"
        )

        val apps = mutableMapOf<String, String>()

        // Query by MIME type
        bookMimeTypes.forEach { mimeType ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setType(mimeType)
            }
            try {
                val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                resolveInfos.forEach { resolveInfo ->
                    val packageName = resolveInfo.activityInfo.packageName
                    if (!excludedPackages.contains(packageName) && !apps.containsKey(packageName)) {
                        val appName = resolveInfo.loadLabel(packageManager).toString()
                        apps[packageName] = appName
                    }
                }
            } catch (e: Exception) { }
        }

        // Query with content:// URI + MIME type
        bookMimeTypes.forEach { mimeType ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("content://com.example.test/book"), mimeType)
            }
            try {
                val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                resolveInfos.forEach { resolveInfo ->
                    val packageName = resolveInfo.activityInfo.packageName
                    if (!excludedPackages.contains(packageName) && !apps.containsKey(packageName)) {
                        val appName = resolveInfo.loadLabel(packageManager).toString()
                        apps[packageName] = appName
                    }
                }
            } catch (e: Exception) { }
        }

        // Query with file:// URI + MIME type
        val testFiles = listOf(
            "file:///test.epub" to "application/epub+zip",
            "file:///test.fb2" to "application/x-fictionbook+xml",
            "file:///test.pdf" to "application/pdf",
            "file:///test.mobi" to "application/x-mobipocket-ebook"
        )
        testFiles.forEach { (fileUri, mimeType) ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(fileUri), mimeType)
            }
            try {
                val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                resolveInfos.forEach { resolveInfo ->
                    val packageName = resolveInfo.activityInfo.packageName
                    if (!excludedPackages.contains(packageName) && !apps.containsKey(packageName)) {
                        val appName = resolveInfo.loadLabel(packageManager).toString()
                        apps[packageName] = appName
                    }
                }
            } catch (e: Exception) { }
        }

        apps.entries.sortedBy { it.value.lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Reader App") },
        text = {
            Column {
                Text(
                    "Choose the app to use when opening books:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                // System default option
                ListItem(
                    headlineContent = { Text("System Default") },
                    supportingContent = { Text("Ask every time") },
                    leadingContent = {
                        Icon(Icons.Default.Settings, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        onReaderSelected(null, "System Default (Ask Every Time)")
                    }
                )

                HorizontalDivider()

                if (readerApps.isEmpty()) {
                    Text(
                        text = "No reader apps found. Install a reader app like Moon+ Reader, ReadEra, or FBReader.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.height(300.dp)
                    ) {
                        items(readerApps.size) { index ->
                            val entry = readerApps[index]
                            ListItem(
                                headlineContent = { Text(entry.value) },
                                supportingContent = { Text(entry.key) },
                                leadingContent = {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                },
                                modifier = Modifier.clickable {
                                    onReaderSelected(entry.key, entry.value)
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatCacheSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / 1024 / 1024} MB"
    }
}

private fun formatDate(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
    return dateFormat.format(Date(timestamp))
}
