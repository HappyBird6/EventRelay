package dev.eventrelay.app

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal const val GMAIL_SEND_SCOPE="https://www.googleapis.com/auth/gmail.send"

/** Only an account identifier is persisted; access tokens remain in memory. */
object GmailSessionVault:GmailSessionProvider {
 @Volatile private var cached:AuthorizedGmailSession?=null
 @Volatile private var expiresAt:Long=0
 override suspend fun session():AuthorizedGmailSession?=if(BuildConfig.GMAIL_ENABLED&&System.currentTimeMillis()<expiresAt)cached else null
 internal fun remember(session:AuthorizedGmailSession){cached=session;expiresAt=System.currentTimeMillis()+45*60*1000L}
 fun forgetExpiredToken(){cached=null;expiresAt=0}
}
internal fun gmailAuthorizationRequest(ownEmail:String):AuthorizationRequest {
 require(isSafeEmail(ownEmail))
 return AuthorizationRequest.builder().setAccount(Account(ownEmail,"com.google"))
  .setRequestedScopes(listOf(Scope(GMAIL_SEND_SCOPE))).setOptOutIncludingGrantedScopes(true).build()
}
internal enum class GmailVerificationFailure(val code:String) {
 REQUEST_INVALID("AUTH_REQUEST_INVALID"),
 RESOLUTION_REQUIRED("AUTH_RESOLUTION_REQUIRED"),
 SCOPE_MISSING("AUTH_SCOPE_MISSING"),
 SCOPE_UNEXPECTED("AUTH_SCOPE_UNEXPECTED"),
 ACCOUNT_MISMATCH("AUTH_ACCOUNT_MISMATCH"),
 TOKEN_MISSING("AUTH_TOKEN_MISSING"),
}
internal sealed interface GmailSessionVerification {
 class Accepted(val session:AuthorizedGmailSession):GmailSessionVerification
 data class Rejected(val failure:GmailVerificationFailure):GmailSessionVerification
}

/**
 * Verify against the ACTUAL immutable request submitted to Google, never a mutable email draft.
 * setAccount binds that device account unless SELECT_ACCOUNT overrides it. A send-only grant does
 * not request profile/email scopes, so GoogleSignInAccount.email may legitimately be absent.
 * An optional returned identity is an additional consistency check, not a required extra scope.
 */
@Suppress("DEPRECATION")
internal fun verifyGmailSession(result:AuthorizationResult,request:AuthorizationRequest):GmailSessionVerification {
 fun reject(failure:GmailVerificationFailure)=GmailSessionVerification.Rejected(failure)
 val account=request.account
 if(account==null||account.type!="com.google"||!isSafeEmail(account.name)||
    (request.prompt and AuthorizationRequest.Prompt.SELECT_ACCOUNT)!=0)
  return reject(GmailVerificationFailure.REQUEST_INVALID)
 if(request.requestedScopes.map{it.scopeUri}!=listOf(GMAIL_SEND_SCOPE)||
    !request.optOutIncludingGrantedScopes||request.isOfflineAccessRequested||
    request.isForceCodeForRefreshToken||request.serverClientId!=null)
  return reject(GmailVerificationFailure.REQUEST_INVALID)
 if(result.hasResolution())return reject(GmailVerificationFailure.RESOLUTION_REQUIRED)
 if(GMAIL_SEND_SCOPE !in result.grantedScopes)return reject(GmailVerificationFailure.SCOPE_MISSING)
 if(result.grantedScopes.toSet()!=setOf(GMAIL_SEND_SCOPE))return reject(GmailVerificationFailure.SCOPE_UNEXPECTED)
 val optionalEmail=try{result.toGoogleSignInAccount()?.email}catch(_:Exception){
  return reject(GmailVerificationFailure.ACCOUNT_MISMATCH)
 }
 if(optionalEmail!=null&&(!isSafeEmail(optionalEmail)||!optionalEmail.equals(account.name,ignoreCase=true)))
  return reject(GmailVerificationFailure.ACCOUNT_MISMATCH)
 val token=result.accessToken?.takeIf{it.isNotBlank()} ?: return reject(GmailVerificationFailure.TOKEN_MISSING)
 return try{GmailSessionVerification.Accepted(AuthorizedGmailSession(account.name,token))}
 catch(_:IllegalArgumentException){reject(GmailVerificationFailure.TOKEN_MISSING)}
}

