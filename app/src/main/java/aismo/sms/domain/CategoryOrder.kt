package aismo.sms.domain

/** The order categories are shown in: the user's saved order first, then anything the saved order does not know about. */
object CategoryOrder {
    /** The built-in categories in their default order. */
    val DEFAULT: List<String> = SmsCategory.entries.map { it.key }

    /**
     * [saved] is what the user arranged, [available] is every category that exists now in its natural order (built-in,
     * then custom). Categories that no longer exist drop out; new ones go to the end in their natural order.
     */
    fun arrange(saved: List<String>, available: List<String>): List<String> {
        val present = available.toSet()
        val kept = saved.filter { it in present }.distinct()
        val keptSet = kept.toSet()
        return kept + available.filter { it !in keptSet }
    }

    /**
     * The categories the inbox bar lists, in order: those that have conversations ([withMessages]), plus the
     * [selected] one even when empty, so the view that is open always has its tab. A hidden category keeps its
     * tab: hiding only takes its messages out of the All view, and the tab is how they are reached again.
     */
    fun shown(order: List<String>, withMessages: Set<String>, selected: String?): List<String> =
        order.filter { it in withMessages || it == selected }

    /**
     * Whether a conversation of [category] shows in the All view: a hidden category never does, a secured category
     * only while it is unlocked, and spam follows the user's "show spam in All" switch.
     */
    fun inAll(category: String, hidden: Set<String>, secured: Set<String>, securedUnlocked: Boolean, includeSpam: Boolean): Boolean =
        if (category in hidden) false
        else if (category in secured && !securedUnlocked) false
        else if (category == SmsCategory.SPAM.key) includeSpam
        else true

    /** Moves [key] by [steps] places, negative towards the front, and stops at either end. Unknown keys change nothing. */
    fun move(order: List<String>, key: String, steps: Int): List<String> {
        val from = order.indexOf(key)
        if (from < 0) return order
        val to = (from + steps).coerceIn(0, order.lastIndex)
        if (to == from) return order
        return order.toMutableList().also { it.removeAt(from); it.add(to, key) }
    }
}
