package com.peter.mutter

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyRecyclerTest {

    private val utc = ZoneId.of("UTC")

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, utc).toInstant().toEpochMilli()

    @Test
    fun beforeFive_firesSameDay() {
        assertEquals(
            millis(2026, 6, 8, 5, 0),
            DailyRecycler.nextFireAt(millis(2026, 6, 8, 3, 0), 5, utc),
        )
    }

    @Test
    fun afterFive_firesNextDay() {
        assertEquals(
            millis(2026, 6, 9, 5, 0),
            DailyRecycler.nextFireAt(millis(2026, 6, 8, 6, 0), 5, utc),
        )
    }

    @Test
    fun exactlyFive_rollsToNextDay() {
        // Strictly-after: at 05:00 we want tomorrow, not a zero-delay fire.
        assertEquals(
            millis(2026, 6, 9, 5, 0),
            DailyRecycler.nextFireAt(millis(2026, 6, 8, 5, 0), 5, utc),
        )
    }

    @Test
    fun oneMinuteBefore_firesSameDay() {
        assertEquals(
            millis(2026, 6, 8, 5, 0),
            DailyRecycler.nextFireAt(millis(2026, 6, 8, 4, 59), 5, utc),
        )
    }

    @Test
    fun crossesMonthBoundary() {
        assertEquals(
            millis(2026, 7, 1, 5, 0),
            DailyRecycler.nextFireAt(millis(2026, 6, 30, 9, 0), 5, utc),
        )
    }

    @Test
    fun crossesYearBoundary() {
        assertEquals(
            millis(2027, 1, 1, 5, 0),
            DailyRecycler.nextFireAt(millis(2026, 12, 31, 23, 30), 5, utc),
        )
    }
}
