package dev.hadapkar.carport.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

enum class InstallOp { INSTALL, UNINSTALL }

/**
 * A status report for one install or uninstall attempt. [attempt] identifies the request,
 * so results from a cancelled or older attempt can be ignored.
 */
data class InstallEvent(
    val op: InstallOp,
    val attempt: Int,
    val packageName: String,
    val status: Int,
    val message: String?,
    /** Android's confirmation screen, set when [status] is STATUS_PENDING_USER_ACTION. */
    val confirmIntent: Intent? = null,
) {
    val isSuccess: Boolean get() = status == PackageInstaller.STATUS_SUCCESS
    val needsUserAction: Boolean get() = status == PackageInstaller.STATUS_PENDING_USER_ACTION
}

/** Results from [InstallResultReceiver]; the view model collects them. */
object InstallEvents {
    private val flow = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<InstallEvent> = flow
    fun emit(event: InstallEvent) {
        flow.tryEmit(event)
    }
}

object Installer {
    const val ACTION_RESULT = "dev.hadapkar.carport.action.INSTALL_RESULT"
    const val EXTRA_OP = "dev.hadapkar.carport.extra.OP"
    const val EXTRA_PACKAGE = "dev.hadapkar.carport.extra.PACKAGE"
    const val EXTRA_ATTEMPT = "dev.hadapkar.carport.extra.ATTEMPT"

    private val attempts = AtomicInteger(1)

    /** Reserve the id before starting a request, so no result can arrive for an unknown attempt. */
    fun nextAttempt(): Int = attempts.getAndIncrement()

    /**
     * Installs [apks] (base first, then splits) as one session and returns the session id.
     * Blocking: call off the main thread.
     */
    fun install(context: Context, packageName: String, apks: List<File>, attempt: Int): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setSize(apks.sumOf { it.length() })
            // Re-patches of apps CarPort installed can then update without an extra prompt.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                for (apk in apks) {
                    session.openWrite(apk.name, 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out, 1 shl 16) }
                        session.fsync(out)
                    }
                }
                session.commit(statusReceiver(context, InstallOp.INSTALL, packageName, attempt))
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
        return sessionId
    }

    /** Asks Android to uninstall [packageName]; the user confirms in a system dialog. */
    fun uninstall(context: Context, packageName: String, attempt: Int) {
        context.packageManager.packageInstaller.uninstall(
            packageName,
            statusReceiver(context, InstallOp.UNINSTALL, packageName, attempt),
        )
    }

    /** Gives up on an install session that is still waiting for the user. */
    fun abandon(context: Context, sessionId: Int) {
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
    }

    private fun statusReceiver(context: Context, op: InstallOp, packageName: String, attempt: Int): IntentSender {
        val intent = Intent(context, InstallResultReceiver::class.java)
            .setAction(ACTION_RESULT)
            .putExtra(EXTRA_OP, op.name)
            .putExtra(EXTRA_PACKAGE, packageName)
            .putExtra(EXTRA_ATTEMPT, attempt)
        // Mutable: the system adds the status extras. Explicit intent, so this is safe.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, attempt, intent, flags).intentSender
    }

    fun describeFailure(event: InstallEvent): String {
        val reason = when (event.status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "It was cancelled."
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked it. Check that CarPort is allowed to install apps."
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "A copy signed with a different key is still on this phone (maybe in a work or cloned profile). Remove it and try again."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "The app is not compatible with this phone."
            PackageInstaller.STATUS_FAILURE_INVALID -> "Android rejected the patched APK."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "There is not enough storage space."
            else -> "Android reported an error."
        }
        return listOfNotNull(reason, event.message?.takeIf { it.isNotBlank() }).joinToString("\n\n")
    }
}
