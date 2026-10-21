package dev.hadapkar.carport.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hadapkar.carport.R
import dev.hadapkar.carport.data.Logbook
import dev.hadapkar.carport.data.SettingsStore
import dev.hadapkar.carport.media.MediaAccessListener
import dev.hadapkar.carport.media.MediaMirror
import dev.hadapkar.carport.media.SmartPlay
import kotlinx.coroutines.delay

/**
 * Tests car media controls on the phone alone. The mirror runs as if the phone were in the
 * car; the car display at the top looks like Android Auto's media player (as for YT Music),
 * and every control goes through CarPort's own media session exactly as Android Auto's would:
 * screen buttons and the progress bar as transport controls, steering-wheel buttons as raw
 * media key presses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarPreviewScreen(onBack: () -> Unit, onOpenSetup: () -> Unit) {
    val context = LocalContext.current
    val granted = remember { MediaAccessListener.isGranted(context) }
    val preview by MediaMirror.preview.collectAsStateWithLifecycle()
    // The same client view Android Auto has of CarPort's session.
    val car = remember { MediaController(context, MediaMirror.carToken(context)) }
    var lastAction by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        MediaMirror.startTest(context)
        onDispose { MediaMirror.stopTest() }
    }

    // Landscape view: car screens are wide, so this rotates the phone while on this screen.
    val activity = remember(context) { context.findActivity() }
    var landscape by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(landscape) {
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    val wide = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

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
                title = { Text("Test without a car") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = { landscape = !landscape }) { Text(if (landscape) "Portrait" else "Landscape") }
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
                "Play music on your phone, for example in Samsung Browser. The screen below is what " +
                    "Android Auto shows, and its buttons work like the car's.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CarDisplay(preview, car, wide) { lastAction = it }

            Section("Steering-wheel buttons")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                KeyButton(R.drawable.ic_media_previous, "Previous", Modifier.weight(1f)) {
                    pressKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "previous")
                }
                KeyButton(R.drawable.ic_media_play, "Play/pause", Modifier.weight(1f)) {
                    pressKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "play/pause")
                }
                KeyButton(R.drawable.ic_media_next, "Next", Modifier.weight(1f)) {
                    pressKey(KeyEvent.KEYCODE_MEDIA_NEXT, "next")
                }
            }

            val skipStore = remember { SettingsStore(context) }
            Section("Skip 10 seconds")
            Hint(
                "−10 s / +10 s buttons: ${if (skipStore.skipButtons) "on" else "off"} · " +
                    "Hold to skip: ${if (skipStore.holdToSeek) "on" else "off"} · " +
                    "Keep skipping while held: ${if (skipStore.keepSkippingWhileHeld) "on" else "off"} (change in Settings)"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        car.transportControls.sendCustomAction(MediaMirror.ACTION_REWIND_10, null)
                        lastAction = "Car: back 10 seconds"
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("−10 s") }
                OutlinedButton(
                    onClick = {
                        car.transportControls.sendCustomAction(MediaMirror.ACTION_FORWARD_10, null)
                        lastAction = "Car: forward 10 seconds"
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("+10 s") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { holdKey(car, KeyEvent.KEYCODE_MEDIA_PREVIOUS); lastAction = "Steering wheel: hold previous" },
                    modifier = Modifier.weight(1f),
                ) { Text("Hold ⏮") }
                OutlinedButton(
                    onClick = { holdKey(car, KeyEvent.KEYCODE_MEDIA_NEXT); lastAction = "Steering wheel: hold next" },
                    modifier = Modifier.weight(1f),
                ) { Text("Hold ⏭") }
            }

            Section("Car connection")
            val store = remember { SettingsStore(context) }
            Hint(
                "Resume on connect: ${if (store.resumeOnConnect) "on" else "off"} · " +
                    "Open music link on connect: ${if (store.openLinkOnConnect) "on" else "off"} · " +
                    "Pause on disconnect: ${if (store.pauseOnDisconnect) "on" else "off"} (change in Settings)"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { lastAction = "Car connects: " + MediaMirror.simulateCarConnection(context, connected = true) },
                    modifier = Modifier.weight(1f),
                ) { Text("Car connects") }
                OutlinedButton(
                    onClick = { lastAction = "Car disconnects: " + MediaMirror.simulateCarConnection(context, connected = false) },
                    modifier = Modifier.weight(1f),
                ) { Text("Car disconnects") }
            }

            Section("Car browse screen")
            if (SmartPlay.hasLink(context)) {
                OutlinedButton(onClick = {
                    // Exactly what tapping the tile in the car sends.
                    car.transportControls.playFromMediaId(MediaMirror.OPEN_LINK_MEDIA_ID, null)
                    lastAction = "Car: tapped \"Open my music link\""
                }) { Text("Tap \"Open my music link\" tile") }
            } else {
                Hint("Save a music link in Settings › Smart play button to get the \"Open my music link\" tile.")
            }

            lastAction?.let { Hint("Last button: $it") }

            Section("What happened")
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
 * A car display laid out like Android Auto's media player: app rail on the left, blurred
 * artwork behind, artwork with title/artist/app, progress bar and the round play button.
 */
