package dev.eventrelay.core

/** Dependency-free executable tests. The Gradle coreCheck task invokes main. */
private object Tests {
    var passed = 0
    fun test(name: String, run: () -> Unit) {
        try { run(); passed++; println("PASS $name") }
        catch (failure: Throwable) { throw AssertionError("FAIL $name", failure) }
    }
}

private fun sms(
    id: String = "provider:42", body: String = "Payment approved ₩42,000\nComplete original text",
    source: String = "SHOP", original: Map<String, EventValue> = emptyMap(),
) = RelayEvent(SourceKind.SMS, source, id, 1_700_000_000_000, 1_700_000_000_001,
    null, body, original)

private fun notification(
    id: String = "notification-key", body: String = "Payment approved ₩42,000", source: String = "dev.shop",
    original: Map<String, EventValue> = emptyMap(),
) = RelayEvent(SourceKind.NOTIFICATION, source, id, 1_700_000_000_000, 1_700_000_000_001,
    "Shop alert", body, original)

private fun policy(event: RelayEvent = sms()) = RelayPolicy(
    setOf(SourceSelection(event.kind, event.sourceIdentity)),
    listOf(MatchCondition(MatchTarget.BODY, MatchOperator.CONTAINS, "Payment")), "dev.eventrelay",
)

private class FakeGateway(vararg results: DeliveryResult) : DeliveryGateway {
    val calls = mutableListOf<DeliveryEnvelope>()
    private val results = results.toMutableList()
    override fun send(envelope: DeliveryEnvelope): DeliveryResult {
        calls += envelope
        check(results.isNotEmpty()) { "Unexpected send" }
        return results.removeAt(0)
    }
}

