package dev.mohak.scrinium.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test
    fun expiryReadsLikeTheWeb() {
        val hour = 3_600_000L
        assertEquals("", expiryLabel(null, 0))
        assertEquals("expired", expiryLabel(0, 0))
        assertEquals("expires within the hour", expiryLabel(hour / 2, 0))
        assertEquals("expires in 5 hours", expiryLabel(5 * hour, 0))
        assertEquals("expires in 1 day", expiryLabel(24 * hour, 0))
        assertEquals("expires in 7 days", expiryLabel(7 * 24 * hour, 0))
    }
}
