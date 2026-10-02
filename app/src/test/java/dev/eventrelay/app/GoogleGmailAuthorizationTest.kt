package dev.eventrelay.app

import android.app.Activity
import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Disabled-build guards and pure request values only; never invokes Google account authorization. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class GoogleGmailAuthorizationTest {
    private val syntheticEmail = "synthetic-owner@example.test"

    @After fun clearSyntheticMemoryOnlySession() { GmailSessionVault.forgetExpiredToken() }

    @Test fun disabledControllerReturnsConfigurationRequiredBeforeAnyGrantStateOrResolution() {
        org.junit.Assume.assumeFalse(BuildConfig.GMAIL_ENABLED)
        val activity = object : Activity() {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                throw AssertionError("Disabled controller must not inspect or create authorization state")
            }
        }
        val controller = GoogleGmailAuthorization(activity)
        val results = mutableListOf<String>()
        var resolutions = 0
        controller.request(syntheticEmail, { resolutions++ }, { results += it })
        assertEquals(listOf("CONFIGURATION_REQUIRED"), results)
        assertEquals(0, resolutions)
        controller.consumeResolution(Intent(), { results += it })
        assertEquals(listOf("CONFIGURATION_REQUIRED", "AUTHORIZATION_CANCELLED"), results)
        assertEquals(0, resolutions)
    }

    @Test fun disabledProviderAndVaultReturnNullEvenWhenSyntheticSessionExists() = runBlocking {
        org.junit.Assume.assumeFalse(BuildConfig.GMAIL_ENABLED)
        GmailSessionVault.remember(AuthorizedGmailSession(syntheticEmail, "synthetic-token-never-valid"))
        assertNull(GmailSessionVault.session())
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                throw AssertionError("Disabled provider must not inspect prior grants or request authorization")
            }
        }
        val provider = GoogleGmailSessionProvider(context)
        assertNull(provider.session())
        provider.invalidateRejectedToken(AuthorizedGmailSession(syntheticEmail, "synthetic-token-never-valid"))
        assertNull(GmailSessionVault.session())
    }

    @Test fun connectionBuildKeepsActualDeliveryLocked() { assertFalse(gmailDeliveryEnabled(RuntimeEnvironment.getApplication())) }

    @Test fun pureAuthorizationRequestBindsSpecifiedAccountAndOnlySendScopeWithoutOfflineAccess() {
        val request = gmailAuthorizationRequest(syntheticEmail)
        assertEquals(syntheticEmail, request.account?.name)
        assertEquals("com.google", request.account?.type)
        assertEquals(listOf("https://www.googleapis.com/auth/gmail.send"), request.requestedScopes.map { it.scopeUri })
        assertTrue("Previously granted broader scopes must not be included", request.optOutIncludingGrantedScopes)
        assertFalse(request.isOfflineAccessRequested)
        assertFalse(request.isForceCodeForRefreshToken)
        assertNull(request.serverClientId)
    }
}