@Composable
private fun CarDisplay(preview: MediaMirror.Preview, car: MediaController, wide: Boolean, onAction: (String) -> Unit) {
    val art = preview.art
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0B0B0B)),
    ) {
        // Blurred artwork backdrop, darkened, as Android Auto does.
        if (art != null) {
            Image(
                art.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur(40.dp),
            )
        }
        Box(Modifier.matchParentSize().background(Color(0xCC0B0B0B)))

        Row(Modifier.fillMaxWidth()) {
            AppRail(showClock = wide)
            if (wide) {
                // Android Auto's landscape player: large artwork left, details and controls right.
                Row(Modifier.weight(1f).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(art, 180.dp)
                    Column(Modifier.weight(1f).padding(start = 24.dp)) {
                        TrackInfo(preview, large = true)
                        Spacer(Modifier.height(16.dp))
                        CarSeekBar(car, onAction)
                        CarControls(preview, car, onAction)
                    }
                }
            } else {
                Column(Modifier.weight(1f).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Artwork(art, 104.dp)
                        Column(Modifier.padding(start = 16.dp)) { TrackInfo(preview, large = false) }
                    }
                    Spacer(Modifier.height(12.dp))
                    CarSeekBar(car, onAction)
                    CarControls(preview, car, onAction)
                }
            }
        }
    }
}

@Composable
private fun Artwork(art: android.graphics.Bitmap?, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF2A2A2A)),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art.asImageBitmap(), "Artwork", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text("♪", color = Color(0xFF9E9E9E), style = MaterialTheme.typography.headlineLarge)
        }
    }
}

/** Title, artist and "CarPort · app", as Android Auto shows them. */
@Composable
private fun TrackInfo(preview: MediaMirror.Preview, large: Boolean) {
    Text(
        preview.title ?: if (preview.running) "Nothing is playing" else preview.state,
        color = Color.White,
        style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    preview.artist?.let {
        Text(it, color = Color(0xFFBDBDBD), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(4.dp))
    Text(
        listOfNotNull("CarPort", preview.sourceApp).joinToString(" · "),
        color = Color(0xFF9E9E9E),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
    )
}

/** Android Auto's left rail: navigation, media (active), phone, notifications, app launcher. */
@Composable
private fun AppRail(showClock: Boolean) {
    Column(
        Modifier
            .width(56.dp)
            .heightIn(min = 272.dp)
            .background(Color(0xFF000000))
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (showClock) {
            val locale = LocalConfiguration.current.locales[0]
            Text(
                java.text.SimpleDateFormat("H:mm", locale).format(java.util.Date()),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        RailIcon(Icons.Filled.Place, active = false)
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF2E2E2E)), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_media_play), contentDescription = "Media", tint = Color.White, modifier = Modifier.size(20.dp))
        }
        RailIcon(Icons.Filled.Phone, active = false)
        RailIcon(Icons.Filled.Notifications, active = false)
        RailIcon(Icons.Filled.Menu, active = false)
    }
}

@Composable
private fun RailIcon(icon: ImageVector, active: Boolean) {
    Icon(icon, contentDescription = null, tint = if (active) Color.White else Color(0xFF8A8A8A), modifier = Modifier.size(22.dp))
}

/**
 * Android Auto's control row: previous, the large round play/pause, next, with the session's
 * custom buttons (CarPort's −10 s / +10 s) on either side. The custom buttons are read from
 * CarPort's live session, the same list Android Auto reads, so they show here exactly when they
 * show in the car.
 */
