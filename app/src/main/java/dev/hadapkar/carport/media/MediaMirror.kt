package dev.hadapkar.carport.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.common.util.UnstableApi
import dev.hadapkar.carport.MainActivity
import dev.hadapkar.carport.R
import dev.hadapkar.carport.data.Logbook
import dev.hadapkar.carport.data.RecentlyPlayed
import dev.hadapkar.carport.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mirrors the phone's playing media session (e.g. Samsung Browser playing music) into
 * CarPort's own session, and forwards controls back to it.
 *
 * It runs whenever CarPort has notification access ([MediaAccessListener] starts it, and
 * Android keeps that listener running), not only while Android Auto shows CarPort. So when
 * music starts on the phone, CarPort's session is already "playing" with the same track:
 * Android Auto switches to it on its own, and steering-wheel buttons reach it without the
 * user opening CarPort. [CarMediaService] hands this session to Android Auto.
 *
 * Main thread only.
 */
object MediaMirror {
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var session: MediaSession? = null
    private var sessionManager: MediaSessionManager? = null
    private var source: MediaController? = null
    /** A session that just ended; never picked again (Android may still list it for a moment). */
    private var endedToken: MediaSession.Token? = null
    private var listening = false

    /** Called when the mirrored app changes, so each engine's car browse list can refresh. */
    private val sourceChangedListeners = mutableListOf<() -> Unit>()
    fun addSourceChangedListener(listener: () -> Unit) { sourceChangedListeners += listener }
    fun removeSourceChangedListener(listener: () -> Unit) { sourceChangedListeners -= listener }

    /** What the car would show; drives CarPort's "Test car media controls" screen. */
    data class Preview(
        val running: Boolean = false,
        val sourceApp: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val art: Bitmap? = null,
        val playing: Boolean = false,
        val state: String = "Not running",
    )

    private val _preview = MutableStateFlow(Preview())
    val preview: StateFlow<Preview> = _preview.asStateFlow()

    // Why the mirror is running. It stops once none of these holds.
    private var carConnected = false
    private var testMode = false
    private var serviceBound = false

    /** From the Android Auto connection watcher. */
    fun onCarConnection(context: Context, connected: Boolean) {
        val wasConnected = carConnected
        carConnected = connected
        val settings = SettingsStore(context)
        if (connected) {
            start(context)
            if (!wasConnected && (settings.resumeOnConnect || settings.openLinkOnConnect)) scheduleResume()
        } else {
            // Only a real disconnect, not the first "not connected" report when CarPort starts.
            if (wasConnected && settings.pauseOnDisconnect) pauseForDisconnect()
            stopIfUnused()
        }
    }

    /**
     * The test screen's "car connects / disconnects" buttons: run the same resume and pause
     * logic as a real connection change, honouring the same settings, without touching the
     * real connection state. Returns what happened, for the screen.
     */
    fun simulateCarConnection(context: Context, connected: Boolean): String {
        val settings = SettingsStore(context)
        Logbook.log("Car media: simulated car ${if (connected) "connect" else "disconnect"}")
        return if (connected) {
            if (!settings.resumeOnConnect && !settings.openLinkOnConnect) {
                "Resume and open-link on connect are both off, so nothing happens"
            } else {
                scheduleResume()
                "In 2 seconds: resume a paused song, or open your music link if there's nothing to resume"
            }
        } else {
            if (!settings.pauseOnDisconnect) "Pause when the car disconnects is off, so nothing happens"
            else if (pauseForDisconnect()) "Paused the music" else "Nothing was playing"
        }
    }

    /**
     * After connecting: playing → leave it; paused and resume-on-connect on → resume; otherwise,
     * with open-link-on-connect on, open the saved music link in Samsung Browser.
     */
    private val resumeRunnable = Runnable {
        val app = appContext ?: return@Runnable
        if (!(carConnected || testMode)) return@Runnable
        val settings = SettingsStore(app)
        val controller = source
        val state = controller?.playbackState?.state
        when {
            state == PlaybackState.STATE_PLAYING -> Unit
            controller != null && state == PlaybackState.STATE_PAUSED && settings.resumeOnConnect -> {
                Logbook.log("Car media: resuming ${controller.packageName} after connecting")
                controller.transportControls.play()
            }
            settings.openLinkOnConnect -> {
                Logbook.log("Car media: nothing to resume after connecting, opening the music link")
                if (SmartPlay.open(app)) _preview.value = _preview.value.copy(state = "Opened your music link in the browser")
            }
        }
    }

