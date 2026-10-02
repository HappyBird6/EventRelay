package dev.eventrelay.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.Continuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.json.JSONObject

/**
 * Runs the real worker method with a test-owned Room DB and no WorkManager scheduling.
 * Authorization/provider/transport shadows fail immediately if the delivery gate regresses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [35],
    shadows = [BlockedGoogleSessionProviderShadow::class, BlockedGmailTransportShadow::class, NoDeliverySchedulingShadow::class],
)
class DeliveryWorkerSafetyTest {
    private lateinit var db: RelayDb
    private var previousDb: RelayDb? = null
    private val runtimeDbField = RelayRuntime::class.java.getDeclaredField("db").apply { isAccessible = true }

    @Before fun installIsolatedDatabaseAndGuards() {
        BlockedGoogleSessionProviderShadow.calls = 0
        BlockedGmailTransportShadow.calls = 0
        previousDb = runtimeDbField.get(null) as? RelayDb
        RuntimeEnvironment.getApplication().getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RelayDb::class.java)
            .allowMainThreadQueries().build()
        // The production singleton is restored after this test; its implementation is unchanged.
        runtimeDbField.set(null, db)
    }

    @After fun restoreProductionSingleton() {
        runtimeDbField.set(null, previousDb)
        db.close()
    }

    @SuppressLint("RestrictedApi")
    private fun workerParameters(): WorkerParameters {
        val direct = Executor { it.run() }
        return WorkerParameters(
            UUID.randomUUID(), Data.EMPTY, emptyList(), WorkerParameters.RuntimeExtras(),
            0, 0, direct, Dispatchers.Unconfined, WorkManagerTaskExecutor(direct),
            object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                    throw AssertionError("This test directly invokes its worker and must not schedule others")
                }
            },
            ProgressUpdater { _, _, _ -> throw AssertionError("No progress/account/network updater is needed") },
            ForegroundUpdater { _, _, _ -> throw AssertionError("No foreground/account/network updater is needed") },
        )
    }

    @Test fun disabledDeliveryBlocksPendingQueueBeforeAccountTokenOrNetworkAccess() = runBlocking {
        assertFalse("Gmail sending must default to OFF", gmailDeliveryEnabled(RuntimeEnvironment.getApplication()))
        val payload = "전체 허용 원문 😀\n".repeat(2_000) + "final untouched tail"
        val dao = db.dao()
        dao.enqueue(QueuedEvent("pending", "SMS", "SYNTHETIC-SENDER", 1, payload,
            status = "PENDING", destination = "synthetic-owner@example.test"))
        dao.enqueue(QueuedEvent("retryable", "SMS", "SYNTHETIC-SENDER", 2, payload,
            status = "RETRYABLE_FAILURE", destination = "synthetic-owner@example.test", nextAttemptAt = Long.MAX_VALUE))
        dao.enqueue(QueuedEvent("unknown", "SMS", "SYNTHETIC-SENDER", 3, payload,
            status = "OUTCOME_UNKNOWN", destination = "synthetic-owner@example.test"))
        dao.enqueue(QueuedEvent("auth-blocked", "SMS", "SYNTHETIC-SENDER", 4, payload))

        var accountPreferenceReads = 0
        var accountServiceReads = 0
        val guardedContext = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = super.getSharedPreferences(name, mode)
                return object : SharedPreferences by delegate {
                    override fun getString(key: String, defValue: String?): String? {
                        accountPreferenceReads++
                        throw AssertionError("Disabled delivery must not look up account/authorization settings")
                    }
                    override fun getAll(): Map<String, *> {
                        accountPreferenceReads++
                        throw AssertionError("Disabled delivery must not inspect all account settings")
                    }
                }
            }
            override fun getSystemService(name: String): Any? {
                if (name == Context.ACCOUNT_SERVICE) {
                    accountServiceReads++
                    throw AssertionError("Disabled delivery cannot access AccountManager")
                }
                return super.getSystemService(name)
            }
        }

        val result = DeliveryWorker(guardedContext, workerParameters()).doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        val states = dao.watchQueue().first().associate { it.id to it.status }
        assertEquals("BLOCKED_DELIVERY", states["pending"])
        assertEquals("BLOCKED_DELIVERY", states["retryable"])
        assertEquals("OUTCOME_UNKNOWN", states["unknown"])
        assertEquals("BLOCKED_AUTH", states["auth-blocked"])
        assertTrue(dao.pending().isEmpty())
        listOf("pending", "retryable", "unknown", "auth-blocked").forEach { assertEquals(payload, dao.payload(it)) }
        db.openHelper.readableDatabase.query(
            "SELECT lastError,completedAt,gmailMessageId FROM queue WHERE id IN ('pending','retryable')",
        ).use { cursor ->
            var rows = 0
            while (cursor.moveToNext()) {
                rows++
                assertEquals("DELIVERY_APPROVAL_REQUIRED", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
            }
            assertEquals(2, rows)
        }
        assertEquals(0, accountPreferenceReads)
        assertEquals(0, accountServiceReads)
        assertEquals(0, BlockedGoogleSessionProviderShadow.calls)
        assertEquals(0, BlockedGmailTransportShadow.calls)
    }

    @Test fun collectionWhileOffAndPreAuthorizationQueueNeverBecomeSendableWhenEnabled() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        preferences.edit().putString("gmailOwnEmail", "synthetic-owner@example.test").commit()
        db.dao().saveRule(StoredRule("synthetic-rule", "Synthetic source", "SMS", "SYNTHETIC-SENDER", "[]"))
        RelayRuntime.collect(context, "SMS", "SYNTHETIC-SENDER", "collected-while-off", 1_700_000_000_000,
            null, "Complete permitted original text 😀", JSONObject().put("synthetic", true))
        db.dao().enqueue(QueuedEvent("before-authorization", "SMS", "SYNTHETIC-SENDER", 1, "pre-auth original",
            status = "BLOCKED_AUTH", destination = null))
        val offRow = db.dao().watchQueue().first().single { it.id != "before-authorization" }
        assertEquals("BLOCKED_DELIVERY", offRow.status)
        val heldPayload = db.dao().payload(offRow.id)
        preferences.edit().putLong("gmailDeliveryEnabledSince", System.currentTimeMillis())
            .putBoolean("gmailDeliveryEnabled", true).commit()
        assertTrue(db.dao().pending().isEmpty())
        assertEquals(heldPayload, db.dao().payload(offRow.id))
        val states = db.dao().watchQueue().first().associate { it.id to it.status }
        assertEquals("BLOCKED_DELIVERY", states[offRow.id])
        assertEquals("BLOCKED_AUTH", states["before-authorization"])
        assertEquals(0, BlockedGoogleSessionProviderShadow.calls)
        assertEquals(0, BlockedGmailTransportShadow.calls)
    }

    @Test fun eligibilityRequiresStrictlyNewerEventThanTheCurrentEnableCutoff() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        assertFalse(gmailDeliveryEligible(context, 101))
        preferences.edit().putBoolean("gmailDeliveryEnabled", true).commit()
        assertFalse("Missing cutoff must fail closed", gmailDeliveryEligible(context, 101))
        preferences.edit().putLong("gmailDeliveryEnabledSince", 100).commit()
        assertFalse(gmailDeliveryEligible(context, 99))
        assertFalse(gmailDeliveryEligible(context, 100))
        assertEquals(BuildConfig.GMAIL_ENABLED, gmailDeliveryEligible(context, 101))
        preferences.edit().putLong("gmailDeliveryEnabledSince", 200).commit()
        assertFalse("A new confirmation must invalidate prior-period events", gmailDeliveryEligible(context, 101))
        preferences.edit().putBoolean("gmailDeliveryEnabled", false).commit()
        assertFalse(gmailDeliveryEligible(context, 201))
    }
}

@Implements(value = GoogleGmailSessionProvider::class, isInAndroidSdk = false)
class BlockedGoogleSessionProviderShadow {
    companion object { @JvmField var calls = 0 }

    @Implementation fun __constructor__(context: Context) {
        calls++
        throw AssertionError("Connection-only DeliveryWorker must not create a Google token provider")
    }

    @Implementation fun session(continuation: Continuation<AuthorizedGmailSession?>): Any {
        calls++
        throw AssertionError("Connection-only DeliveryWorker must not obtain or refresh tokens")
    }
}

@Implements(value = HttpsGmailTransport::class, isInAndroidSdk = false)
class BlockedGmailTransportShadow {
    companion object { @JvmField var calls = 0 }

    @Implementation fun __constructor__() {
        calls++
        throw AssertionError("Connection-only DeliveryWorker must not create a real Gmail transport")
    }

    @Implementation fun post(accessToken: String, jsonBody: String, continuation: Continuation<GmailHttpResponse>): Any {
        calls++
        throw AssertionError("Connection-only DeliveryWorker must not submit mail or transmit a token")
    }
}

/** Collector tests store their event without starting WorkManager or any real delivery worker. */
@Implements(value = RelayRuntime::class, isInAndroidSdk = false)
class NoDeliverySchedulingShadow {
    @Implementation fun schedule(context: Context) = Unit
}
