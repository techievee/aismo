package aismo.sms.domain.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageGuesserTest {
    /** A non-Latin script pins the language outright. */
    @Test fun theScriptDecidesForNonLatinText() {
        assertEquals(Language.HI, LanguageGuesser.guess("कल दोपहर को खाने पर आनन"))
        assertEquals(Language.BN, LanguageGuesser.guess("কাল বিকেলে দেখা হবে"))
        assertEquals(Language.TA, LanguageGuesser.guess("காலை சாப்பிடவா வா"))
        assertEquals(Language.AR, LanguageGuesser.guess("أنا في الطريق الآن"))
        assertEquals(Language.HE, LanguageGuesser.guess("נראה מחר בבוקר"))
        assertEquals(Language.RU, LanguageGuesser.guess("Завтра встретимся в обед?"))
        assertEquals(Language.EL, LanguageGuesser.guess("Συναντηθούμε αύριο μεσημέρι;"))
        assertEquals(Language.TH, LanguageGuesser.guess("พรุ่งนี้เจอกันตอนเที่ยง"))
        assertEquals(Language.KO, LanguageGuesser.guess("내일 낮에 만날까요?"))
        assertEquals(Language.ZH, LanguageGuesser.guess("明天中午一起吃午饭？"))
    }

    /** Kana is what tells Japanese apart from Chinese, which shares the ideographs. */
    @Test fun kanaSeparateJapaneseFromChinese() {
        assertEquals(Language.JA, LanguageGuesser.guess("明日は昼に会えますか"))
        assertEquals(Language.ZH, LanguageGuesser.guess("明天中午一起吃午饭？"))
    }

    /** Latin-script languages are recognised from a couple of their most common words. */
    @Test fun latinScriptTextIsRecognisedFromItsCommonWords() {
        assertEquals(Language.ES, LanguageGuesser.guess("No puedo ir mañana, lo siento mucho"))
        assertEquals(Language.FR, LanguageGuesser.guess("Je t'appelle plus tard, d'accord ?"))
        assertEquals(Language.DE, LanguageGuesser.guess("Ich schicke dir morgen eine Nachricht."))
        assertEquals(Language.PT, LanguageGuesser.guess("Vou chegar em dez minutos, tá bom?"))
        assertEquals(Language.IT, LanguageGuesser.guess("Mi dispiace, non posso venire domani"))
        assertEquals(Language.NL, LanguageGuesser.guess("Ik stuur je morgen een bericht."))
        assertEquals(Language.SV, LanguageGuesser.guess("Jag ringer senare, det går bra"))
        assertEquals(Language.TR, LanguageGuesser.guess("Yarın öğlen yemeğe gelir misin, çok güzel"))
        assertEquals(Language.PL, LanguageGuesser.guess("Zjem jutro obiad w południe, tak?"))
        assertEquals(Language.ID, LanguageGuesser.guess("Besok siang mau makan bersama dengan kamu?"))
        assertEquals(Language.SW, LanguageGuesser.guess("Kesho mchana tutaonana, ni sawa"))
    }

    /** A text that does not look like any known language is not guessed; the caller reads that as its own language. */
    @Test fun unclearTextIsNotGuessed() {
        assertNull(LanguageGuesser.guess("Are you coming to lunch tomorrow?"))
        assertNull(LanguageGuesser.guess("I'll have a cafe tomorrow"))
        assertNull(LanguageGuesser.guess("123456 is your code. Do not share it."))
        assertNull(LanguageGuesser.guess(""))
        assertNull(LanguageGuesser.guess("   "))
    }

    /** One foreign word in an English text is not enough to call it that language. */
    @Test fun aFewLoanwordsDoNotChangeTheLanguage() {
        assertNull(LanguageGuesser.guess("Meet me at the cafe, it's not far"))
        assertNull(LanguageGuesser.guess("Send it, and also the other file"))
    }
}
