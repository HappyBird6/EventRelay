package dev.eventrelay.app

/** No credential, account, or network implementation is included until separately approved. */
data class GmailAuthorizationConfig(val androidClientId:String?=null,val scope:String="https://www.googleapis.com/auth/gmail.send")
sealed interface SendOutcome {
 data class Accepted(val gmailMessageId:String):SendOutcome { init{require(gmailMessageId.isNotBlank())} }
 data class Retryable(val code:String,val retryAfterMillis:Long?=null):SendOutcome {init{require(retryAfterMillis==null||retryAfterMillis>=0)}}
 data class Permanent(val code:String):SendOutcome
 data class Unknown(val code:String):SendOutcome
 data object AuthorizationRequired:SendOutcome
 data object PolicyHeld:SendOutcome
}
interface GmailGateway { suspend fun sendToOwnAccount(eventId:String,structuredPayload:String):SendOutcome }
class UnconfiguredGmailGateway:GmailGateway {
 override suspend fun sendToOwnAccount(eventId:String,structuredPayload:String)=SendOutcome.AuthorizationRequired
}
/** Test fixture only. Acceptance means API acceptance, never downstream AI processing. */
class FakeGmailGateway(private val outcome:SendOutcome):GmailGateway {
 val attemptedEventIds=mutableListOf<String>()
 override suspend fun sendToOwnAccount(eventId:String,structuredPayload:String):SendOutcome { attemptedEventIds+=eventId;return outcome }
}
