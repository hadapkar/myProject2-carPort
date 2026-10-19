package dev.hadapkar.carport.ui

import android.media.session.MediaController
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hadapkar.carport.data.Logbook
import dev.hadapkar.carport.media.MediaAccessListener
import dev.hadapkar.carport.media.MediaMirror

/**
 * Tests car media controls on the phone alone. The mirror runs as if the phone were in the
 * car; the player card shows what Android Auto would show, and every button goes through
 * CarPort's own media session exactly as Android Auto's would: the car buttons as transport
 * controls, the steering-wheel buttons as raw media key presses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarPreviewScreen(onBack: () -> Unit, onOpenSetup: () -> Unit) {
    val context = LocalContext.current
    val granted = remember { MediaAccessListener.isGranted(context) }
    val preview by MediaMirror.preview.collectAsStateWithLifecycle()
    // The same client view Android Auto has of CarPort's session.
    val car = remember { MediaController(context, MediaMirror.sessionToken(context)) }
    var lastAction by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        MediaMirror.startTest(context)
        onDispose { MediaMirror.stopTest() }
    }

    fun pressKey(keyCode: Int, label: String) {
        car.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        car.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        lastAction = "Steering wheel: $label"
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Test car media controls") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!granted) {
                Text(
                    "Car media controls need notification access first.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = onOpenSetup) { Text("Open setup guide") }
                return@Column
            }

            Text(
                "Play music on your phone, for example in Samsung Browser, then use the buttons below. " +
                    "They take the same path as Android Auto and your steering wheel.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PlayerCard(preview)

            Text("Car screen buttons", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = { car.transportControls.skipToPrevious(); lastAction = "Car: previous" }, modifier = Modifier.weight(1f)) {
                    Text("⏮ Prev")
                }
                Button(
                    onClick = {
                        if (preview.playing) car.transportControls.pause() else car.transportControls.play()
                        lastAction = if (preview.playing) "Car: pause" else "Car: play"
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(if (preview.playing) "⏸ Pause" else "▶ Play") }
                FilledTonalButton(onClick = { car.transportControls.skipToNext(); lastAction = "Car: next" }, modifier = Modifier.weight(1f)) {
                    Text("Next ⏭")
                }
            }

            Text("Steering-wheel buttons", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { pressKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "previous") }, modifier = Modifier.weight(1f)) { Text("⏮") }
                OutlinedButton(onClick = { pressKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "play/pause") }, modifier = Modifier.weight(1f)) { Text("⏯") }
                OutlinedButton(onClick = { pressKey(KeyEvent.KEYCODE_MEDIA_NEXT, "next") }, modifier = Modifier.weight(1f)) { Text("⏭") }
            }

            lastAction?.let {
                Text("Last button: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text("What happened", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            // Re-read whenever the player changes or a button was pressed.
            val lines = remember(preview, lastAction) { Logbook.recent(8, "Car media") }
            Text(
                lines.joinToString("\n").ifBlank { "Nothing yet." },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { shareLog(context) }) { Text("Share log") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Dark card laid out like Android Auto's media player. */
@Composable
private fun PlayerCard(preview: MediaMirror.Preview) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .background(Color(0xFF121212))
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2A2A2A)),
                contentAlignment = Alignment.Center,
            ) {
                val art = preview.art
                if (art != null) {
                    Image(art.asImageBitmap(), contentDescription = "Artwork", modifier = Modifier.fillMaxSize())
                } else {
                    Text("♪", color = Color(0xFF9E9E9E), style = MaterialTheme.typography.headlineMedium)
                }
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(
                    preview.title ?: if (preview.running) "Nothing is playing" else preview.state,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                listOfNotNull(preview.artist, preview.sourceApp).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color(0xFFBDBDBD), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (preview.running) preview.state else "Mirror not running",
                    color = if (preview.playing) Color(0xFF81C784) else Color(0xFF9E9E9E),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
