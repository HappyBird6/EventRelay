package dev.eventrelay.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Only isolated in-memory rows and synthetic accounts; no Google or HTTP calls. */
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class QueuePolicyTest {
 private lateinit var context:Context
 private lateinit var db:RelayDb
 private lateinit var policy:QueuePolicy
 private val own="owner@example.test"
 private val rule=StoredRule("rule-a","Fake rule","SMS","FAKE-SENDER","[]")
 @Before fun setup()=runBlocking {
  context=RuntimeEnvironment.getApplication()
  context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().clear().putString("gmailOwnEmail",own)
   .putBoolean("gmailDeliveryEnabled",true).putLong("gmailDeliveryEnabledSince",0).commit()
  db=Room.inMemoryDatabaseBuilder(context,RelayDb::class.java).allowMainThreadQueries().build()
  policy=QueuePolicy(context,db);db.dao().saveRule(rule)
 }
 @After fun close(){db.close();GmailSessionVault.forgetExpiredToken();context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().clear().commit()}
 private fun event(id:String,status:String="PENDING",source:String="FAKE-SENDER",ids:List<String> = listOf(rule.id),epoch:Long=0)=
  QueuedEvent(id,"SMS",source,100,"FAKE ORIGINAL BODY",status,destination=own,ruleIds=JSONArray(ids).toString(),accountEpoch=epoch)
 @Test fun disableHoldsOnlyAffectedRowsIncludingLegacyAndNeverRevivesOnEnable()=runBlocking {
  listOf(event("matching"),event("legacy",ids=emptyList()),event("unrelated",source="OTHER",ids=listOf("other-rule")),
   event("completed","ACCEPTED"),event("uncertain","OUTCOME_UNKNOWN")).forEach{db.dao().enqueue(it)}
  val impact=policy.ruleImpact(rule);assertEquals(setOf("matching","legacy","uncertain"),impact.ids)
  assertTrue(policy.disableRule(rule,impact))
  assertEquals("BLOCKED_RULE",db.dao().event("matching")!!.status);assertEquals("BLOCKED_RULE",db.dao().event("legacy")!!.status)
  assertEquals("PENDING",db.dao().event("unrelated")!!.status);assertEquals("ACCEPTED",db.dao().event("completed")!!.status)
  assertEquals("OUTCOME_UNKNOWN",db.dao().event("uncertain")!!.status)
  policy.enableRule(rule)
  assertEquals("BLOCKED_RULE",db.dao().event("matching")!!.status)
  assertTrue(db.dao().activeRules().single().enabledSince>100)
  assertEquals("FAKE ORIGINAL BODY",db.dao().payload("matching"))
 }
 @Test fun aDisabledMatchingRuleStopsEvenAnEventAlsoMatchedByAnotherRule()=runBlocking {
  db.dao().saveRule(rule.copy(id="rule-b"));db.dao().enqueue(event("both",ids=listOf("rule-a","rule-b")))
  assertTrue(policy.disableRule(rule,policy.ruleImpact(rule)))
  assertEquals("BLOCKED_RULE",db.dao().event("both")!!.status)
  assertTrue(db.dao().activeRules().any{it.id=="rule-b"})
 }
 @Test fun disconnectPreventsOldEpochCallbackAndOldQueueFromBindingToNewAccount()=runBlocking {
  db.dao().enqueue(event("old"));GmailSessionVault.remember(AuthorizedGmailSession(own,"fake-never-valid"))
  assertTrue(policy.disconnectAccount(policy.unfinishedImpact()))
  val preferences=context.getSharedPreferences("settings",Context.MODE_PRIVATE)
  assertNull(preferences.getString("gmailOwnEmail",null));assertFalse(gmailDeliveryEnabled(context));assertNull(GmailSessionVault.session())
  assertEquals(1L,preferences.getLong("gmailAccountEpoch",0))
  assertFalse(rememberAuthorizedAccount(context,0,AuthorizedGmailSession(own,"fake-stale-token")))
  assertTrue(rememberAuthorizedAccount(context,1,AuthorizedGmailSession("new@example.test","fake-new-token")))
  assertFalse(gmailDeliveryEnabled(context))
  if(BuildConfig.GMAIL_ENABLED){assertTrue(policy.enableFutureDelivery());assertEquals("BLOCKED_ACCOUNT_BINDING",policy.holdReason(db.dao().event("old")!!)!!.status)}
  assertEquals("BLOCKED_ACCOUNT_BINDING",db.dao().event("old")!!.status)
 }
 @Test fun reconnectOnlyPausesAndEnablingFutureDeliveryDoesNotUnblockHistory()=runBlocking {
  db.dao().enqueue(event("old"));policy.pauseForAuthorization()
  assertFalse(gmailDeliveryEnabled(context));assertEquals("BLOCKED_ACCOUNT_BINDING",db.dao().event("old")!!.status)
  assertTrue(rememberAuthorizedAccount(context,0,AuthorizedGmailSession(own,"fake-token")))
  assertFalse(gmailDeliveryEnabled(context))
  if(BuildConfig.GMAIL_ENABLED)assertTrue(policy.enableFutureDelivery())
  assertTrue(db.dao().pending().isEmpty());assertEquals("FAKE ORIGINAL BODY",db.dao().payload("old"))
 }
 @Test fun deletionRejectsChangedExactSetEvenWithTheSameCount()=runBlocking {
  db.dao().enqueue(event("first"));val preview=policy.unfinishedImpact()
  db.dao().deleteUnfinished(listOf("first"));db.dao().enqueue(event("replacement"))
  assertEquals(preview.count,policy.unfinishedImpact().count)
  assertFalse(policy.deleteUnfinished(preview));assertNotNull(db.dao().event("replacement"))
 }
 @Test fun confirmedDeleteRemovesOnlyPreviewedUnfinishedAndKeepsTerminalHistory()=runBlocking {
  listOf(event("held","BLOCKED_RULE"),event("unknown","OUTCOME_UNKNOWN"),event("done","ACCEPTED"),event("failed","PERMANENT_FAILURE"))
   .forEach{db.dao().enqueue(it)}
  val preview=policy.unfinishedImpact();assertEquals(2,preview.count)
  assertTrue(policy.deleteUnfinished(preview))
  assertNull(db.dao().payload("held"));assertNull(db.dao().payload("unknown"))
  assertNotNull(db.dao().event("done"));assertNotNull(db.dao().event("failed"))
 }
 @Test fun confirmedDeletionHandlesMoreThanLegacySQLiteBindingLimit()=runBlocking {
  db.withTransaction{repeat(1003){db.dao().enqueue(event("bulk-"+it,"BLOCKED_RULE"))}}
  val preview=policy.unfinishedImpact();assertEquals(1003,preview.count)
  assertTrue(policy.deleteUnfinished(preview));assertEquals(0,db.dao().activeCount())
 }
 @Test fun finalSubmissionGuardChecksCurrentRulesEpochAndPausedStatus()=runBlocking {
  db.dao().enqueue(event("flight","SENDING"))
  val session=AuthorizedGmailSession(own,"fake-token")
  if(BuildConfig.GMAIL_ENABLED)assertTrue(policy.submissionAllowed("flight",session))
  db.dao().setRuleEnabled(rule.id,false,0)
  assertFalse(policy.submissionAllowed("flight",session))
  assertEquals(if(BuildConfig.GMAIL_ENABLED)"BLOCKED_RULE"else"BLOCKED_DELIVERY",db.dao().event("flight")!!.status)
  db.dao().settleIfSending("flight","RETRYABLE_FAILURE","FAKE_RETRY")
  assertNotEquals("RETRYABLE_FAILURE",db.dao().event("flight")!!.status)
 }
 @Test fun gateRejectsMissingProvenanceAndDeletedRows()=runBlocking {
  db.dao().enqueue(event("legacy","SENDING",ids=emptyList()))
  assertFalse(policy.submissionAllowed("missing",AuthorizedGmailSession(own,"fake-token")))
  assertFalse(policy.submissionAllowed("legacy",AuthorizedGmailSession(own,"fake-token")))
  assertEquals(if(BuildConfig.GMAIL_ENABLED)"BLOCKED_POLICY"else"BLOCKED_DELIVERY",db.dao().event("legacy")!!.status)
 }
 @Test fun accountOnlyAcceptanceNeverEnablesDeliveryAndPausedCollectionCannotEnableIt()=runBlocking {
  assertTrue(rememberAuthorizedAccount(context,0,AuthorizedGmailSession(own,"fake-token")))
  assertFalse(gmailDeliveryEnabled(context))
  context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putBoolean("collectionPaused",true).commit()
  assertFalse(policy.enableFutureDelivery());assertFalse(gmailDeliveryEnabled(context))
 }
}
