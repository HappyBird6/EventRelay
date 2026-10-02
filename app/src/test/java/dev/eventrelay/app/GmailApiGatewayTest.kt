package dev.eventrelay.app

import android.app.Application
import dev.eventrelay.core.EventIdentity
import dev.eventrelay.core.EventValue
import dev.eventrelay.core.RelayEvent
import dev.eventrelay.core.SourceKind
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Only synthetic sessions and fake transports: these tests cannot grant access or send email. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class GmailApiGatewayTest {
    private val ownEmail = "owner@example.test"
    private val fakeToken = "synthetic-test-token-never-valid"

    private data class Request(val token: String, val jsonBody: String)
    private class RecordingTransport(
        var response: GmailHttpResponse = GmailHttpResponse(200, "{\"id\":\"gmail-api-id\"}"),
        var failure: Exception? = null,
    ) : GmailTransport {
        val requests = mutableListOf<Request>()
        override suspend fun post(accessToken: String, jsonBody: String): GmailHttpResponse {
            requests += Request(accessToken, jsonBody)
            failure?.let { throw it }
            return response
        }
    }

    private fun event(kind: SourceKind = SourceKind.SMS, body: String = "Payment approved\n전체 원문 😀") = RelayEvent(
        kind, if (kind == SourceKind.SMS) "SYNTHETIC-SENDER" else "dev.synthetic.source",
        "synthetic-source-record", 1_700_000_000_000, 1_700_000_000_001,
        "원본 알림 제목", body,
        mapOf("nested" to EventValue.Object(mapOf(
            "original" to EventValue.Text("보존된 전체 텍스트\n\"quote\"\\slash"),
            "messages" to EventValue.Array(listOf(EventValue.Text("first"), EventValue.Text("second 😀"))),
            "timestamp" to EventValue.Number(1_700_000_000_000),
        ))),
    )

    private fun gateway(transport: GmailTransport, session: AuthorizedGmailSession? =
        AuthorizedGmailSession(ownEmail, fakeToken)) = GmailApiGateway(GmailSessionProvider { session }, transport)

    private data class DecodedMessage(val headers: Map<String, String>, val payload: String, val rawMime: String)

    private fun decodeRequest(jsonBody: String): DecodedMessage {
        val request = JSONObject(jsonBody)
        assertEquals(setOf("raw"), request.keys().asSequence().toSet())
        val raw = request.getString("raw")
        assertTrue("Gmail raw must be unpadded URL-safe base64", Regex("^[A-Za-z0-9_-]+$").matches(raw))
        val mime = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
        val separator = mime.indexOf("\r\n\r\n")
        assertTrue("RFC message must separate headers from body with CRLF", separator > 0)
        val headers = mime.substring(0, separator).split("\r\n").associate { line ->
            val colon = line.indexOf(':'); assertTrue(colon > 0)
            line.substring(0, colon) to line.substring(colon + 1).trim()
        }
        val encodedBody = mime.substring(separator + 4).removeSuffix("\r\n")
        assertTrue(encodedBody.split("\r\n").all { it.length <= 76 })
        val payload = String(Base64.getMimeDecoder().decode(encodedBody), Charsets.UTF_8)
        return DecodedMessage(headers, payload, mime)
    }

    @Test fun finalPolicyGateBlocksTransportAfterPayloadPreparation() = runBlocking {
        val event=event();val transport=RecordingTransport()
        val guarded=GmailApiGateway(GmailSessionProvider{AuthorizedGmailSession(ownEmail,fakeToken)},transport){false}
        assertEquals(SendOutcome.PolicyHeld,guarded.sendToOwnAccount(event.eventId,event.toJson()))
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun requestUsesOwnAccountOnlyAndFullRfcMimeHeaders() = runBlocking {
        val event = event(); val transport = RecordingTransport()
        val result = gateway(transport).sendToOwnAccount(event.eventId, event.toJson())
        assertEquals(SendOutcome.Accepted("gmail-api-id"), result)
        val request = transport.requests.single()
        assertEquals(fakeToken, request.token)
        val message = decodeRequest(request.jsonBody)
        assertEquals(ownEmail, message.headers["From"])
        assertEquals(ownEmail, message.headers["To"])
        assertFalse(message.headers.keys.any { it.equals("Cc", true) || it.equals("Bcc", true) })
        assertEquals(EventIdentity.messageId(event.eventId, ownEmail), message.headers["Message-ID"])
        assertEquals("[EventRelay] SMS", message.headers["Subject"])
        assertEquals("1.0", message.headers["MIME-Version"])
        assertEquals("text/plain; charset=UTF-8", message.headers["Content-Type"])
        assertEquals("base64", message.headers["Content-Transfer-Encoding"])
        assertEquals(externalMailPayload(JSONObject(event.toJson())).toString(2), message.payload)
        assertFalse(request.jsonBody.contains(fakeToken))
        assertFalse(message.rawMime.contains(fakeToken))
    }

    @Test fun largeMultilineUtf8AndOriginalStructuredPayloadRoundTripExactly() = runBlocking {
        val body = "줄바꿈\n원문 😀\r\nquote=\"\" backslash=\\\t\n".repeat(12_000) + "FINAL ORIGINAL TAIL"
        val event = event(body = body); val transport = RecordingTransport()
        gateway(transport).sendToOwnAccount(event.eventId, event.toJson())
        val decoded = decodeRequest(transport.requests.single().jsonBody)
        assertEquals(externalMailPayload(JSONObject(event.toJson())).toString(2), decoded.payload)
        val json = JSONObject(decoded.payload)
        assertEquals(body, json.getString("body"))
        assertEquals("second 😀", JSONObject(event.toJson()).getJSONObject("originalFields").getJSONObject("nested")
            .getJSONArray("messages").getString(1))
        assertEquals("2023-11-14T22:13:20Z", json.getString("occurredAt"))
        assertFalse(json.has("observedAt")); assertFalse(json.has("sourceRecordId"))
    }

    @Test fun notificationKindUsesNotificationSubjectAndDoesNotPutOriginalTextIntoHeaders() = runBlocking {
        val event = event(SourceKind.NOTIFICATION, "body\r\nBcc: somebody@example.test")
        val transport = RecordingTransport(); gateway(transport).sendToOwnAccount(event.eventId, event.toJson())
        val message = decodeRequest(transport.requests.single().jsonBody)
        assertEquals("[EventRelay] NOTIFICATION", message.headers["Subject"])
        assertFalse(message.headers.containsKey("Bcc"))
        assertEquals(externalMailPayload(JSONObject(event.toJson())).toString(2), message.payload)
    }

    @Test fun repeatedSubmissionRetainsStableMessageIdWithoutClaimingServerIdempotence() = runBlocking {
        val event = event(); val transport = RecordingTransport(); val gateway = gateway(transport)
        repeat(2) { gateway.sendToOwnAccount(event.eventId, event.toJson()) }
        assertEquals(2, transport.requests.size)
        val ids = transport.requests.map { decodeRequest(it.jsonBody).headers["Message-ID"] }
        assertEquals(ids[0], ids[1])
    }

    @Test fun absentSessionReturnsAuthorizationRequiredWithoutAnyTransportCall() = runBlocking {
        val event = event(); val transport = RecordingTransport()
        assertEquals(SendOutcome.AuthorizationRequired,
            gateway(transport, null).sendToOwnAccount(event.eventId, event.toJson()))
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun failedSessionProviderReturnsAuthorizationRequiredWithoutTransportCall() = runBlocking {
        val event = event(); val transport = RecordingTransport()
        val gateway = GmailApiGateway(GmailSessionProvider { throw IOException("provider unavailable") }, transport)
        assertEquals(SendOutcome.AuthorizationRequired, gateway.sendToOwnAccount(event.eventId, event.toJson()))
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun cancelledSessionProviderPropagatesWithoutTransportCall() {
        val event = event(); val transport = RecordingTransport()
        val gateway = GmailApiGateway(GmailSessionProvider { throw CancellationException("provider cancelled") }, transport)
        assertThrows(CancellationException::class.java) {
            runBlocking { gateway.sendToOwnAccount(event.eventId, event.toJson()) }
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun invalidSchemaIdentityKindAndJsonAreRejectedBeforeNetworkSubmission() = runBlocking {
        val event = event()
        val invalidPayloads = listOf("not-json", "{}",
            JSONObject(event.toJson()).put("schema", "wrong.schema").toString(),
            JSONObject(event.toJson()).put("eventId", "different-id").toString(),
            JSONObject(event.toJson()).put("kind", "UNKNOWN").toString())
        invalidPayloads.forEach { payload ->
            val transport = RecordingTransport()
            assertEquals(SendOutcome.Permanent("INVALID_PAYLOAD"), gateway(transport).sendToOwnAccount(event.eventId, payload))
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test fun everySuccessfulHttpStatusRequiresConfirmedNonblankStringMessageId() = runBlocking {
        val event = event()
        listOf(200, 201, 202, 299).forEach { status ->
            val transport = RecordingTransport(GmailHttpResponse(status, "{\"id\":\"confirmed-api-id\"}"))
            assertEquals(SendOutcome.Accepted("confirmed-api-id"), gateway(transport).sendToOwnAccount(event.eventId, event.toJson()))
        }
    }

    @Test fun missingBlankMalformedOrNonStringSuccessIdIsAnUnconfirmedOutcome() = runBlocking {
        val event = event()
        listOf("{}", "{\"id\":\"\"}", "{\"id\":\" \"}", "not-json", "{\"id\":null}",
            "{\"id\":123}", "{\"id\":{\"unexpected\":true}}", "{\"id\":[\"unexpected\"]}").forEach { body ->
            val transport = RecordingTransport(GmailHttpResponse(200, body))
            assertEquals("Response: $body", SendOutcome.Unknown("ACCEPTANCE_UNCONFIRMED"),
                gateway(transport).sendToOwnAccount(event.eventId, event.toJson()))
        }
    }

    @Test fun unauthorizedResponseRequiresAuthorizationWithoutAutomaticResubmission() = runBlocking {
        val event = event(); val transport = RecordingTransport(GmailHttpResponse(401, "{\"error\":\"unauthorized\"}"))
        assertEquals(SendOutcome.AuthorizationRequired, gateway(transport).sendToOwnAccount(event.eventId, event.toJson()))
        assertEquals(1, transport.requests.size)
    }

    @Test fun explicitRateLimitIsKnownRetryableFailure() = runBlocking {
        val event = event(); val transport = RecordingTransport(GmailHttpResponse(429, "{}", retryAfterSeconds = 120))
        assertEquals(SendOutcome.Retryable("RATE_LIMITED", 120_000), gateway(transport).sendToOwnAccount(event.eventId, event.toJson()))
        assertEquals(1, transport.requests.size)
    }

    @Test fun known403RateReasonsAreRetryableWhilePermissionRejectionRemainsPermanent() = runBlocking {
        val event = event()
        listOf("rateLimitExceeded", "userRateLimitExceeded").forEach { reason ->
            val body = "{\"error\":{\"errors\":[{\"reason\":\"$reason\"}]}}"
            val transport = RecordingTransport(GmailHttpResponse(403, body, retryAfterSeconds = 45))
            assertEquals(SendOutcome.Retryable("RATE_LIMITED", 45_000),
                gateway(transport).sendToOwnAccount(event.eventId, event.toJson()))
        }
        val permissionError = "{\"error\":{\"errors\":[{\"reason\":\"insufficientPermissions\"}]}}"
        val transport = RecordingTransport(GmailHttpResponse(403, permissionError))
        assertEquals(SendOutcome.Permanent("GMAIL_REJECTED_403"),
            gateway(transport).sendToOwnAccount(event.eventId, event.toJson()))
    }

    @Test fun retryAfterConversionRejectsNegativeValuesAndSaturatesOverflow() = runBlocking {
        val event = event()
        val negative = RecordingTransport(GmailHttpResponse(429, "{}", retryAfterSeconds = -1))
        assertEquals(SendOutcome.Retryable("RATE_LIMITED"), gateway(negative).sendToOwnAccount(event.eventId, event.toJson()))
        val overflow = RecordingTransport(GmailHttpResponse(429, "{}", retryAfterSeconds = Long.MAX_VALUE))
        assertEquals(SendOutcome.Retryable("RATE_LIMITED", Long.MAX_VALUE),
            gateway(overflow).sendToOwnAccount(event.eventId, event.toJson()))
    }

    @Test fun explicitPermanentClientRejectionsAreNotClaimedAsDeliverySuccess() = runBlocking {
        val event = event()
        listOf(400, 403, 404, 413).forEach { status ->
            val transport = RecordingTransport(GmailHttpResponse(status, "{\"error\":\"rejected\"}"))
            assertEquals(SendOutcome.Permanent("GMAIL_REJECTED_$status"), gateway(transport).sendToOwnAccount(event.eventId, event.toJson()))
        }
    }

    @Test fun serverErrorsRemainUnknownToAvoidDuplicateEmailAfterAmbiguousAcceptance() = runBlocking {
        val event = event()
        listOf(500, 502, 503, 504, 599).forEach { status ->
            val transport = RecordingTransport(GmailHttpResponse(status, "{}"))
            assertEquals(SendOutcome.Unknown("SERVER_ACCEPTANCE_UNKNOWN"), gateway(transport).sendToOwnAccount(event.eventId, event.toJson()))
            assertEquals(1, transport.requests.size)
        }
    }

    @Test fun ioExceptionAfterSubmissionIsUnknownAndNeverAutomaticallyRetried() = runBlocking {
        val event = event(); val transport = RecordingTransport(failure = IOException("private diagnostic text"))
        assertEquals(SendOutcome.Unknown("SUBMISSION_UNKNOWN"), gateway(transport).sendToOwnAccount(event.eventId, event.toJson()))
        assertEquals(1, transport.requests.size)
    }

    @Test fun unexpectedResponseStatusesRemainUnknown() = runBlocking {
        val event = event()
        listOf(301, 302, 408, 418).forEach { status ->
            val transport = RecordingTransport(GmailHttpResponse(status, "{}"))
            assertEquals(SendOutcome.Unknown("UNEXPECTED_RESPONSE"), gateway(transport).sendToOwnAccount(event.eventId, event.toJson()))
        }
    }

    @Test fun cancellationPropagatesSoWorkerCanMarkItsInFlightClaimUnknown() {
        val event = event(); val transport = RecordingTransport(failure = CancellationException("cancelled"))
        assertThrows(CancellationException::class.java) {
            runBlocking { gateway(transport).sendToOwnAccount(event.eventId, event.toJson()) }
        }
        assertEquals(1, transport.requests.size)
    }

    @Test fun sessionRejectsHeaderInjectionAndItsStringRepresentationRedactsToken() {
        assertThrows(IllegalArgumentException::class.java) { AuthorizedGmailSession("$ownEmail\r\nBcc: stranger@example.test", fakeToken) }
        assertThrows(IllegalArgumentException::class.java) { AuthorizedGmailSession(ownEmail, "$fakeToken\nInjected: value") }
        val session = AuthorizedGmailSession(ownEmail, fakeToken)
        assertFalse(session.toString().contains(fakeToken))
        assertTrue(session.toString().contains("redacted"))
    }
}
