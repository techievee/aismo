package aismo.sms.domain.mms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class MmsLayoutTest {
    private fun img(name: String) = MmsPart("image/jpeg", name)
    private val text = MmsPart("text/plain", "text_0.txt")

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())

    @Test fun smilIsWellFormedXmlWithOneSlidePerMediaPart() {
        val doc = parse(SmilBuilder.build(text, listOf(img("a.jpg"), img("b.jpg"), MmsPart("video/3gpp", "v.3gp"), MmsPart("audio/amr", "s.amr"))))
        assertEquals(4, doc.getElementsByTagName("par").length)
        assertEquals(2, doc.getElementsByTagName("img").length)
        assertEquals(1, doc.getElementsByTagName("video").length)
        assertEquals(1, doc.getElementsByTagName("audio").length)
    }

    @Test fun textGoesOnTheFirstSlideOnly() {
        val doc = parse(SmilBuilder.build(text, listOf(img("a.jpg"), img("b.jpg"))))
        assertEquals(1, doc.getElementsByTagName("text").length)
        val firstPar = doc.getElementsByTagName("par").item(0)
        assertTrue((0 until firstPar.childNodes.length).any { firstPar.childNodes.item(it).nodeName == "text" })
    }

    @Test fun textOnlyMessageStillGetsASlide() {
        val doc = parse(SmilBuilder.build(text, emptyList()))
        assertEquals(1, doc.getElementsByTagName("par").length)
        assertEquals(1, doc.getElementsByTagName("text").length)
    }

    @Test fun unsupportedTypesAreReferencedGenerically() {
        val doc = parse(SmilBuilder.build(null, listOf(MmsPart("text/x-vCard", "c.vcf"))))
        assertEquals(1, doc.getElementsByTagName("ref").length)
    }

    @Test fun fileNamesWithXmlSpecialCharactersCannotBreakTheDocument() {
        val xml = SmilBuilder.build(null, listOf(img("a&b\"<c>.jpg")))
        val doc = parse(xml) // must not throw
        assertEquals("a&b\"<c>.jpg", doc.getElementsByTagName("img").item(0).attributes.getNamedItem("src").nodeValue)
    }

    @Test fun budgetSplitsTheRoomBetweenImages() {
        val each = MmsBudget.perImageBytes(maxBytes = 300_000, textBytes = 1_000, otherBytes = 0, imageCount = 2)!!
        assertEquals((300_000 - MmsBudget.OVERHEAD - 1_000) / 2, each)
    }

    @Test fun budgetRejectsWhenNothingWouldFit() {
        assertNull(MmsBudget.perImageBytes(300_000, 0, otherBytes = 299_000, imageCount = 1))
        assertNull("non-image content alone is already too big", MmsBudget.perImageBytes(300_000, 0, otherBytes = 400_000, imageCount = 0))
        assertNotNull(MmsBudget.perImageBytes(300_000, 100, otherBytes = 1_000, imageCount = 0))
    }

    @Test fun carrierLimitIsClampedToSaneBounds() {
        assertEquals(MmsBudget.DEFAULT_MAX, MmsBudget.clampMax(null))
        assertEquals(50 * 1024, MmsBudget.clampMax(1))
        assertEquals(5 * 1024 * 1024, MmsBudget.clampMax(Int.MAX_VALUE))
        assertFalse(MmsBudget.clampMax(1_048_576) != 1_048_576)
    }
}
