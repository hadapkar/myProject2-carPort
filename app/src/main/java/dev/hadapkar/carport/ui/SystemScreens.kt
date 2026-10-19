package dev.hadapkar.carport.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import dev.hadapkar.carport.data.InstalledApps

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

private fun startOrToast(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "Couldn't open that screen on this phone.", Toast.LENGTH_LONG).show() }
}
