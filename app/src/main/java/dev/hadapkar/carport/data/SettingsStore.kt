package dev.hadapkar.carport.data

import android.content.Context
import androidx.core.content.edit
import dev.hadapkar.carport.patcher.PatchOptions

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var options: PatchOptions
        get() = PatchOptions(
            unlockOrientation = prefs.getBoolean(KEY_UNLOCK_ORIENTATION, true),
            forceResizeable = prefs.getBoolean(KEY_FORCE_RESIZEABLE, true),
            keepStateOnScreenChange = prefs.getBoolean(KEY_KEEP_STATE, false),
            allLauncherEntries = prefs.getBoolean(KEY_ALL_LAUNCHER_ENTRIES, false),
        )
        set(value) = prefs.edit {
            putBoolean(KEY_UNLOCK_ORIENTATION, value.unlockOrientation)
            putBoolean(KEY_FORCE_RESIZEABLE, value.forceResizeable)
            putBoolean(KEY_KEEP_STATE, value.keepStateOnScreenChange)
            putBoolean(KEY_ALL_LAUNCHER_ENTRIES, value.allLauncherEntries)
        }

    /**
     * Smart play button: when play is pressed in the car and nothing can be resumed, open
     * [smartPlayUrl] (for example a YouTube Music playlist) in Samsung Browser.
     */
    var smartPlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMART_PLAY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_SMART_PLAY_ENABLED, value) }

    /** Pause the mirrored app when the phone disconnects from Android Auto. */
    var pauseOnDisconnect: Boolean
        get() = prefs.getBoolean(KEY_PAUSE_ON_DISCONNECT, false)
        set(value) = prefs.edit { putBoolean(KEY_PAUSE_ON_DISCONNECT, value) }

    /** Resume a paused song when the phone connects to Android Auto. */
    var resumeOnConnect: Boolean
        get() = prefs.getBoolean(KEY_RESUME_ON_CONNECT, false)
        set(value) = prefs.edit { putBoolean(KEY_RESUME_ON_CONNECT, value) }

    var smartPlayUrl: String
        get() = prefs.getString(KEY_SMART_PLAY_URL, null) ?: DEFAULT_SMART_PLAY_URL
        set(value) = prefs.edit { putString(KEY_SMART_PLAY_URL, value.trim()) }

    /** Fingerprint of the key the user last exported; a mismatch means "not backed up". */
    var backedUpKeySha256: String?
        get() = prefs.getString(KEY_BACKED_UP_KEY, null)
        set(value) = prefs.edit { putString(KEY_BACKED_UP_KEY, value) }

    companion object {
        const val DEFAULT_SMART_PLAY_URL = "https://music.youtube.com/"
        private const val KEY_SMART_PLAY_ENABLED = "smart_play_enabled"
        private const val KEY_SMART_PLAY_URL = "smart_play_url"
        private const val KEY_PAUSE_ON_DISCONNECT = "pause_on_disconnect"
        private const val KEY_RESUME_ON_CONNECT = "resume_on_connect"
        private const val KEY_UNLOCK_ORIENTATION = "unlock_orientation"
        private const val KEY_FORCE_RESIZEABLE = "force_resizeable"
        private const val KEY_KEEP_STATE = "keep_state_on_screen_change"
        private const val KEY_ALL_LAUNCHER_ENTRIES = "all_launcher_entries"
        private const val KEY_BACKED_UP_KEY = "backed_up_key_sha256"
    }
}
