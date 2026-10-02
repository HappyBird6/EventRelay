package dev.eventrelay.core

enum class QueueStatus { PENDING, SENDING, RETRY_WAIT, OUTCOME_UNKNOWN, SENT, FAILED }
enum class DisallowedQueuedEventHandling { HOLD, REMOVE_PAYLOAD }
enum class FailureCode {
    NETWORK_UNAVAILABLE, AUTH_REQUIRED, RATE_LIMITED, SERVER_REJECTED, INVALID_DESTINATION,
    PERMISSION_DENIED, SUBMISSION_UNKNOWN, RETRY_LIMIT, EXPIRED, POLICY_REVOKED,
}

data class QueuePolicy(
    val maxAttempts: Int = 5,
    val firstRetryDelayMillis: Long = 30_000,
    val maxRetryDelayMillis: Long = 6 * 60 * 60 * 1_000L,
    /** Pending and outcome-unknown expiry is opt-in; null leaves this decision to the user. */
    val payloadRetentionMillis: Long? = null,
    /** Optional creation-relative dedup window; null has no pending identity expiry. */
    val tombstoneRetentionMillis: Long? = null,
    val completedRetentionMillis: Long = 7 * 24 * 60 * 60 * 1_000L,
    val disallowedQueuedEventHandling: DisallowedQueuedEventHandling = DisallowedQueuedEventHandling.HOLD,
    val maxEntries: Int = 1_000,
    val maxPayloadBytes: Long = 8 * 1024 * 1024L,
) {
    init {
        require(maxAttempts > 0 && firstRetryDelayMillis > 0)
        require(maxRetryDelayMillis >= firstRetryDelayMillis)
        require(payloadRetentionMillis == null || payloadRetentionMillis > 0)
        require(tombstoneRetentionMillis == null || tombstoneRetentionMillis > 0)
        require(payloadRetentionMillis == null || tombstoneRetentionMillis == null || tombstoneRetentionMillis >= payloadRetentionMillis)
        require(completedRetentionMillis > 0)
        require(maxEntries > 0 && maxPayloadBytes > 0)
    }

    fun retryDelayMillis(attempts: Int): Long {
        var result = firstRetryDelayMillis
        repeat((attempts - 1).coerceIn(0, 63)) {
            result = if (result > maxRetryDelayMillis / 2) maxRetryDelayMillis else (result * 2).coerceAtMost(maxRetryDelayMillis)
        }
        return result
    }
}

/** Delivered/expired records retain only identity metadata for the finite dedup window. */
data class QueueEntry(
    val eventId: String,
    val messageId: String,
    val destination: String,
    val event: RelayEvent?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val nextAttemptAtMillis: Long,
    val status: QueueStatus = QueueStatus.PENDING,
    val attempts: Int = 0,
    val version: Long = 0,
    val remoteMessageId: String? = null,
    val failureCode: FailureCode? = null,
)

/**
 * Implement with durable transactional storage in production. Insert and replace must be atomic.
 * The byte/entry limits and insert must be one transaction, including across separate workers.
 */
interface QueueStore {
    fun get(eventId: String): QueueEntry?
    fun all(): List<QueueEntry>
    fun insertIfAbsent(entry: QueueEntry, maxEntries: Int, maxPayloadBytes: Long): StoreInsert
    fun compareAndSet(expectedVersion: Long, entry: QueueEntry): Boolean
    fun removeIfVersion(eventId: String, expectedVersion: Long): Boolean
}

enum class StoreInsert { INSERTED, DUPLICATE, CAPACITY_REACHED }

/** Thread-safe reference implementation and gateway-test fake; intentionally not durable. */
class InMemoryQueueStore : QueueStore {
    private val entries = linkedMapOf<String, QueueEntry>()

