package aismo.sms.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryOrderTest {
    private val natural = listOf("personal", "otp", "finance", "spam", "custom:abc12345")

    @Test fun withNothingSavedTheNaturalOrderApplies() = assertEquals(natural, CategoryOrder.arrange(emptyList(), natural))

    @Test fun theSavedOrderComesFirst() =
        assertEquals(listOf("spam", "personal", "otp", "finance", "custom:abc12345"), CategoryOrder.arrange(listOf("spam", "personal"), natural))

    @Test fun categoriesThatNoLongerExistDropOut() =
        assertEquals(listOf("otp", "personal", "finance", "spam", "custom:abc12345"), CategoryOrder.arrange(listOf("otp", "custom:gone9999", "personal"), natural))

    @Test fun aNewCategoryGoesToTheEndOfWhatWasSaved() {
        val saved = listOf("spam", "finance", "otp", "personal")
        assertEquals(listOf("spam", "finance", "otp", "personal", "custom:abc12345"), CategoryOrder.arrange(saved, natural))
    }

    @Test fun duplicatesInTheSavedOrderCountOnce() =
        assertEquals(listOf("otp", "personal", "finance", "spam", "custom:abc12345"), CategoryOrder.arrange(listOf("otp", "otp", "personal"), natural))

    @Test fun builtInDefaultCoversEveryCategoryOnce() {
        assertEquals(SmsCategory.entries.size, CategoryOrder.DEFAULT.size)
        assertEquals(CategoryOrder.DEFAULT.size, CategoryOrder.DEFAULT.toSet().size)
    }

    @Test fun moveShiftsOnePlace() {
        assertEquals(listOf("personal", "finance", "otp"), CategoryOrder.move(listOf("personal", "otp", "finance"), "finance", -1))
        assertEquals(listOf("otp", "personal", "finance"), CategoryOrder.move(listOf("personal", "otp", "finance"), "personal", 1))
    }

    @Test fun moveStopsAtTheEnds() {
        val order = listOf("personal", "otp", "finance")
        assertEquals(order, CategoryOrder.move(order, "personal", -1))
        assertEquals(order, CategoryOrder.move(order, "finance", 1))
        assertEquals(listOf("finance", "personal", "otp"), CategoryOrder.move(order, "finance", -10))
    }

    @Test fun moveIgnoresAnUnknownKey() {
        val order = listOf("personal", "otp")
        assertEquals(order, CategoryOrder.move(order, "nope", 1))
    }

    private val order = listOf("personal", "otp", "finance", "promo")

    @Test fun theBarListsCategoriesWithMessagesInTheUsersOrder() {
        assertEquals(listOf("personal", "finance"), CategoryOrder.shown(order, setOf("finance", "personal"), null))
    }

    @Test fun aHiddenCategoryKeepsItsTab() {
        // Hiding only takes the messages out of the All view; the tab stays, so the category can be reached and un-hidden.
        assertEquals(listOf("personal", "otp", "finance", "promo"), CategoryOrder.shown(order, setOf("personal", "otp", "finance", "promo"), null))
    }

    @Test fun anEmptyCategoryStillLeavesTheBarUnlessItIsOpen() {
        assertEquals(listOf("personal", "finance"), CategoryOrder.shown(order, setOf("personal", "finance"), null))
        assertEquals(listOf("personal", "otp"), CategoryOrder.shown(order, setOf("personal"), "otp"))
    }

    @Test fun theOpenCategoryKeepsItsTabWhetherHiddenOrEmpty() {
        assertEquals(listOf("personal", "otp"), CategoryOrder.shown(order, setOf("personal"), "otp"))
        assertEquals(listOf("personal", "promo"), CategoryOrder.shown(order, setOf("personal"), "promo"))
    }

    @Test fun aSelectionNotInTheOrderGetsNoTab() {
        assertEquals(order, CategoryOrder.shown(order, order.toSet(), null))
        assertEquals("a selection that is not in the order has no tab to show", order, CategoryOrder.shown(order, order.toSet(), "gone"))
    }

    @Test fun inAllListsEveryOtherCategoryAndExcludesHiddenAndLockedSecuredOnes() {
        assertTrue(CategoryOrder.inAll("personal", emptySet(), emptySet(), false, false))
        assertTrue(CategoryOrder.inAll("finance", emptySet(), emptySet(), false, false))
        assertFalse("a hidden category leaves the All view", CategoryOrder.inAll("finance", setOf("finance"), emptySet(), false, false))
        assertFalse("a secured category leaves the All view while locked", CategoryOrder.inAll("finance", emptySet(), setOf("finance"), false, false))
        assertTrue("a secured category is back in the All view once unlocked", CategoryOrder.inAll("finance", emptySet(), setOf("finance"), true, false))
    }

    @Test fun inAllFollowsTheShowSpamSwitch() {
        assertFalse(CategoryOrder.inAll("spam", emptySet(), emptySet(), false, false))
        assertTrue(CategoryOrder.inAll("spam", emptySet(), emptySet(), false, true))
        assertFalse("hidden beats the switch", CategoryOrder.inAll("spam", setOf("spam"), emptySet(), false, true))
    }
}