    /** Waits briefly so the car's audio is ready, then resumes a paused song. */
    private fun scheduleResume() {
        handler.removeCallbacks(resumeRunnable)
        handler.postDelayed(resumeRunnable, RESUME_DELAY_MS)
    }

    /** Returns true when something was playing and got paused. */
    private fun pauseForDisconnect(): Boolean {
        handler.removeCallbacks(resumeRunnable)
        val controller = source ?: return false
        if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) return false
        Logbook.log("Car media: pausing ${controller.packageName} because the car disconnected")
        controller.transportControls.pause()
        return true
    }

    private const val RESUME_DELAY_MS = 2_000L

    /** Android Auto bound (or unbound) CarPort's media service. */
    fun onServiceBound(context: Context, bound: Boolean) {
        serviceBound = bound
        if (bound) start(context) else stopIfUnused()
    }

    /** The test screen runs the mirror without a car. */
    fun startTest(context: Context) {
        testMode = true
        start(context)
    }

    fun stopTest() {
        testMode = false
        stopIfUnused()
    }

    private fun stopIfUnused() {
        if (!carConnected && !serviceBound && !testMode) stop()
    }

    /** The Classic engine's session token (Android Auto, when Classic is selected). */
    fun sessionToken(context: Context): MediaSession.Token = ensureSession(context).sessionToken

    /** Token of the selected engine's session: what Android Auto talks to (used by the test screen). */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun carToken(context: Context): MediaSession.Token {
        ensureSession(context)
        return if (MediaEngine.isClassic(context)) sessionToken(context) else Media3Engine.platformToken(context)
    }

    private fun ensureSession(context: Context): MediaSession {
        session?.let { return it }
        val app = context.applicationContext
        appContext = app
        sessionManager = app.getSystemService(MediaSessionManager::class.java)
        return MediaSession(app, "CarPort").apply {
            setCallback(ForwardingCallback(), handler)
            // "Open on phone" from the car's media screen opens CarPort.
            setSessionActivity(
                PendingIntent.getActivity(
                    app, 0,
                    Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            session = this
            mirror()
        }
    }

    /** Starts following the phone's media sessions; harmless to call repeatedly. */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun start(context: Context) {
        ensureSession(context)
        // With Media3 selected, its session must exist before music starts so the car can follow it.
        if (!MediaEngine.isClassic(context)) Media3Engine.ensure(context)
        if (listening) return
        val app = appContext ?: return
        if (!MediaAccessListener.isGranted(app)) {
            mirror()
            return
        }
        val manager = sessionManager ?: return
        try {
            val component = MediaAccessListener.component(app)
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            listening = true
            Logbook.log("Car media: following the phone's media sessions")
            pickSource(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            Logbook.log("Car media: no access to media sessions", e)
            mirror()
        }
    }

    /** Settings changed (e.g. smart play or the media engine): refresh what the car shows. */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun refresh() {
        val app = appContext
        if (app != null && listening && !MediaEngine.isClassic(app)) Media3Engine.ensure(app)
        mirror()
    }

    /** Stops following media sessions (car disconnected, test screen closed, or access revoked). */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun stop() {
        if (listening) {
            runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
            listening = false
            Logbook.log("Car media: stopped")
        }
        setSource(null)
        mirror()
        // A Media3 session can't be made inactive, so drop it when not in the car; the next
        // start() creates it again. (Kept while Android Auto is still bound to the service.)
        if (!serviceBound) Media3Engine.release()
    }

    val sourcePackage: String? get() = source?.packageName
    val sourceTitle: String? get() = source?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
    val sourceArtist: String? get() = source?.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)

    /** The track's artwork, scaled down so a browse result stays well under Android's binder limit. */
    fun sourceArt(): Bitmap? {
        val metadata = source?.metadata ?: return null
        val art = ART_KEYS.firstNotNullOfOrNull { metadata.getBitmap(it) } ?: return null
        val scale = MAX_BROWSE_ART_PX.toFloat() / maxOf(art.width, art.height)
        return if (scale >= 1f) art
        else Bitmap.createScaledBitmap(art, (art.width * scale).toInt().coerceAtLeast(1), (art.height * scale).toInt().coerceAtLeast(1), true)
    }

    private val ART_KEYS = listOf(
        MediaMetadata.METADATA_KEY_ALBUM_ART,
        MediaMetadata.METADATA_KEY_ART,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON,
    )
    private const val MAX_BROWSE_ART_PX = 256

    fun sourceLabel(): String? {
        val pkg = source?.packageName ?: return null
        val pm = appContext?.packageManager ?: return pkg
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        pickSource(controllers.orEmpty())
    }

    private val sourceCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = mirror()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            mirror()
            // Another app may have started playing; prefer whichever is playing now.
            refreshSessions()
        }
        override fun onSessionDestroyed() {
            // Pick the replacement while still knowing which app we followed, skipping the dead session.
            endedToken = source?.sessionToken
            refreshSessions()
            if (source?.sessionToken == endedToken) setSource(null)
        }
    }

    private fun refreshSessions() {
        val app = appContext ?: return
        if (!listening) return
        runCatching { pickSource(sessionManager?.getActiveSessions(MediaAccessListener.component(app)).orEmpty()) }
    }

    /**
     * Prefers a playing session; otherwise keeps the current one. If the current app replaced its
     * session (Samsung Browser does when the track changes), stays with that app instead of
     * jumping to another app's paused session for a moment.
     */
    private fun pickSource(controllers: List<MediaController>) {
        val own = appContext?.packageName
        val ended = endedToken
        val candidates = controllers.filter { it.packageName != own && it.sessionToken != ended }
        val playing = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val current = source?.let { cur -> candidates.firstOrNull { it.sessionToken == cur.sessionToken } }
        val followedApp = source?.packageName ?: lastFollowedPackage
        val sameApp = followedApp?.let { pkg -> candidates.firstOrNull { it.packageName == pkg } }
        // Before anything was followed, take the most recent session (e.g. a paused song to resume).
        // After that, only switch to a different app when it's actually playing.
        val fallback = if (followedApp == null) candidates.firstOrNull() else null
        setSource(playing ?: current ?: sameApp ?: fallback)
    }

    /** The app last followed, kept while its session is being replaced. */
    private var lastFollowedPackage: String? = null

    private fun setSource(controller: MediaController?) {
        if (controller?.sessionToken == source?.sessionToken) {
            mirror()
            return
        }
        source?.unregisterCallback(sourceCallback)
        source = controller
        if (controller != null) lastFollowedPackage = controller.packageName
        controller?.registerCallback(sourceCallback, handler)
        Logbook.log("Car media: mirroring ${controller?.packageName ?: "nothing"}")
        mirror()
        sourceChangedListeners.toList().forEach { it() }
    }

    /** Copies the source's metadata and playback state into CarPort's session. */
    private fun mirror() {
        val session = session ?: return
        val app = appContext ?: return
        val controller = source
        if (controller == null) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Nothing is playing")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Start music on your phone, e.g. in Samsung Browser")
                    .build()
            )
            val smartPlay = SettingsStore(app).smartPlayEnabled
            session.setPlaybackState(
                // With smart play on, keep a play button so pressing it can open the saved link.
                PlaybackState.Builder().setActions(if (smartPlay) SMART_PLAY_ACTIONS else 0).apply {
                    if (MediaAccessListener.isGranted(app)) {
                        setState(if (smartPlay) PlaybackState.STATE_STOPPED else PlaybackState.STATE_NONE, 0, 0f)
                    } else {
                        setState(PlaybackState.STATE_ERROR, 0, 0f)
                        setErrorMessage("Open CarPort on your phone and allow notification access for car media controls.")
                    }
                }.build()
            )
            // Don't claim media buttons or Android Auto's attention when nothing is playing.
            session.isActive = false
            _preview.value = Preview(
                running = listening,
                state = when {
                    !MediaAccessListener.isGranted(app) -> "Notification access is off"
                    !listening -> "Not running"
                    else -> "Nothing is playing"
                },
            )
            notifyChange()
            return
        }

        val metadata = controller.metadata
        session.setMetadata(
            MediaMetadata.Builder(metadata ?: MediaMetadata.Builder().build()).apply {
                if (metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()) {
                    putString(MediaMetadata.METADATA_KEY_TITLE, "Playing")
                }
                if (metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).isNullOrBlank()) {
                    putString(MediaMetadata.METADATA_KEY_ARTIST, sourceLabel())
                }
            }.build()
        )
        val sourceState = controller.playbackState
        session.setPlaybackState(
            if (sourceState != null) {
                PlaybackState.Builder(sourceState)
                    .setActions(
                        sourceState.actions and FORWARDED_ACTIONS or ALWAYS_SHOWN_ACTIONS or
                            PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH
                    )
                    .apply {
                        if (skipButtonsShown()) {
                            addCustomAction(PlaybackState.CustomAction.Builder(ACTION_REWIND_10, "Back 10 seconds", R.drawable.ic_media_rewind).build())
                            addCustomAction(PlaybackState.CustomAction.Builder(ACTION_FORWARD_10, "Forward 10 seconds", R.drawable.ic_media_forward).build())
                        }
                    }
                    .build()
            } else {
                PlaybackState.Builder().setState(PlaybackState.STATE_NONE, 0, 0f).build()
            }
        )
        // Only the selected engine's session may be active, so the car sees one CarPort player.
        session.isActive = MediaEngine.isClassic(app)
        recordRecentlyPlayed(app, controller, sourceState)
        _preview.value = Preview(
            running = listening,
            sourceApp = sourceLabel(),
            title = sourceTitle,
            artist = sourceArtist,
            art = sourceArt(),
            playing = sourceState?.state == PlaybackState.STATE_PLAYING,
            state = stateName(sourceState?.state),
        )
        notifyChange()
    }

    /** Adds the song to Recently played when it starts playing (once per song). */
    private fun recordRecentlyPlayed(app: Context, controller: MediaController, state: PlaybackState?) {
        if (state?.state != PlaybackState.STATE_PLAYING) return
        val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return
        val artist = controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val key = "${controller.packageName}|$title|$artist"
        if (key == lastRecorded) return
        lastRecorded = key
        RecentlyPlayed.add(app, title, artist, sourceLabel())
    }

    private var lastRecorded: String? = null

    private fun notifyChange() {
        // Copy: a listener may remove itself while being called.
        changeListeners.toList().forEach { it() }
    }

    private fun stateName(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "Playing"
        PlaybackState.STATE_PAUSED -> "Paused"
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "Loading"
        PlaybackState.STATE_STOPPED -> "Stopped"
        PlaybackState.STATE_ERROR -> "Error"
        else -> "Idle"
    }

    // ---- Commands from the car, shared by both media engines (Classic and Media3) ----

    /** Resumes the playing app, or, with nothing to resume, opens the smart play link. */
    fun playOrOpenLink() {
        val controller = source
        if (controller != null) {
            controller.transportControls.play()
            return
        }
        val app = appContext ?: return
        if (SmartPlay.openIfEnabled(app)) {
            _preview.value = _preview.value.copy(state = "Opened your music link in the browser")
        } else {
            Logbook.log("Car media: play pressed, but nothing is playing and smart play didn't open anything")
        }
    }

    fun pause() { source?.transportControls?.pause() }
    fun stopPlayback() { source?.transportControls?.stop() }
    fun seekTo(positionMs: Long) { source?.transportControls?.seekTo(positionMs) }
    fun fastForward() { source?.transportControls?.fastForward() }
    fun rewind() { source?.transportControls?.rewind() }
    /** CarPort's own −10 s / +10 s buttons are handled here; anything else goes to the playing app. */
    fun sendCustomAction(action: String, extras: Bundle?) {
        when (action) {
            ACTION_REWIND_10 -> seekBy(-SKIP_MS)
            ACTION_FORWARD_10 -> seekBy(SKIP_MS)
            else -> source?.transportControls?.sendCustomAction(action, extras)
        }
    }

    /** Jumps [deltaMs] from the current position, kept within the song. */
    fun seekBy(deltaMs: Long) {
        val controller = source ?: return
        val state = controller.playbackState ?: return
        val speed = if (state.state == PlaybackState.STATE_PLAYING) state.playbackSpeed else 0f
        val now = state.position + ((android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * speed).toLong()
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (now + deltaMs).coerceIn(0L, duration)
        Logbook.log("Car media: ${if (deltaMs < 0) "back" else "forward"} ${kotlin.math.abs(deltaMs) / 1000} s")
        controller.transportControls.seekTo(target)
    }

    /** −10 s / +10 s buttons are shown when turned on and the playing app allows seeking. */
    fun skipButtonsShown(): Boolean {
        val app = appContext ?: return false
        val seekable = (source?.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L
        return seekable && SettingsStore(app).skipButtons
    }

    const val ACTION_REWIND_10 = "dev.hadapkar.carport.REWIND_10"
    const val ACTION_FORWARD_10 = "dev.hadapkar.carport.FORWARD_10"
    private const val SKIP_MS = 10_000L
    fun skipToNext() = skip(PlaybackState.ACTION_SKIP_TO_NEXT, KeyEvent.KEYCODE_MEDIA_NEXT) { it.skipToNext() }
    fun skipToPrevious() = skip(PlaybackState.ACTION_SKIP_TO_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PREVIOUS) { it.skipToPrevious() }

    /** A browse item was played: the "Open my music link" tile, or the now-playing item. */
    fun playFromMediaId(mediaId: String?) {
        if (mediaId == OPEN_LINK_MEDIA_ID) {
            // The car's "Open my music link" tile: the user asked for it explicitly.
            val app = appContext ?: return
            if (SmartPlay.open(app)) _preview.value = _preview.value.copy(state = "Opened your music link in the browser")
            return
        }
        playOrOpenLink()
    }

    /**
     * Steering-wheel and headset buttons: pass the raw key press to the playing app, exactly as
     * if a Bluetooth headset sent it, so the app decides (e.g. a website's next track). Returns
     * true when handled.
     */
    fun forwardMediaKey(event: KeyEvent?): Boolean {
        event ?: return false
        if (handleHoldToSeek(event)) return true
        val controller = source
        if (controller == null &&
            (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        ) {
            // Nothing to resume: the smart play button may open the saved music link.
            if (event.action == KeyEvent.ACTION_DOWN) playOrOpenLink()
            return true
        }
        if (controller != null && controller.dispatchMediaButtonEvent(event)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Logbook.log("Car media: forwarded key ${event.keyCode} to ${controller.packageName}")
            }
            return true
        }
        return false
    }

    // Hold-to-seek state: whether the next/previous key being held became a hold.
    private var heldLong = false
    private var heldForward = false
    private var lastHoldSeek = 0L

    /** Tap fallback: if the release never arrives, the press still changes the song. */
    private val tapFallback = Runnable {
        Logbook.log("Car media: no key release received, treating the press as a tap")
        heldLong = false
        if (heldForward) skipToNext() else skipToPrevious()
    }

    /**
     * "Hold next/previous to skip 10 s" (setting, off by default). A tap still changes the song,
     * on release; holding (the car repeats the key) jumps 10 s, again every [HOLD_SEEK_INTERVAL_MS]
     * while held. Cars that send fast-forward/rewind keys for a hold get the same jumps.
     *
     * Media3 passes only the first press of a key to apps (no repeats, no release), so a hold
     * can't be seen there: with the Media3 engine next/previous act at once, and only dedicated
     * fast-forward/rewind keys jump 10 s. Returns true when the key was handled here.
     */
    private fun handleHoldToSeek(event: KeyEvent): Boolean {
        val app = appContext ?: return false
        if (!SettingsStore(app).holdToSeek) return false
        val forward = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> true
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> false
            else -> return false
        }
        val seekKey = event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
        // Media3: next/previous go through normally (forwarded at once); only seek keys are handled.
        if (!seekKey && !MediaEngine.isClassic(app)) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            Logbook.log("Car media: key ${event.keyCode} down, repeat ${event.repeatCount}")
        }
        val now = android.os.SystemClock.elapsedRealtime()
        when {
            // A dedicated fast-forward / rewind key: jump on each press or repeat.
            seekKey && event.action == KeyEvent.ACTION_DOWN -> {
                if (now - lastHoldSeek >= HOLD_SEEK_INTERVAL_MS || event.repeatCount == 0) {
                    lastHoldSeek = now
                    seekBy(if (forward) SKIP_MS else -SKIP_MS)
                }
            }
            seekKey -> Unit
            // First press of next/previous: wait to see whether it becomes a hold.
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> {
                heldLong = false
                heldForward = forward
                handler.removeCallbacks(tapFallback)
                handler.postDelayed(tapFallback, TAP_FALLBACK_MS)
            }
            // Repeats while held: a hold, so jump instead of changing the song.
            event.action == KeyEvent.ACTION_DOWN -> {
                heldLong = true
                handler.removeCallbacks(tapFallback)
                if (now - lastHoldSeek >= HOLD_SEEK_INTERVAL_MS) {
                    lastHoldSeek = now
                    seekBy(if (forward) SKIP_MS else -SKIP_MS)
                }
            }
            // Released: a tap changes the song; a hold already jumped.
            event.action == KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(tapFallback)
                if (!heldLong) {
                    if (forward) skipToNext() else skipToPrevious()
                }
                heldLong = false
            }
        }
        return true
    }

    private const val HOLD_SEEK_INTERVAL_MS = 500L
    /** A hold repeats within about half a second; no repeat and no release after this means a tap. */
    private const val TAP_FALLBACK_MS = 1_500L

    /** Uses the app's own action when it offers one, otherwise a media key press like a headset. */
    private fun skip(action: Long, keyCode: Int, viaAction: (MediaController.TransportControls) -> Unit) {
        val controller = source ?: return
        if ((controller.playbackState?.actions ?: 0L) and action != 0L) {
            viaAction(controller.transportControls)
        } else {
            controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    // ---- For the Media3 engine ----

    /** The mirrored app's current metadata and playback state (null when nothing is followed). */
    val sourceMetadata: MediaMetadata? get() = source?.metadata
    val sourcePlaybackState: PlaybackState? get() = source?.playbackState

    private val changeListeners = mutableListOf<() -> Unit>()

    /** Called after every change the car should see (track, state, smart play setting). */
    fun addChangeListener(listener: () -> Unit) { changeListeners += listener }
    fun removeChangeListener(listener: () -> Unit) { changeListeners -= listener }

    /** The Classic engine's session: Android's MediaSession calling the shared commands. */
    private class ForwardingCallback : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean =
            forwardMediaKey(mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)) ||
                super.onMediaButtonEvent(mediaButtonIntent)

        override fun onPlay() = playOrOpenLink()
        override fun onPause() = pause()
        override fun onStop() = stopPlayback()
        override fun onSkipToNext() = skipToNext()
        override fun onSkipToPrevious() = skipToPrevious()
        override fun onSeekTo(pos: Long) = seekTo(pos)
        override fun onFastForward() = fastForward()
        override fun onRewind() = rewind()
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) = playFromMediaId(mediaId)
        // "Hey Google, play …" in the car: resume whatever the phone was playing.
        override fun onPlayFromSearch(query: String?, extras: Bundle?) = playOrOpenLink()
        override fun onCustomAction(action: String, extras: Bundle?) = sendCustomAction(action, extras)
    }

    private const val FORWARDED_ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND

    /** Media id of the car browse tile that opens the saved music link. */
    const val OPEN_LINK_MEDIA_ID = "open_music_link"

    private const val SMART_PLAY_ACTIONS =PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH

    /** Shown in the car even if the app doesn't advertise them; presses fall back to media keys. */
    private const val ALWAYS_SHOWN_ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
}
