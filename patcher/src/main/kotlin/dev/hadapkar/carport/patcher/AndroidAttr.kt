package dev.hadapkar.carport.patcher

/**
 * Framework attribute resource ids (android.R.attr.*). Binary manifests reference
 * android: attributes by these ids, not by name. The app module's unit test checks
 * them against the real android.R.attr values.
 */
object AndroidAttr {
    const val NAME = 0x01010003
    const val SCREEN_ORIENTATION = 0x0101001e
    const val VALUE = 0x01010024
    const val RESOURCE = 0x01010025
    const val RESIZEABLE_ACTIVITY = 0x010104f6
}
