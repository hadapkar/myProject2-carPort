package dev.hadapkar.carport.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hadapkar.carport.data.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class AppFilter(val label: String) { ALL("All apps"), PATCHED("Patched") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(viewModel: MainViewModel, onOpenSetup: () -> Unit, onOpenSettings: () -> Unit) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val setup by viewModel.setup.collectAsStateWithLifecycle()
    val keyInfo by viewModel.keyInfo.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.ALL) }
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }

    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.patchApkFile(uri)
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("CarPort") },
                actions = {
                    IconButton(onClick = onOpenSetup) {
                        Icon(Icons.Filled.Info, contentDescription = "Setup guide")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Patch APK file") },
            )
        },
    ) { padding ->
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val matches = remember(list, query, filter) {
            list.filter { app ->
                (query.isBlank() || app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)) &&
                    (filter == AppFilter.ALL || app.isPatched)
            }
        }
        val onCar = matches.filter { it.isPatched }
        val others = matches.filterNot { it.isPatched }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { viewModel.refresh(userInitiated = true) },
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 96.dp),
            ) {
                (update as? UpdateState.Available)?.let { available ->
                    item {
                        NoticeCard(
                            title = "CarPort ${available.release.version} is available",
                            body = available.release.notes.ifBlank { "A new version of CarPort is ready to install." }
                                .take(300),
                            action = "Update now",
                            onAction = viewModel::installUpdate,
                        )
                    }
                }
                if (!setup.canInstallApps || !setup.androidAutoInstalled) {
                    item {
                        NoticeCard(
                            title = "Finish setup",
                            body = if (!setup.canInstallApps) "CarPort needs your permission to install apps."
                            else "Android Auto is not installed on this phone.",
                            action = "Open setup",
                            onAction = onOpenSetup,
                        )
                    }
                }
                if (setup.canInstallApps && !setup.mediaAccessGranted) {
                    item {
                        NoticeCard(
                            title = "Turn on car media controls",
                            body = "Control music playing on your phone (for example in Samsung Browser) from " +
                                "Android Auto, even with the phone locked.",
                            action = "Set up",
                            onAction = onOpenSetup,
                        )
                    }
                }
                if (list.any { it.signedByCarPort } && keyInfo?.backedUp == false) {
                    item {
                        NoticeCard(
                            title = "Back up your signing key",
                            body = "Without a backup, reinstalling CarPort means your patched apps can only be " +
                                "updated by uninstalling them, which erases their data.",
                            action = "Back up now",
                            onAction = onOpenSetup,
                        )
                    }
                }
                loadError?.let { error ->
                    item {
                        NoticeCard(title = "Couldn't load apps", body = error, action = "Try again") {
                            viewModel.refresh(userInitiated = true)
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search apps") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                    )
                }
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppFilter.entries.forEach { option ->
                            FilterChip(
                                selected = filter == option,
                                onClick = { filter = option },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }

                if (matches.isEmpty()) {
                    item { EmptyState(if (list.isEmpty()) "No apps found." else "No apps match.") }
                }
                appSection("On your car", onCar) { selectedPackage = it.packageName }
                appSection(if (onCar.isEmpty()) "Apps" else "Other apps", others) { selectedPackage = it.packageName }
            }
        }

        val selected = list.firstOrNull { it.packageName == selectedPackage }
        if (selected != null) {
            AppActionDialog(
                app = selected,
                canInstall = setup.canInstallApps,
                onPatch = {
                    selectedPackage = null
                    viewModel.patchInstalledApp(selected)
                },
                onRestore = {
                    selectedPackage = null
                    viewModel.restoreOriginal(selected)
                },
                onOpenSetup = {
                    selectedPackage = null
                    onOpenSetup()
                },
                onDismiss = { selectedPackage = null },
            )
        }
    }
}

private fun LazyListScope.appSection(title: String, apps: List<AppEntry>, onClick: (AppEntry) -> Unit) {
    if (apps.isEmpty()) return
    item(key = "header-$title") {
        Text(
            text = "$title · ${apps.size}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        )
    }
    items(apps, key = { it.packageName }) { app -> AppRow(app = app, onClick = { onClick(app) }) }
}

@Composable
private fun AppRow(app: AppEntry, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { AppIcon(app.packageName, 44) },
        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                text = listOfNotNull(app.versionName, app.packageName).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            when {
                app.isPatched && app.signedByCarPort -> StatusPill("Ready", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                app.isPatched -> StatusPill("Old key", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    )
}

@Composable
private fun StatusPill(text: String, container: Color, content: Color) {
    Surface(shape = CircleShape, color = container) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun EmptyState(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(48.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AppIcon(packageName: String, sizeDp: Int) {
    val context = LocalContext.current
    var icon by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName) {
        icon = withContext(Dispatchers.IO) { loadIcon(context, packageName, sizeDp) }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(sizeDp.dp))
    } else {
        Spacer(Modifier.size(sizeDp.dp))
    }
}

private fun loadIcon(context: Context, packageName: String, sizeDp: Int): ImageBitmap? = runCatching {
    val sizePx = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx).asImageBitmap()
}.getOrNull()

@Composable
private fun NoticeCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun AppActionDialog(
    app: AppEntry,
    canInstall: Boolean,
    onPatch: () -> Unit,
    onRestore: () -> Unit,
    onOpenSetup: () -> Unit,
    onDismiss: () -> Unit,
) {
    val repatch = app.signedByCarPort
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { AppIcon(app.packageName, 48) },
        title = { Text(app.label, textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    !canInstall -> Text("First allow CarPort to install apps. The setup guide shows how.")
                    repatch -> Text("Already on your car. Patch again if it stops opening after an Android update. Your data is kept.")
                    else -> Text(
                        "CarPort makes a copy of this app that Android Auto can show. Android has to uninstall " +
                            "the current version first, which erases its data on this phone (logins, settings, downloads)."
                    )
                }
                if (app.installedFromPlayStore && !repatch) {
                    Text("The Play Store can't update the patched copy. To update later: restore the original, update it, then patch again.")
                }
                if (app.splitSourceDirs.isNotEmpty()) {
                    Text("This app is installed in ${app.splitSourceDirs.size + 1} parts. CarPort handles all of them.")
                }
                Text(
                    "Apps with DRM, Play Integrity or tamper checks may refuse to run after patching.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (canInstall) {
                TextButton(onClick = onPatch) { Text(if (repatch) "Patch again" else "Patch & install") }
            } else {
                TextButton(onClick = onOpenSetup) { Text("Open setup") }
            }
        },
        dismissButton = {
            Row {
                if (app.isPatched) {
                    TextButton(onClick = onRestore) { Text("Restore original") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
