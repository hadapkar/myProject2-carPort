package dev.hadapkar.carport.media

import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.os.Bundle
import android.service.media.MediaBrowserService

/**
 * Android Auto's entry point for CarPort as a media app. The session and all mirroring live in
 * [MediaMirror], which runs in the background whenever notification access is granted, so the
 * car can follow and control the phone's music without CarPort being opened first.
 */
class CarMediaService : MediaBrowserService() {

    override fun onCreate() {
        super.onCreate()
        sessionToken = MediaMirror.sessionToken(this)
        MediaMirror.onSourceChanged = { notifyChildrenChanged(ROOT_ID) }
        MediaMirror.start(this)
    }

    override fun onDestroy() {
        MediaMirror.onSourceChanged = null
        super.onDestroy()
    }

    /** Any client may browse; the only content is the mirrored "now playing" item. */
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        // Access may have been granted since this service started.
        MediaMirror.start(this)
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        if (parentId != ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }
        val description = MediaDescription.Builder()
            .setMediaId(NOW_PLAYING_ID)
            .setTitle(MediaMirror.sourceTitle ?: "Now playing")
            .setSubtitle(MediaMirror.sourceLabel() ?: "Play something on your phone")
            .build()
        result.sendResult(mutableListOf(MediaBrowser.MediaItem(description, MediaBrowser.MediaItem.FLAG_PLAYABLE)))
    }

    private companion object {
        const val ROOT_ID = "root"
        const val NOW_PLAYING_ID = "now_playing"
    }
}
