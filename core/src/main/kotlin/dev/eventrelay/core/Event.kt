package dev.eventrelay.core

import java.security.MessageDigest
import java.time.Instant

enum class SourceKind { SMS, NOTIFICATION }

/** Lossless JSON-compatible values. Numbers retain their original decimal representation. */
sealed class EventValue {
    data class Text(val value: String) : EventValue()
    data class Number(val value: String) : EventValue() {
        init { require(JSON_NUMBER.matches(value)) { "Not a JSON number" } }
        constructor(value: Long) : this(value.toString())
        constructor(value: Int) : this(value.toString())
        companion object { private val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?") }
    }
    data class Bool(val value: Boolean) : EventValue()
    data class Object(val fields: Map<String, EventValue>) : EventValue()
    data class Array(val values: List<EventValue>) : EventValue()
    object Null : EventValue()

    fun toJson(): String = when (this) {
        is Text -> jsonString(value)
        is Number -> value
        is Bool -> value.toString()
        is Object -> fields.toSortedMap().entries.joinToString(",", "{", "}") { (key, value) ->
            "${jsonString(key)}:${value.toJson()}"
        }
        is Array -> values.joinToString(",", "[", "]") { it.toJson() }
        Null -> "null"
    }

    internal fun snapshot(): EventValue = when (this) {
        is Object -> Object(fields.mapValues { it.value.snapshot() }.toMap())
        is Array -> Array(values.map { it.snapshot() })
        else -> this
    }

    internal fun textValues(): List<String> = when (this) {
        is Text -> listOf(value)
        is Number -> listOf(value)
        is Bool -> listOf(value.toString())
        is Object -> fields.flatMap { (key, value) -> listOf(key) + value.textValues() }
        is Array -> values.flatMap { it.textValues() }
        Null -> emptyList()
    }
}

/**
 * No parsing, amount extraction, truncation, or transaction correlation is performed.
 * [sourceIdentity] is an exact SMS sender or notification package name.
 * [sourceRecordId] must be a stable provider/PDU identity for SMS or notification key.
 * [observedAtMillis] is local receipt time and is deliberately excluded from dedup identity.
 */
data class RelayEvent(
    val kind: SourceKind,
    val sourceIdentity: String,
    val sourceRecordId: String,
    val occurredAtMillis: Long,
    val observedAtMillis: Long,
    val title: String? = null,
    val body: String,
    val originalFields: Map<String, EventValue> = emptyMap(),
) {
    init {
        require(sourceIdentity.isNotBlank()) { "A source identity is required" }
        require(sourceRecordId.isNotBlank()) { "A stable source record identity is required" }
        require(occurredAtMillis >= 0 && observedAtMillis >= 0) { "Timestamps must be nonnegative" }
    }

    /** Stable for an SMS occurrence; distinct for a changed notification revision. */
    val eventId: String get() = EventIdentity.eventId(this)

    fun snapshot(): RelayEvent = copy(originalFields = originalFields.mapValues { it.value.snapshot() }.toMap())

    /** This includes every captured permitted field, including multiline and Unicode text. */
    fun toJson(): String = EventValue.Object(linkedMapOf(
        "schema" to EventValue.Text("eventrelay.event.v1"),
        "eventId" to EventValue.Text(eventId),
        "kind" to EventValue.Text(kind.name),
        "sourceIdentity" to EventValue.Text(sourceIdentity),
        "sourceRecordId" to EventValue.Text(sourceRecordId),
        "occurredAtMillis" to EventValue.Number(occurredAtMillis),
        "occurredAt" to EventValue.Text(Instant.ofEpochMilli(occurredAtMillis).toString()),
        "observedAtMillis" to EventValue.Number(observedAtMillis),
        "observedAt" to EventValue.Text(Instant.ofEpochMilli(observedAtMillis).toString()),
        "title" to (title?.let { EventValue.Text(it) } ?: EventValue.Null),
        "body" to EventValue.Text(body),
        "originalFields" to EventValue.Object(originalFields),
    )).toJson()
}

object EventIdentity {
    fun revisionFingerprint(event: RelayEvent): String = sha256(EventValue.Object(linkedMapOf(
        "occurredAtMillis" to EventValue.Number(event.occurredAtMillis),
        "title" to (event.title?.let { EventValue.Text(it) } ?: EventValue.Null),
        "body" to EventValue.Text(event.body),
        "originalFields" to EventValue.Object(event.originalFields),
    )).toJson())

    fun eventId(event: RelayEvent): String {
        val fields = linkedMapOf<String, EventValue>(
            "version" to EventValue.Number(1),
            "kind" to EventValue.Text(event.kind.name),
            "sourceIdentity" to EventValue.Text(event.sourceIdentity),
            "sourceRecordId" to EventValue.Text(event.sourceRecordId),
        )
        if (event.kind == SourceKind.NOTIFICATION) fields["revision"] = EventValue.Text(revisionFingerprint(event))
        return "er1_${sha256(EventValue.Object(fields).toJson())}"
    }

    /** Deterministic Message-ID aids reconciliation; Gmail does not guarantee idempotent send. */
    fun messageId(eventId: String, destination: String): String =
        "<${sha256("eventrelay-v1\n$eventId\n$destination")}@eventrelay.invalid>"
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
    }
    append('"')
}
