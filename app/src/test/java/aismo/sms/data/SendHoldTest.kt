package aismo.sms.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SendHoldTest {
    private class Rig(scope: CoroutineScope, now: () -> Long, outcome: (HeldMessage) -> SendOutcome) {
        val sent = mutableListOf<String>()
        val delivered = mutableListOf<HeldMessage>()
        val failures = mutableListOf<FailedSend>()
        val hold = SendHold(scope, now, dispatch = { m -> sent += m.text; outcome(m) }, onDelivered = { delivered += it }, onFailed = { failures += it })

        fun put(text: String, key: String = "p:1", delayMs: Long = 5_000, restoresDraft: Boolean = true) =
            hold.hold(key, listOf("+61400000001"), text, emptyList(), subId = -1, restoresDraft = restoresDraft, delayMs = delayMs)
    }

    private val sentOk: (HeldMessage) -> SendOutcome = { SendOutcome.Sent(1, SendRoute.SMS) }

    private fun TestScope.rig(outcome: (HeldMessage) -> SendOutcome = sentOk) = Rig(backgroundScope, { currentTime }, outcome)

    @Test fun aHeldMessageGoesOutOnlyWhenItsTimeIsUp() = runTest {
        val r = rig()
        r.put("hello")
        advanceTimeBy(4_999); runCurrent()
        assertTrue("still held", r.sent.isEmpty() && r.hold.held.value.containsKey("p:1"))
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("hello"), r.sent)
        assertTrue(r.hold.held.value.isEmpty())
        assertEquals("its files are released once it is sent", 1, r.delivered.size)
    }

    @Test fun theHeldMessageKnowsWhenItWillGo() = runTest {
        advanceTimeBy(1_000)
        val m = rig().put("hello", delayMs = 7_000)
        assertEquals(1_000, m.heldAt)
        assertEquals(8_000, m.releaseAt)
    }

    @Test fun undoBeforeTheTimeHandsTheMessageBackAndNeverSendsIt() = runTest {
        val r = rig()
        r.put("hello")
        advanceTimeBy(2_000); runCurrent()
        assertEquals("hello", r.hold.undo("p:1")?.text)
        advanceTimeBy(20_000); runCurrent()
        assertTrue(r.sent.isEmpty())
        assertNull("a second undo finds nothing", r.hold.undo("p:1"))
        assertTrue(r.delivered.isEmpty())
    }

    @Test fun sendNowSendsOnceAndTheTimerDoesNotSendItAgain() = runTest {
        val r = rig()
        r.put("hello")
        r.hold.sendNow("p:1"); runCurrent()
        assertEquals(listOf("hello"), r.sent)
        advanceTimeBy(20_000); runCurrent()
        assertEquals("still once", listOf("hello"), r.sent)
    }

    @Test fun undoAfterTheMessageWentOutFindsNothing() = runTest {
        val r = rig()
        r.put("hello", delayMs = 1_000)
        advanceTimeBy(1_001); runCurrent()
        assertNull(r.hold.undo("p:1"))
        assertEquals(listOf("hello"), r.sent)
    }

    @Test fun anUndoAtTheVeryMomentTheTimerIsDueWinsAndNothingIsSent() = runTest {
        val r = rig()
        r.put("hello", delayMs = 1_000)
        advanceTimeBy(1_000)   // the timer is due but has not run yet
        assertEquals("hello", r.hold.undo("p:1")?.text)
        runCurrent()
        assertTrue(r.sent.isEmpty())
    }

    @Test fun aSecondMessageInTheSameConversationSendsTheFirstAtOnceSoTheyStayInOrder() = runTest {
        val r = rig()
        r.put("first")
        r.put("second")
        runCurrent()
        assertEquals(listOf("first"), r.sent)
        assertEquals("second", r.hold.held.value.getValue("p:1").text)
        advanceTimeBy(5_001); runCurrent()
        assertEquals(listOf("first", "second"), r.sent)
    }

    @Test fun conversationsAreHeldIndependently() = runTest {
        val r = rig()
        r.put("to one", key = "p:1")
        r.put("to two", key = "p:2")
        assertEquals("to one", r.hold.undo("p:1")?.text)
        advanceTimeBy(5_001); runCurrent()
        assertEquals(listOf("to two"), r.sent)
    }

    @Test fun flushAllSendsEverythingHeldRightAway() = runTest {
        val r = rig()
        r.put("one", key = "p:1")
        r.put("two", key = "p:2", delayMs = 30_000)
        r.hold.flushAll(); runCurrent()
        assertEquals(setOf("one", "two"), r.sent.toSet())
        assertTrue(r.hold.held.value.isEmpty())
        advanceTimeBy(40_000); runCurrent()
        assertEquals("nothing is sent twice", 2, r.sent.size)
    }

    @Test fun aMessageThatCannotBeSentIsKeptWithItsReasonUntilTheConversationClaimsIt() = runTest {
        val r = rig { SendOutcome.Failed("Couldn't send. Check the number and try again.") }
        r.put("hello", delayMs = 1_000)
        advanceTimeBy(1_001); runCurrent()
        assertTrue(r.delivered.isEmpty())
        assertEquals(1, r.failures.size)
        val failures = r.hold.takeFailures("p:1")
        assertEquals("hello", failures.single().message.text)
        assertEquals("Couldn't send. Check the number and try again.", failures.single().reason)
        assertTrue("handed over once", r.hold.takeFailures("p:1").isEmpty())
        assertTrue(r.hold.failed.value.isEmpty())
    }

    @Test fun twoFailuresInOneConversationAreBothKeptInOrder() = runTest {
        val r = rig { SendOutcome.Failed("no") }
        r.put("first"); r.put("second", delayMs = 1_000)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(listOf("first", "second"), r.hold.takeFailures("p:1").map { it.message.text })
    }

    @Test fun aSendThatThrowsIsAFailureNotACrash() = runTest {
        val r = Rig(backgroundScope, { currentTime }) { throw IllegalStateException("provider unavailable") }
        r.put("hello", delayMs = 1_000)
        advanceTimeBy(1_001); runCurrent()
        assertEquals("provider unavailable", r.hold.takeFailures("p:1").single().reason)
    }

    @Test fun aQuickReplyIsMarkedAsHavingNoDraftToReturnTo() = runTest {
        val r = rig()
        assertEquals(false, r.put("Yes", restoresDraft = false).restoresDraft)
        assertEquals(true, r.put("typed", key = "p:2").restoresDraft)
    }
}
