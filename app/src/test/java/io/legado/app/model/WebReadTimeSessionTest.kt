package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebReadTimeSessionTest {

    @Test
    fun minimumDurationUsesServerEndTime() {
        val range = WebReadTimeSession.fromDuration(
            WebReadTimeSession.MIN_DURATION_MS,
            endAtMs = 100_999L
        )

        assertEquals(WebReadTimeSession.Range(startSec = 95L, endSec = 100L), range)
    }

    @Test
    fun invalidDurationsAreRejected() {
        assertNull(WebReadTimeSession.fromDuration(4_999L, endAtMs = 100_000L))
        assertNull(
            WebReadTimeSession.fromDuration(
                WebReadTimeSession.MAX_DURATION_MS + 1L,
                endAtMs = WebReadTimeSession.MAX_DURATION_MS + 100_000L
            )
        )
        assertNull(WebReadTimeSession.fromDuration(5_000L, endAtMs = -1L))
    }

    @Test
    fun directRangeValidationIsBounded() {
        assertTrue(WebReadTimeSession.isValidRange(startSec = 10L, endSec = 15L))
        assertFalse(WebReadTimeSession.isValidRange(startSec = 10L, endSec = 14L))
        assertFalse(WebReadTimeSession.isValidRange(startSec = -1L, endSec = 10L))
        assertFalse(WebReadTimeSession.isValidRange(startSec = 10L, endSec = 10L))
        assertFalse(
            WebReadTimeSession.isValidRange(
                startSec = 0L,
                endSec = WebReadTimeSession.MAX_DURATION_MS / 1_000L + 1L
            )
        )
    }
}
