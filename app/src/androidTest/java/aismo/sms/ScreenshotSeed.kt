package aismo.sms

import android.app.role.RoleManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.data.db.MessageIds
import aismo.sms.data.db.MmsDownloadEntity
import aismo.sms.data.settings.AppPalette
import aismo.sms.data.settings.ThemeMode
import aismo.sms.domain.mms.MmsAddress
import aismo.sms.domain.mms.MmsCharset
import aismo.sms.domain.mms.MmsPart
import aismo.sms.domain.mms.MmsPdu
import aismo.sms.domain.mms.MmsType
import java.io.ByteArrayOutputStream

/**
 * Not a test: seeds the full demo data set (SMS across every inbox category plus the MMS story) for
 * manual/visual checks and screenshot runs. Runs only with `-e seed true`. Optional extras:
 * `-e palette ocean` (light) or `-e palette ocean-dark` (dark) applies the Ocean theme;
 * `-e secure <category>` locks that category (or `-e secure none` unlocks everything).
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotSeed {
    private val app get() = ApplicationProvider.getApplicationContext<AismoApp>()
    private val c get() = app.container
    private val me = "+61411999000"

    private fun scene(): ByteArray {
        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, 480f, Color.rgb(255, 183, 77), Color.rgb(79, 195, 247), Shader.TileMode.CLAMP); cv.drawRect(0f, 0f, 640f, 480f, p)
        p.shader = null; p.color = Color.rgb(255, 235, 59); cv.drawCircle(500f, 110f, 60f, p)
        p.color = Color.rgb(25, 118, 210); cv.drawRect(0f, 320f, 640f, 480f, p)
        p.color = Color.rgb(46, 125, 50); cv.drawCircle(130f, 330f, 90f, p)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun retrieved(from: String, to: List<String>, text: String?, subject: String? = null, extra: List<MmsPart> = emptyList(), ageMs: Long = 0) = MmsPdu(
        type = MmsType.RETRIEVE_CONF, transactionId = "seed-${System.nanoTime()}", from = MmsAddress.of(from), to = to.map { MmsAddress.of(it) },
        subject = subject, dateSeconds = (System.currentTimeMillis() - ageMs) / 1000, contentType = "application/vnd.wap.multipart.related",
        parts = listOfNotNull(text?.let { MmsPart("text/plain", "text_0.txt", "text_0", MmsCharset.UTF_8, it.toByteArray()) }) + extra,
    )

    @Test fun seed() = runBlocking {
        assumeTrue("seed only on request", InstrumentationRegistry.getArguments().getString("seed") == "true")
        assumeTrue(app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        c.db.conversations().allKeys().forEach { c.repository.deleteConversation(it) }
        c.provider.delete(c.provider.allIds().orEmpty()); c.mmsProvider.delete(c.mmsProvider.allIds().orEmpty())
        val args = InstrumentationRegistry.getArguments()
        c.settings.update { s ->
            var next = s.copy(ownNumber = me, autoDownloadMms = false, onboardingDone = true)
            when (args.getString("palette")) {
                "ocean" -> next = next.copy(palette = AppPalette.OCEAN, theme = ThemeMode.LIGHT)
                "ocean-dark" -> next = next.copy(palette = AppPalette.OCEAN, theme = ThemeMode.DARK)
            }
            args.getString("secure")?.let {
                next = if (it == "none") next.copy(securedCategories = emptySet()) else next.copy(securedCategories = setOf(it))
            }
            next
        }
        val now = System.currentTimeMillis()

        // Plain text across the inbox categories: the classifier files each one, like a real week of messages.
        val ids = buildList {
            add(c.provider.insertIncoming("+61411000555", "Are we still meeting on Saturday?", now - 3_600_000, now - 3_600_000, -1))
            add(c.provider.insertIncoming("+61411000321", "अरे भैया, कल शाम को मिलेंगे न? घर पर खाना बनाऊंगा।", now - 2 * 3_600_000, now - 2 * 3_600_000, -1))
            add(c.provider.insertIncoming("GOOGLE", "Google: your one-time code is 482913. It expires in 10 minutes. Google never asks for this code.", now - 40 * 60_000, now - 40 * 60_000, -1))
            add(c.provider.insertIncoming("IN-INDIGO", "INDIGO: flight 6E-505 to BOM departs 09:15 on 22 Nov from gate 12. Check in online. PNR KX7Q2M.", now - 6 * 3_600_000, now - 6 * 3_600_000, -1))
            add(c.provider.insertIncoming("POWERLINK", "PowerLink: your electricity bill of $142.30 is due 28 Nov. Pay online to avoid a late fee. Account 048211.", now - 26 * 3_600_000, now - 26 * 3_600_000, -1))
            add(c.provider.insertIncoming("CBA-BANK", "CBA: $58.40 charged at WOOLWORTHS SYD. New balance $1,204.55.", now - 20 * 3_600_000, now - 20 * 3_600_000, -1))
            add(c.provider.insertIncoming("CBA-BANK", "CBA: $32.10 charged at UBER RIDE SHARE. New balance $1,262.95.", now - 30 * 3_600_000, now - 30 * 3_600_000, -1))
            add(c.provider.insertIncoming("ARAMEX", "Aramex: your parcel 88231 is out for delivery today between 1pm and 5pm. Track: au.ar/x88231", now - 50 * 60_000, now - 50 * 60_000, -1))
            add(c.provider.insertIncoming("JBHI", "JB Hi-Fi: weekend sale, up to 40% off TVs and audio. This weekend only. T&Cs apply.", now - 52 * 3_600_000, now - 52 * 3_600_000, -1))
            add(c.provider.insertIncoming("WINNER", "Congratulations! You have been selected to receive $10,000. Claim within 24 hours: bit.ly/claim10k", now - 72 * 3_600_000, now - 72 * 3_600_000, -1))
            add(c.provider.insertIncoming("BANK-SUPPORT", "Your account will be suspended. Verify your details now: bit.ly/verify-account-3821", now - 74 * 3_600_000, now - 74 * 3_600_000, -1))
        }
        // A couple of fresh arrivals stay unread so the inbox badges are visible; the rest read.
        val unread = setOf(ids[2], ids[7], ids[1])
        c.provider.markRead(ids - unread, true); c.provider.markRead(unread, false)
        val photo = MmsPart("image/jpeg", "beach.jpg", "beach", null, scene())
        val a = c.mmsProvider.insertRetrieved(retrieved("+61411000555", listOf(me), "Look at the beach!", "Holiday", listOf(photo), 1_800_000), -1, now - 1_800_000, setOf("+61411000555"))
        val vcard = MmsPart("text/x-vCard", "Sam.vcf", "vcf", null, "BEGIN:VCARD\nVERSION:3.0\nFN:Sam Rivera\nTEL:+61411000321\nEND:VCARD\n".toByteArray())
        val b = c.mmsProvider.insertRetrieved(retrieved("+61411000555", listOf(me), null, null, listOf(vcard), 1_700_000), -1, now - 1_700_000, setOf("+61411000555"))
        val out = c.mmsProvider.insertOutgoing(listOf("+61411000555"), null, listOf(MmsPart("text/plain", "t.txt", "t", MmsCharset.UTF_8, "Sending you the plan".toByteArray()), MmsPart("image/jpeg", "plan.jpg", "p", null, scene())), -1, "seed-out")
        c.mmsProvider.updateBox(out, Telephony.Mms.MESSAGE_BOX_FAILED)

        val g = c.mmsProvider.insertRetrieved(retrieved("+61411000001", listOf(me, "+61411000002", "+61411000003"), "Dinner at 7? Table is booked.", ageMs = 600_000), -1, now - 600_000, setOf("+61411000001", "+61411000002", "+61411000003"))

        val n1 = c.mmsProvider.insertNotification(MmsPdu(type = MmsType.NOTIFICATION_IND, transactionId = "seed-n1", from = MmsAddress.of("+61411000777"), subject = "Photos from the trip", messageSize = 420_000, expiryRelativeSeconds = 259_200, contentLocation = "http://10.0.2.2:8089/mms/retrieve/1"), -1, now - 300_000)
        val n2 = c.mmsProvider.insertNotification(MmsPdu(type = MmsType.NOTIFICATION_IND, transactionId = "seed-n2", from = MmsAddress.of("+61411000888"), subject = null, messageSize = 90_000, expiryRelativeSeconds = 259_200, contentLocation = "http://mmsc.invalid/2"), -1, now - 200_000)

        c.repository.syncNew()
        c.db.mmsDownloads().setState(MessageIds.mms(n2), MmsDownloadEntity.FAILED, 1, 3)
        println("seeded: $a $b $out $g $n1 $n2")
    }
}