fun main() {
    Tests.test("SMS redelivery uses source record identity and excludes observer time") {
        val event = sms()
        check(event.eventId == event.copy(observedAtMillis = event.observedAtMillis + 99).eventId)
        check(event.eventId == event.copy(body = "Same record reread with a different representation").eventId)
    }
    Tests.test("distinct same-amount SMS records are not transaction-correlated") {
        check(sms("provider:1").eventId != sms("provider:2").eventId)
        check(sms(source = "SHOP").eventId != sms(source = "ANOTHER-SHOP").eventId)
    }
    Tests.test("notification same revision dedups and new content revision differs") {
        val event = notification()
        check(event.eventId == event.copy(observedAtMillis = event.observedAtMillis + 500).eventId)
        check(event.eventId != event.copy(body = event.body + " updated").eventId)
        check(event.eventId != event.copy(title = "Updated title").eventId)
        check(event.eventId != event.copy(occurredAtMillis = event.occurredAtMillis + 1).eventId)
        check(event.eventId != event.copy(originalFields = mapOf("revision" to EventValue.Number(2))).eventId)
    }
    Tests.test("canonical field ordering yields same revision") {
        val a = linkedMapOf("z" to EventValue.Text("Z"), "a" to EventValue.Text("A"))
        val b = linkedMapOf("a" to EventValue.Text("A"), "z" to EventValue.Text("Z"))
        check(notification(original = a).eventId == notification(original = b).eventId)
    }
    Tests.test("source kind separates same-content SMS and notification") {
        val event = sms()
        check(event.eventId != event.copy(kind = SourceKind.NOTIFICATION).eventId)
    }
    Tests.test("full original nested payload and long Unicode text preserved") {
        val body = "완전한 원문 😀\n".repeat(7_000) + "final tail"
        val fields = mapOf("extras" to EventValue.Object(mapOf(
            "messages" to EventValue.Array(listOf(EventValue.Text("line1\nline2"), EventValue.Null)),
            "total" to EventValue.Number("12345678901234567890.001"),
            "bool" to EventValue.Bool(true),
        )))
        val event = sms(body = body, original = fields)
        check(event.snapshot().body == body)
        val json = event.toJson()
        check(json.contains("eventrelay.event.v1"))
        check(json.contains("2023-11-14T22:13:20Z"))
        check(json.contains("2023-11-14T22:13:20.001Z"))
        check(json.contains("12345678901234567890.001"))
        check(json.contains("line1\\nline2"))
        check(json.contains("final tail"))
        check(json.contains("😀"))
    }
    Tests.test("JSON escaping is deterministic and valid for controls") {
        check(EventValue.Text("\"\\\n\t\u0001").toJson() == "\"\\\"\\\\\\n\\t\\u0001\"")
    }
    Tests.test("mutable originals cannot alter a policy-approved snapshot") {
        val nested = mutableMapOf<String, EventValue>("value" to EventValue.Text("initial"))
        val outer = mutableMapOf<String, EventValue>("nested" to EventValue.Object(nested))
        val approved = PolicyEngine.evaluate(sms(original = outer), policy()) as PolicyDecision.Approved
        nested["value"] = EventValue.Text("mutated")
        outer.clear()
        check(approved.event.originalFields["nested"]?.toJson() == "{\"value\":\"initial\"}")
    }
    Tests.test("equals contains not_contains are ANDed") {
        val event = sms()
        val configured = policy().copy(conditions = listOf(
            MatchCondition(MatchTarget.SOURCE_IDENTITY, MatchOperator.EQUALS, "shop"),
            MatchCondition(MatchTarget.BODY, MatchOperator.CONTAINS, "APPROVED"),
            MatchCondition(MatchTarget.BODY, MatchOperator.NOT_CONTAINS, "cancelled"),
        ))
        check(PolicyEngine.evaluate(event, configured) is PolicyDecision.Approved)
        check(PolicyEngine.evaluate(event.copy(body = event.body + " cancelled"), configured) ==
            PolicyDecision.Rejected(RejectionReason.RULES_NOT_MATCHED))
        check(PolicyEngine.evaluate(event.copy(body = "Payment pending"), configured) is PolicyDecision.Rejected)
    }
    Tests.test("case-sensitive conditions and missing fields fail safely") {
        check(!MatchCondition(MatchTarget.BODY, MatchOperator.CONTAINS, "PAYMENT", true).matches(sms()))
        check(!MatchCondition(MatchTarget.TITLE, MatchOperator.NOT_CONTAINS, "blocked").matches(sms()))
        check(!MatchCondition(MatchTarget.ORIGINAL_FIELD, MatchOperator.NOT_CONTAINS, "blocked", originalField = "missing").matches(sms()))
    }
    Tests.test("original-field matching preserves text and JSON structure") {
        val event = sms(original = mapOf("thread" to EventValue.Text("Exact Thread")))
        check(MatchCondition(MatchTarget.ORIGINAL_FIELD, MatchOperator.EQUALS, "Exact Thread", originalField = "thread").matches(event))
    }
    Tests.test("source allowlist is exact and empty rule sets fail closed") {
        check(PolicyEngine.evaluate(sms(source = "shop"), policy()) == PolicyDecision.Rejected(RejectionReason.SOURCE_NOT_ALLOWED))
        check(PolicyEngine.evaluate(sms(), policy().copy(conditions = emptyList())) == PolicyDecision.Rejected(RejectionReason.NO_RULES))
        check(PolicyEngine.evaluate(sms(), policy().copy(allowedSources = emptySet())) is PolicyDecision.Rejected)
    }
    Tests.test("own-app and Gmail notifications excluded even explicitly allowlisted") {
        listOf("dev.eventrelay", "com.google.android.gm", "com.google.android.gm.lite").forEach { pkg ->
            val event = notification(source = pkg)
            check(PolicyEngine.evaluate(event, policy(event)) == PolicyDecision.Rejected(RejectionReason.LOOP_SOURCE))
        }
    }
    Tests.test("English OTP and authentication secrets excluded") {
        listOf("Your OTP is 123456", "Verification code: 123456", "One-time password 555555",
            "Your PIN is 1234", "Payment approved; CVV: 123", "Password reset link",
            "Payment approved; use code 123456 to confirm your identity").forEach { text ->
            check(PrivacyGuard.isSensitive(sms(body = text))) { text }
        }
    }
    Tests.test("Korean authentication markers excluded with suffixes or spacing") {
        listOf("인증번호는 123456입니다", "인증코드: 1234", "인증 번호 123456", "비밀번호를 변경하세요",
            "보안코드는 987654", "일회용 번호", "OTP코드는 333333", "주민등록번호: 123456-1234567").forEach { text ->
            check(PrivacyGuard.isSensitive(sms(body = text))) { text }
        }
    }
    Tests.test("OTP context is checked across title body and structured fields") {
        check(PrivacyGuard.isSensitive(notification(body = "123456").copy(title = "Code")))
        check(PrivacyGuard.isSensitive(sms(original = mapOf("code" to EventValue.Number(123456)))))
        check(PrivacyGuard.isSensitive(sms(original = mapOf("access_token" to EventValue.Text("hidden-secret")))))
    }
    Tests.test("fullwidth and invisible secret labels normalize only for detection") {
        val event = sms(body = "ＯＴＰ １２３４５６")
        check(PrivacyGuard.isSensitive(event))
        check(event.body == "ＯＴＰ １２３４５６")
        check(PrivacyGuard.isSensitive(sms(body = "O\u200bTP 123456")))
    }
    Tests.test("sensitive originals and custom exclusions examined recursively") {
        val event = sms(original = mapOf("nested" to EventValue.Array(listOf(EventValue.Text("Your recovery code is SECRET")))))
        check(PrivacyGuard.isSensitive(event))
        check(PolicyEngine.evaluate(sms(), policy().copy(additionalSensitiveTerms = setOf("original text"))) ==
            PolicyDecision.Rejected(RejectionReason.SENSITIVE_CONTENT))
    }
    Tests.test("card secrets excluded and amounts alone remain permitted") {
        check(PrivacyGuard.isSensitive(sms(body = "Payment approved 4111 1111 1111 1111")))
        check(PrivacyGuard.isSensitive(sms(body = "Payment approved; 123-45-6789")))
        check(!PrivacyGuard.isSensitive(sms(body = "Payment approved ₩123456; card ending 1234")))
    }
    Tests.test("unmatched and sensitive events never enter queue storage") {
        val store = InMemoryQueueStore()
        val queue = QueueCoordinator(store)
        check(queue.enqueue(sms(body = "Something else"), policy(), "relay@example.com", 0) is EnqueueResult.Rejected)
        check(queue.enqueue(sms(body = "Payment OTP 123456"), policy(), "relay@example.com", 0) is EnqueueResult.Rejected)
        check(store.all().isEmpty())
    }
    Tests.test("source dedup survives accepted delivery without retaining full payload") {
        val store = InMemoryQueueStore()
        val queue = QueueCoordinator(store)
        val event = sms()
        check(queue.enqueue(event, policy(), "relay@example.com", 1) is EnqueueResult.Enqueued)
        val gateway = FakeGateway(DeliveryResult.Accepted("gmail-1"))
        val result = queue.dispatch(event.eventId, policy(), gateway, 2) as DispatchResult.Updated
        check(result.entry.status == QueueStatus.SENT && result.entry.event == null)
        check(result.entry.remoteMessageId == "gmail-1")
        check(queue.enqueue(event.copy(observedAtMillis = event.observedAtMillis + 1), policy(), "relay@example.com", 3) is EnqueueResult.Duplicate)
        check(gateway.calls.single().event.body == event.body)
    }
    Tests.test("known rejection retries with bounded exponential backoff and stable message ID") {
        val store = InMemoryQueueStore()
        val queue = QueueCoordinator(store, QueuePolicy(firstRetryDelayMillis = 100, maxRetryDelayMillis = 400))
        val event = sms()
        queue.enqueue(event, policy(), "relay@example.com", 0)
        val gateway = FakeGateway(DeliveryResult.RetryableFailure(FailureCode.RATE_LIMITED),
            DeliveryResult.RetryableFailure(FailureCode.NETWORK_UNAVAILABLE), DeliveryResult.Accepted("gmail-2"))
        queue.dispatch(event.eventId, policy(), gateway, 0)
        check(store.get(event.eventId)?.nextAttemptAtMillis == 100L)
        check(queue.dispatch(event.eventId, policy(), gateway, 99) == DispatchResult.NotReady)
        queue.dispatch(event.eventId, policy(), gateway, 100)
        check(store.get(event.eventId)?.nextAttemptAtMillis == 300L)
        queue.dispatch(event.eventId, policy(), gateway, 300)
        check(gateway.calls.map { it.messageId }.distinct().size == 1)
        check(gateway.calls.map { it.eventId }.distinct().size == 1)
    }
    Tests.test("server retry-after is respected") {
        val store = InMemoryQueueStore(); val queue = QueueCoordinator(store)
        val event = sms(); queue.enqueue(event, policy(), "relay@example.com", 0)
        queue.dispatch(event.eventId, policy(), FakeGateway(DeliveryResult.RetryableFailure(FailureCode.RATE_LIMITED, 90_000)), 0)
        check(store.get(event.eventId)?.nextAttemptAtMillis == 90_000L)
    }
    Tests.test("unknown Gmail outcome pauses automatic resend and requires duplicate-risk acceptance") {
        val store = InMemoryQueueStore(); val queue = QueueCoordinator(store)
        val event = sms(); queue.enqueue(event, policy(), "relay@example.com", 0)
        val gateway = FakeGateway(DeliveryResult.OutcomeUnknown(), DeliveryResult.Accepted("gmail-after-review"))
        queue.dispatch(event.eventId, policy(), gateway, 0)
        check(store.get(event.eventId)?.status == QueueStatus.OUTCOME_UNKNOWN)
        check(queue.due(60_000).isEmpty())
        check(queue.dispatch(event.eventId, policy(), gateway, 60_000) == DispatchResult.NotReady)
        check(!queue.retryUnknown(event.eventId, false, 60_000))
        check(queue.retryUnknown(event.eventId, true, 60_000))
        queue.dispatch(event.eventId, policy(), gateway, 60_000)
        check(gateway.calls.size == 2)
        check(gateway.calls[0].messageId == gateway.calls[1].messageId)
    }
    Tests.test("unexpected adapter exceptions become unknown without exposing exception text") {
        val store = InMemoryQueueStore(); val queue = QueueCoordinator(store)
        val event = sms(); queue.enqueue(event, policy(), "relay@example.com", 0)
        val throwing = DeliveryGateway { error("private message must not enter diagnostics") }
        queue.dispatch(event.eventId, policy(), throwing, 0)
        check(store.get(event.eventId)?.status == QueueStatus.OUTCOME_UNKNOWN)
        check(store.get(event.eventId)?.failureCode == FailureCode.SUBMISSION_UNKNOWN)
    }
    Tests.test("startup recovers persisted sending as unknown instead of auto-retry") {
        val store = InMemoryQueueStore(); val queue = QueueCoordinator(store)
        val event = sms(); queue.enqueue(event, policy(), "relay@example.com", 0)
        val old = store.get(event.eventId)!!
        check(store.compareAndSet(old.version, old.copy(status = QueueStatus.SENDING, attempts = 1, version = 1)))
        check(queue.recoverInterrupted(1) == 1)
        check(queue.due(2).isEmpty())
        check(store.get(event.eventId)?.status == QueueStatus.OUTCOME_UNKNOWN)
        check(queue.resolveUnknownAsSent(event.eventId, "verified-gmail-id", 2))
        check(store.get(event.eventId)?.event == null)
    }
    Tests.test("atomic claim blocks overlapping dispatchers") {
        val store = InMemoryQueueStore(); val queue = QueueCoordinator(store); val other = QueueCoordinator(store)
        val event = sms(); queue.enqueue(event, policy(), "relay@example.com", 0)
        var sends = 0
        val gateway = DeliveryGateway {
            sends++
            check(other.dispatch(event.eventId, policy(), DeliveryGateway { error("must never send") }, 0) == DispatchResult.NotReady)
            DeliveryResult.Accepted("once")
        }
        queue.dispatch(event.eventId, policy(), gateway, 0)
        check(sends == 1)
    }
    Tests.test("attempt ceiling prevents unlimited known-failure retries") {
        val store = InMemoryQueueStore(); val queue = QueueCoordinator(store, QueuePolicy(maxAttempts = 1))
        val event = sms(); queue.enqueue(event, policy(), "relay@example.com", 0)
        queue.dispatch(event.eventId, policy(), FakeGateway(DeliveryResult.RetryableFailure(FailureCode.NETWORK_UNAVAILABLE)), 0)
        check(store.get(event.eventId)?.status == QueueStatus.FAILED)
        check(store.get(event.eventId)?.failureCode == FailureCode.RETRY_LIMIT)
    }
    Tests.test("permanent failures are not scheduled again") {
        val store = InMemoryQueueStore(); val queue = QueueCoordinator(store)
        val event = sms(); queue.enqueue(event, policy(), "relay@example.com", 0)
        queue.dispatch(event.eventId, policy(), FakeGateway(DeliveryResult.PermanentFailure(FailureCode.INVALID_DESTINATION)), 0)
        check(store.get(event.eventId)?.status == QueueStatus.FAILED)
        check(queue.due(1).isEmpty())
    }
    Tests.test("policy pause holds payload; explicitly configured revocation purges before sending") {
        val store = InMemoryQueueStore(); val queue = QueueCoordinator(store,
            QueuePolicy(disallowedQueuedEventHandling = DisallowedQueuedEventHandling.REMOVE_PAYLOAD))
        val event = sms(); queue.enqueue(event, policy(), "relay@example.com", 0)
        val gateway = DeliveryGateway { error("must never send") }
        check(queue.dispatch(event.eventId, policy().copy(paused = true), gateway, 1) == DispatchResult.NotReady)
        check(store.get(event.eventId)?.event != null)
        queue.dispatch(event.eventId, policy().copy(allowedSources = emptySet()), gateway, 2)
        check(store.get(event.eventId)?.event == null)
        check(store.get(event.eventId)?.failureCode == FailureCode.POLICY_REVOKED)
    }
    Tests.test("default pending retention and policy-change decisions remain on hold") {
        val store = InMemoryQueueStore(); val queue = QueueCoordinator(store)
        val event = sms(); queue.enqueue(event, policy(), "relay@example.com", 0)
        queue.sweep(60L * 24 * 60 * 60 * 1_000)
        check(store.get(event.eventId)?.event != null)
        val gateway = DeliveryGateway { error("must never send") }
        check(queue.dispatch(event.eventId, policy().copy(conditions = emptyList()), gateway, 1) == DispatchResult.NotReady)
        check(store.get(event.eventId)?.event != null)
    }
    Tests.test("completed-only cleanup defaults to seven days") {
        val store = InMemoryQueueStore(); val queue = QueueCoordinator(store)
        val event = sms(); queue.enqueue(event, policy(), "relay@example.com", 0)
        queue.dispatch(event.eventId, policy(), FakeGateway(DeliveryResult.Accepted("completed")), 10)
        val sevenDays = 7L * 24 * 60 * 60 * 1_000
        queue.sweep(10 + sevenDays - 1)
        check(store.get(event.eventId) != null)
        queue.sweep(10 + sevenDays)
        check(store.get(event.eventId) == null)
    }
    Tests.test("payload TTL strips originals; finite tombstone TTL bounds dedup") {
        val store = InMemoryQueueStore()
        val queue = QueueCoordinator(store, QueuePolicy(payloadRetentionMillis = 100, tombstoneRetentionMillis = 200))
        val event = sms(); queue.enqueue(event, policy(), "relay@example.com", 0)
        queue.sweep(100)
        check(store.get(event.eventId)?.event == null)
        check(store.get(event.eventId)?.failureCode == FailureCode.EXPIRED)
        check(queue.enqueue(event, policy(), "relay@example.com", 101) is EnqueueResult.Duplicate)
        queue.sweep(200)
        check(store.all().isEmpty())
        check(queue.enqueue(event, policy(), "relay@example.com", 200) is EnqueueResult.Enqueued)
    }
    Tests.test("queue entry capacity rejects without storing another payload") {
        val store = InMemoryQueueStore(); val queue = QueueCoordinator(store, QueuePolicy(maxEntries = 1))
        check(queue.enqueue(sms("1"), policy(), "relay@example.com", 0) is EnqueueResult.Enqueued)
        check(queue.enqueue(sms("2"), policy(), "relay@example.com", 0) == EnqueueResult.CapacityReached)
        check(store.all().size == 1)
    }
    Tests.test("oversized payload is rejected whole rather than truncated") {
        val store = InMemoryQueueStore(); val queue = QueueCoordinator(store, QueuePolicy(maxPayloadBytes = 32))
        check(queue.enqueue(sms(), policy(), "relay@example.com", 0) == EnqueueResult.CapacityReached)
        check(store.all().isEmpty())
    }
    Tests.test("retry delay arithmetic is bounded and does not overflow") {
        val configured = QueuePolicy(firstRetryDelayMillis = 1, maxRetryDelayMillis = Long.MAX_VALUE)
        check(configured.retryDelayMillis(Int.MAX_VALUE) == Long.MAX_VALUE)
        val store = InMemoryQueueStore(); val queue = QueueCoordinator(store, configured)
        val event = sms(); val now = Long.MAX_VALUE - 10
        queue.enqueue(event, policy(), "relay@example.com", now)
        queue.dispatch(event.eventId, policy(), FakeGateway(DeliveryResult.RetryableFailure(FailureCode.RATE_LIMITED, 100)), now)
        check(store.get(event.eventId)?.nextAttemptAtMillis == Long.MAX_VALUE)
    }
    Tests.test("different destination snapshot changes Message-ID but not source dedup ID") {
        val event = sms()
        check(EventIdentity.messageId(event.eventId, "a@example.com") != EventIdentity.messageId(event.eventId, "b@example.com"))
        check(EventIdentity.messageId(event.eventId, "a@example.com") == EventIdentity.messageId(event.eventId, "a@example.com"))
    }
    println("${Tests.passed} core tests passed")
}
