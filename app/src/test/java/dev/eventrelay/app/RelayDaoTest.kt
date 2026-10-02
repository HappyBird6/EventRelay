package dev.eventrelay.app

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

/** Database tests never initialize RelayRuntime, WorkManager, OAuth, or a network gateway. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RelayDaoTest {
    private lateinit var db: RelayDb
    private lateinit var dao: RelayDao

    @Before fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RelayDb::class.java)
            .allowMainThreadQueries().build()
        dao = db.dao()
    }

    @After fun closeDatabase() { db.close() }

    private fun event(
        id: String,
        observedAt: Long = 1_000,
        status: String = "BLOCKED_AUTH",
        payload: String = "{\"body\":\"허용된 원문 😀\\n전체 본문\"}",
        completedAt: Long? = null,
    ) = QueuedEvent(id, "SMS", "ALLOWLISTED-SENDER", observedAt, payload, status,
        completedAt = completedAt)

    private fun storedAttempts(id: String): Int = db.openHelper.readableDatabase
        .query("SELECT attempts FROM queue WHERE id=?", arrayOf(id)).use {
            assertTrue(it.moveToFirst()); it.getInt(0)
        }

    private fun queueRows(): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM queue").use {
        assertTrue(it.moveToFirst()); it.getInt(0)
    }

    @Test fun sourceEventIdentityIsInsertOnceAndDuplicateCannotOverwriteOriginal() = runBlocking {
        val original = event("source-occurrence-1", payload = "original exact body\n한글😀")
        assertNotEquals(-1L, dao.enqueue(original))
        assertEquals(-1L, dao.enqueue(original.copy(payload = "different body", status = "ACCEPTED")))
        assertEquals(original.payload, dao.payload(original.id))
        assertEquals("BLOCKED_AUTH", dao.watchQueue().first().single().status)
        assertEquals(1, queueRows())
    }

    @Test fun differentSourceIdentitiesWithEqualPayloadAreBothStored() = runBlocking {
        val text = "same payment amount, no transaction heuristic"
        dao.enqueue(event("sms-source-occurrence", payload = text))
        dao.enqueue(event("notification-key-revision", payload = text))
        assertEquals(2, queueRows())
        assertEquals(text, dao.payload("sms-source-occurrence"))
        assertEquals(text, dao.payload("notification-key-revision"))
    }

    @Test fun completeLongMultilineUnicodePayloadRoundTripsWithoutTruncation() = runBlocking {
        val full = "줄바꿈과 원문 😀\n\"quote\"\\backslash\n".repeat(8_000) + "final-tail"
        dao.enqueue(event("full-payload", payload = full))
        assertEquals(full, dao.payload("full-payload"))
    }

    @Test fun queueListIsMetadataOnlyAndLimitedToNewestHundred() = runBlocking {
        for (i in 1..105) dao.enqueue(event("row-$i", observedAt = i.toLong(), payload = "PRIVATE BODY $i"))
        val summaries = dao.watchQueue().first()
        assertEquals(100, summaries.size)
        assertEquals("row-105", summaries.first().id)
        assertEquals("row-6", summaries.last().id)
        assertTrue(summaries.zipWithNext().all { (a, b) -> a.observedAt >= b.observedAt })
        // Verify the projection model has no payload field and its representation cannot expose it.
        assertFalse(QueueSummary::class.java.declaredFields.any { it.name == "payload" })
        assertFalse(summaries.toString().contains("PRIVATE BODY"))
        assertEquals("PRIVATE BODY 105", dao.payload("row-105"))
    }

    @Test fun onlyPendingAndKnownRetryableFailuresAreEligibleForSending() = runBlocking {
        val states = listOf("PENDING", "RETRYABLE_FAILURE", "BLOCKED_AUTH", "SENDING", "OUTCOME_UNKNOWN",
            "ACCEPTED", "PERMANENT_FAILURE")
        states.forEach { dao.enqueue(event(it, status = it)) }
        assertEquals(setOf("PENDING", "RETRYABLE_FAILURE"), dao.pending().map { it.status }.toSet())
        assertEquals(5, dao.activeCount())
    }

    @Test fun atomicClaimIncrementsAttemptsOnceAndCannotReclaimSendingRecord() = runBlocking {
        dao.enqueue(event("pending", status = "PENDING"))
        assertEquals(1, dao.claim("pending"))
        assertEquals(0, dao.claim("pending"))
        assertEquals(0, dao.claim("missing"))
        assertEquals(1, storedAttempts("pending"))
        assertEquals("SENDING", dao.watchQueue().first().single().status)
        assertTrue(dao.pending().isEmpty())
    }

    @Test fun concurrentWorkersCanClaimOneRecordOnlyOnce() = runBlocking {
        dao.enqueue(event("contended", status = "PENDING"))
        val claims = (1..24).map { async(Dispatchers.Default) { dao.claim("contended") } }.awaitAll()
        assertEquals(1, claims.sum())
        assertEquals(1, claims.count { it == 1 })
        assertEquals(1, storedAttempts("contended"))
    }

    @Test fun blockedUnknownAndTerminalRecordsCannotBeClaimed() = runBlocking {
        listOf("BLOCKED_AUTH", "OUTCOME_UNKNOWN", "ACCEPTED", "PERMANENT_FAILURE").forEach { status ->
            dao.enqueue(event(status, status = status))
            assertEquals(0, dao.claim(status))
            assertEquals(0, storedAttempts(status))
        }
    }

    @Test fun interruptedSendingBecomesUnknownWithoutDeletingPayloadOrRetrying() = runBlocking {
        val sending = event("interrupted", status = "PENDING", payload = "private permitted payload")
        dao.enqueue(sending); dao.claim(sending.id)
        dao.enqueue(event("blocked", status = "BLOCKED_AUTH"))
        dao.recoverInterrupted()
        val states = dao.watchQueue().first().associate { it.id to it.status }
        assertEquals("OUTCOME_UNKNOWN", states[sending.id])
        assertEquals("BLOCKED_AUTH", states["blocked"])
        assertEquals(sending.payload, dao.payload(sending.id))
        assertEquals(1, storedAttempts(sending.id))
        assertTrue(dao.pending().isEmpty())
        db.openHelper.readableDatabase.query("SELECT lastError FROM queue WHERE id=?", arrayOf(sending.id)).use {
            assertTrue(it.moveToFirst()); assertEquals("PROCESS_INTERRUPTED", it.getString(0))
        }
    }

    @Test fun cancellationUnknownTransitionOnlyAffectsStillSendingRecord() = runBlocking {
        dao.enqueue(event("in-flight", status = "SENDING"))
        dao.enqueue(event("accepted", status = "ACCEPTED", completedAt = 200))
        dao.unknownIfSending("in-flight")
        dao.unknownIfSending("accepted")
        val states = dao.watchQueue().first().associate { it.id to it.status }
        assertEquals("OUTCOME_UNKNOWN", states["in-flight"])
        assertEquals("ACCEPTED", states["accepted"])
    }

    @Test fun knownRetryableFailureCanBeClaimedAgainWithStableIdentityAndPayload() = runBlocking {
        val original = event("stable-retry", status = "PENDING")
        dao.enqueue(original); dao.claim(original.id)
        dao.status(original.id, "RETRYABLE_FAILURE", "RATE_LIMITED")
        val retry = dao.pending().single()
        assertEquals(original.id, retry.id)
        assertEquals(original.payload, retry.payload)
        assertEquals(1, retry.attempts)
        assertEquals("RATE_LIMITED", retry.lastError)
        assertEquals(1, dao.claim(original.id))
        assertEquals(2, storedAttempts(original.id))
    }

    @Test fun retryNotBeforeTimeIsPersistedWithStableOriginalPayload() = runBlocking {
        val original = event("delayed-retry", status = "PENDING")
        dao.enqueue(original); dao.claim(original.id)
        dao.status(original.id, "RETRYABLE_FAILURE", "RATE_LIMITED", nextAttemptAt = 123_456_789)
        val queued = dao.pending().single()
        assertEquals(123_456_789L, queued.nextAttemptAt)
        assertEquals(original.payload, queued.payload)
        assertEquals(1, queued.attempts)
    }

    @Test fun onlyPreviouslyBoundOwnAccountBlockedRowsResumeAfterAuthorization() = runBlocking {
        dao.enqueue(event("same-account").copy(destination = "owner@example.test"))
        dao.enqueue(event("other-account").copy(destination = "other@example.test"))
        dao.enqueue(event("unbound-before-grant"))
        dao.enqueue(event("unknown-same-account", status = "OUTCOME_UNKNOWN").copy(destination = "owner@example.test"))
        dao.unblockOwnAccount("owner@example.test")
        val states = dao.watchQueue().first().associate { it.id to it.status }
        assertEquals("PENDING", states["same-account"])
        assertEquals("BLOCKED_AUTH", states["other-account"])
        assertEquals("BLOCKED_AUTH", states["unbound-before-grant"])
        assertEquals("OUTCOME_UNKNOWN", states["unknown-same-account"])
    }

    @Test fun acceptanceStoresRemoteIdAndCompletionTimeWithoutClaimingAiProcessing() = runBlocking {
        dao.enqueue(event("accepted", status = "PENDING"))
        dao.claim("accepted")
        dao.status("accepted", "ACCEPTED", null, completedAt = 1_234, gmailMessageId = "gmail-api-message-id")
        assertEquals("ACCEPTED", dao.watchQueue().first().single().status)
        assertEquals(0, dao.activeCount())
        assertTrue(dao.pending().isEmpty())
        db.openHelper.readableDatabase.query("SELECT completedAt,gmailMessageId,lastError FROM queue WHERE id='accepted'").use {
            assertTrue(it.moveToFirst()); assertEquals(1_234L, it.getLong(0))
            assertEquals("gmail-api-message-id", it.getString(1)); assertTrue(it.isNull(2))
        }
    }

    @Test fun retentionDeletesOnlyOldCompletedAcceptanceOrPermanentFailure() = runBlocking {
        dao.enqueue(event("accepted-old", status = "ACCEPTED", completedAt = 999))
        dao.enqueue(event("permanent-old", status = "PERMANENT_FAILURE", completedAt = 999))
        dao.enqueue(event("accepted-boundary", status = "ACCEPTED", completedAt = 1_000))
        dao.enqueue(event("accepted-new", status = "ACCEPTED", completedAt = 1_001))
        dao.enqueue(event("terminal-without-completion", status = "PERMANENT_FAILURE"))
        listOf("PENDING", "RETRYABLE_FAILURE", "BLOCKED_AUTH", "SENDING", "OUTCOME_UNKNOWN").forEach {
            // Even corrupt/legacy nonterminal records with a completion timestamp remain untouched.
            dao.enqueue(event(it, observedAt = 0, status = it, completedAt = 0))
        }
        dao.purgeCompleted(1_000)
        assertNull(dao.payload("accepted-old"))
        assertNull(dao.payload("permanent-old"))
        val retained = dao.watchQueue().first().map { it.id }.toSet()
        assertEquals(setOf("accepted-boundary", "accepted-new", "terminal-without-completion", "PENDING",
            "RETRYABLE_FAILURE", "BLOCKED_AUTH", "SENDING", "OUTCOME_UNKNOWN"), retained)
    }

    @Test fun activeRulesIncludeOnlyEnabledRulesAndReplacingDoesNotDuplicate() = runBlocking {
        val rule = StoredRule("rule-1", "Allowed source", "SMS", "ALLOWLISTED-SENDER", "[]")
        dao.saveRule(rule)
        dao.saveRule(StoredRule("disabled", "Disabled source", "SMS", "OTHER", "[]", enabled = false))
        assertEquals(listOf(rule), dao.activeRules())
        dao.saveRule(rule.copy(name = "Renamed"))
        assertEquals(2, dao.watchRules().first().size)
        assertEquals("Renamed", dao.activeRules().single().name)
        dao.saveRule(rule.copy(enabled = false))
        assertTrue(dao.activeRules().isEmpty())
    }
}
