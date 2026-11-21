package aismo.sms.domain

/** Which SIM a message is sent from. */
object SimChoice {
    /** Lets the platform pick (the SIM chosen in the system settings for texts). */
    const val SYSTEM_DEFAULT = -1

    /**
     * In order of preference: the SIM the user just picked, the SIM this conversation last used, the SIM chosen in this
     * app's settings, the system's default SIM for texts. Only SIMs in [active] count, so a SIM that was removed is
     * skipped. With fewer than two active SIMs there is nothing to choose between, so only the app's setting applies.
     */
    fun resolve(picked: Int?, thread: Int?, preferred: Int, systemDefault: Int?, active: List<Int>): Int {
        if (active.size < 2) return preferred
        return listOf(picked, thread, preferred.takeIf { it >= 0 }, systemDefault)
            .firstOrNull { it != null && it >= 0 && it in active }
            ?: SYSTEM_DEFAULT
    }
}
