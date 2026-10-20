package dev.hadapkar.carport.media

import android.graphics.Bitmap
import android.media.MediaMetadata as PlatformMetadata
import android.media.session.PlaybackState
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream

/**
 * A Media3 [Player] that plays nothing itself: it reports the mirrored app's track, artwork,
 * position and play state (from [MediaMirror]) and sends every command back through the same
 * shared commands the Classic engine uses, so both engines behave identically.
 */
@UnstableApi
class MirrorPlayer(looper: Looper) : SimpleBasePlayer(looper) {

    /**
     * Media3 turns "play this browse item" and "Hey Google, play …" into setMediaItems followed
     * by play. The item is only remembered here, and the single action happens on play, so a
     * tap does exactly one thing (as in the Classic engine).
     */
    private var pendingMediaId: String? = null

    /** The mirror changed: rebuild the state Android Auto sees. */
    fun refresh() = invalidateState()

    // Media3 metadata (with compressed artwork) for the current source metadata, built once per track.
    private var cachedFor: PlatformMetadata? = null
    private var cachedMetadata: MediaMetadata? = null

    private fun media3Metadata(metadata: PlatformMetadata?): MediaMetadata {
        if (metadata !== cachedFor || cachedMetadata == null) {
            cachedFor = metadata
            cachedMetadata = toMedia3(metadata)
        }
        return cachedMetadata!!
    }

    override fun getState(): State {
        val playback = MediaMirror.sourcePlaybackState
        val metadata = MediaMirror.sourceMetadata
        val following = MediaMirror.sourcePackage != null

        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_PREPARE,
            )
            .apply {
                if (playback != null && (playback.actions and PlaybackState.ACTION_SEEK_TO) != 0L) {
                    add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                }
            }
            .build()

        val builder = State.Builder().setAvailableCommands(commands)
        if (!following) {
            // Nothing to mirror: an empty, idle player. Play still reaches handleSetPlayWhenReady,
            // which may open the smart play link.
            return builder
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                .build()
        }

        val durationMs = metadata?.getLong(PlatformMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        val media3 = media3Metadata(metadata)
        val item = SimpleBasePlayer.MediaItemData.Builder(NOW_PLAYING_UID)
            .setMediaItem(MediaItem.Builder().setMediaId(NOW_PLAYING_UID).setMediaMetadata(media3).build())
            .setMediaMetadata(media3)
            .setDurationUs(durationMs?.times(1000) ?: androidx.media3.common.C.TIME_UNSET)
            .setIsSeekable(commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
            .build()

        val playing = playback?.state == PlaybackState.STATE_PLAYING
        val playerState = when (playback?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED -> Player.STATE_READY
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        return builder
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(playerState)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs(positionSupplier(playback))
            .build()
    }

    /** The source's position, moving on while it plays (like Android's own PlaybackState). */
    private fun positionSupplier(playback: PlaybackState?): PositionSupplier {
        playback ?: return PositionSupplier.ZERO
        val speed = if (playback.state == PlaybackState.STATE_PLAYING) playback.playbackSpeed else 0f
        val elapsed = ((SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime) * speed).toLong()
        val now = (playback.position + elapsed).coerceAtLeast(0)
        return if (speed > 0f) PositionSupplier.getExtrapolating(now, speed) else PositionSupplier.getConstant(now)
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val requested = pendingMediaId
        pendingMediaId = null
        when {
            !playWhenReady -> MediaMirror.pause()
            // "Open my music link" tile: open the link only (playFromMediaId handles the id).
            requested == MediaMirror.OPEN_LINK_MEDIA_ID -> MediaMirror.playFromMediaId(requested)
            else -> MediaMirror.playOrOpenLink()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> MediaMirror.skipToNext()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> MediaMirror.skipToPrevious()
            else -> if (positionMs >= 0) MediaMirror.seekTo(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        MediaMirror.stopPlayback()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    /** A browse item (or a voice request) was chosen; remember it, the action happens on play. */
    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        pendingMediaId = mediaItems.getOrNull(startIndex.coerceAtLeast(0))?.mediaId
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        pendingMediaId = mediaItems.firstOrNull()?.mediaId
        return Futures.immediateVoidFuture()
    }

    companion object {
        const val NOW_PLAYING_UID = "now_playing"
        private const val MAX_ART_PX = 512

        /** Android's media metadata to Media3's, with the artwork as compressed bytes. */
        fun toMedia3(metadata: PlatformMetadata?): MediaMetadata {
            val builder = MediaMetadata.Builder()
            if (metadata == null) return builder.setTitle("Nothing is playing").build()
            builder
                .setTitle(metadata.getString(PlatformMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: "Playing")
                .setArtist(metadata.getString(PlatformMetadata.METADATA_KEY_ARTIST) ?: MediaMirror.sourceLabel())
                .setAlbumTitle(metadata.getString(PlatformMetadata.METADATA_KEY_ALBUM))
                .setIsPlayable(true)
                .setIsBrowsable(false)
            val art = listOf(
                PlatformMetadata.METADATA_KEY_ALBUM_ART,
                PlatformMetadata.METADATA_KEY_ART,
                PlatformMetadata.METADATA_KEY_DISPLAY_ICON,
            ).firstNotNullOfOrNull { metadata.getBitmap(it) }
            if (art != null) builder.setArtworkData(compress(art), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            return builder.build()
        }

        private fun compress(art: Bitmap): ByteArray {
            val scale = MAX_ART_PX.toFloat() / maxOf(art.width, art.height)
            val sized = if (scale >= 1f) art
            else Bitmap.createScaledBitmap(art, (art.width * scale).toInt().coerceAtLeast(1), (art.height * scale).toInt().coerceAtLeast(1), true)
            return ByteArrayOutputStream().also { sized.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        }
    }
}
