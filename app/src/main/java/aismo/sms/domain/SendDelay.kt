package aismo.sms.domain

/** How long a sent message is held so it can still be undone. 0 means off: a message goes out as soon as Send is tapped. */
object SendDelay {
    const val DEFAULT = 0

    /** The choices Settings offers, in seconds. */
    val OPTIONS: List<Int> = listOf(0, 3, 5, 10, 15, 30)

    fun label(seconds: Int): String = if (seconds <= 0) "Off" else "$seconds seconds"

    /** A stored value that is not one of the [OPTIONS] (from a newer backup, say) becomes the nearest option, never a surprise delay. */
    fun normalise(seconds: Int): Int = OPTIONS.minByOrNull { kotlin.math.abs(it - seconds) } ?: DEFAULT
}
