package dev.mohak.scrinium.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdaterTest {
    @Test
    fun onlyNewerReleasesAreOffered() {
        assertTrue(isNewer("0.3.0-beta", "0.2.0-beta"))
        assertTrue(isNewer("0.2.10", "0.2.9"))
        assertTrue(isNewer("1.0.0", "1.0.0-beta"))
        assertFalse(isNewer("0.2.0-beta", "0.2.0-beta"))
        assertFalse(isNewer("0.1.9", "0.2.0-beta"))
        assertFalse(isNewer("1.0.0-beta", "1.0.0"))
    }
}