    @Synchronized override fun get(eventId: String): QueueEntry? = entries[eventId]?.snapshot()
    @Synchronized override fun all(): List<QueueEntry> = entries.values.map { it.snapshot() }
    @Synchronized override fun insertIfAbsent(entry: QueueEntry, maxEntries: Int, maxPayloadBytes: Long): StoreInsert {
        if (entries.containsKey(entry.eventId)) return StoreInsert.DUPLICATE
        if (entries.size >= maxEntries) return StoreInsert.CAPACITY_REACHED
        var total = entry.payloadBytes()
        if (total > maxPayloadBytes) return StoreInsert.CAPACITY_REACHED
        for (existing in entries.values) {
            val bytes = existing.payloadBytes()
            if (bytes > maxPayloadBytes - total) return StoreInsert.CAPACITY_REACHED
            total += bytes
        }
        entries[entry.eventId] = entry.snapshot()
        return StoreInsert.INSERTED
    }
    @Synchronized override fun compareAndSet(expectedVersion: Long, entry: QueueEntry): Boolean {
        val old = entries[entry.eventId] ?: return false
        if (old.version != expectedVersion || entry.version != expectedVersion + 1) return false
        entries[entry.eventId] = entry.snapshot()
        return true
    }
    @Synchronized override fun removeIfVersion(eventId: String, expectedVersion: Long): Boolean {
        if (entries[eventId]?.version != expectedVersion) return false
        entries.remove(eventId)
        return true
    }

    private fun QueueEntry.snapshot(): QueueEntry = copy(event = event?.snapshot())
    private fun QueueEntry.payloadBytes(): Long = event?.toJson()?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0
}

sealed class EnqueueResult {
    data class Enqueued(val eventId: String) : EnqueueResult()
    data class Duplicate(val eventId: String) : EnqueueResult()
    data class Rejected(val reason: RejectionReason) : EnqueueResult()
    object CapacityReached : EnqueueResult()
}

data class DeliveryEnvelope(
    val eventId: String,
    val messageId: String,
    val destination: String,
    val event: RelayEvent,
) {
    /** Full structured payload, without extracting or truncating any captured content. */
    fun plainTextBody(): String = "EventRelay event: $eventId\n\n${event.toJson()}\n"
}

sealed class DeliveryResult {
    data class Accepted(val remoteMessageId: String) : DeliveryResult() {
        init { require(remoteMessageId.isNotBlank()) }
    }
    /** Use ONLY when the adapter knows Gmail did not accept the message. */
    data class RetryableFailure(val code: FailureCode, val retryAfterMillis: Long? = null) : DeliveryResult() {
        init { require(retryAfterMillis == null || retryAfterMillis >= 0) }
    }
    data class PermanentFailure(val code: FailureCode) : DeliveryResult()
    /** Timeout, connection loss after submission, or unconfirmed server acceptance. */
    data class OutcomeUnknown(val code: FailureCode = FailureCode.SUBMISSION_UNKNOWN) : DeliveryResult()
}

fun interface DeliveryGateway { fun send(envelope: DeliveryEnvelope): DeliveryResult }

sealed class DispatchResult {
    data class Updated(val entry: QueueEntry) : DispatchResult()
    object NotReady : DispatchResult()
    object Contended : DispatchResult()
}

/**
 * Reference/experimental coordinator, not the Android app's adopted persistence/status policy.
 * Queue account switching and rule-change handling require an explicit application decision.
 * Defaults hold disallowed queued entries and never expire pending/unknown payloads. Known terminal
 * records are removed after seven days; optional pending expiry must be deliberately configured.
 * Local source/revision dedup is bounded by retained identity metadata. This is not end-to-end exactly once:
 * Gmail send has no idempotency contract, so unknown outcomes are held for review, not auto-retried.
 */
