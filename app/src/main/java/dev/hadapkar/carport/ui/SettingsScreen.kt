package dev.hadapkar.carport.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.remember
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.hadapkar.carport.data.Logbook
import dev.hadapkar.carport.data.SettingsStore
import dev.hadapkar.carport.media.MediaMirror
import dev.hadapkar.carport.media.SmartPlay
import dev.hadapkar.carport.update.UpdateChecker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit, onOpenSetup: () -> Unit, onTestMedia: () -> Unit) {
    val options by viewModel.options.collectAsStateWithLifecycle()
    val keyInfo by viewModel.keyInfo.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmImport by rememberSaveable { mutableStateOf(false) }

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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsCard("Patch options") {
                SwitchItem(
                    title = "Unlock screen orientation",
                    body = "Lets portrait-only apps fill the car's landscape screen.",
                    checked = options.unlockOrientation,
                    onCheckedChange = { viewModel.setOptions(options.copy(unlockOrientation = it)) },
                )
                SwitchItem(
                    title = "Make screens resizable",
                    body = "Lets apps resize to the car display instead of showing black bars.",
                    checked = options.forceResizeable,
                    onCheckedChange = { viewModel.setOptions(options.copy(forceResizeable = it)) },
                )
                SwitchItem(
                    title = "Keep page open when switching screens",
                    body = "The app keeps its page or video when it moves between the phone and the car " +
                        "screen. Some apps may draw the wrong layout; if so, turn this off and patch again.",
                    checked = options.keepStateOnScreenChange,
                    onCheckedChange = { viewModel.setOptions(options.copy(keepStateOnScreenChange = it)) },
                )
                SwitchItem(
                    title = "Show every launcher icon in the car",
                    body = "Off: one car icon per app (the icon your phone shows). On: every launcher entry, " +
                        "including alternate icons.",
                    checked = options.allLauncherEntries,
                    onCheckedChange = { viewModel.setOptions(options.copy(allLauncherEntries = it)) },
                )
                Hint("Changes apply the next time you patch an app. Turning an option off and patching again undoes it.")
            }

            SettingsCard("Car media controls") {
                Text(
                    "Android Auto shows music playing on your phone (for example in Samsung Browser) in CarPort's " +
                        "player, and the steering wheel controls it. It runs only while the phone is connected to " +
                        "Android Auto.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                CarConnectionSwitches()
                OutlinedButton(onClick = onTestMedia) { Text("Test without a car") }
            }

            SmartPlayCard()

            SettingsCard("Repair") {
                Text(
                    "If patched apps stop opening after an Android or Android Auto update, patch them all again " +
                        "in place. Their data is kept.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = viewModel::repairAll) { Text("Repair all patched apps") }
            }

            SettingsCard("Signing key") {
                Text(
                    "Every patched app is signed with this key. Android only updates a patched app in place " +
                        "(keeping its data) when the key matches, so keep a backup.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                keyInfo?.let { info ->
                    Text(
                        text = if (info.backedUp) "✓ Backed up" else "Not backed up yet",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (info.backedUp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Text("SHA-256 fingerprint", style = MaterialTheme.typography.labelMedium)
                    Text(
                        info.sha256.chunked(2).joinToString(":"),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { exportKey.launch("carport-signing-key.p12") }) { Text("Back up") }
                    OutlinedButton(onClick = { confirmImport = true }) { Text("Import") }
                }
            }

            SettingsCard("Updates") {
                Text("Installed: CarPort ${UpdateChecker.currentVersion}", style = MaterialTheme.typography.bodyMedium)
                when (val state = update) {
                    UpdateState.Idle -> Unit
                    UpdateState.Checking -> Hint("Checking for updates…")
                    UpdateState.UpToDate -> Hint("✓ You're on the latest version.")
                    is UpdateState.Available -> Text(
                        "Version ${state.release.version} is available.",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is UpdateState.Failed -> Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (update is UpdateState.Available) {
                        Button(onClick = viewModel::installUpdate) { Text("Update now") }
                    }
                    OutlinedButton(
                        onClick = { viewModel.checkForUpdate(manual = true) },
                        enabled = update != UpdateState.Checking,
                    ) { Text("Check for updates") }
                }
            }

            SettingsCard("Help") {
                Text(
                    "Patched app not showing in the car? Check the setup steps, especially Android Auto's Unknown sources.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onOpenSetup) { Text("Open setup guide") }
                Text(
                    "Something went wrong? Share the diagnostic log. It lists what CarPort did and any errors, " +
                        "with no personal data beyond app names.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { shareLog(context) }) { Text("Share log") }
                    TextButton(onClick = {
                        Logbook.clear()
                        Toast.makeText(context, "Log cleared", Toast.LENGTH_SHORT).show()
                    }) { Text("Clear log") }
                }
            }

            SettingsCard("Safety") {
                Text(
                    "Use projected apps only when parked or as a passenger. Never watch or operate them while driving.\n\n" +
                        "Patched apps are for your own phone. Don't share patched APKs: that can break the original " +
                        "developers' terms.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
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

/** What happens to the music when the phone connects to or disconnects from the car. */
@Composable
private fun CarConnectionSwitches() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    var resume by remember { mutableStateOf(store.resumeOnConnect) }
    var pause by remember { mutableStateOf(store.pauseOnDisconnect) }
    SwitchItem(
        title = "Resume when the car connects",
        body = "If a song is paused (for example in Samsung Browser), play it a moment after the phone connects to Android Auto.",
        checked = resume,
        onCheckedChange = {
            resume = it
            store.resumeOnConnect = it
        },
    )
    SwitchItem(
        title = "Pause when the car disconnects",
        body = "Stops the music continuing out loud from the phone speaker when you leave the car.",
        checked = pause,
        onCheckedChange = {
            pause = it
            store.pauseOnDisconnect = it
        },
    )
}

/** Smart play button: open a saved music link when play is pressed and nothing can be resumed. */
@Composable
private fun SmartPlayCard() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    var enabled by remember { mutableStateOf(store.smartPlayEnabled) }
    var link by remember { mutableStateOf(store.smartPlayUrl) }
    var canOpen by remember { mutableStateOf(SmartPlay.canOpenFromBackground(context)) }
    // The permission is granted on a system screen; re-check when the user comes back.
    LifecycleResumeEffect(Unit) {
        canOpen = SmartPlay.canOpenFromBackground(context)
        onPauseOrDispose { }
    }
    val linkValid = SmartPlay.isValidLink(link)

    SettingsCard("Smart play button") {
        Text(
            "If you press play in the car and there's no song to resume (for example the browser tab was " +
                "closed), CarPort opens this link in Samsung Browser (or another browser if Samsung Browser " +
                "can't) instead of doing nothing. If the phone is " +
                "locked, the page appears after you unlock it, and the browser may need one tap to start.",
            style = MaterialTheme.typography.bodyMedium,
        )
        SwitchItem(
            title = "Open my music link when nothing is playing",
            body = "Works with the steering wheel, the car's play button and \"Hey Google, play\".",
            checked = enabled,
            onCheckedChange = {
                enabled = it
                store.smartPlayEnabled = it
                MediaMirror.refresh()
            },
        )
        OutlinedTextField(
            value = link,
            onValueChange = {
                link = it
                if (SmartPlay.isValidLink(it)) store.smartPlayUrl = it
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Music link") },
            placeholder = { Text("https://music.youtube.com/playlist?list=…") },
            singleLine = true,
            isError = !linkValid,
            supportingText = {
                Text(
                    if (linkValid) "Paste a YouTube Music playlist, album or song link, or any web address."
                    else "Enter a web address starting with https://"
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        )
        TextButton(onClick = {
            link = SettingsStore.DEFAULT_SMART_PLAY_URL
            store.smartPlayUrl = link
        }) { Text("Use YouTube Music home") }
        if (canOpen) {
            Text(
                "✓ Display over other apps is allowed",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                "Android only lets CarPort open the browser from the background with \"Display over other " +
                    "apps\". CarPort never draws over other apps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = { openOverlayPermission(context) }) { Text("Allow display over other apps") }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SwitchItem(title: String, body: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
