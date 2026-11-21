package aismo.sms

import android.app.role.RoleManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Not a test: fills the inbox with the search fixture for manual and visual checks. Runs only with `-e seedsearch true`. */
@RunWith(AndroidJUnit4::class)
class SearchFixtureSeed {
    @Test fun seed() = runBlocking {
        assumeTrue("seeder", InstrumentationRegistry.getArguments().getString("seedsearch") == "true")
        val app = ApplicationProvider.getApplicationContext<AismoApp>()
        assumeTrue(app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        val c = app.container
        c.db.conversations().allKeys().forEach { c.repository.deleteConversation(it) }
        c.provider.delete(c.provider.allIds().orEmpty())
        c.mmsProvider.delete(c.mmsProvider.allIds().orEmpty())
        c.db.embeddings().clear()
        val now = System.currentTimeMillis()
        val fixture = Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets.open("ai/search-fixture.json").bufferedReader().readText())
        for (m in fixture.jsonObject["messages"]!!.jsonArray) {
            val id = m.jsonObject["id"]!!.jsonPrimitive.int
            c.provider.insertIncoming("+6141100${1000 + id}", m.jsonObject["text"]!!.jsonPrimitive.content, now - id * 3_600_000L, now - id * 3_600_000L, -1)
        }
        c.repository.syncNew()
        Unit
    }
}
