package dev.brahma.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturesTest {
    @Test
    fun swipeNeedsTheFullDistanceMostlyStraight() {
        assertFalse(EdgeSwipe.counts(travel = 99f, sideways = 0f, distance = 100f))
        assertTrue(EdgeSwipe.counts(travel = 100f, sideways = 10f, distance = 100f))
        // A diagonal drag is not a swipe, however long.
        assertFalse(EdgeSwipe.counts(travel = 300f, sideways = 160f, distance = 100f))
    }

    @Test
    fun longerSettingsNeedLongerSwipes() {
        val distances = SwipeDistance.entries.map { it.dp }
        assertEquals(distances.sorted(), distances)
        assertTrue(SwipeDistance.MEDIUM.dp > 72)
    }
}
