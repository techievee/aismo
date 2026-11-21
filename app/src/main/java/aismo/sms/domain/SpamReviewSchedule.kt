package aismo.sms.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** When the weekly "review your spam" reminder is due: Sunday morning, local time. */
object SpamReviewSchedule {
    val DAY: DayOfWeek = DayOfWeek.SUNDAY
    const val HOUR = 10

    /** Time from [now] to the next Sunday at 10:00; a full week if it is that time exactly. */
    fun delayUntilNext(now: ZonedDateTime): Duration {
        var next = now.with(TemporalAdjusters.nextOrSame(DAY)).withHour(HOUR).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusWeeks(1)
        return Duration.between(now, next)
    }
}