/** Small synchronization boundary so SDK callbacks cannot replace another attempt's request. */
internal class GmailAuthorizationAttempts {
 internal data class Attempt(val id:Long,val request:AuthorizationRequest,val accountEpoch:Long=0)
 private enum class Phase { AUTHORIZING, AWAITING_RESOLUTION, RESOLVING, FINISHING, COMMITTING }
 private data class Active(val attempt:Attempt,val phase:Phase)
 private var active:Active?=null
 private var nextId=0L

 @Synchronized fun begin(request:AuthorizationRequest,accountEpoch:Long=0):Attempt? {
  if(active!=null)return null
  val attempt=Attempt(++nextId,request,accountEpoch)
  active=Active(attempt,Phase.AUTHORIZING)
  return attempt
 }
 @Synchronized fun isCurrent(id:Long)=active?.attempt?.id==id
 @Synchronized fun epoch(id:Long):Long?=active?.attempt?.takeIf{it.id==id}?.accountEpoch
 @Synchronized fun awaitResolution(id:Long):Boolean {
  val current=active ?: return false
  if(current.attempt.id!=id||current.phase!=Phase.AUTHORIZING)return false
  active=current.copy(phase=Phase.AWAITING_RESOLUTION);return true
 }
 @Synchronized fun acquireResolution():Attempt? {
  val current=active ?: return null
  if(current.phase!=Phase.AWAITING_RESOLUTION)return null
  active=current.copy(phase=Phase.RESOLVING);return current.attempt
 }
 @Synchronized fun claimResult(id:Long):AuthorizationRequest? {
  val current=active ?: return null
  if(current.attempt.id!=id||current.phase !in setOf(Phase.AUTHORIZING,Phase.RESOLVING))return null
  active=current.copy(phase=Phase.FINISHING);return current.attempt.request
 }
 @Synchronized fun complete(id:Long):Boolean {
  if(active?.attempt?.id!=id||active?.phase==Phase.COMMITTING)return false
  active=null;return true
 }
 /** Acceptance has one linearization point: a cancellation before this lock wins. */
 @Synchronized fun commit(id:Long,action:()->Unit):Boolean {
  val current=active ?: return false
  if(current.attempt.id!=id||current.phase!=Phase.FINISHING)return false
  active=current.copy(phase=Phase.COMMITTING)
  try{action()}finally{active=null}
  return true
 }
 @Synchronized fun cancel():Boolean {
  if(active==null||active?.phase==Phase.COMMITTING)return false
  active=null;return true
 }
}

