package dev.hadapkar.carport.patcher

/**
 * User-tunable switches for a patch run. The car declarations are always added;
 * these control which launcher entries get a car icon and the display adjustments.
 */
data class PatchOptions(
    /** Drop android:screenOrientation from activities so they fit the car's landscape display. */
    val unlockOrientation: Boolean = true,
    /** Force android:resizeableActivity="true" so activities can fill the car display. */
    val forceResizeable: Boolean = true,
    /**
     * Declare that activities handle screen-size, density and UI-mode changes themselves, so
     * moving between the phone and the car display keeps the open page instead of restarting.
     * Off by default: a few apps draw the wrong layout when they are not restarted; if that
     * happens, patch again without it.
     */
    val keepStateOnScreenChange: Boolean = false,
    /**
     * Give every launcher entry a car icon. Off by default: only the entries the phone
     * launcher shows get one, so apps with alternate icons (Firefox has 23) show a single icon.
     */
    val allLauncherEntries: Boolean = false,
    /**
     * Fully qualified names of the launcher components currently enabled on the phone, for
     * in-place re-patches: they keep the icon the user picked. Ignored when empty or unknown.
     */
    val preferredLauncherComponents: Set<String> = emptySet(),
)
