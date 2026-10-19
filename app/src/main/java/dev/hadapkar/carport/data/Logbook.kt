package dev.hadapkar.carport.data

import android.content.Context
import android.os.Build
import dev.hadapkar.carport.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small on-device log of what CarPort did (jobs, steps, results, errors, crashes), so a
 * problem on the phone can be shared and diagnosed. Kept under [MAX_BYTES] in filesDir.
 */
object Logbook {
    private const val MAX_BYTES = 128 * 1024
    private const val FILE_NAME = "carport.log"
    private var file: File? = null
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
    }

    @Synchronized
    fun log(message: String, error: Throwable? = null) {
        val target = file ?: return
        val line = buildString {
            append(time.format(Date())).append("  ").append(message).append('\n')
            if (error != null) {
                append(StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString())
            }
        }
        runCatching {
            target.appendText(line)
            if (target.length() > MAX_BYTES) {
                // Keep the newer half.
                val text = target.readText()
                target.writeText(text.substring(text.length / 2).substringAfter('\n'))
            }
        }
    }

    /** The log with a device header, newest last, for sharing. */
    @Synchronized
    fun export(): String {
        val body = runCatching { file?.readText() }.getOrNull().orEmpty()
        return buildString {
            append("CarPort ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Heap limit ").append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append(" MB\n\n")
            append(body.ifBlank { "(log is empty)\n" })
        }
    }

    @Synchronized
    fun clear() {
        runCatching { file?.writeText("") }
    }
}
