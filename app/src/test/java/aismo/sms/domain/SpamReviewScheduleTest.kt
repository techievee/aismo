package aismo.sms.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class SpamReviewScheduleTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone)

    @Test fun fromMidweekItWaitsForSunday() {
        val wednesday = at(2026, 11, 18, 9)            // a Wednesday
        val due = wednesday.plus(SpamReviewSchedule.delayUntilNext(wednesday))
        assertEquals(DayOfWeek.SUNDAY, due.dayOfWeek)
        assertEquals(22, due.dayOfMonth)
        assertEquals(10, due.hour)
    }

    @Test fun onSundayBeforeTenItIsToday() {
        val sunday = at(2026, 11, 22, 7, 30)
        assertEquals(Duration.ofMinutes(150), SpamReviewSchedule.delayUntilNext(sunday))
    }

    @Test fun onSundayAfterTenItIsNextWeek() {
        val sunday = at(2026, 11, 22, 10, 1)
        val due = sunday.plus(SpamReviewSchedule.delayUntilNext(sunday))
        assertEquals(29, due.dayOfMonth)
        assertEquals(10, due.hour)
    }

    @Test fun atExactlyTenOnSundayItWaitsAWeek() {
        val sunday = at(2026, 11, 22, 10, 0)
        assertEquals(Duration.ofDays(7), SpamReviewSchedule.delayUntilNext(sunday))
    }

    @Test fun neverMoreThanAWeekAndNeverNegative() {
        var t = at(2026, 1, 1, 0)
        repeat(400) {
            val d = SpamReviewSchedule.delayUntilNext(t)
            assertTrue("delay $d at $t", !d.isNegative && !d.isZero && d <= Duration.ofDays(7))
            t = t.plusHours(13)
        }
    }

    @Test fun clockChangesDoNotBreakIt() {
        // Daylight saving starts in Sydney on 2026-10-04 (a Sunday): 10:00 that day is still 10:00 local.
        val saturday = at(2026, 10, 3, 12)
        val due = saturday.plus(SpamReviewSchedule.delayUntilNext(saturday))
        assertEquals(10, due.hour)
        assertEquals(DayOfWeek.SUNDAY, due.dayOfWeek)
    }
}
