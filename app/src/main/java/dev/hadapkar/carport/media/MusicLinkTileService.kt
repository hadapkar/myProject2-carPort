package dev.hadapkar.carport.media

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.hadapkar.carport.MainActivity
import dev.hadapkar.carport.data.Logbook
import dev.hadapkar.carport.data.SettingsStore

/**
 * Quick Settings tile: one tap opens the saved music link in Samsung Browser (another browser if
 * Samsung Browser can't), for example before driving. Without a saved link it opens CarPort.
 */
class MusicLinkTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val hasLink = SmartPlay.hasLink(this)
        tile.state = if (hasLink) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        tile.subtitle = if (hasLink) {
            runCatching { android.net.Uri.parse(SettingsStore(this).smartPlayUrl).host }.getOrNull()
        } else {
            "Set a link in CarPort"
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = SmartPlay.viewIntent(this)
            ?: Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Logbook.log("Quick Settings tile: opening ${intent.data ?: "CarPort"} in ${intent.`package` ?: "the default app"}")
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Collapses the Quick Settings panel and opens the page (works on the lock screen after unlocking).
        startActivityAndCollapse(pending)
    }
}