@Composable
private fun CarControls(preview: MediaMirror.Preview, car: MediaController, onAction: (String) -> Unit) {
    var customActions by remember { mutableStateOf(car.playbackState?.customActions.orEmpty()) }
    DisposableEffect(car) {
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                customActions = state?.customActions.orEmpty()
            }
        }
        car.registerCallback(callback)
        onDispose { car.unregisterCallback(callback) }
    }
    val rewind = customActions.firstOrNull { it.action == MediaMirror.ACTION_REWIND_10 }
    val forward = customActions.firstOrNull { it.action == MediaMirror.ACTION_FORWARD_10 }

    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rewind != null) {
            CarIcon(R.drawable.ic_media_rewind, rewind.name.toString()) {
                car.transportControls.sendCustomAction(rewind.action, null)
                onAction("Car: back 10 seconds")
            }
        }
        CarIcon(R.drawable.ic_media_previous, "Previous") {
            car.transportControls.skipToPrevious()
            onAction("Car: previous")
        }
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable {
                    if (preview.playing) car.transportControls.pause() else car.transportControls.play()
                    onAction(if (preview.playing) "Car: pause" else "Car: play")
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (preview.playing) R.drawable.ic_media_pause else R.drawable.ic_media_play),
                contentDescription = if (preview.playing) "Pause" else "Play",
                tint = Color(0xFF0B0B0B),
                modifier = Modifier.size(34.dp),
            )
        }
        CarIcon(R.drawable.ic_media_next, "Next") {
            car.transportControls.skipToNext()
            onAction("Car: next")
        }
        if (forward != null) {
            CarIcon(R.drawable.ic_media_forward, forward.name.toString()) {
                car.transportControls.sendCustomAction(forward.action, null)
                onAction("Car: forward 10 seconds")
            }
        }
    }
    if (rewind == null && SettingsStore(LocalContext.current).skipButtons) {
        Text(
            "−10 s / +10 s appear here (and in the car) while a song that allows seeking is playing.",
            color = Color(0xFF9E9E9E),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun CarIcon(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Icon(painterResource(icon), contentDescription = label, tint = Color.White, modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun KeyButton(@DrawableRes icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(painterResource(icon), contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
    }
}

/**
 * The progress bar exactly as Android Auto gets it: duration, position and the seek permission
 * are read from CarPort's own session, and dragging seeks through that session like the car does.
 */
@Composable
private fun CarSeekBar(car: MediaController, onAction: (String) -> Unit) {
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
    if (duration <= 0) {
        Text(
            "No song length shared by the playing app, so the car shows no progress bar.",
            color = Color(0xFF9E9E9E),
            style = MaterialTheme.typography.labelSmall,
        )
        return
    }
    val position = current?.let {
        val elapsed = if (it.state == PlaybackState.STATE_PLAYING) ((now - it.lastPositionUpdateTime) * it.playbackSpeed).toLong() else 0L
        (it.position + elapsed).coerceIn(0L, duration)
    } ?: 0L

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
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color(0x55FFFFFF),
            disabledThumbColor = Color(0xFF9E9E9E),
            disabledActiveTrackColor = Color(0xFF9E9E9E),
            disabledInactiveTrackColor = Color(0x33FFFFFF),
        ),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(dragging?.toLong() ?: position), color = Color(0xFFBDBDBD), style = MaterialTheme.typography.labelSmall)
        if (!canSeek) Text("Seeking not allowed by the app", color = Color(0xFF9E9E9E), style = MaterialTheme.typography.labelSmall)
        Text(formatTime(duration), color = Color(0xFFBDBDBD), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Imitates holding a steering-wheel key for 2 seconds on a car that repeats it while held, in
 * real time: press, a repeat every half second (as Android sends them), release.
 */
private fun holdKey(car: MediaController, keyCode: Int) {
    val handler = Handler(Looper.getMainLooper())
    val down = SystemClock.uptimeMillis()
    car.dispatchMediaButtonEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, 0))
    for (repeat in 1..3) {
        handler.postDelayed({
            car.dispatchMediaButtonEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keyCode, repeat))
        }, repeat * 500L)
    }
    handler.postDelayed({
        car.dispatchMediaButtonEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
    }, 2_000L)
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** The Activity hosting [this] context, for requesting the screen orientation. */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
