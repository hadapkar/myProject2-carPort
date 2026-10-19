package dev.hadapkar.carport.ui

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
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
import androidx.compose.material3.Slider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
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

            SeekBar(car) { lastAction = it }

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

/**
 * The progress bar exactly as Android Auto gets it: duration, position and the seek permission
 * are read from CarPort's own session, and dragging seeks through that session like the car does.
 */
@Composable
private fun SeekBar(car: MediaController, onAction: (String) -> Unit) {
    var state by remember { mutableStateOf(car.playbackState) }
    var duration by remember { mutableStateOf(car.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) }
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    var dragging by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(car) {
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(newState: PlaybackState?) { state = newState }
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            }
        }
        car.registerCallback(callback)
        onDispose { car.unregisterCallback(callback) }
    }
    // Tick while playing so the position moves like in the car.
    LaunchedEffect(state?.state) {
        while (state?.state == PlaybackState.STATE_PLAYING) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }

    val current = state
    val canSeek = current != null && (current.actions and PlaybackState.ACTION_SEEK_TO) != 0L
    val position = current?.let {
        val elapsed = if (it.state == PlaybackState.STATE_PLAYING) ((now - it.lastPositionUpdateTime) * it.playbackSpeed).toLong() else 0L
        (it.position + elapsed).coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
    } ?: 0L

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Progress bar", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (duration <= 0) {
            Text(
                "The playing app hasn't shared the song's length, so the car shows no progress bar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Slider(
            value = dragging ?: position.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { target ->
                    car.transportControls.seekTo(target.toLong())
                    onAction("Car: seek to ${formatTime(target.toLong())}")
                }
                dragging = null
            },
            valueRange = 0f..duration.toFloat(),
            enabled = canSeek,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime((dragging?.toLong()) ?: position), style = MaterialTheme.typography.labelMedium)
            Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
        }
        Text(
            if (canSeek) "✓ Seeking allowed: drag the bar; the song should jump to that point."
            else "Seeking not allowed by the playing app: the car shows the bar but you can't drag it.",
            style = MaterialTheme.typography.bodySmall,
            color = if (canSeek) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
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
