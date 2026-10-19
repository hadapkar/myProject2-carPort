package dev.hadapkar.carport.media

import android.content.ComponentName
import android.content.Context
import android.app.NotificationManager
import android.service.notification.NotificationListenerService

/**
 * Exists only so the user can grant CarPort notification access, which Android requires
 * before an app may see other apps' media sessions (MediaSessionManager.getActiveSessions).
 * CarPort never reads notification contents.
 */
class MediaAccessListener : NotificationListenerService() {
    companion object {
        fun component(context: Context) = ComponentName(context, MediaAccessListener::class.java)

        fun isGranted(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(component(context)) == true
    }
}
