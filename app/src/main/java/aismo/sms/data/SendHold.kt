package aismo.sms.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import aismo.sms.data.mms.OutgoingAttachment
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A message the user sent that is waiting out its undo window. [heldAt] and [releaseAt] are on the clock the hold was given. */
class HeldMessage(
    val id: Long,
    val conversationKey: String,
    val recipients: List<String>,
    val text: String,
    val attachments: List<OutgoingAttachment>,
    val subId: Int,
    /** True for a message typed in the composer, which Undo puts back there; a quick reply has no draft to return to. */
    val restoresDraft: Boolean,
    val heldAt: Long,
    val releaseAt: Long,
)

/** A held message that could not be sent when its time came. Its attachments are kept so the user can try again. */
class FailedSend(val message: HeldMessage, val reason: String)

/**
 * Holds sent messages for a few seconds so they can be undone, and sends them when the time is up. It belongs to the
 * app, not to a screen, so leaving the conversation neither cancels nor loses a held message.
 *
 * A held message ends exactly one way. Undo, Send now, the timer and [flushAll] all start by taking it out of [held] in one
 * atomic step, and only whoever took it acts on it, so a tap at the moment the timer fires cannot send it twice or send and undo it.
 * Only one message is held per conversation: sending another one first sends the earlier one, which keeps them in order.
 */
class SendHold(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val dispatch: suspend (HeldMessage) -> SendOutcome,
    private val onDelivered: (HeldMessage) -> Unit,
    private val onFailed: (FailedSend) -> Unit = {},
) {
    private val heldByKey = MutableStateFlow<Map<String, HeldMessage>>(emptyMap())
    private val failedByKey = MutableStateFlow<Map<String, List<FailedSend>>>(emptyMap())
    private val timers = ConcurrentHashMap<Long, Job>()
    private val nextId = AtomicLong()

    /** The message held for each conversation, by conversation key. */
    val held: StateFlow<Map<String, HeldMessage>> = heldByKey

    /** Messages that failed after being held, until the conversation claims them with [takeFailures]. */
    val failed: StateFlow<Map<String, List<FailedSend>>> = failedByKey

    fun hold(
        conversationKey: String, recipients: List<String>, text: String, attachments: List<OutgoingAttachment>,
        subId: Int, restoresDraft: Boolean, delayMs: Long,
    ): HeldMessage {
        val heldAt = now()
        val message = HeldMessage(nextId.incrementAndGet(), conversationKey, recipients, text, attachments, subId, restoresDraft, heldAt, heldAt + delayMs)
        var earlier: HeldMessage?
        while (true) {
            val current = heldByKey.value
            earlier = current[conversationKey]
            if (heldByKey.compareAndSet(current, current + (conversationKey to message))) break
        }
        earlier?.let { timers.remove(it.id)?.cancel(); release(it) }
        // Registered before it can run, so a timer that fires at once still finds (and removes) itself.
        val timer = scope.launch(start = CoroutineStart.LAZY) {
            delay(delayMs)
            take(conversationKey, message.id)?.let { timers.remove(it.id); release(it) }
        }
        timers[message.id] = timer
        timer.start()
        return message
    }

    /** Takes the held message back, or null if it has already gone out (or was never there). */
    fun undo(conversationKey: String): HeldMessage? = take(conversationKey)?.also { timers.remove(it.id)?.cancel() }

    /** Sends the held message now. */
    fun sendNow(conversationKey: String) {
        take(conversationKey)?.let { timers.remove(it.id)?.cancel(); release(it) }
    }

    /** Sends everything held, for when the app leaves the screen: a message must not wait where nobody can undo it. */
    fun flushAll() = heldByKey.value.keys.forEach(::sendNow)

    /** The messages of [conversationKey] that failed, oldest first; they are handed over once. */
    fun takeFailures(conversationKey: String): List<FailedSend> {
        while (true) {
            val current = failedByKey.value
            val failures = current[conversationKey] ?: return emptyList()
            if (failedByKey.compareAndSet(current, current - conversationKey)) return failures
        }
    }

    private fun take(conversationKey: String, id: Long? = null): HeldMessage? {
        while (true) {
            val current = heldByKey.value
            val message = current[conversationKey] ?: return null
            if (id != null && message.id != id) return null
            if (heldByKey.compareAndSet(current, current - conversationKey)) return message
        }
    }

    private fun release(message: HeldMessage) {
        scope.launch {
            val outcome = try {
                dispatch(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SendOutcome.Failed(e.message ?: "Couldn't send")
            }
            when (outcome) {
                is SendOutcome.Sent -> onDelivered(message)
                is SendOutcome.Failed -> {
                    val failure = FailedSend(message, outcome.reason)
                    while (true) {
                        val current = failedByKey.value
                        if (failedByKey.compareAndSet(current, current + (message.conversationKey to (current[message.conversationKey].orEmpty() + failure)))) break
                    }
                    onFailed(failure)
                }
            }
        }
    }
}