/** User-initiated grant flow. No call is made by the disabled development UI. */
class GoogleGmailAuthorization(private val activity:Activity) {
 private val attempts=GmailAuthorizationAttempts()
 /** Invalidate canceled ActivityResult state and ignore any late SDK callbacks. */
 fun cancel():Boolean=attempts.cancel()
 fun request(ownEmail:String,onResolution:(PendingIntent)->Unit,onResult:(String)->Unit){
  if(!BuildConfig.GMAIL_ENABLED){onResult("CONFIGURATION_REQUIRED");return}
  if(!isSafeEmail(ownEmail)){onResult("INVALID_EMAIL");return}
  val saved=activity.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("gmailOwnEmail",null)
  if(saved!=null&&!saved.equals(ownEmail,ignoreCase=true)){onResult("ACCOUNT_CHANGE_REQUIRED");return}
  val request=gmailAuthorizationRequest(ownEmail)
  val epoch=activity.getSharedPreferences("settings",Context.MODE_PRIVATE).getLong("gmailAccountEpoch",0)
  val attempt=attempts.begin(request,epoch) ?: run{onResult("AUTHORIZATION_IN_PROGRESS");return}
  try{Identity.getAuthorizationClient(activity).authorize(request)
   .addOnSuccessListener {result->
    if(!attempts.isCurrent(attempt.id))return@addOnSuccessListener
    if(result.hasResolution()){
     val pending=result.pendingIntent
     if(pending==null){if(attempts.complete(attempt.id))onResult("AUTHORIZATION_RESOLUTION_UNAVAILABLE")}
     else if(attempts.awaitResolution(attempt.id)){
      try{onResolution(pending)}catch(_:Exception){if(attempts.complete(attempt.id))onResult("AUTHORIZATION_RESOLUTION_FAILED")}
     }
    }else finish(attempt.id,result,onResult)
   }.addOnFailureListener{if(attempts.complete(attempt.id))onResult("AUTHORIZATION_FAILED")}
  }catch(_:Exception){if(attempts.complete(attempt.id))onResult("AUTHORIZATION_FAILED")}
 }
 fun consumeResolution(data:Intent?,onResult:(String)->Unit){
  if(!BuildConfig.GMAIL_ENABLED){onResult("AUTHORIZATION_CANCELLED");return}
  val attempt=attempts.acquireResolution() ?: run{onResult("AUTHORIZATION_STALE_RESULT");return}
  if(data==null){attempts.complete(attempt.id);onResult("AUTHORIZATION_CANCELLED");return}
  try{finish(attempt.id,Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data),onResult)}
  catch(_:Exception){if(attempts.complete(attempt.id))onResult("AUTHORIZATION_FAILED")}
 }
 private fun finish(attemptId:Long,result:AuthorizationResult,onResult:(String)->Unit){
  val epoch=attempts.epoch(attemptId) ?: return
  val request=attempts.claimResult(attemptId) ?: return
  when(val verification=verifyGmailSession(result,request)){
   is GmailSessionVerification.Rejected->{if(attempts.complete(attemptId))onResult(verification.failure.code)}
   is GmailSessionVerification.Accepted->{
    val session=verification.session
    var stale=false
    val committed=try{attempts.commit(attemptId){
     stale=!rememberAuthorizedAccount(activity,epoch,session)
    }}catch(_:Exception){onResult("AUTHORIZATION_FAILED");return}
    if(committed)onResult(if(stale)"AUTHORIZATION_STALE_RESULT" else "AUTHORIZED_OWN_ACCOUNT")
   }
  }
 }
}

/** Reuses only the previously granted, bound account. A new consent resolution is never launched here. */
class GoogleGmailSessionProvider(private val context:Context):GmailSessionProvider {
 suspend fun invalidateRejectedToken(session:AuthorizedGmailSession){
  GmailSessionVault.forgetExpiredToken()
  if(!BuildConfig.GMAIL_ENABLED)return
  suspendCancellableCoroutine<Unit>{continuation->
   Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(session.accessToken).build())
    .addOnCompleteListener{if(continuation.isActive)continuation.resume(Unit)}
  }
 }
 override suspend fun session():AuthorizedGmailSession? {
  if(!BuildConfig.GMAIL_ENABLED)return null
  val ownEmail=context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("gmailOwnEmail",null) ?: return null
  val epoch=context.getSharedPreferences("settings",Context.MODE_PRIVATE).getLong("gmailAccountEpoch",0)
  if(!isSafeEmail(ownEmail))return null
  GmailSessionVault.session()?.let{if(it.ownEmail.equals(ownEmail,ignoreCase=true))return it else GmailSessionVault.forgetExpiredToken()}
  val request=gmailAuthorizationRequest(ownEmail)
  val result=suspendCancellableCoroutine<AuthorizationResult?> {continuation->
   Identity.getAuthorizationClient(context).authorize(request)
    .addOnSuccessListener{if(continuation.isActive)continuation.resume(it)}
    .addOnFailureListener{if(continuation.isActive)continuation.resume(null)}
  } ?: return null
  val session=(verifyGmailSession(result,request) as? GmailSessionVerification.Accepted)?.session ?: return null
  return synchronized(AccountBinding.lock){
   val preferences=context.getSharedPreferences("settings",Context.MODE_PRIVATE)
   if(preferences.getLong("gmailAccountEpoch",0)!=epoch||preferences.getString("gmailOwnEmail",null)?.equals(ownEmail,true)!=true)null
   else{GmailSessionVault.remember(session);session}
  }
 }
}
