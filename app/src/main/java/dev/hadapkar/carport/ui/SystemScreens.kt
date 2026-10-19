package dev.hadapkar.carport.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import dev.hadapkar.carport.data.InstalledApps
import dev.hadapkar.carport.data.Logbook
import dev.hadapkar.carport.media.MediaAccessListener

/** Opens the system page where the user allows CarPort to install apps. */
internal fun openInstallPermission(context: Context) {
    startOrToast(context, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
}

/** Opens Android Auto, or its app-info page on phones where it has no launcher entry. */
internal fun openAndroidAuto(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(InstalledApps.ANDROID_AUTO_PACKAGE)
        ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${InstalledApps.ANDROID_AUTO_PACKAGE}"))
    startOrToast(context, intent)
}

/** Opens the notification-access page for CarPort's media listener (needed for car media controls). */
internal fun openMediaAccess(context: Context) {
    val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, MediaAccessListener.component(context).flattenToString())
    if (runCatching { context.startActivity(detail) }.isFailure) {
        startOrToast(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}

/** The "Display over other apps" page for CarPort (lets the smart play button open the browser). */
internal fun openOverlayPermission(context: Context) {
    startOrToast(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
}

/** CarPort's App info page, where Android 15 hides "Allow restricted settings" (⋮ menu). */
internal fun openCarPortAppInfo(context: Context) {
    startOrToast(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
}

/** Shares the diagnostic log as text (the newest part, to stay within Android's intent size limit). */
internal fun shareLog(context: Context) {
    val text = Logbook.export().let { if (it.length > MAX_SHARED_LOG_CHARS) it.takeLast(MAX_SHARED_LOG_CHARS) else it }
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "CarPort log")
        .putExtra(Intent.EXTRA_TEXT, text)
    startOrToast(context, Intent.createChooser(send, "Share CarPort log"))
}

private const val MAX_SHARED_LOG_CHARS = 60_000

private fun startOrToast(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "Couldn't open that screen on this phone.", Toast.LENGTH_LONG).show() }
}
