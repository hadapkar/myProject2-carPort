package dev.hadapkar.carport.patcher

/**
 * User-tunable switches for a patch run. The car-launcher declarations are always added;
 * these only control the extra display adjustments.
 */
data class PatchOptions(
    /** Drop android:screenOrientation from launcher activities so they fit the car's landscape display. */
    val unlockOrientation: Boolean = true,
    /** Force android:resizeableActivity="true" on launcher activities so they can fill the car display. */
    val forceResizeable: Boolean = true,
)
