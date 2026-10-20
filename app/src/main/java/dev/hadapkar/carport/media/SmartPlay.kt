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
 * Samsung Browser instead of doing nothing. Samsung Browser is always tried first; another
 * browser is used only if Samsung Browser is missing, disabled or can't open the link.
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

    /** Opens the saved link if the automatic smart play button is on. */
    fun openIfEnabled(context: Context): Boolean =
        SettingsStore(context).smartPlayEnabled && open(context)

    /** A valid link is saved, so the car can offer the "Open my music link" tile. */
    fun hasLink(context: Context): Boolean = isValidLink(SettingsStore(context).smartPlayUrl)

    /** Opens the saved link (explicit request, e.g. the car tile); returns true when a page opened. */
    fun open(context: Context): Boolean {
        val link = SettingsStore(context).smartPlayUrl
        if (!isValidLink(link)) {
            Logbook.log("Smart play: the saved link is not a web address")
            return false
        }
        if (!canOpenFromBackground(context)) {
            Logbook.log("Smart play: 'Display over other apps' is not allowed, so the browser can't be opened")
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpened < MIN_INTERVAL_MS) return true // Already opening; don't stack tabs.
        lastOpened = now

        val view = Intent(Intent.ACTION_VIEW, Uri.parse(link.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Samsung Browser first; another browser only if Samsung Browser can't open the link.
        if (InstalledApps.isInstalled(context, SAMSUNG_BROWSER) && start(context, Intent(view).setPackage(SAMSUNG_BROWSER))) {
            Logbook.log("Smart play: opened $link in Samsung Browser")
            return true
        }
        Logbook.log("Smart play: Samsung Browser is missing, disabled or couldn't open the link; trying another browser")
        if (start(context, view)) {
            Logbook.log("Smart play: opened $link in another browser")
            return true
        }
        Logbook.log("Smart play: no browser could open $link")
        return false
    }

    /**
     * The saved link as an "open" intent for something the user starts directly (the Quick
     * Settings tile): Samsung Browser if it can open it, otherwise any browser. Null without a link.
     */
    fun viewIntent(context: Context): Intent? {
        val link = SettingsStore(context).smartPlayUrl.trim()
        if (!isValidLink(link)) return null
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val samsung = Intent(view).setPackage(SAMSUNG_BROWSER)
        return if (samsung.resolveActivity(context.packageManager) != null) samsung else view
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: RuntimeException) {
        Logbook.log("Smart play: Android blocked opening ${intent.`package` ?: "the browser"}", e)
        false
    }
}
