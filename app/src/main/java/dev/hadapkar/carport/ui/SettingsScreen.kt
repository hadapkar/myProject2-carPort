package dev.hadapkar.carport.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hadapkar.carport.data.Logbook
import dev.hadapkar.carport.data.SettingsStore
import dev.hadapkar.carport.media.MediaEngine
import dev.hadapkar.carport.media.MediaMirror
import dev.hadapkar.carport.media.SmartPlay
import dev.hadapkar.carport.update.UpdateChecker

/**
 * Settings laid out like Android's own: groups of short rows (icon, title, one line, a switch or
 * action), status chips, and the longer explanations behind "Learn more".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenSetup: () -> Unit,
    onTestMedia: () -> Unit,
    onOpenRecent: () -> Unit,
) {
    val context = LocalContext.current
    val options by viewModel.options.collectAsStateWithLifecycle()
    val keyInfo by viewModel.keyInfo.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val setup by viewModel.setup.collectAsStateWithLifecycle()
    val expressive by viewModel.expressiveDesign.collectAsStateWithLifecycle()
    val store = remember { SettingsStore(context) }

    var useMedia3 by remember { mutableStateOf(store.useMedia3) }
    var skipButtons by remember { mutableStateOf(store.skipButtons) }
    var holdToSeek by remember { mutableStateOf(store.holdToSeek) }
    var openLinkOnConnect by remember { mutableStateOf(store.openLinkOnConnect) }
    var resumeOnConnect by remember { mutableStateOf(store.resumeOnConnect) }
    var pauseOnDisconnect by remember { mutableStateOf(store.pauseOnDisconnect) }
    var smartPlay by remember { mutableStateOf(store.smartPlayEnabled) }
    var musicLink by remember { mutableStateOf(store.smartPlayUrl) }
    var canOpenBrowser by remember { mutableStateOf(SmartPlay.canOpenFromBackground(context)) }
    var editingLink by rememberSaveable { mutableStateOf(false) }
    var confirmImport by rememberSaveable { mutableStateOf(false) }
    var showFingerprint by rememberSaveable { mutableStateOf(false) }

    // Permissions are granted on system screens; re-check when the user comes back.
    LifecycleResumeEffect(Unit) {
        canOpenBrowser = SmartPlay.canOpenFromBackground(context)
        onPauseOrDispose { }
    }

    val exportKey = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pkcs12")) { uri ->
        if (uri != null) viewModel.exportKey(uri)
    }
    val importKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importKey(uri)
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (update as? UpdateState.Available)?.let { available ->
                Group(title = null) {
                    ActionRow(
                        icon = Icons.Filled.Refresh,
                        title = "CarPort ${available.release.version} is available",
                        summary = "Tap Update to install it",
                    ) { Button(onClick = viewModel::installUpdate) { Text("Update") } }
                }
            }

            // ---- Car music ----
            Group(
                title = "Car music",
                learnMore = "Android Auto shows music playing on your phone (for example in Samsung Browser) in " +
                    "CarPort's player, and the steering wheel, the car screen and \"Hey Google\" control it. It " +
                    "runs only while the phone is connected to Android Auto.\n\n" +
                    "Hold to skip 10 s works when your car repeats the next/previous key while you hold it (most " +
                    "do) or sends fast-forward/rewind; with it on, a tap changes the song when you release the " +
                    "button. If your car sends only one press however long you hold, a hold acts like a tap. The " +
                    "log shows what your car sends.\n\n" +
                    "Media engine: Classic uses Android's own media system and is the proven default. Media3 is " +
                    "Google's newer library and works the same way; if anything misbehaves in the car, turn it " +
                    "off to go back to Classic.\n\n" +
                    "Smart play: if you press play and there's no song to resume (for example the browser tab " +
                    "was closed), CarPort opens your music link in Samsung Browser, or another browser if Samsung " +
                    "Browser can't. On a locked phone the page appears after unlocking, and the browser may need " +
                    "one tap to start. Android only allows opening the browser from the background with " +
                    "\"Display over other apps\"; CarPort never draws over other apps.\n\n" +
                    "Quick Settings tile: swipe down from the top of the screen, tap the pencil (edit), and drag " +
                    "\"Music link\" into your tiles. Tapping it opens your music link.",
            ) {
                ActionRow(
                    icon = Icons.Filled.PlayArrow,
                    title = "Car media controls",
                    summary = if (setup.mediaAccessGranted) "Ready" else "Needs notification access",
                    onClick = if (setup.mediaAccessGranted) onTestMedia else onOpenSetup,
                ) { StatusChip(if (setup.mediaAccessGranted) "On" else "Set up", ok = setup.mediaAccessGranted) }
                Divider()
                SwitchRow(
                    icon = Icons.Filled.Build,
                    title = "Use Media3 engine",
                    summary = if (useMedia3) "Google's newer media library (try it; turn off if it misbehaves)"
                    else "Off: Classic engine (proven)",
                    checked = useMedia3,
                ) {
                    useMedia3 = it
                    store.useMedia3 = it
                    MediaEngine.apply(context)
                }
                Divider()
                ActionRow(
                    icon = Icons.Filled.Face,
                    title = "Test without a car",
                    summary = "See and try the car's music player on your phone",
                    onClick = onTestMedia,
                ) { Chevron() }
                Divider()
                SwitchRow(
                    icon = Icons.Filled.Refresh,
                    title = "−10 s / +10 s buttons",
                    summary = "Extra buttons in the car player (when the song allows seeking)",
                    checked = skipButtons,
                ) {
                    skipButtons = it
                    store.skipButtons = it
                    MediaMirror.refresh()
                }
                Divider()
                SwitchRow(
                    icon = Icons.Filled.Refresh,
                    title = "Hold next/previous to skip 10 s",
                    summary = "Steering wheel: tap changes song, hold jumps 10 s",
                    checked = holdToSeek,
                ) {
                    holdToSeek = it
                    store.holdToSeek = it
                }
                Divider()
                SwitchRow(
                    icon = Icons.Filled.PlayArrow,
                    title = "Resume when the car connects",
                    summary = "Plays a paused song a moment after connecting",
                    checked = resumeOnConnect,
                ) {
                    resumeOnConnect = it
                    store.resumeOnConnect = it
                }
                Divider()
                SwitchRow(
                    icon = Icons.Filled.Star,
                    title = "Open my music link when the car connects",
                    summary = "If there's no song to resume (needs Display over other apps)",
                    checked = openLinkOnConnect,
                ) {
                    openLinkOnConnect = it
                    store.openLinkOnConnect = it
                }
                Divider()
                SwitchRow(
                    icon = Icons.Filled.Clear,
                    title = "Pause when the car disconnects",
                    summary = "No music from the phone speaker when you leave",
                    checked = pauseOnDisconnect,
                ) {
                    pauseOnDisconnect = it
                    store.pauseOnDisconnect = it
                }
                Divider()
                SwitchRow(
                    icon = Icons.Filled.Star,
                    title = "Smart play button",
                    summary = "Opens your music link when nothing can resume",
                    checked = smartPlay,
                ) {
                    smartPlay = it
                    store.smartPlayEnabled = it
                    MediaMirror.refresh()
                }
                Divider()
                ActionRow(
                    icon = Icons.Filled.Edit,
                    title = "Music link",
                    summary = musicLink,
                    onClick = { editingLink = true },
                ) { Chevron() }
                Divider()
                ActionRow(
                    icon = Icons.Filled.Menu,
                    title = "Recently played",
                    summary = "Songs played through car media controls",
                    onClick = onOpenRecent,
                ) { Chevron() }
                Divider()
                ActionRow(
                    icon = Icons.Filled.Notifications,
                    title = "Display over other apps",
                    summary = if (canOpenBrowser) "Allowed, so smart play can open the browser" else "Needed for smart play",
                    onClick = if (canOpenBrowser) null else ({ openOverlayPermission(context) }),
                ) { StatusChip(if (canOpenBrowser) "Allowed" else "Allow", ok = canOpenBrowser) }
            }

            // ---- Patching apps ----
            Group(
                title = "Patching apps",
                learnMore = "These options change how patched apps behave on the car screen. Changes apply the " +
                    "next time you patch an app; turning an option off and patching again undoes it.\n\n" +
                    "Keep page open: the app keeps its page or video when it moves between the phone and the car " +
                    "screen, but a few apps may then draw the wrong layout.\n\n" +
                    "Repair all patches every patched app again in place, for example if they stop opening after " +
                    "an Android or Android Auto update. Their data is kept.",
            ) {
                SwitchRow(
                    icon = Icons.Filled.Menu,
                    title = "Unlock screen orientation",
                    summary = "Portrait-only apps fit the car's wide screen",
                    checked = options.unlockOrientation,
                ) { viewModel.setOptions(options.copy(unlockOrientation = it)) }
                Divider()
                SwitchRow(
                    icon = Icons.Filled.Menu,
                    title = "Make screens resizable",
                    summary = "No black bars on the car screen",
                    checked = options.forceResizeable,
                ) { viewModel.setOptions(options.copy(forceResizeable = it)) }
                Divider()
                SwitchRow(
                    icon = Icons.Filled.Refresh,
                    title = "Keep page open when switching screens",
                    summary = "May show a wrong layout in some apps",
                    checked = options.keepStateOnScreenChange,
                ) { viewModel.setOptions(options.copy(keepStateOnScreenChange = it)) }
                Divider()
                SwitchRow(
                    icon = Icons.Filled.Star,
                    title = "Show every launcher icon",
                    summary = "Off: one car icon per app",
                    checked = options.allLauncherEntries,
                ) { viewModel.setOptions(options.copy(allLauncherEntries = it)) }
                Divider()
                ActionRow(
                    icon = Icons.Filled.Build,
                    title = "Repair all patched apps",
                    summary = "Patch them again after Android updates",
                    onClick = viewModel::repairAll,
                ) { Chevron() }
            }

            // ---- CarPort ----
            Group(
                title = "CarPort",
                learnMore = "Every patched app is signed with CarPort's signing key. Android only updates a " +
                    "patched app in place (keeping its data) when the key matches, so keep a backup and import " +
                    "it after reinstalling CarPort or switching phones. Only import a key you backed up from " +
                    "CarPort.",
            ) {
                ActionRow(
                    icon = Icons.Filled.Refresh,
                    title = "Updates",
                    summary = "Installed ${UpdateChecker.currentVersion} · " + when (val state = update) {
                        UpdateState.Idle -> "tap Check"
                        UpdateState.Checking -> "checking…"
                        UpdateState.UpToDate -> "up to date"
                        is UpdateState.Available -> "${state.release.version} available"
                        is UpdateState.Failed -> state.message
                    },
                ) {
                    if (update is UpdateState.Available) {
                        Button(onClick = viewModel::installUpdate) { Text("Update") }
                    } else {
                        TextButton(
                            onClick = { viewModel.checkForUpdate(manual = true) },
                            enabled = update != UpdateState.Checking,
                        ) { Text("Check") }
                    }
                }
                Divider()
                SwitchRow(
                    icon = Icons.Filled.Face,
                    title = "Expressive design",
                    summary = if (expressive) "Rounder shapes and bolder titles" else "Classic Material design",
                    checked = expressive,
                ) { viewModel.setExpressiveDesign(it) }
                Divider()
                ActionRow(
                    icon = Icons.Filled.Lock,
                    title = "Signing key",
                    summary = if (keyInfo?.backedUp == true) "Backed up" else "Not backed up yet: back it up",
                ) {
                    FilledTonalButton(onClick = { exportKey.launch("carport-signing-key.p12") }) { Text("Back up") }
                }
                Divider()
                ActionRow(
                    icon = Icons.Filled.Share,
                    title = "Import a signing key",
                    summary = "Use a key backed up from CarPort",
                    onClick = { confirmImport = true },
                ) { Chevron() }
                Divider()
                ActionRow(
                    icon = Icons.Filled.Info,
                    title = "Key fingerprint",
                    summary = if (showFingerprint) "Tap to hide" else "Tap to show",
                    onClick = { showFingerprint = !showFingerprint },
                ) {}
                if (showFingerprint) {
                    Text(
                        keyInfo?.sha256?.chunked(2)?.joinToString(":") ?: "Loading…",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }

            // ---- Help ----
            Group(
                title = "Help",
                learnMore = "Patched app not showing in the car? Check the setup guide, especially Android Auto's " +
                    "Unknown sources.\n\n" +
                    "The diagnostic log lists what CarPort did and any errors, with no personal data beyond app " +
                    "names. Share it when something doesn't work.\n\n" +
                    "Safety: use projected apps only when parked or as a passenger. Patched apps are for your own " +
                    "phone; don't share patched APKs.",
            ) {
                ActionRow(
                    icon = Icons.Filled.Settings,
                    title = "Setup guide",
                    summary = "Permissions and Android Auto settings",
                    onClick = onOpenSetup,
                ) { Chevron() }
                Divider()
                ActionRow(
                    icon = Icons.Filled.Share,
                    title = "Share diagnostic log",
                    summary = "Send it when something doesn't work",
                    onClick = { shareLog(context) },
                ) { Chevron() }
                Divider()
                ActionRow(
                    icon = Icons.Filled.Delete,
                    title = "Clear log",
                    summary = "Start a fresh log",
                    onClick = {
                        Logbook.clear()
                        Toast.makeText(context, "Log cleared", Toast.LENGTH_SHORT).show()
                    },
                ) {}
                Divider()
                ActionRow(
                    icon = Icons.Filled.Warning,
                    title = "Drive safely",
                    summary = "Use the car screen only when parked",
                ) {}
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (editingLink) {
        MusicLinkDialog(
            initial = musicLink,
            onSave = {
                musicLink = it
                store.smartPlayUrl = it
                editingLink = false
                MediaMirror.refresh()
            },
            onDismiss = { editingLink = false },
        )
    }

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Replace the signing key?") },
            text = {
                Text(
                    "Apps patched with the current key can't be updated in place after this. " +
                        "Only import a key you backed up from CarPort."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    importKey.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*"))
                }) { Text("Choose file") }
            },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } },
        )
    }
}

/** Edits the smart play link, with validation and a one-tap default. */
@Composable
private fun MusicLinkDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var link by remember { mutableStateOf(initial) }
    val valid = SmartPlay.isValidLink(link)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Music link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste a YouTube Music playlist, album or song link (Share › Copy link in YouTube Music), " +
                        "or any web address.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://music.youtube.com/playlist?list=…") },
                    singleLine = true,
                    isError = !valid,
                    supportingText = { if (!valid) Text("Enter a web address starting with https://") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                )
                TextButton(onClick = { link = SettingsStore.DEFAULT_SMART_PLAY_URL }) { Text("Use YouTube Music home") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(link.trim()) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A titled card of rows, with an optional "Learn more" that expands the longer explanation. */
@Composable
private fun Group(title: String?, learnMore: String? = null, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    if (title != null) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp),
        )
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            content()
            if (learnMore != null) {
                Divider()
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(start = 8.dp)) {
                    Text(if (expanded) "Show less" else "Learn more")
                }
                if (expanded) {
                    Text(
                        learnMore,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}

/** Icon, title, one-line summary, and a trailing control; the whole row is tappable when [onClick] is set. */
@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(title) },
        supportingContent = { Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** A switch row; tapping anywhere on the row toggles it. */
@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ActionRow(icon, title, summary, onClick = { onCheckedChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StatusChip(text: String, ok: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = if (ok) "✓ $text" else text,
            style = MaterialTheme.typography.labelMedium,
            color = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
}
