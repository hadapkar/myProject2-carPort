package dev.hadapkar.carport.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import dev.hadapkar.carport.data.InstalledApps
import dev.hadapkar.carport.data.Logbook
import dev.hadapkar.carport.data.SettingsStore

/**
 * Smart play button: when play is pressed in the car and there is no song to resume (the
 * browser tab was closed or Android stopped the browser), open the user's saved music link in
 * Samsung Browser instead of doing nothing. Only Samsung Browser is used: if it's missing or
 * disabled, nothing is opened.
 *
 * Android only lets a background app open a screen when the user granted it "Display over
 * other apps", so this needs that permission. A locked phone shows the page after unlocking,
 * and the browser may still want one tap before sound starts.
 */
object SmartPlay {
    const val SAMSUNG_BROWSER = "com.sec.android.app.sbrowser"
    private const val MIN_INTERVAL_MS = 15_000L
    private var lastOpened = 0L

    fun canOpenFromBackground(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isValidLink(link: String): Boolean {
        val uri = runCatching { Uri.parse(link.trim()) }.getOrNull() ?: return false
        return (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()
    }

    /** Opens the saved link if the feature is on; returns true when a page was opened. */
    fun openIfEnabled(context: Context): Boolean {
        val settings = SettingsStore(context)
        if (!settings.smartPlayEnabled) return false
        val link = settings.smartPlayUrl
        if (!isValidLink(link)) {
            Logbook.log("Smart play: the saved link is not a web address")
            return false
        }
        if (!canOpenFromBackground(context)) {
            Logbook.log("Smart play: 'Display over other apps' is not allowed, so the browser can't be opened")
            return false
        }
        if (!InstalledApps.isInstalled(context, SAMSUNG_BROWSER)) {
            Logbook.log("Smart play: Samsung Browser is not installed, so nothing was opened")
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpened < MIN_INTERVAL_MS) return true // Already opening; don't stack tabs.
        lastOpened = now

        // Samsung Browser only, never another browser.
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(link.trim()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setPackage(SAMSUNG_BROWSER)
        return try {
            context.startActivity(view)
            Logbook.log("Smart play: opened $link in Samsung Browser")
            true
        } catch (e: ActivityNotFoundException) {
            Logbook.log("Smart play: Samsung Browser is disabled or can't open $link, so nothing was opened")
            false
        } catch (e: RuntimeException) {
            Logbook.log("Smart play: Android blocked opening the browser", e)
            false
        }
    }
}
