package dev.eventrelay.app

import androidx.core.content.edit
import dev.eventrelay.core.*
import android.app.Application
import android.content.Context
import androidx.room.*
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Entity(tableName="rules")
data class StoredRule(@PrimaryKey val id:String, val name:String, val channel:String, val source:String, val conditions:String, val enabled:Boolean=true, @ColumnInfo(defaultValue="0") val enabledSince:Long=0, @ColumnInfo(defaultValue="NULL") val sourceLabel:String?=null)
@Entity(tableName="queue")
data class QueuedEvent(@PrimaryKey val id:String, val channel:String, val source:String, val observedAt:Long, val payload:String, val status:String="BLOCKED_AUTH", val attempts:Int=0, val lastError:String?=null, val completedAt:Long?=null, val gmailMessageId:String?=null,val destination:String?=null,val nextAttemptAt:Long=0, @ColumnInfo(defaultValue="'[]'") val ruleIds:String="[]", @ColumnInfo(defaultValue="0") val accountEpoch:Long=0, @ColumnInfo(defaultValue="NULL") val sourceLabel:String?=null)
data class QueueSummary(val id:String,val channel:String,val source:String,val observedAt:Long,val status:String,val sourceLabel:String?=null)
data class QueueCounts(val unfinished:Int,val accepted:Int,val unknown:Int)
data class QueueRuleReference(val id:String,val channel:String,val source:String,val ruleIds:String)
@Dao interface RelayDao {
 @Query("SELECT * FROM rules ORDER BY name") fun watchRules():Flow<List<StoredRule>>
 @Query("SELECT * FROM rules WHERE enabled=1") suspend fun activeRules():List<StoredRule>
 @Query("SELECT * FROM rules") suspend fun allRules():List<StoredRule>
 @Query("UPDATE rules SET enabled=:enabled,enabledSince=:since WHERE id=:id") suspend fun setRuleEnabled(id:String,enabled:Boolean,since:Long)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveRule(rule:StoredRule)
 @Query("SELECT id,channel,source,observedAt,status,sourceLabel FROM queue ORDER BY observedAt DESC LIMIT 100") fun watchQueue():Flow<List<QueueSummary>>
 @Query("SELECT payload FROM queue WHERE id=:id") suspend fun payload(id:String):String?
 @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun enqueue(event:QueuedEvent):Long
 @Query("SELECT * FROM queue WHERE status IN ('PENDING','RETRYABLE_FAILURE')") suspend fun pending():List<QueuedEvent>
 @Query("UPDATE queue SET status='SENDING',attempts=attempts+1 WHERE id=:id AND status IN ('PENDING','RETRYABLE_FAILURE')") suspend fun claim(id:String):Int
 @Query("UPDATE queue SET status='OUTCOME_UNKNOWN',lastError='PROCESS_INTERRUPTED' WHERE status='SENDING'") suspend fun recoverInterrupted()
 @Query("UPDATE queue SET status='OUTCOME_UNKNOWN',lastError='WORK_INTERRUPTED' WHERE id=:id AND status='SENDING'") suspend fun unknownIfSending(id:String)
 @Query("UPDATE queue SET status=:status,lastError=:error,completedAt=:completedAt,gmailMessageId=:gmailMessageId,nextAttemptAt=:nextAttemptAt WHERE id=:id") suspend fun status(id:String,status:String,error:String?,completedAt:Long?=null,gmailMessageId:String?=null,nextAttemptAt:Long=0)
 @Query("DELETE FROM queue WHERE status IN ('ACCEPTED','PERMANENT_FAILURE') AND completedAt IS NOT NULL AND completedAt<:cutoff") suspend fun purgeCompleted(cutoff:Long)
 @Query("UPDATE queue SET status='PENDING',lastError=NULL WHERE status='BLOCKED_AUTH' AND destination=:ownEmail") suspend fun unblockOwnAccount(ownEmail:String)
 @Query("SELECT COUNT(*) FROM queue WHERE status NOT IN ('ACCEPTED','PERMANENT_FAILURE')") suspend fun activeCount():Int
 @Query("SELECT COUNT(CASE WHEN status NOT IN ('ACCEPTED','PERMANENT_FAILURE') THEN 1 END) AS unfinished,COUNT(CASE WHEN status='ACCEPTED' THEN 1 END) AS accepted,COUNT(CASE WHEN status='OUTCOME_UNKNOWN' THEN 1 END) AS unknown FROM queue") fun watchCounts():Flow<QueueCounts>
 @Query("SELECT * FROM queue WHERE status NOT IN ('ACCEPTED','PERMANENT_FAILURE')") suspend fun unfinished():List<QueuedEvent>
 @Query("SELECT id FROM queue WHERE status NOT IN ('ACCEPTED','PERMANENT_FAILURE')") suspend fun unfinishedIds():List<String>
 @Query("SELECT id,channel,source,ruleIds FROM queue WHERE status NOT IN ('ACCEPTED','PERMANENT_FAILURE')") suspend fun unfinishedRuleReferences():List<QueueRuleReference>
 @Query("SELECT id FROM queue WHERE status IN ('PENDING','RETRYABLE_FAILURE')") suspend fun pendingIds():List<String>
 @Query("SELECT * FROM queue WHERE id=:id") suspend fun event(id:String):QueuedEvent?
 @Query("UPDATE queue SET status=:status,lastError=:reason WHERE id=:id AND status NOT IN ('ACCEPTED','PERMANENT_FAILURE','OUTCOME_UNKNOWN')") suspend fun hold(id:String,status:String,reason:String)
 @Query("UPDATE queue SET status=:status,lastError=:error,completedAt=:completedAt,nextAttemptAt=:nextAttemptAt WHERE id=:id AND status='SENDING'") suspend fun settleIfSending(id:String,status:String,error:String?,completedAt:Long?=null,nextAttemptAt:Long=0)
 @Query("DELETE FROM queue WHERE id IN (:ids) AND status NOT IN ('ACCEPTED','PERMANENT_FAILURE')") suspend fun deleteUnfinished(ids:List<String>):Int
}
@Database(entities=[StoredRule::class,QueuedEvent::class],version=2,exportSchema=false)
abstract class RelayDb:RoomDatabase(){ abstract fun dao():RelayDao }
internal val RELAY_MIGRATION_1_2=object:Migration(1,2){
 override fun migrate(db:SupportSQLiteDatabase){
  db.execSQL("ALTER TABLE rules ADD COLUMN enabledSince INTEGER NOT NULL DEFAULT 0")
  db.execSQL("ALTER TABLE rules ADD COLUMN sourceLabel TEXT DEFAULT NULL")
  db.execSQL("ALTER TABLE queue ADD COLUMN ruleIds TEXT NOT NULL DEFAULT '[]'")
  db.execSQL("ALTER TABLE queue ADD COLUMN accountEpoch INTEGER NOT NULL DEFAULT 0")
  db.execSQL("ALTER TABLE queue ADD COLUMN sourceLabel TEXT DEFAULT NULL")
  db.execSQL("UPDATE queue SET status='BLOCKED_POLICY',lastError='LEGACY_QUEUE_HELD' WHERE status IN ('PENDING','RETRYABLE_FAILURE','SENDING')")
 }
}
class RelayApplication:Application(){ override fun onCreate(){super.onCreate(); RelayRuntime.initialize(this);RelayRuntime.scope.launch {RelayRuntime.db.dao().recoverInterrupted();RelayRuntime.schedule(this@RelayApplication)}
 WorkManager.getInstance(this).enqueueUniquePeriodicWork("eventrelay-retention",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<RetentionWorker>(24,TimeUnit.HOURS).build())
} }
object RelayRuntime {
 lateinit var db:RelayDb; private set
 val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 val policyMutex=Mutex()
 @Synchronized fun initialize(context:Context){if(!::db.isInitialized){
  db=Room.databaseBuilder(context.applicationContext,RelayDb::class.java,"eventrelay.db").addMigrations(RELAY_MIGRATION_1_2).build()
  val preferences=context.getSharedPreferences("settings",Context.MODE_PRIVATE)
  if(preferences.getInt("queuePolicyVersion",0)<2) preferences.edit().putBoolean("gmailDeliveryEnabled",false).putInt("queuePolicyVersion",2).commit()
 }}
 fun recordCollectorFailure(context:Context){context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit {putString("lastCollectorError","COLLECTION_FAILED")}}
 fun schedule(context:Context){
  val work=OneTimeWorkRequestBuilder<DeliveryWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
  WorkManager.getInstance(context).enqueueUniqueWork("eventrelay-delivery",ExistingWorkPolicy.APPEND_OR_REPLACE,work)
 }
 suspend fun collect(context:Context,channel:String,source:String,record:String,occurred:Long,title:String?,body:String,fields:JSONObject)=policyMutex.withLock {
  if(context.getSharedPreferences("settings",Context.MODE_PRIVATE).getBoolean("collectionPaused",false)) return@withLock
  val now=System.currentTimeMillis()
  val deliveryAllowedAtCollectionStart=gmailDeliveryEligible(context,now)
  fun value(v:Any?):EventValue = when(v){
   null,JSONObject.NULL->EventValue.Null
   is JSONObject->EventValue.Object(v.keys().asSequence().associateWith{value(v.get(it))})
   is org.json.JSONArray->EventValue.Array((0 until v.length()).map{value(v.get(it))})
   is Boolean->EventValue.Bool(v)
   is Number->EventValue.Number(v.toString())
   else->EventValue.Text(v.toString())
  }
  val event=RelayEvent(SourceKind.valueOf(channel),source,record,occurred,now,title,body,(value(fields) as EventValue.Object).fields)
  val rules=db.dao().activeRules().filter{it.channel==channel && it.source==source}
  val matched=rules.filter { rule ->
   try {
   val stored=org.json.JSONArray(rule.conditions)
   val conditions=mutableListOf(MatchCondition(MatchTarget.SOURCE_IDENTITY,MatchOperator.EQUALS,source,caseSensitive=true))
   for(i in 0 until stored.length()) {
    val c=stored.getJSONObject(i)
    val target=when(c.getString("field")){"sender","packageName"->MatchTarget.SOURCE_IDENTITY;"title"->MatchTarget.TITLE;"body","content"->MatchTarget.BODY;else->return@filter false}
    val operator=when(c.getString("operator")){"equals"->MatchOperator.EQUALS;"contains"->MatchOperator.CONTAINS;"not_contains"->MatchOperator.NOT_CONTAINS;else->return@filter false}
    conditions+=MatchCondition(target,operator,c.getString("value"))
   }
   PolicyEngine.evaluate(event,RelayPolicy(setOf(SourceSelection(event.kind,source)),conditions,context.packageName)) is PolicyDecision.Approved
   } catch(_:IllegalArgumentException){false} catch(_:org.json.JSONException){false}
  }
  if(matched.isEmpty()) return@withLock
  val payload=JSONObject(event.toJson()).put("schema","eventrelay.event.v1").put("eventId",event.eventId)
  val destination=if(BuildConfig.GMAIL_ENABLED)context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("gmailOwnEmail",null)?.takeIf{isSafeEmail(it)} else null
  val status=if(fields.optBoolean("synthetic",false)||!deliveryAllowedAtCollectionStart||!gmailDeliveryEligible(context,now))"BLOCKED_DELIVERY" else if(destination==null)"BLOCKED_AUTH" else "PENDING"
  val epoch=context.getSharedPreferences("settings",Context.MODE_PRIVATE).getLong("gmailAccountEpoch",0)
  val sourceLabel=if(channel=="NOTIFICATION")fields.optString("appName").takeIf{it.isNotBlank()}else null
  val inserted=db.dao().enqueue(QueuedEvent(event.eventId,channel,source,now,payload.toString(2),status,destination=destination,ruleIds=org.json.JSONArray(matched.map{it.id}).toString(),accountEpoch=epoch,sourceLabel=sourceLabel))!=-1L
  if(inserted&&!fields.optBoolean("synthetic",false)) schedule(context)
 }
}
class DeliveryWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
 override suspend fun doWork():Result {
  RelayRuntime.initialize(applicationContext)
  // Default-off delivery stops before token refresh or mail submission.
  if(!gmailDeliveryEnabled(applicationContext)) {
   for(event in RelayRuntime.db.dao().pending()) if(RelayRuntime.db.dao().claim(event.id)==1) RelayRuntime.db.dao().status(event.id,"BLOCKED_DELIVERY","DELIVERY_APPROVAL_REQUIRED")
   return Result.success()
  }
  if(applicationContext.getSharedPreferences("settings",Context.MODE_PRIVATE).getBoolean("collectionPaused",false)) return Result.success()
  val provider=GoogleGmailSessionProvider(applicationContext)
  val policy=QueuePolicy(applicationContext)
  var retry=false
  for(event in RelayRuntime.db.dao().pending()) {
   if(event.nextAttemptAt>System.currentTimeMillis()){retry=true;continue}
   if(RelayRuntime.db.dao().claim(event.id)!=1) continue
   try {
   if(!gmailDeliveryEligible(applicationContext,event.observedAt)){RelayRuntime.db.dao().status(event.id,"BLOCKED_DELIVERY","DELIVERY_DISABLED");continue}
   val initialHold=policy.holdReason(event)
   if(initialHold!=null){RelayRuntime.db.dao().hold(event.id,initialHold.status,initialHold.reason);continue}
   val boundSession=provider.session()
   if(boundSession==null){RelayRuntime.db.dao().status(event.id,"BLOCKED_AUTH","AUTH_REQUIRED");continue}
   if(event.destination==null||!event.destination.equals(boundSession.ownEmail,ignoreCase=true)){RelayRuntime.db.dao().status(event.id,"BLOCKED_ACCOUNT_BINDING","ACCOUNT_BINDING_POLICY_PENDING");continue}
   val gateway:GmailGateway=GmailApiGateway(GmailSessionProvider{boundSession},HttpsGmailTransport()){policy.submissionAllowed(event.id,boundSession)}
   val outcome=try { gateway.sendToOwnAccount(event.id,event.payload) } catch(cancelled:CancellationException){throw cancelled} catch(_:Exception){ SendOutcome.Unknown("SUBMISSION_UNKNOWN") }
   when(outcome){
    SendOutcome.PolicyHeld->Unit
    SendOutcome.AuthorizationRequired->{provider.invalidateRejectedToken(boundSession);RelayRuntime.db.dao().settleIfSending(event.id,"BLOCKED_AUTH","AUTH_REQUIRED")}
    is SendOutcome.Accepted->RelayRuntime.db.dao().status(event.id,"ACCEPTED",null,System.currentTimeMillis(),outcome.gmailMessageId)
    is SendOutcome.Retryable->{
     if(event.attempts>=4) RelayRuntime.db.dao().settleIfSending(event.id,"PERMANENT_FAILURE","RETRY_LIMIT",System.currentTimeMillis())
     else {
      val now=System.currentTimeMillis()
      val delay=outcome.retryAfterMillis ?: (30_000L shl event.attempts.coerceAtMost(4))
      val due=if(delay>Long.MAX_VALUE-now)Long.MAX_VALUE else now+delay
      RelayRuntime.db.dao().settleIfSending(event.id,"RETRYABLE_FAILURE",outcome.code,nextAttemptAt=due);retry=true
     }
    }
    is SendOutcome.Permanent->RelayRuntime.db.dao().settleIfSending(event.id,"PERMANENT_FAILURE",outcome.code,System.currentTimeMillis())
    is SendOutcome.Unknown->RelayRuntime.db.dao().status(event.id,"OUTCOME_UNKNOWN",outcome.code)
   }
   } catch(cancelled:CancellationException){withContext(NonCancellable){RelayRuntime.db.dao().unknownIfSending(event.id)};throw cancelled} catch(_:Exception){RelayRuntime.db.dao().unknownIfSending(event.id)}
  }
  val days=applicationContext.getSharedPreferences("settings",Context.MODE_PRIVATE).getInt("retentionDays",7)
  RelayRuntime.db.dao().purgeCompleted(System.currentTimeMillis()-TimeUnit.DAYS.toMillis(days.toLong()))
  return if(retry) Result.retry() else Result.success()
 }
}

class RetentionWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
 override suspend fun doWork():Result {
  RelayRuntime.initialize(applicationContext)
  val days=applicationContext.getSharedPreferences("settings",Context.MODE_PRIVATE).getInt("retentionDays",7)
  RelayRuntime.db.dao().purgeCompleted(System.currentTimeMillis()-TimeUnit.DAYS.toMillis(days.toLong()))
  return Result.success()
 }
}

internal fun gmailDeliveryEnabled(context:Context):Boolean=BuildConfig.GMAIL_ENABLED&&context.getSharedPreferences("settings",Context.MODE_PRIVATE).getBoolean("gmailDeliveryEnabled",false)

internal fun gmailDeliveryEligible(context:Context,observedAt:Long):Boolean=gmailDeliveryEnabled(context)&&observedAt>context.getSharedPreferences("settings",Context.MODE_PRIVATE).getLong("gmailDeliveryEnabledSince",Long.MAX_VALUE)
