package dev.eventrelay.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal const val EXTERNAL_MAIL_SCHEMA = "eventrelay.message.v2"

/** A projection only: the complete eventrelay.event.v1 original stays unchanged in Room. */
internal fun externalMailPayload(event: JSONObject): JSONObject {
    require(event.getString("schema") == "eventrelay.event.v1")
    val kind = event.getString("kind")
    require(kind == "SMS" || kind == "NOTIFICATION")
    val occurred = event.getLong("occurredAtMillis"); require(occurred >= 0)
    val originals = event.optJSONObject("originalFields") ?: JSONObject()
    fun text(json: JSONObject, key: String): String? = (json.opt(key) as? String)?.takeIf { it.isNotEmpty() }
    val result = JSONObject().put("schema", EXTERNAL_MAIL_SCHEMA)
        .put("eventId", event.getString("eventId"))
        .put("sourceType", kind).put("occurredAt", Instant.ofEpochMilli(occurred).toString())
        .put("appName", if (kind == "SMS") "SMS" else text(originals, "appName") ?: event.getString("sourceIdentity"))
    text(event, "title")?.let { result.put("title", it) }
    if (kind == "SMS") {
        result.put("sender", event.getString("sourceIdentity"))
        result.put("body", event.getString("body"))
        // Normally identical; retain any unexpected distinct captured representation.
        text(originals, "body")?.takeIf { it != event.getString("body") }?.let {
            result.put("additionalText", JSONArray().put(JSONObject().put("label", "originalBody").put("text", it)))
        }
        return result
    }

    val messages = JSONArray()
    val messageTexts = mutableListOf<String>()
    val currentTexts = mutableListOf<String>()
    for ((field, historic) in listOf("messages" to false, "historicMessages" to true)) {
        val captured = originals.optJSONArray(field) ?: continue
        for (index in 0 until captured.length()) {
            val original = captured.optJSONObject(index) ?: continue
            val rawText = original.opt("text") as? String ?: ""
            val message = JSONObject().put("text", rawText)
            text(original, "sender")?.let { message.put("sender", it) }
            if (original.has("timestamp") && !original.isNull("timestamp")) {
                val timestamp = original.getLong("timestamp"); require(timestamp >= 0)
                message.put("occurredAt", Instant.ofEpochMilli(timestamp).toString())
            }
            if (historic) message.put("historical", true) else currentTexts += rawText
            messages.put(message); messageTexts += rawText
        }
    }
    if (messages.length() > 0) result.put("messages", messages)
    // Exact comparison only. No trimming, normalization, substring matching, or truncation.
    // A repeated message is still a separate occurrence and is never removed.
    val represented = messageTexts.toMutableSet()
    if (currentTexts.isNotEmpty()) represented += currentTexts.joinToString("\n")
    if (messageTexts.isNotEmpty()) represented += messageTexts.joinToString("\n")
    val representations = mutableListOf<Pair<String, String>>()
    text(originals, "bigText")?.let { representations += "expandedText" to it }
    text(originals, "text")?.let { representations += "notificationText" to it }
    originals.optJSONArray("textLines")?.let { lines ->
        if (lines.length() > 0) representations += "lines" to (0 until lines.length()).joinToString("\n") { lines.getString(it) }
    }
    if (representations.isEmpty() && messages.length() == 0) representations += "body" to event.getString("body")
    val additional = JSONArray()
    for ((label, value) in representations) {
        if (!represented.add(value)) continue
        if (!result.has("body")) result.put("body", value)
        else additional.put(JSONObject().put("label", label).put("text", value))
    }
    // Every distinct public text field captured by NotificationCollector is retained.
    for ((field, label) in listOf("android.title.big" to "expandedTitle", "android.subText" to "subtitle",
        "android.summaryText" to "summary", "android.infoText" to "info", "conversationTitle" to "conversation")) {
        text(originals, field)?.let { value ->
            if (value != text(event, "title") && represented.add(value))
                additional.put(JSONObject().put("label", label).put("text", value))
        }
    }
    if (additional.length() > 0) result.put("additionalText", additional)
    if (!result.has("body") && messages.length() == 0) result.put("body", "")
    return result
}
