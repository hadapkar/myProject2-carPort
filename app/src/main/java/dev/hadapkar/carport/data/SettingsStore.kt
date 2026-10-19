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

    /** Fingerprint of the key the user last exported; a mismatch means "not backed up". */
    var backedUpKeySha256: String?
        get() = prefs.getString(KEY_BACKED_UP_KEY, null)
        set(value) = prefs.edit { putString(KEY_BACKED_UP_KEY, value) }

    private companion object {
        const val KEY_UNLOCK_ORIENTATION = "unlock_orientation"
        const val KEY_FORCE_RESIZEABLE = "force_resizeable"
        const val KEY_KEEP_STATE = "keep_state_on_screen_change"
        const val KEY_ALL_LAUNCHER_ENTRIES = "all_launcher_entries"
        const val KEY_BACKED_UP_KEY = "backed_up_key_sha256"
    }
}
