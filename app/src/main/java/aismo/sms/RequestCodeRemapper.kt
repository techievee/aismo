package aismo.sms

/**
 * The ActivityResultRegistry in androidx.activity 1.13.0 allocates request codes in the upper 16 bits
 * (0x00010000..Int.MAX_VALUE, per its "Use upper 16 bits for request codes" comment) and hands them to
 * the framework unmasked. Android 17 rejects any request code above 0xFFFF with "Can only use lower 16
 * bits for requestCode", so every launcher launch (photo picker, document picker, role picker, ...) used
 * to crash the app. [remap] translates those high codes to a reserved band of small codes on the way
 * out and [unremap] translates them back on the way in; the registry keeps bookkeeping with the
 * original high codes, so results still reach the right callback. Small codes (fragments, the
 * biometric prompt, ...) pass through unchanged.
 *
 * A pair is removed when its result arrives, so a second launch of the same launcher re-registers
 * cleanly instead of reusing a low whose result has already been delivered.
 */
internal class RequestCodeRemapper {
    private val highToLow = HashMap<Int, Int>()
    private val lowToHigh = HashMap<Int, Int>()
    var nextLow = BAND_START
        private set

    fun isHigh(requestCode: Int) = requestCode >= HIGH_START

    /** The translated code, or the input when it is already in the small band. */
    fun remap(requestCode: Int): Int {
        if (!isHigh(requestCode)) return requestCode
        val known = highToLow[requestCode]
        if (known != null) return known
        if (nextLow >= HIGH_START) nextLow = BAND_START
        var low = nextLow++
        while (lowToHigh.containsKey(low)) low = nextLow++
        highToLow[requestCode] = low
        lowToHigh[low] = requestCode
        return low
    }

    /**
     * The original code for a translated one. Removes both directions: leaving a stale `highToLow`
     * entry would make [remap] hand out a low that already has no matching result, and the second
     * launch's result would be delivered to a key the registry no longer knows.
     */
    fun unremap(requestCode: Int): Int {
        val high = lowToHigh.remove(requestCode) ?: return requestCode
        highToLow.remove(high)
        return high
    }

    /** The (low, high) pairs currently in flight, for instance-state persistence. */
    fun savedPairs(): IntArray {
        val pairs = IntArray(highToLow.size * 2)
        var i = 0
        for ((high, low) in highToLow) {
            pairs[i * 2] = low
            pairs[i * 2 + 1] = high
            i++
        }
        return pairs
    }

    /** Restores [pairs] and [nextLowValue], clearing whatever was in flight before. */
    fun restore(pairs: IntArray?, nextLowValue: Int = BAND_START) {
        highToLow.clear()
        lowToHigh.clear()
        nextLow = nextLowValue
        if (pairs != null) {
            for (i in pairs.indices step 2) {
                highToLow[pairs[i + 1]] = pairs[i]
                lowToHigh[pairs[i]] = pairs[i + 1]
            }
        }
    }

    internal companion object {
        /** The first of the small codes reserved for translated activity results. */
        const val BAND_START = 0x1000

        /** The first code the activity-result registry can allocate; anything above 0xFFFF breaks on Android 17. */
        const val HIGH_START = 0x10000
    }
}
