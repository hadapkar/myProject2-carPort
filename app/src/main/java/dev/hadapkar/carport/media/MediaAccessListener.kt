package dev.hadapkar.carport.media

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

/**
 * Granting CarPort notification access (required to see other apps' media sessions) makes
 * Android keep this listener running, so it starts [MediaMirror] in the background: the car
 * can then follow and control the phone's music without CarPort being opened.
 * CarPort never reads notification contents.
 */
class MediaAccessListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaMirror.start(this)
    }

    override fun onListenerDisconnected() {
        MediaMirror.stop()
        super.onListenerDisconnected()
    }

    companion object {
        fun component(context: Context) = ComponentName(context, MediaAccessListener::class.java)

        fun isGranted(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(component(context)) == true
    }
}
