package dev.hadapkar.carport.media

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession

/**
 * Android Auto's entry point when the Media3 engine is selected (Settings › Media engine).
 * Disabled by default; [MediaEngine.apply] enables it instead of the Classic [CarMediaService].
 * The session lives in [Media3Engine] with the mirror, so the car can follow the phone's music
 * without CarPort being opened first.
 */
@UnstableApi
class CarMedia3Service : MediaLibraryService() {

    override fun onCreate() {
        super.onCreate()
        // Android Auto binding this service means the phone is in the car.
        MediaMirror.onServiceBound(this, bound = true)
    }

    /** No session while Classic is selected, so the car can never see two CarPort players. */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        if (MediaEngine.isClassic(this)) null else Media3Engine.ensure(this)

    /**
     * No notification: the playing app (e.g. Samsung Browser) already shows its own music
     * notification, and CarPort plays no audio, so it never needs to run in the foreground.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) = Unit

    override fun onDestroy() {
        MediaMirror.onServiceBound(this, bound = false)
        // The session belongs to the mirror and outlives this service; don't release it here.
        super.onDestroy()
    }
}
