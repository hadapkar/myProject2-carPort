package dev.hadapkar.carport.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Process
import dev.hadapkar.carport.patcher.SigningKey

data class AppEntry(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val sourceDir: String,
    val splitSourceDirs: List<String>,
    /** Carries CarPort's patch marker, whichever key signed it. */
    val isPatched: Boolean,
    /** Signed with this device's CarPort key, so re-patching keeps its data. */
    val signedByCarPort: Boolean,
    val installedFromPlayStore: Boolean,
)

enum class SignerState {
    NOT_INSTALLED,
    CARPORT_KEY,
    OTHER_KEY,
    /** Archived (Android 15): the package is still registered, so a differently signed install fails. */
    ARCHIVED,
    /** Also installed in a work, cloned or private profile; uninstalling here would not free the package. */
    OTHER_PROFILE,
}

object InstalledApps {
    const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
    private const val PLAY_STORE_PACKAGE = "com.android.vending"
    private const val PATCH_MARKER = "dev.hadapkar.carport.patched"

    /** User-installed apps that have a launcher icon, sorted by name. System apps cannot be replaced. */
    fun load(context: Context, carPortKeySha256: String): List<AppEntry> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
            .map { it.activityInfo.packageName }
            .toSet()
        return packages.mapNotNull { packageName ->
            if (packageName == context.packageName) return@mapNotNull null
            val info = packageInfo(pm, packageName) ?: return@mapNotNull null
            val app = info.applicationInfo ?: return@mapNotNull null
            if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) return@mapNotNull null
            AppEntry(
                packageName = packageName,
                label = app.loadLabel(pm).toString(),
                versionName = info.versionName,
                sourceDir = app.sourceDir,
                splitSourceDirs = app.splitSourceDirs?.toList().orEmpty(),
                isPatched = app.metaData?.containsKey(PATCH_MARKER) == true,
                signedByCarPort = isSignedBy(info, carPortKeySha256),
                installedFromPlayStore = installerOf(pm, packageName) == PLAY_STORE_PACKAGE,
            )
        }.sortedBy { it.label.lowercase() }
    }

    fun signerState(context: Context, packageName: String, carPortKeySha256: String): SignerState {
        val pm = context.packageManager
        val info = packageInfo(pm, packageName)
        // Every copy of a package on the device shares one signature, so a CarPort-signed copy is safe to update.
        if (info != null && isSignedBy(info, carPortKeySha256)) return SignerState.CARPORT_KEY
        if (installedInOtherProfile(context, packageName)) return SignerState.OTHER_PROFILE
        if (info != null) return SignerState.OTHER_KEY
        return if (isArchived(pm, packageName)) SignerState.ARCHIVED else SignerState.NOT_INSTALLED
    }

    private fun installedInOtherProfile(context: Context, packageName: String): Boolean {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return false
        val me = Process.myUserHandle()
        return launcherApps.profiles.any { profile ->
            profile != me && runCatching { launcherApps.isPackageEnabled(packageName, profile) }.getOrDefault(false)
        }
    }

    private fun isArchived(pm: PackageManager, packageName: String): Boolean = try {
        pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_ARCHIVED_PACKAGES))
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun isInstalled(context: Context, packageName: String): Boolean =
        packageInfo(context.packageManager, packageName) != null

    private fun packageInfo(pm: PackageManager, packageName: String): PackageInfo? = try {
        pm.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(
                (PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES).toLong()
            ),
        )
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun isSignedBy(info: PackageInfo, sha256: String): Boolean {
        val signing = info.signingInfo ?: return false
        val signers = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        return signers.orEmpty().any { SigningKey.certificateSha256(it.toByteArray()) == sha256 }
    }

    private fun installerOf(pm: PackageManager, packageName: String): String? = try {
        pm.getInstallSourceInfo(packageName).installingPackageName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
