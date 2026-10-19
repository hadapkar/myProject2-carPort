package dev.hadapkar.carport

import dev.hadapkar.carport.patcher.AndroidAttr
import org.junit.Assert.assertEquals
import org.junit.Test

/** The patcher hard-codes framework attribute ids; android.R.attr is the source of truth. */
class AndroidAttrTest {
    @Test
    fun attributeIdsMatchTheFramework() {
        assertEquals(android.R.attr.name, AndroidAttr.NAME)
        assertEquals(android.R.attr.screenOrientation, AndroidAttr.SCREEN_ORIENTATION)
        assertEquals(android.R.attr.value, AndroidAttr.VALUE)
        assertEquals(android.R.attr.resource, AndroidAttr.RESOURCE)
        assertEquals(android.R.attr.resizeableActivity, AndroidAttr.RESIZEABLE_ACTIVITY)
        assertEquals(android.R.attr.enabled, AndroidAttr.ENABLED)
        assertEquals(android.R.attr.configChanges, AndroidAttr.CONFIG_CHANGES)
        assertEquals(android.R.attr.appCategory, AndroidAttr.APP_CATEGORY)
        assertEquals(android.content.pm.ApplicationInfo.CATEGORY_GAME, AndroidAttr.APP_CATEGORY_GAME)
        val screenSwitch = with(android.content.pm.ActivityInfo::class.java) {
            listOf("KEYBOARD", "KEYBOARD_HIDDEN", "NAVIGATION", "ORIENTATION", "SCREEN_LAYOUT", "UI_MODE",
                "SCREEN_SIZE", "SMALLEST_SCREEN_SIZE", "DENSITY").sumOf { getField("CONFIG_$it").getInt(null) }
        }
        assertEquals(screenSwitch, AndroidAttr.CONFIG_CHANGES_SCREEN_SWITCH)
    }
}
