package com.hzzmonet.zkbomb.domain.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionEnforcerTest {

    private val day = 24L * 60L * 60L * 1000L
    private val now = 1_000L * day // an arbitrary but fixed "now"

    private fun ref(id: String, ageInDays: Long) =
        RecordingRef(id, createdAtEpochMillis = now - ageInDays * day)

    @Test
    fun `forever never expires anything`() {
        val recordings = listOf(ref("old", 3650), ref("new", 0))
        assertTrue(RetentionEnforcer.expired(recordings, RetentionPolicy.FOREVER, now).isEmpty())
    }

    @Test
    fun `only recordings past the cutoff expire`() {
        val recordings = listOf(
            ref("keep-fresh", 1),
            ref("keep-edge", 6),
            ref("drop", 8),
            ref("drop-ancient", 400),
        )
        val expired = RetentionEnforcer.expired(recordings, RetentionPolicy.DAYS_7, now).map { it.id }
        assertEquals(listOf("drop", "drop-ancient"), expired)
    }

    @Test
    fun `a recording exactly at the cutoff is kept`() {
        // "strictly older than" — 7 days old on a 7-day policy survives.
        val recordings = listOf(ref("exactly-seven", 7))
        assertTrue(RetentionEnforcer.expired(recordings, RetentionPolicy.DAYS_7, now).isEmpty())
    }

    @Test
    fun `a future-dated recording is never expired`() {
        val future = RecordingRef("skewed", createdAtEpochMillis = now + 5 * day)
        assertTrue(RetentionEnforcer.expired(listOf(future), RetentionPolicy.DAYS_7, now).isEmpty())
    }

    @Test
    fun `empty input yields empty output`() {
        assertTrue(RetentionEnforcer.expired(emptyList(), RetentionPolicy.DAYS_30, now).isEmpty())
    }

    @Test
    fun `ninety day policy keeps a sixty day old recording`() {
        assertTrue(RetentionEnforcer.expired(listOf(ref("mid", 60)), RetentionPolicy.DAYS_90, now).isEmpty())
    }
}
