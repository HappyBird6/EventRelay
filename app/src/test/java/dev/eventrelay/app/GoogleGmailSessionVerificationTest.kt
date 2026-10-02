package dev.eventrelay.app

import android.accounts.Account
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.Scope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Pure request values and synthetic SDK results; no Identity/account/network APIs. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@Suppress("DEPRECATION")
class GoogleGmailSessionVerificationTest {
    private val email = "synthetic-owner@example.test"
    private val fakeToken = "synthetic-token-never-valid"

    private fun request(
        account: Account? = Account(email, "com.google"),
        scopes: List<String> = listOf(GMAIL_SEND_SCOPE),
        optOut: Boolean = true,
        offline: Boolean = false,
        selectAccount: Boolean = false,
    ): AuthorizationRequest {
        val builder = AuthorizationRequest.builder().setRequestedScopes(scopes.map(::Scope))
            .setOptOutIncludingGrantedScopes(optOut)
        account?.let(builder::setAccount)
        if (offline) builder.requestOfflineAccess("synthetic-client-never-authorized")
        if (selectAccount) builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        return builder.build()
    }

    private fun result(
        optionalEmail: String? = null,
        token: String? = fakeToken,
        scopes: List<String> = listOf(GMAIL_SEND_SCOPE),
        resolution: Boolean = false,
        absentSignInAccount: Boolean = false,
    ): AuthorizationResult {
        val account = if (absentSignInAccount) null else syntheticSignInAccount(optionalEmail)
        val pending = if (resolution) PendingIntent.getActivity(
            RuntimeEnvironment.getApplication(), 1, Intent("dev.eventrelay.synthetic.RESOLUTION"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
        ) else null
        return AuthorizationResult(null, token, null, scopes, account, pending)
    }

    private fun rejected(expected: GmailVerificationFailure, result: AuthorizationResult, request: AuthorizationRequest = request()) {
        val verification = verifyGmailSession(result, request)
        assertTrue(verification is GmailSessionVerification.Rejected)
        assertEquals(expected, (verification as GmailSessionVerification.Rejected).failure)
    }

    @Test fun boundSendOnlyGrantAcceptsNullEmailWithoutRequestingIdentityScopes() {
        val submitted = gmailAuthorizationRequest(email)
        val verification = verifyGmailSession(result(optionalEmail = null), submitted)
        assertTrue(verification is GmailSessionVerification.Accepted)
        val session = (verification as GmailSessionVerification.Accepted).session
        assertEquals(email, session.ownEmail)
        assertEquals(fakeToken, session.accessToken)
        assertEquals(listOf(GMAIL_SEND_SCOPE), submitted.requestedScopes.map { it.scopeUri })
    }

    @Test fun boundGrantDoesNotRequireAnyReturnedGoogleSignInAccount() {
        val verification = verifyGmailSession(result(absentSignInAccount = true), request())
        assertTrue(verification is GmailSessionVerification.Accepted)
        assertEquals(email, (verification as GmailSessionVerification.Accepted).session.ownEmail)
    }

    @Test fun optionalMatchingEmailIsConsistentButSessionUsesTheSubmittedAccountName() {
        val verification = verifyGmailSession(result(optionalEmail = email.uppercase()), request())
        assertTrue(verification is GmailSessionVerification.Accepted)
        assertEquals(email, (verification as GmailSessionVerification.Accepted).session.ownEmail)
    }

    @Test fun conflictingOrInvalidOptionalEmailIsRejected() {
        listOf("different-owner@example.test", "", "$email\r\nInjected: value").forEach {
            rejected(GmailVerificationFailure.ACCOUNT_MISMATCH, result(optionalEmail = it))
        }
    }

    @Test fun missingBlankOrHeaderUnsafeTokenIsRejectedWithOnlySafeCode() {
        listOf(null, "", " ", "synthetic\nInjected: value").forEach {
            rejected(GmailVerificationFailure.TOKEN_MISSING, result(token = it))
        }
    }

    @Test fun resolutionStillRequiredCannotProduceAnAuthorizedSession() {
        rejected(GmailVerificationFailure.RESOLUTION_REQUIRED, result(resolution = true))
    }

    @Test fun missingSendGrantIsReportedSeparatelyFromUnexpectedAdditionalScopes() {
        rejected(GmailVerificationFailure.SCOPE_MISSING, result(scopes = emptyList()))
        rejected(GmailVerificationFailure.SCOPE_MISSING, result(scopes = listOf("profile")))
        rejected(GmailVerificationFailure.SCOPE_UNEXPECTED, result(scopes = listOf(GMAIL_SEND_SCOPE, "email")))
        rejected(GmailVerificationFailure.SCOPE_UNEXPECTED,
            result(scopes = listOf(GMAIL_SEND_SCOPE, "https://www.googleapis.com/auth/gmail.readonly")))
    }

    @Test fun unboundWrongTypeOrUnsafeAccountRequestCannotAuthorize() {
        rejected(GmailVerificationFailure.REQUEST_INVALID, result(), request(account = null))
        rejected(GmailVerificationFailure.REQUEST_INVALID, result(), request(account = Account(email, "other.account.type")))
        rejected(GmailVerificationFailure.REQUEST_INVALID, result(), request(account = Account("not-an-email", "com.google")))
    }

    @Test fun selectAccountPromptCannotOverrideTheBoundAccount() {
        rejected(GmailVerificationFailure.REQUEST_INVALID, result(), request(selectAccount = true))
    }

    @Test fun widenedRequestedScopeOrPreviouslyGrantedScopeInclusionFailsClosed() {
        rejected(GmailVerificationFailure.REQUEST_INVALID, result(), request(scopes = listOf(GMAIL_SEND_SCOPE, "email")))
        rejected(GmailVerificationFailure.REQUEST_INVALID, result(), request(optOut = false))
    }

    @Test fun offlineAccessRequestCannotBeUsedForEphemeralSendOnlySession() {
        rejected(GmailVerificationFailure.REQUEST_INVALID, result(), request(offline = true))
    }

    @Test fun concurrentAttemptCannotReplaceTheActualSubmittedRequest() {
        val attempts = GmailAuthorizationAttempts()
        val submitted = request()
        val first = attempts.begin(submitted)!!
        assertNull(attempts.begin(request(Account("different@example.test", "com.google"))))
        assertSame(submitted, attempts.claimResult(first.id))
        assertNull(attempts.claimResult(first.id))
        assertTrue(attempts.complete(first.id))
    }

    @Test fun resolutionIsConsumedOnceAndRetainsTheOriginalRequest() {
        val attempts = GmailAuthorizationAttempts()
        val submitted = request()
        val attempt = attempts.begin(submitted)!!
        assertNull(attempts.acquireResolution())
        assertTrue(attempts.awaitResolution(attempt.id))
        assertFalse(attempts.awaitResolution(attempt.id))
        assertNull(attempts.claimResult(attempt.id))
        val resolution = attempts.acquireResolution()!!
        assertSame(submitted, resolution.request)
        assertNull(attempts.acquireResolution())
        assertSame(submitted, attempts.claimResult(resolution.id))
        assertNull(attempts.claimResult(resolution.id))
        assertTrue(attempts.complete(resolution.id))
        assertNull(attempts.acquireResolution())
    }

    @Test fun canceledOrCompletedAttemptCallbacksCannotCompleteANewerRequest() {
        val attempts = GmailAuthorizationAttempts()
        val old = attempts.begin(request())!!
        assertTrue(attempts.cancel())
        assertFalse(attempts.cancel())
        val current = attempts.begin(request())!!
        assertFalse(attempts.isCurrent(old.id))
        assertNull(attempts.claimResult(old.id))
        assertFalse(attempts.awaitResolution(old.id))
        assertFalse(attempts.complete(old.id))
        assertTrue(attempts.isCurrent(current.id))
        assertSame(current.request, attempts.claimResult(current.id))
        assertTrue(attempts.complete(current.id))
        assertNull(attempts.claimResult(current.id))
    }

    @Test fun cancellationAfterResultClaimPreventsAnyAcceptanceCommit() {
        val attempts = GmailAuthorizationAttempts()
        val old = attempts.begin(request())!!
        assertSame(old.request, attempts.claimResult(old.id))
        assertTrue(attempts.cancel())
        val current = attempts.begin(request())!!
        var commits = 0
        assertFalse(attempts.commit(old.id) { commits++ })
        assertEquals(0, commits)
        assertTrue(attempts.isCurrent(current.id))
    }

    @Test fun acceptanceCommitRequiresCurrentFinishingAttemptAndRunsExactlyOnce() {
        val attempts = GmailAuthorizationAttempts()
        val attempt = attempts.begin(request())!!
        var commits = 0
        assertFalse(attempts.commit(attempt.id) { commits++ })
        assertSame(attempt.request, attempts.claimResult(attempt.id))
        assertTrue(attempts.commit(attempt.id) { commits++ })
        assertFalse(attempts.commit(attempt.id) { commits++ })
        assertEquals(1, commits)
        assertFalse(attempts.isCurrent(attempt.id))
    }

    /**
     * Fixed constructor signature from the pinned Play services base dependency. This creates only
     * a parcelable value with synthetic IDs, no AccountManager, identity client, or extra OAuth scope.
     * In particular its actual getEmail() returns null when the send-only result has no identity data.
     */
    private fun syntheticSignInAccount(optionalEmail: String?): GoogleSignInAccount {
        val constructor = GoogleSignInAccount::class.java.getDeclaredConstructor(
            Integer.TYPE, String::class.java, String::class.java, String::class.java, String::class.java,
            Uri::class.java, String::class.java, java.lang.Long.TYPE, String::class.java,
            List::class.java, String::class.java, String::class.java,
        ).apply { isAccessible = true }
        return constructor.newInstance(3, "synthetic-user-id", null, optionalEmail, null, null, null,
            4_102_444_800L, "synthetic-obfuscated-id", emptyList<Scope>(), null, null)
    }
}
