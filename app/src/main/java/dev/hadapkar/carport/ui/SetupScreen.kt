package dev.hadapkar.carport.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** First-run guide: the three things that must be in place before a patched app shows up in the car. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(viewModel: MainViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val setup by viewModel.setup.collectAsStateWithLifecycle()
    val keyInfo by viewModel.keyInfo.collectAsStateWithLifecycle()
    val exportKey = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pkcs12")) { uri ->
        if (uri != null) viewModel.exportKey(uri)
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Set up CarPort") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "A few steps, once. After that, patching an app takes one tap.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StepCard(
                number = 1,
                done = setup.canInstallApps,
                title = "Allow CarPort to install apps",
                body = "CarPort installs the patched copy of each app. Android asks you to allow this once.",
                action = if (setup.canInstallApps) null else "Allow",
                onAction = { openInstallPermission(context) },
            )

            StepCard(
                number = 2,
                done = null,
                title = "Turn on Unknown sources in Android Auto",
                body = (if (setup.androidAutoInstalled) "" else "Install Android Auto from the Play Store first.\n\n") +
                    "1. Open Android Auto settings (or phone Settings › search \"Android Auto\").\n" +
                    "2. Tap Version 10 times, then OK, to unlock developer mode.\n" +
                    "3. Tap ⋮ (top right) › Developer settings.\n" +
                    "4. Turn on Unknown sources.",
                action = "Open Android Auto",
                onAction = { openAndroidAuto(context) },
            )

            StepCard(
                number = 3,
                done = setup.mediaAccessGranted,
                title = "Car media controls",
                body = "Shows music playing on your phone (for example in Samsung Browser) in Android Auto, " +
                    "with play/pause and next/previous, even with the phone locked. Open CarPort in the car's " +
                    "app list to see it.\n\n" +
                    "Android only lets CarPort see what's playing with notification access:\n" +
                    "1. Tap Allow restricted settings: on CarPort's App info, tap ⋮ (top right) › Allow " +
                    "restricted settings. (Needed because CarPort was installed from a download.)\n" +
                    "2. Tap Allow access and turn on CarPort car media controls.\n\n" +
                    "CarPort never reads your notifications.",
                action = null,
                onAction = {},
            )
            if (!setup.mediaAccessGranted) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { openCarPortAppInfo(context) }) { Text("Allow restricted settings") }
                    FilledTonalButton(onClick = { openMediaAccess(context) }) { Text("Allow access") }
                }
            }

            StepCard(
                number = 4,
                done = keyInfo?.backedUp == true,
                title = "Back up your signing key",
                body = "Patched apps are signed with a key that lives in CarPort. Keep a copy so you can " +
                    "re-patch them without losing their data after reinstalling CarPort or switching phones.",
                action = if (keyInfo?.backedUp == true) null else "Back up key",
                onAction = { exportKey.launch("carport-signing-key.p12") },
            )

            Spacer(Modifier.height(4.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            Text(
                "Use projected apps only when parked or as a passenger.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

/** @param done true = complete, false = to do, null = can't be checked automatically. */
@Composable
private fun StepCard(
    number: Int,
    done: Boolean?,
    title: String,
    body: String,
    action: String?,
    onAction: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (done == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (done == true) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Done",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Text(
                                number.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (action != null) {
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = onAction) { Text(action) }
            }
        }
    }
}
