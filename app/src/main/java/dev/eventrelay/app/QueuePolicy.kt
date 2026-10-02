package dev.eventrelay.app

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

internal data class QueueImpact(val ids:Set<String>) { val count:Int get()=ids.size }
internal data class QueueHold(val status:String,val reason:String)
internal object AccountBinding { val lock=Any() }

internal fun rememberAuthorizedAccount(context:Context,epoch:Long,session:AuthorizedGmailSession):Boolean=synchronized(AccountBinding.lock){
    val preferences=context.getSharedPreferences("settings",Context.MODE_PRIVATE)
    val saved=preferences.getString("gmailOwnEmail",null)
    if(preferences.getLong("gmailAccountEpoch",0)!=epoch||(saved!=null&&!saved.equals(session.ownEmail,true)))return@synchronized false
    require(preferences.edit().putString("gmailOwnEmail",session.ownEmail).putBoolean("gmailDeliveryEnabled",false).commit())
    GmailSessionVault.remember(session)
    true
}

/** Every irreversible action rechecks the exact preview set under the same submission gate. */
internal class QueuePolicy(private val context:Context,private val db:RelayDb=RelayRuntime.db) {
    private val preferences get()=context.getSharedPreferences("settings",Context.MODE_PRIVATE)
    suspend fun unfinishedImpact()=QueueImpact(db.dao().unfinishedIds().toSet())
    suspend fun ruleImpact(rule:StoredRule)=QueueImpact(db.dao().unfinishedRuleReferences().filter{affectedBy(it,rule)}.map{it.id}.toSet())

    suspend fun disableRule(rule:StoredRule,preview:QueueImpact):Boolean=RelayRuntime.policyMutex.withLock {
        db.withTransaction {
            if(ruleImpact(rule)!=preview) return@withTransaction false
            db.dao().setRuleEnabled(rule.id,false,rule.enabledSince)
            for(event in db.dao().unfinishedRuleReferences().filter{affectedBy(it,rule)}) db.dao().hold(event.id,"BLOCKED_RULE","RULE_DISABLED")
            true
        }
    }
    suspend fun enableRule(rule:StoredRule)=RelayRuntime.policyMutex.withLock {
        // No queue transition: reconnecting/re-enabling never revives an old item.
        db.dao().setRuleEnabled(rule.id,true,System.currentTimeMillis())
    }
    suspend fun pauseForAuthorization()=RelayRuntime.policyMutex.withLock {
        db.withTransaction {
            require(preferences.edit().putBoolean("gmailDeliveryEnabled",false).commit())
            for(id in db.dao().unfinishedIds()) db.dao().hold(id,"BLOCKED_ACCOUNT_BINDING","RECONNECT_HELD")
        }
    }
    suspend fun disconnectAccount(preview:QueueImpact):Boolean=RelayRuntime.policyMutex.withLock {
        db.withTransaction {
            if(unfinishedImpact()!=preview) return@withTransaction false
            synchronized(AccountBinding.lock){
                val epoch=preferences.getLong("gmailAccountEpoch",0)
                require(epoch<Long.MAX_VALUE)
                require(preferences.edit().putBoolean("gmailDeliveryEnabled",false).putLong("gmailAccountEpoch",epoch+1)
                    .remove("gmailOwnEmail").commit())
                GmailSessionVault.forgetExpiredToken()
            }
            for(id in db.dao().unfinishedIds()) db.dao().hold(id,"BLOCKED_ACCOUNT_BINDING","ACCOUNT_REMOVED")
            true
        }
    }
    suspend fun deleteUnfinished(preview:QueueImpact):Boolean=RelayRuntime.policyMutex.withLock {
        db.withTransaction {
            if(unfinishedImpact()!=preview) return@withTransaction false
            // Older Android SQLite versions permit only 999 bound variables per statement.
            for(batch in preview.ids.toList().chunked(500)) db.dao().deleteUnfinished(batch)
            true
        }
    }
    suspend fun stopDelivery()=RelayRuntime.policyMutex.withLock {
        db.withTransaction {
            require(preferences.edit().putBoolean("gmailDeliveryEnabled",false).commit())
            for(id in db.dao().unfinishedIds()) db.dao().hold(id,"BLOCKED_DELIVERY","DELIVERY_DISABLED")
        }
    }
    suspend fun enableFutureDelivery():Boolean=RelayRuntime.policyMutex.withLock {
        db.withTransaction {
            if(!BuildConfig.GMAIL_ENABLED||preferences.getBoolean("collectionPaused",false)||preferences.getString("gmailOwnEmail",null)?.let(::isSafeEmail)!=true) return@withTransaction false
            for(id in db.dao().pendingIds()) db.dao().hold(id,"BLOCKED_DELIVERY","HISTORICAL_QUEUE_HELD")
            require(preferences.edit().putLong("gmailDeliveryEnabledSince",System.currentTimeMillis()).putBoolean("gmailDeliveryEnabled",true).commit())
            true
        }
    }

    /** Called immediately before transport starts; rule/account decisions share one linearization point. */
    suspend fun submissionAllowed(id:String,session:AuthorizedGmailSession):Boolean=RelayRuntime.policyMutex.withLock {
        db.withTransaction {
            val event=db.dao().event(id) ?: return@withTransaction false
            if(event.status!="SENDING") return@withTransaction false
            val hold=holdReason(event,session)
            if(hold!=null){db.dao().hold(id,hold.status,hold.reason);false}else true
        }
    }
    suspend fun holdReason(event:QueuedEvent,session:AuthorizedGmailSession?=null):QueueHold? {
        val ownEmail=preferences.getString("gmailOwnEmail",null)
        if(event.accountEpoch!=preferences.getLong("gmailAccountEpoch",0)||
            event.destination==null||ownEmail==null||!event.destination.equals(ownEmail,true)||
            (session!=null&&!event.destination.equals(session.ownEmail,true)))
            return QueueHold("BLOCKED_ACCOUNT_BINDING","ACCOUNT_BINDING_CHANGED")
        if(!gmailDeliveryEligible(context,event.observedAt)) return QueueHold("BLOCKED_DELIVERY","DELIVERY_DISABLED")
        val ids=ruleIds(event)
        if(ids.isEmpty()) return QueueHold("BLOCKED_POLICY","RULE_PROVENANCE_MISSING")
        val active=db.dao().activeRules().associateBy{it.id}
        if(ids.any{active[it]?.let{rule->rule.channel==event.channel&&rule.source==event.source&&event.observedAt>=rule.enabledSince}!=true})
            return QueueHold("BLOCKED_RULE","RULE_DISABLED")
        return null
    }
}

internal fun ruleIds(event:QueuedEvent):Set<String> = parseRuleIds(event.ruleIds)
private fun parseRuleIds(raw:String):Set<String> = try {
    val values=JSONArray(raw)
    (0 until values.length()).map{values.getString(it)}.toSet()
}catch(_:Exception){emptySet()}

private fun affectedBy(event:QueueRuleReference,rule:StoredRule):Boolean {
    val ids=parseRuleIds(event.ruleIds)
    // Old versions have no rule provenance: hold all same-source items conservatively.
    return rule.id in ids || (ids.isEmpty()&&event.channel==rule.channel&&event.source==rule.source)
}
