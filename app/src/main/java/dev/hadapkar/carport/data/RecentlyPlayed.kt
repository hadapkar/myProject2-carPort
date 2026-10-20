package dev.hadapkar.carport.data

import android.content.Context
import java.io.File

/**
 * The last songs car media controls mirrored (title, artist, app, time), stored only on the
 * phone. Titles only: Android doesn't give CarPort the page a song came from, so it can't replay
 * them. Main thread only; the file is tiny.
 */
object RecentlyPlayed {
    data class Entry(val timeMs: Long, val title: String, val artist: String?, val app: String?)

    private const val FILE_NAME = "recently_played.tsv"
    private const val MAX_ENTRIES = 30

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** Adds a song unless it is the same as the most recent entry. */
    fun add(context: Context, title: String, artist: String?, app: String?) {
        val entries = list(context)
        if (entries.firstOrNull()?.let { it.title == title && it.artist == artist } == true) return
        val updated = (listOf(Entry(System.currentTimeMillis(), title, artist, app)) + entries).take(MAX_ENTRIES)
        runCatching {
            file(context).writeText(updated.joinToString("\n") { e ->
                listOf(e.timeMs.toString(), e.title, e.artist.orEmpty(), e.app.orEmpty()).joinToString("\t") { clean(it) }
            })
        }
    }

    /** Newest first. */
    fun list(context: Context): List<Entry> = runCatching {
        val f = file(context)
        if (!f.isFile) return emptyList()
        f.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            val time = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val title = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Entry(time, title, parts.getOrNull(2)?.takeIf { it.isNotBlank() }, parts.getOrNull(3)?.takeIf { it.isNotBlank() })
        }
    }.getOrDefault(emptyList())

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** Tabs and line breaks would break the file format. */
    private fun clean(value: String) = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
