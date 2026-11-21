package aismo.sms.data.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinCryptoTest {
    @Test fun aPinMustBeFourToEightDigits() {
        assertFalse(PinCrypto.isAcceptable(""))
        assertFalse(PinCrypto.isAcceptable("123"))
        assertTrue(PinCrypto.isAcceptable("1234"))
        assertTrue(PinCrypto.isAcceptable("12345678"))
        assertFalse(PinCrypto.isAcceptable("123456789"))
        assertFalse(PinCrypto.isAcceptable("12a4"))
    }

    @Test fun aRecordVerifiesItsOwnPin() {
        assertTrue(PinCrypto.check("1234", PinCrypto.record("1234")))
    }

    @Test fun theWrongPinFails() {
        assertFalse(PinCrypto.check("1235", PinCrypto.record("1234")))
    }

    @Test fun aTamperedRecordFails() {
        val parts = PinCrypto.record("1234").split(":")
        assertFalse(PinCrypto.check("1234", parts[0] + ":" + parts[1] + ":" + parts[2].reversed()))
        assertFalse("the format version is part of the check", PinCrypto.check("1234", "2:" + parts[1] + ":" + parts[2]))
        assertFalse(PinCrypto.check("1234", "garbage"))
    }

    @Test fun twoRecordsOfTheSamePinDifferBecauseTheyAreSalted() {
        assertNotEquals(PinCrypto.record("1234"), PinCrypto.record("1234"))
    }

    @Test fun theRecordNeverContainsThePin() {
        assertFalse(PinCrypto.record("4242").contains("4242"))
    }
}
