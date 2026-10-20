package dev.hadapkar.carport.media

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaSession as PlatformSession
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.hadapkar.carport.MainActivity
import dev.hadapkar.carport.data.Logbook
import dev.hadapkar.carport.data.SettingsStore

/**
 * Which media engine Android Auto sees. Classic is Android's own MediaSession and is the proven
 * fallback; Media3 is Google's current library. Only one service is enabled at a time, so the
 * car never shows two CarPort players.
 */
object MediaEngine {
    fun isClassic(context: Context): Boolean = !SettingsStore(context).useMedia3

    /** Enables the selected engine's service and disables the other. Safe to call often. */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun apply(context: Context) {
        val classic = isClassic(context)
        setEnabled(context, CarMediaService::class.java, classic)
        setEnabled(context, CarMedia3Service::class.java, !classic)
        if (classic) Media3Engine.release()
        // Re-evaluates which session is active and creates the Media3 one if it's now selected.
        MediaMirror.refresh()
    }

    private fun setEnabled(context: Context, service: Class<*>, enabled: Boolean) {
        val pm = context.packageManager
        val component = ComponentName(context, service)
        val wanted = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (pm.getComponentEnabledSetting(component) != wanted) {
            pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
            Logbook.log("Car media: ${service.simpleName} ${if (enabled) "enabled" else "disabled"}")
        }
    }
}

/**
 * The Media3 engine's session, created with the mirror (not only when Android Auto binds), so it
 * is already playing when music starts on the phone, as with the Classic engine.
 */
@UnstableApi
object Media3Engine {
    private var session: MediaLibrarySession? = null
    private var appContext: Context? = null
    private var refreshListener: (() -> Unit)? = null
    private var browseListener: (() -> Unit)? = null

    fun ensure(context: Context): MediaLibrarySession {
        session?.let { return it }
        val app = context.applicationContext
        appContext = app
        val player = MirrorPlayer(Looper.getMainLooper())
        val created = MediaLibrarySession.Builder(app, player, LibraryCallback)
            .setId("CarPort")
            .setSessionActivity(
                PendingIntent.getActivity(
                    app, 1,
                    Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        session = created
        // Refresh the player on every change; update the ±10 s buttons only when they come or go.
        var buttonsShown: Boolean? = null
        refreshListener = {
            player.refresh()
            val shown = MediaMirror.skipButtonsShown()
            if (shown != buttonsShown) {
                buttonsShown = shown
                created.setMediaButtonPreferences(skipButtons())
            }
        }
        MediaMirror.addChangeListener(refreshListener!!)
        // Refresh the car's browse list (now playing + music link tile) when the source changes.
        browseListener = { created.notifyChildrenChanged(ROOT_ID, Int.MAX_VALUE, null) }
        MediaMirror.addSourceChangedListener(browseListener!!)
        Logbook.log("Car media: Media3 session created")
        return created
    }

    fun platformToken(context: Context): PlatformSession.Token = ensure(context).platformToken

    /** Switching back to Classic: release everything so only one CarPort player exists. */
    fun release() {
        val current = session ?: return
        session = null
        // Remove the same function object that was added (a new ::refresh reference wouldn't match).
        refreshListener?.let(MediaMirror::removeChangeListener)
        refreshListener = null
        browseListener?.let(MediaMirror::removeSourceChangedListener)
        browseListener = null
        current.player.release()
        current.release()
        Logbook.log("Car media: Media3 session released")
    }

    private const val ROOT_ID = "root"

    private val rewindCommand = SessionCommand(MediaMirror.ACTION_REWIND_10, Bundle.EMPTY)
    private val forwardCommand = SessionCommand(MediaMirror.ACTION_FORWARD_10, Bundle.EMPTY)

    /** The car player's −10 s / +10 s buttons, when turned on and the playing app allows seeking. */
    private fun skipButtons(): List<CommandButton> =
        if (!MediaMirror.skipButtonsShown()) {
            emptyList()
        } else {
            listOf(
                CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
                    .setSessionCommand(rewindCommand)
                    .setDisplayName("Back 10 seconds")
                    .build(),
                CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
                    .setSessionCommand(forwardCommand)
                    .setDisplayName("Forward 10 seconds")
                    .build(),
            )
        }

    /** Browse tree (now playing + music link tile), ±10 s buttons and raw steering-wheel keys. */
    private object LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(rewindCommand)
                        .add(forwardCommand)
                        .build()
                )
                .setMediaButtonPreferences(skipButtons())
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == MediaMirror.ACTION_REWIND_10 || customCommand.customAction == MediaMirror.ACTION_FORWARD_10) {
                MediaMirror.sendCustomAction(customCommand.customAction, args)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).setTitle("CarPort").build())
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != ROOT_ID) return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
            val items = ImmutableList.builder<MediaItem>()
            val nowPlaying = MirrorPlayer.toMedia3(MediaMirror.sourceMetadata).buildUpon()
                .setSubtitle(MediaMirror.sourceLabel() ?: "Play something on your phone")
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
            items.add(MediaItem.Builder().setMediaId(MirrorPlayer.NOW_PLAYING_UID).setMediaMetadata(nowPlaying).build())
            val app = appContext
            if (app != null && SmartPlay.hasLink(app)) {
                items.add(
                    MediaItem.Builder()
                        .setMediaId(MediaMirror.OPEN_LINK_MEDIA_ID)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("Open my music link")
                                .setSubtitle("Opens your saved link in Samsung Browser")
                                .setIsPlayable(true)
                                .setIsBrowsable(false)
                                .build()
                        )
                        .build()
                )
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(items.build(), params))
        }

        /** Browse items carry only an id; the player handles them by id, so pass them through. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems)

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean = MediaMirror.forwardMediaKey(
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java)
        )
    }
}
