package com.example.opdslibrary.update

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.opdslibrary.BuildConfig
import java.util.Locale

/**
 * Compose front-end for [AppUpdater]. Drop one instance high in the tree with
 * [autoCheckOnLaunch] = true for the silent on-launch check, and (optionally) another anywhere
 * a manual "Check for updates" button lives, driven by incrementing [manualTrigger].
 *
 * Renders only popups (AlertDialogs), so it takes no layout space — safe to place beside real UI.
 * All [AppUpdater] callbacks arrive on the main thread, so touching Compose state here is fine.
 */
@Composable
fun AppUpdateChecker(
    autoCheckOnLaunch: Boolean = false,
    manualTrigger: Int = 0,
) {
    val context = LocalContext.current

    var release by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var showUpdateInfo by remember { mutableStateOf(false) }
    var showNeedPermission by remember { mutableStateOf(false) }
    // (done, total) while a download is in flight; null when idle.
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    // Silent auto-check, once per process launch. Never nags when up to date / offline / errored,
    // and honours a build the user previously tapped "Later" on.
    LaunchedEffect(Unit) {
        if (autoCheckOnLaunch) {
            AppUpdater.check(context) { r, _ ->
                if (r != null && r.versionCode > AppUpdater.dismissedCode(context)) {
                    release = r
                    showUpdateInfo = true
                }
            }
        }
    }

    // Manual check — reports every outcome via a Toast (mirrors MyNavvy's manual check UX).
    LaunchedEffect(manualTrigger) {
        if (manualTrigger > 0) {
            AppUpdater.check(context) { r, error ->
                when {
                    r != null -> { release = r; showUpdateInfo = true }
                    error == "offline" ->
                        Toast.makeText(context, "No connection — can't check for updates", Toast.LENGTH_SHORT).show()
                    error != null ->
                        Toast.makeText(context, "Update check failed: $error", Toast.LENGTH_LONG).show()
                    else ->
                        Toast.makeText(context, "OPDS Library ${BuildConfig.VERSION_NAME} is up to date", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun startDownload(r: AppUpdater.Release) {
        progress = 0L to r.size
        AppUpdater.downloadAndInstall(context, r,
            onProgress = { done, total -> progress = done to total },
            onDone = { ok, msg ->
                progress = null
                Toast.makeText(
                    context,
                    if (ok) "Opening installer…" else "Update failed: $msg",
                    if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            })
    }

    // --- Update available -----------------------------------------------------
    val r = release
    if (showUpdateInfo && r != null) {
        val mb = if (r.size > 0) String.format(Locale.US, " · %.1f MB", r.size / 1048576.0) else ""
        AlertDialog(
            onDismissRequest = { showUpdateInfo = false },
            title = { Text("Update available") },
            text = {
                Text(
                    "OPDS Library ${r.versionName} (build ${r.versionCode})$mb\n\n" +
                        "You have ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUpdateInfo = false
                    if (AppUpdater.canInstall(context)) startDownload(r) else showNeedPermission = true
                }) { Text("Update") }
            },
            dismissButton = {
                TextButton(onClick = {
                    AppUpdater.setDismissed(context, r.versionCode)
                    showUpdateInfo = false
                }) { Text("Later") }
            }
        )
    }

    // --- "Allow app installs" gate -------------------------------------------
    if (showNeedPermission) {
        AlertDialog(
            onDismissRequest = { showNeedPermission = false },
            title = { Text("Allow app installs") },
            text = {
                Text(
                    "To update itself, OPDS Library needs permission to install apps. Enable it on the " +
                        "next screen, then tap “Check for updates” again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNeedPermission = false
                    runCatching { context.startActivity(AppUpdater.unknownSourcesSettings(context)) }
                        .onFailure { Toast.makeText(context, "Couldn't open settings", Toast.LENGTH_SHORT).show() }
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { showNeedPermission = false }) { Text("Cancel") }
            }
        )
    }

    // --- Download progress (non-cancelable) ----------------------------------
    progress?.let { (done, total) ->
        val msg = if (total > 0)
            String.format(Locale.US, "Downloading… %d%%  (%.1f / %.1f MB)",
                done * 100 / total, done / 1048576.0, total / 1048576.0)
        else
            String.format(Locale.US, "Downloading… %.1f MB", done / 1048576.0)
        AlertDialog(
            onDismissRequest = { /* non-cancelable */ },
            title = { Text("Downloading update") },
            text = { Text(msg) },
            confirmButton = {}
        )
    }
}
