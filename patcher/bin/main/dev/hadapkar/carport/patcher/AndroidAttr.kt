package dev.hadapkar.carport.patcher

/**
 * Framework attribute resource ids (android.R.attr.*). Binary manifests reference
 * android: attributes by these ids, not by name. The app module's unit test checks
 * them against the real android.R.attr values.
 */
object AndroidAttr {
    const val NAME = 0x01010003
    const val ENABLED = 0x0101000e
    const val SCREEN_ORIENTATION = 0x0101001e
    const val CONFIG_CHANGES = 0x0101001f
    const val VALUE = 0x01010024
    const val RESOURCE = 0x01010025
    const val RESIZEABLE_ACTIVITY = 0x010104f6
    const val APP_CATEGORY = 0x01010545

    /** android:appCategory="game" (ApplicationInfo.CATEGORY_GAME). */
    const val APP_CATEGORY_GAME = 0

    /**
     * android:configChanges flags (same values as ActivityInfo.CONFIG_*) that change when an
     * activity moves between the phone and the car display: keyboard, keyboardHidden,
     * navigation, orientation, screenLayout, uiMode, screenSize, smallestScreenSize, density.
     */
    const val CONFIG_CHANGES_SCREEN_SWITCH =
        0x0010 or 0x0020 or 0x0040 or 0x0080 or 0x0100 or 0x0200 or 0x0400 or 0x0800 or 0x1000
}
