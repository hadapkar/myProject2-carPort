package dev.hadapkar.carport.data

import android.content.Context
import dev.hadapkar.carport.patcher.SigningKey
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Owns the on-device key that patched apps are signed with. Call from a background thread. */
class KeyRepository(context: Context) {
    private val file = File(context.filesDir, "signing/carport.p12")
    private var cached: SigningKey? = null

    @Synchronized
    fun key(): SigningKey = cached ?: SigningKey.loadOrCreate(file).also { cached = it }

    @Synchronized
    fun export(output: OutputStream) = key().writeTo(output)

    /** Replaces the current key. Validates the input before overwriting anything. */
    @Synchronized
    fun import(input: InputStream): SigningKey {
        val imported = SigningKey.readFrom(input)
        imported.saveTo(file)
        cached = imported
        return imported
    }
}
