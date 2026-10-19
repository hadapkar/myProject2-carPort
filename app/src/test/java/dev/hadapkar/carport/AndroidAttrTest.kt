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
    }
}
