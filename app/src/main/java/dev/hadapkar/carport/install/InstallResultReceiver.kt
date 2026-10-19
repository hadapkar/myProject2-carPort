package dev.hadapkar.carport.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Forwards install/uninstall results to [InstallEvents]. A confirmation screen is not
 * started from here: Android blocks activity starts from the background, so the visible
 * activity launches it instead.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Installer.ACTION_RESULT) return
        val op = intent.getStringExtra(Installer.EXTRA_OP)
            ?.let { runCatching { InstallOp.valueOf(it) }.getOrNull() } ?: return
        val packageName = intent.getStringExtra(Installer.EXTRA_PACKAGE) ?: return
        val attempt = intent.getIntExtra(Installer.EXTRA_ATTEMPT, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirm = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            null
        }
        InstallEvents.emit(
            InstallEvent(
                op = op,
                attempt = attempt,
                packageName = packageName,
                status = status,
                message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                confirmIntent = confirm,
            )
        )
    }
}
