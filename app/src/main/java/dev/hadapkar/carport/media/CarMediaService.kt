package dev.hadapkar.carport.media

import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.os.Bundle
import android.service.media.MediaBrowserService

/**
 * Android Auto's entry point for CarPort as a media app. Android Auto draws the same player
 * as for YT Music from what the session provides: artwork, title, artist, the progress bar
 * (duration + position) and the control buttons, all mirrored from the playing app.
 *
 * The session and all mirroring live in [MediaMirror], which runs while the phone is
 * connected to Android Auto, so the car follows and controls the phone's music without
 * CarPort being opened first.
 */
class CarMediaService : MediaBrowserService() {

    override fun onCreate() {
        super.onCreate()
        sessionToken = MediaMirror.sessionToken(this)
        MediaMirror.onSourceChanged = { notifyChildrenChanged(ROOT_ID) }
        // Android Auto binding this service means the phone is in the car.
        MediaMirror.onServiceBound(this, bound = true)
    }

    override fun onDestroy() {
        MediaMirror.onSourceChanged = null
        MediaMirror.onServiceBound(this, bound = false)
        super.onDestroy()
    }

    /** Any client may browse; the only content is the mirrored "now playing" item. */
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        // Access may have been granted since this service started.
        MediaMirror.start(this)
        val extras = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST)
        }
        return BrowserRoot(ROOT_ID, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        if (parentId != ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }
        val playing = MediaMirror.sourcePackage != null
        val description = MediaDescription.Builder()
            .setMediaId(NOW_PLAYING_ID)
            .setTitle(MediaMirror.sourceTitle ?: if (playing) "Now playing" else "Nothing is playing")
            .setSubtitle(
                listOfNotNull(MediaMirror.sourceArtist, MediaMirror.sourceLabel()).joinToString(" · ")
                    .ifBlank { "Start music on your phone, e.g. in Samsung Browser" }
            )
            .setIconBitmap(MediaMirror.sourceArt())
            .build()
        val items = mutableListOf(MediaBrowser.MediaItem(description, MediaBrowser.MediaItem.FLAG_PLAYABLE))
        if (SmartPlay.hasLink(this)) {
            val openLink = MediaDescription.Builder()
                .setMediaId(MediaMirror.OPEN_LINK_MEDIA_ID)
                .setTitle("Open my music link")
                .setSubtitle("Opens your saved link in Samsung Browser")
                .build()
            items += MediaBrowser.MediaItem(openLink, MediaBrowser.MediaItem.FLAG_PLAYABLE)
        }
        result.sendResult(items)
    }

    private companion object {
        const val ROOT_ID = "root"
        const val NOW_PLAYING_ID = "now_playing"

        // Android Auto content-style hints (androidx.media.utils.MediaConstants).
        const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val CONTENT_STYLE_LIST = 1
    }
}
