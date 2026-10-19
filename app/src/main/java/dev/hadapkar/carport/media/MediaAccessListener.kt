package dev.hadapkar.carport.media

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import dev.hadapkar.carport.data.Logbook

/**
 * Granting CarPort notification access (required to see other apps' media sessions) makes
 * Android keep this listener running. It watches the Android Auto connection and runs
 * [MediaMirror] only while the phone is connected to a car, so nothing runs otherwise.
 * CarPort never reads notification contents.
 */
class MediaAccessListener : NotificationListenerService() {
    private var connectionType: LiveData<Int>? = null

    private val connectionObserver = Observer<Int> { type ->
        val connected = type == CarConnection.CONNECTION_TYPE_PROJECTION || type == CarConnection.CONNECTION_TYPE_NATIVE
        Logbook.log("Android Auto ${if (connected) "connected" else "not connected"} (type $type)")
        MediaMirror.onCarConnection(this, connected)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectionType = CarConnection(this).type.also { it.observeForever(connectionObserver) }
    }

    override fun onListenerDisconnected() {
        connectionType?.removeObserver(connectionObserver)
        connectionType = null
        // Access is gone: nothing can be mirrored any more.
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
