package dev.hadapkar.carport.media

import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import dev.hadapkar.carport.data.Logbook

/**
 * Android Auto's media app for "whatever is playing on the phone".
 *
 * Apps such as Samsung Browser play web audio with a MediaSession but don't offer Android
 * Auto's media service, so the car can't show them. CarPort mirrors the phone's active media
 * session into its own session, which Android Auto shows with title, artwork and controls,
 * and forwards play/pause/next/previous/seek back to the playing app. It works with the
 * phone locked. Needs notification access ([MediaAccessListener]) to see other apps' sessions.
 */
class CarMediaService : MediaBrowserService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSession
    private var sessionManager: MediaSessionManager? = null
    private var source: MediaController? = null
    private var listening = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        pickSource(controllers.orEmpty())
    }

    private val sourceCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = mirror()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            mirror()
            // A different app may have started playing; prefer whichever is playing now.
            refreshSessions()
        }
        override fun onSessionDestroyed() {
            setSource(null)
            refreshSessions()
        }
    }

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "CarPort").apply {
            setCallback(ForwardingCallback(), handler)
            isActive = true
        }
        sessionToken = session.sessionToken
        sessionManager = getSystemService(MediaSessionManager::class.java)
        startListening()
        mirror()
    }

    override fun onDestroy() {
        stopListening()
        setSource(null)
        session.release()
        super.onDestroy()
    }

    /** Any client may browse; the only content is the mirrored "now playing" item. */
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        // Access may have been granted since this service started.
        startListening()
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        if (parentId != ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }
        val metadata = source?.metadata
        val description = MediaDescription.Builder()
            .setMediaId(NOW_PLAYING_ID)
            .setTitle(metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Now playing")
            .setSubtitle(sourceLabel() ?: "Play something on your phone")
            .build()
        result.sendResult(mutableListOf(MediaBrowser.MediaItem(description, MediaBrowser.MediaItem.FLAG_PLAYABLE)))
    }

    private fun startListening() {
        if (listening || !MediaAccessListener.isGranted(this)) {
            if (!listening) mirror()
            return
        }
        val manager = sessionManager ?: return
        try {
            val component = MediaAccessListener.component(this)
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            listening = true
            pickSource(manager.getActiveSessions(component))
            Logbook.log("Car media: listening for media sessions")
        } catch (e: SecurityException) {
            Logbook.log("Car media: no access to media sessions", e)
            mirror()
        }
    }

    private fun stopListening() {
        if (!listening) return
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        listening = false
    }

    private fun refreshSessions() {
        if (!listening) return
        val manager = sessionManager ?: return
        runCatching { pickSource(manager.getActiveSessions(MediaAccessListener.component(this))) }
    }

    /** Prefers a session that is playing; otherwise keeps the current one or takes the most recent. */
    private fun pickSource(controllers: List<MediaController>) {
        val candidates = controllers.filter { it.packageName != packageName }
        val playing = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val current = source?.let { cur -> candidates.firstOrNull { it.sessionToken == cur.sessionToken } }
        setSource(playing ?: current ?: candidates.firstOrNull())
    }

    private fun setSource(controller: MediaController?) {
        if (controller?.sessionToken == source?.sessionToken) {
            mirror()
            return
        }
        source?.unregisterCallback(sourceCallback)
        source = controller
        controller?.registerCallback(sourceCallback, handler)
        Logbook.log("Car media: source is ${controller?.packageName ?: "none"}")
        mirror()
        notifyChildrenChanged(ROOT_ID)
    }

    /** Copies the source's metadata and playback state into CarPort's session. */
    private fun mirror() {
        val controller = source
        if (controller == null) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Nothing is playing")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Start music on your phone, e.g. in Samsung Browser")
                    .build()
            )
            val state = PlaybackState.Builder()
                .setActions(0)
                .apply {
                    if (MediaAccessListener.isGranted(this@CarMediaService)) {
                        setState(PlaybackState.STATE_NONE, 0, 0f)
                    } else {
                        setState(PlaybackState.STATE_ERROR, 0, 0f)
                        setErrorMessage("Open CarPort on your phone and allow notification access for car media controls.")
                    }
                }
                .build()
            session.setPlaybackState(state)
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
        val state = if (sourceState != null) {
            PlaybackState.Builder(sourceState)
                .setActions(
                    sourceState.actions and FORWARDED_ACTIONS or
                        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH
                )
                .build()
        } else {
            PlaybackState.Builder().setState(PlaybackState.STATE_NONE, 0, 0f).build()
        }
        session.setPlaybackState(state)
    }

    private fun sourceLabel(): String? {
        val pkg = source?.packageName ?: return null
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    /** Sends the car's button presses to the app that is actually playing. */
    private inner class ForwardingCallback : MediaSession.Callback() {
        private val controls get() = source?.transportControls
        override fun onPlay() { controls?.play() }
        override fun onPause() { controls?.pause() }
        override fun onStop() { controls?.stop() }
        override fun onSkipToNext() { controls?.skipToNext() }
        override fun onSkipToPrevious() { controls?.skipToPrevious() }
        override fun onSeekTo(pos: Long) { controls?.seekTo(pos) }
        override fun onFastForward() { controls?.fastForward() }
        override fun onRewind() { controls?.rewind() }
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) { controls?.play() }
        // "Hey Google, play …" in the car: resume whatever the phone was playing.
        override fun onPlayFromSearch(query: String?, extras: Bundle?) { controls?.play() }
        override fun onCustomAction(action: String, extras: Bundle?) { controls?.sendCustomAction(action, extras) }
    }

    private companion object {
        const val ROOT_ID = "root"
        const val NOW_PLAYING_ID = "now_playing"
        const val FORWARDED_ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
    }
}
