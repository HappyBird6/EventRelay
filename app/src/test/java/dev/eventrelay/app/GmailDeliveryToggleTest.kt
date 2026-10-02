package dev.eventrelay.app

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Synthetic local preferences and queue only: never presses account authorization or sends mail. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [
    NoDeliverySchedulingShadow::class, BlockedGoogleSessionProviderShadow::class, BlockedGmailTransportShadow::class,
])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GmailDeliveryToggleTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var db: RelayDb
    private val email = "synthetic-owner@example.test"
    private val original = "Full historical permitted payload 😀\nuntouched tail"

    // This outer rule prepares state BEFORE ActivityScenario starts the Compose screen.
    private val isolatedState = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val context = RuntimeEnvironment.getApplication()
            val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            preferences.edit().clear().putString("gmailOwnEmail", email).commit()
            val field = RelayRuntime::class.java.getDeclaredField("db").apply { isAccessible = true }
            val previous = field.get(null) as? RelayDb
            db = Room.inMemoryDatabaseBuilder(context, RelayDb::class.java).allowMainThreadQueries().build()
            field.set(null, db)
            BlockedGoogleSessionProviderShadow.calls = 0
            BlockedGmailTransportShadow.calls = 0
            runBlocking {
                db.dao().enqueue(QueuedEvent("historical-held", "SMS", "SYNTHETIC", 1, original,
                    status = "BLOCKED_DELIVERY", destination = email))
                db.dao().enqueue(QueuedEvent("historical-pending", "SMS", "SYNTHETIC", 2, original,
                    status = "PENDING", destination = email))
                db.dao().enqueue(QueuedEvent("pre-auth", "SMS", "SYNTHETIC", 3, original,
                    status = "BLOCKED_AUTH", destination = null))
            }
            try { base.evaluate() } finally {
                field.set(null, previous)
                db.close()
                preferences.edit().clear().commit()
            }
        }
    } }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(isolatedState).around(compose)

    @Test fun enableRequiresRecipientAndContentConfirmationCancelStaysOffAndPastQueueIsHeld() {
        assumeTrue("Connection UI is available only in its explicitly enabled build", BuildConfig.GMAIL_ENABLED)
        val preferences = compose.activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        assertFalse(preferences.getBoolean("gmailDeliveryEnabled", false))
        compose.onNodeWithText("Gmail 전송: 꺼짐").assertExists()
        compose.onNodeWithText("Gmail 전송 켜기").performScrollTo().performClick()
        compose.onNodeWithText("앞으로 수집한 이벤트 전송 확인").assertIsDisplayed()
        compose.onNodeWithText("받는 사람: $email", substring = true).assertExists()
        compose.onNodeWithText("SMS 및 앱 알림의 원문", substring = true).assertExists()
        compose.onNodeWithText("이전 3개 항목은 자동으로 보내지 않습니다", substring = true).assertExists()
        compose.onNodeWithText("취소").performClick()
        assertFalse(preferences.getBoolean("gmailDeliveryEnabled", false))
        compose.onNodeWithText("앞으로 수집한 이벤트 전송 확인").assertDoesNotExist()

        compose.onNodeWithText("Gmail 전송 켜기").performScrollTo().performClick()
        compose.onNodeWithText("확인하고 전송 켜기").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { preferences.getBoolean("gmailDeliveryEnabled", false) }
        assertTrue(preferences.getLong("gmailDeliveryEnabledSince", 0) > 3)
        // Room completion and preference writes precede the Compose state/frame update.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Gmail 전송: 켜짐").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Gmail 전송: 켜짐").assertExists()
        runBlocking {
            assertTrue(db.dao().pending().isEmpty())
            db.openHelper.readableDatabase.query("SELECT id,status FROM queue").use { cursor ->
                val states = mutableMapOf<String, String>()
                while (cursor.moveToNext()) states[cursor.getString(0)] = cursor.getString(1)
                assertEquals("BLOCKED_DELIVERY", states["historical-held"])
                assertEquals("BLOCKED_DELIVERY", states["historical-pending"])
                assertEquals("BLOCKED_AUTH", states["pre-auth"])
            }
            listOf("historical-held", "historical-pending", "pre-auth").forEach { assertEquals(original, db.dao().payload(it)) }
        }
        assertEquals(0, BlockedGoogleSessionProviderShadow.calls)
        assertEquals(0, BlockedGmailTransportShadow.calls)
    }
}
