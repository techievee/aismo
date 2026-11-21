package aismo.sms.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import aismo.sms.domain.CategoryIconKeys

class CategoryIconCatalogueTest {
    @Test fun thePickerOffersExactlyTheStoredKeysInTheSameOrder() {
        assertEquals(CategoryIconKeys.ALL, CategoryIconChoices.map { it.key })
    }

    @Test fun theDefaultIsFirstSoANewCategoryStartsOnIt() {
        assertEquals(CategoryIconKeys.DEFAULT, CategoryIconKeys.ALL.first())
        assertEquals(CategoryIconKeys.DEFAULT, CategoryIconChoices.first().key)
    }

    @Test fun keysAndNamesAreUniqueAndEveryChoiceHasItsOwnPicture() {
        assertEquals(CategoryIconKeys.ALL.size, CategoryIconKeys.ALL.toSet().size)
        assertEquals(CategoryIconChoices.size, CategoryIconChoices.map { it.label }.toSet().size)
        assertTrue(CategoryIconChoices.all { it.label.isNotBlank() })
        assertEquals("two choices would look the same", CategoryIconChoices.size, CategoryIconChoices.map { it.icon }.toSet().size)
    }

    @Test fun aKnownKeyGivesItsPictureAndAnythingElseGivesTheDefault() {
        for (choice in CategoryIconChoices) assertSame(choice.icon, customCategoryIcon(choice.key))
        val default = CategoryIconChoices.first().icon
        assertSame(default, customCategoryIcon(null))
        assertSame(default, customCategoryIcon(""))
        assertSame("a key from a newer app shows the tag rather than nothing", default, customCategoryIcon("from-the-future"))
    }

    @Test fun thePickerIsASensibleSize() {
        assertTrue("a grid people can scan", CategoryIconChoices.size in 24..60)
    }
}
