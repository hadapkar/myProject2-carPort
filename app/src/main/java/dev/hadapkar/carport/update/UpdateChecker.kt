package dev.hadapkar.carport.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dev.hadapkar.carport.BuildConfig
import dev.hadapkar.carport.patcher.SigningKey
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Release(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
)

/**
 * Self-update from the GitHub Releases of [BuildConfig.UPDATE_REPO]. A release must have a
 * tag like v0.2.0 and a CarPort APK asset signed with the same key as the installed CarPort,
 * otherwise Android refuses the update.
 */
object UpdateChecker {
    private const val TIMEOUT_MS = 15_000

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Returns the latest release if it is newer than this build, null if up to date. Blocking. */
    fun findUpdate(): Release? {
        val connection = open("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND ->
                    throw IOException("No releases found. The update repository must be public and have a published release.")
                else -> throw IOException("GitHub returned HTTP $code.")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").removePrefix("v").removePrefix("V")
            if (compareVersions(version, currentVersion) <= 0) return null
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk", ignoreCase = true) }
                ?: throw IOException("Release $version has no APK attached.")
            return Release(
                version = version,
                notes = json.optString("body").trim(),
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size", -1),
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads [release] to [target], reporting progress 0..100 (or -1 if the size is unknown). Blocking. */
    fun download(release: Release, target: File, onProgress: (Int) -> Unit) {
        target.parentFile?.mkdirs()
        val connection = open(release.apkUrl)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Download failed with HTTP ${connection.responseCode}.")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            var done = 0L
            var lastPercent = -2
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Throws unless [apk] is a newer CarPort signed with the same key as the running app. */
    fun verify(context: Context, apk: File) {
        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val downloaded = pm.getPackageArchiveInfo(apk.path, flags)
            ?: throw IOException("The downloaded file is not a valid APK.")
        if (downloaded.packageName != context.packageName) {
            throw IOException("The downloaded APK is not CarPort (${downloaded.packageName}).")
        }
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (downloaded.longVersionCode <= installed.longVersionCode) {
            throw IOException("The downloaded APK is not newer than the installed CarPort.")
        }
        val newSigners = signers(downloaded)
        if (newSigners.isEmpty() || newSigners.intersect(signers(installed)).isEmpty()) {
            throw IOException(
                "The update is signed with a different key, so Android would refuse it. " +
                    "Releases must be built with CarPort's release key."
            )
        }
    }

    private fun signers(info: PackageInfo): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        val certificates = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        return certificates.orEmpty().map { SigningKey.certificateSha256(it.toByteArray()) }.toSet()
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CarPort/${BuildConfig.VERSION_NAME}")
        }

    /** Numeric comparison of dotted versions: 0.10.0 > 0.9.2. Non-numeric suffixes are ignored. */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.split('.', '-', '+').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val diff = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
}
