package dev.eventrelay.app

import androidx.core.app.NotificationCompat
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class SmsCollector:BroadcastReceiver(){
 override fun onReceive(context:Context,intent:Intent){
  if(intent.action!=Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
  val pending=goAsync()
  RelayRuntime.scope.launch {
   try {
    val messages=Telephony.Sms.Intents.getMessagesFromIntent(intent)
    RelayRuntime.initialize(context)
    if(messages.isNotEmpty()) {
     val sender=messages.first().originatingAddress ?: return@launch
     val body=messages.joinToString(""){it.messageBody.orEmpty()}
     @Suppress("DEPRECATION")
     val raw=(intent.extras?.get("pdus") as? Array<*>)?.filterIsInstance<ByteArray>()?.flatMap{it.toList()}?.toByteArray() ?: return@launch
     val record=MessageDigest.getInstance("SHA-256").digest(raw).joinToString(""){"%02x".format(it)}
     RelayRuntime.collect(context,"SMS",sender,record,messages.first().timestampMillis,null,body,JSONObject().put("sender",sender).put("body",body).put("partCount",messages.size))
    }
   } catch(cancelled:CancellationException){throw cancelled} catch(_:Exception){RelayRuntime.recordCollectorFailure(context)} finally {pending.finish()}
  }
 }
}
class NotificationCollector:NotificationListenerService(){
 override fun onNotificationPosted(sbn:StatusBarNotification){
  try {
  if(sbn.packageName==packageName || sbn.packageName=="com.google.android.gm") return
  val n=sbn.notification; val extras=n.extras
  val title=extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
  val text=extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
  val big=extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
  val lines=extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map{it.toString()}.orEmpty()
  val style=NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
  val messages=style?.messages.orEmpty()
  val historic=style?.historicMessages.orEmpty()
  val original=JSONObject().put("packageName",sbn.packageName).put("title",title).put("text",text).put("bigText",big).put("textLines",JSONArray(lines))
  for(key in listOf(Notification.EXTRA_TITLE_BIG,Notification.EXTRA_SUB_TEXT,Notification.EXTRA_SUMMARY_TEXT,Notification.EXTRA_INFO_TEXT)) {
   extras.getCharSequence(key)?.let{original.put(key,it.toString())}
  }
  original.put("messages",JSONArray().apply{messages.forEach{put(JSONObject().put("text",it.text?.toString().orEmpty()).put("timestamp",it.timestamp).put("sender",it.person?.name?.toString().orEmpty()))}})
  style?.conversationTitle?.let{original.put("conversationTitle",it.toString())}
  original.put("historicMessages",JSONArray().apply{historic.forEach{put(JSONObject().put("text",it.text?.toString().orEmpty()).put("timestamp",it.timestamp).put("sender",it.person?.name?.toString().orEmpty()))}})
  val appName=try{packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName,0)).toString()}catch(_:PackageManager.NameNotFoundException){null}
  appName?.let{original.put("appName",it)}
  // Search body includes every public text representation; originals keep exact separate fields.
  val body=(listOf(text,big)+lines+messages.map{it.text?.toString().orEmpty()}+historic.map{it.text?.toString().orEmpty()}).filter{it.isNotEmpty()}.distinct().joinToString("\n")
  RelayRuntime.scope.launch {try { RelayRuntime.initialize(applicationContext); RelayRuntime.collect(applicationContext,"NOTIFICATION",sbn.packageName,sbn.key,sbn.postTime,title,body,original) } catch(cancelled:CancellationException){throw cancelled} catch(_:Exception){RelayRuntime.recordCollectorFailure(applicationContext)} }
  } catch(_:Exception){RelayRuntime.recordCollectorFailure(applicationContext)}
 }
}
