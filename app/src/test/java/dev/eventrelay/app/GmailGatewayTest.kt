package dev.eventrelay.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Adapter contract tests use fake/no-op implementations only; no authorization or email sending. */
class GmailGatewayTest {
    @Test fun unconfiguredAdapterRequiresAuthorizationAndNeverReportsAcceptance() = runBlocking {
        val result: SendOutcome = UnconfiguredGmailGateway().sendToOwnAccount("local-event-id", "full permitted payload")
        assertEquals(SendOutcome.AuthorizationRequired, result)
        assertFalse(result is SendOutcome.Accepted)
    }

    @Test fun fakeGatewayRecordsStableLocalIdentityForEachAttempt() = runBlocking {
        val gateway = FakeGmailGateway(SendOutcome.Unknown("SUBMISSION_UNKNOWN"))
        repeat(2) { assertEquals(SendOutcome.Unknown("SUBMISSION_UNKNOWN"),
            gateway.sendToOwnAccount("stable-local-id", "full permitted original payload")) }
        assertEquals(listOf("stable-local-id", "stable-local-id"), gateway.attemptedEventIds)
    }

    @Test fun fakeAcceptanceReportsOnlyTheProvidedRemoteApiId() = runBlocking {
        val gateway = FakeGmailGateway(SendOutcome.Accepted("gmail-api-id"))
        assertEquals(SendOutcome.Accepted("gmail-api-id"), gateway.sendToOwnAccount("local-id", "payload"))
        assertEquals(listOf("local-id"), gateway.attemptedEventIds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun acceptanceCannotBeConstructedWithoutAConfirmedRemoteMessageId() {
        SendOutcome.Accepted(" ")
    }

    @Test fun configuredScopeIsSendOnlyWithoutMailboxReadScope() {
        val config = GmailAuthorizationConfig()
        assertNull(config.androidClientId)
        assertEquals("https://www.googleapis.com/auth/gmail.send", config.scope)
    }
}