class QueueCoordinator(
    private val store: QueueStore,
    private val queuePolicy: QueuePolicy = QueuePolicy(),
) {
    fun enqueue(event: RelayEvent, relayPolicy: RelayPolicy, destination: String, nowMillis: Long): EnqueueResult {
        require(destination.isNotBlank() && !destination.contains('\r') && !destination.contains('\n'))
        require(nowMillis >= 0)
        val decision = PolicyEngine.evaluate(event, relayPolicy)
        if (decision is PolicyDecision.Rejected) return EnqueueResult.Rejected(decision.reason)
        decision as PolicyDecision.Approved
        sweep(nowMillis)
        val entry = QueueEntry(
            eventId = decision.eventId,
            messageId = EventIdentity.messageId(decision.eventId, destination),
            destination = destination,
            event = decision.event,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
            nextAttemptAtMillis = nowMillis,
        )
        return when (store.insertIfAbsent(entry, queuePolicy.maxEntries, queuePolicy.maxPayloadBytes)) {
            StoreInsert.INSERTED -> EnqueueResult.Enqueued(entry.eventId)
            StoreInsert.DUPLICATE -> EnqueueResult.Duplicate(entry.eventId)
            StoreInsert.CAPACITY_REACHED -> EnqueueResult.CapacityReached
        }
    }

    fun due(nowMillis: Long): List<QueueEntry> {
        sweep(nowMillis)
        return store.all().filter {
            it.status in setOf(QueueStatus.PENDING, QueueStatus.RETRY_WAIT) && it.nextAttemptAtMillis <= nowMillis
        }.sortedWith(compareBy<QueueEntry> { it.nextAttemptAtMillis }.thenBy { it.createdAtMillis }.thenBy { it.eventId })
    }

    /** Recheck the CURRENT allowlist/filter/privacy policy immediately before each submission. */
    fun dispatch(eventId: String, relayPolicy: RelayPolicy, gateway: DeliveryGateway, nowMillis: Long): DispatchResult {
        require(nowMillis >= 0)
        sweep(nowMillis)
        val old = store.get(eventId) ?: return DispatchResult.NotReady
        if (old.status !in setOf(QueueStatus.PENDING, QueueStatus.RETRY_WAIT) || old.nextAttemptAtMillis > nowMillis)
            return DispatchResult.NotReady
        val event = old.event ?: return DispatchResult.NotReady
        val evaluation = PolicyEngine.evaluate(event, relayPolicy)
        if (evaluation is PolicyDecision.Rejected) {
            // Hold by default. Discarding historical approved payloads is an explicit opt-in policy.
            if (evaluation.reason == RejectionReason.PAUSED ||
                queuePolicy.disallowedQueuedEventHandling == DisallowedQueuedEventHandling.HOLD) return DispatchResult.NotReady
            val revoked = old.copy(event = null, status = QueueStatus.FAILED,
                failureCode = FailureCode.POLICY_REVOKED, updatedAtMillis = nowMillis, version = old.version + 1)
            return if (store.compareAndSet(old.version, revoked)) DispatchResult.Updated(revoked) else DispatchResult.Contended
        }
        val sending = old.copy(status = QueueStatus.SENDING, attempts = old.attempts + 1,
            updatedAtMillis = nowMillis, version = old.version + 1, failureCode = null)
        if (!store.compareAndSet(old.version, sending)) return DispatchResult.Contended
        val envelope = DeliveryEnvelope(sending.eventId, sending.messageId, sending.destination, event)
        val result = try { gateway.send(envelope) } catch (_: Exception) {
            // Unknown adapter exceptions cannot establish that a network submission was rejected.
            DeliveryResult.OutcomeUnknown()
        }
        val finished = when (result) {
            is DeliveryResult.Accepted -> sending.copy(event = null, status = QueueStatus.SENT,
                remoteMessageId = result.remoteMessageId, failureCode = null)
            is DeliveryResult.PermanentFailure -> sending.copy(status = QueueStatus.FAILED, failureCode = result.code)
            is DeliveryResult.OutcomeUnknown -> sending.copy(status = QueueStatus.OUTCOME_UNKNOWN, failureCode = result.code)
            is DeliveryResult.RetryableFailure -> {
                if (sending.attempts >= queuePolicy.maxAttempts) sending.copy(status = QueueStatus.FAILED,
                    failureCode = FailureCode.RETRY_LIMIT)
                else sending.copy(status = QueueStatus.RETRY_WAIT, failureCode = result.code,
                    nextAttemptAtMillis = saturatingAdd(nowMillis,
                        maxOf(queuePolicy.retryDelayMillis(sending.attempts), result.retryAfterMillis ?: 0)))
            }
        }.copy(updatedAtMillis = nowMillis, version = sending.version + 1)
        return if (store.compareAndSet(sending.version, finished)) DispatchResult.Updated(finished) else DispatchResult.Contended
    }

    /** At startup, persisted in-flight entries are ambiguous and must not be silently resubmitted. */
    fun recoverInterrupted(nowMillis: Long): Int {
        var recovered = 0
        store.all().filter { it.status == QueueStatus.SENDING }.forEach { old ->
            val updated = old.copy(status = QueueStatus.OUTCOME_UNKNOWN, failureCode = FailureCode.SUBMISSION_UNKNOWN,
                updatedAtMillis = nowMillis, version = old.version + 1)
            if (store.compareAndSet(old.version, updated)) recovered++
        }
        return recovered
    }

    /** Call only after the user explicitly accepts the possible duplicate email. */
    fun retryUnknown(eventId: String, duplicateRiskAccepted: Boolean, nowMillis: Long): Boolean {
        if (!duplicateRiskAccepted) return false
        sweep(nowMillis)
        val old = store.get(eventId) ?: return false
        if (old.status != QueueStatus.OUTCOME_UNKNOWN || old.event == null || old.attempts >= queuePolicy.maxAttempts) return false
        return store.compareAndSet(old.version, old.copy(status = QueueStatus.RETRY_WAIT, nextAttemptAtMillis = nowMillis,
            failureCode = null, updatedAtMillis = nowMillis, version = old.version + 1))
    }

    /** Mark delivered only after a verified Gmail reconciliation or explicit user confirmation. */
    fun resolveUnknownAsSent(eventId: String, remoteMessageId: String, nowMillis: Long): Boolean {
        require(remoteMessageId.isNotBlank())
        val old = store.get(eventId) ?: return false
        if (old.status != QueueStatus.OUTCOME_UNKNOWN) return false
        return store.compareAndSet(old.version, old.copy(event = null, status = QueueStatus.SENT,
            remoteMessageId = remoteMessageId, failureCode = null, updatedAtMillis = nowMillis, version = old.version + 1))
    }

    /** Terminal-only cleanup by default. Pending expiry applies only when explicitly configured. */
    fun sweep(nowMillis: Long) {
        require(nowMillis >= 0)
        store.all().forEach { old ->
            // Never mutate an actively leased submission; its completion or startup recovery owns it.
            if (old.status == QueueStatus.SENDING) return@forEach
            val terminal = old.status in setOf(QueueStatus.SENT, QueueStatus.FAILED)
            if ((terminal && elapsed(nowMillis, old.updatedAtMillis) >= queuePolicy.completedRetentionMillis) ||
                (queuePolicy.tombstoneRetentionMillis?.let { elapsed(nowMillis, old.createdAtMillis) >= it } == true)) {
                store.removeIfVersion(old.eventId, old.version)
            } else if (old.event != null &&
                (queuePolicy.payloadRetentionMillis?.let { elapsed(nowMillis, old.createdAtMillis) >= it } == true)) {
                store.compareAndSet(old.version, old.copy(event = null, status = QueueStatus.FAILED,
                    failureCode = FailureCode.EXPIRED, updatedAtMillis = nowMillis, version = old.version + 1))
            }
        }
    }

    private fun elapsed(now: Long, then: Long): Long = if (now >= then) now - then else 0
    private fun saturatingAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
}
