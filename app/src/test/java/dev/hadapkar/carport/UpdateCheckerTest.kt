package dev.hadapkar.carport

import dev.hadapkar.carport.update.UpdateChecker.compareVersions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun comparesNumerically() {
        assertTrue(compareVersions("0.10.0", "0.9.2") > 0)
        assertTrue(compareVersions("1.0.0", "0.99.99") > 0)
        assertTrue(compareVersions("0.1.0", "0.1.1") < 0)
    }

    @Test
    fun treatsMissingPartsAsZero() {
        assertEquals(0, compareVersions("1.2", "1.2.0"))
        assertTrue(compareVersions("1.2.1", "1.2") > 0)
    }

    @Test
    fun ignoresSuffixes() {
        assertEquals(0, compareVersions("0.2.0-beta", "0.2.0"))
        assertTrue(compareVersions("0.3.0-rc1", "0.2.9") > 0)
    }
}
