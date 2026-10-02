package dev.eventrelay.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import dev.eventrelay.core.EventIdentity

/** Ephemeral only. Never persist, log, include in events, or expose via data-class toString. */
class AuthorizedGmailSession(val ownEmail:String,val accessToken:String) {
 init { require(isSafeEmail(ownEmail));require(accessToken.isNotBlank()&&!accessToken.contains('\r')&&!accessToken.contains('\n')) }
 override fun toString()="AuthorizedGmailSession(accessToken=<redacted>)"
}
fun interface GmailSessionProvider { suspend fun session():AuthorizedGmailSession? }
data class GmailHttpResponse(val status:Int,val body:String,val retryAfterSeconds:Long?=null)
fun interface GmailTransport { suspend fun post(accessToken:String,jsonBody:String):GmailHttpResponse }

/** This adapter is not reachable with credentials in the shipped development build. */
class HttpsGmailTransport:GmailTransport {
 override suspend fun post(accessToken:String,jsonBody:String):GmailHttpResponse=withContext(Dispatchers.IO){
  val connection=URL("https://gmail.googleapis.com/gmail/v1/users/me/messages/send").openConnection() as HttpURLConnection
  try {
   connection.requestMethod="POST";connection.instanceFollowRedirects=false
   connection.connectTimeout=15_000;connection.readTimeout=30_000;connection.doOutput=true
   connection.setRequestProperty("Authorization","Bearer $accessToken")
   connection.setRequestProperty("Content-Type","application/json; charset=UTF-8")
   val bytes=jsonBody.toByteArray(Charsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.size)
   connection.outputStream.use{it.write(bytes)}
   val status=connection.responseCode
   val stream=if(status in 200..299)connection.inputStream else connection.errorStream
   // Gmail response JSON is bounded; the event and outgoing MIME are never truncated.
   val response=stream?.use{input->
    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true){val count=input.read(buffer);if(count<0)break;require(output.size()+count<=1_048_576);output.write(buffer,0,count)}
    output.toString("UTF-8")
   }.orEmpty()
   GmailHttpResponse(status,response,connection.getHeaderField("Retry-After")?.toLongOrNull())
  } finally {connection.disconnect()}
 }
}
class GmailApiGateway(private val sessions:GmailSessionProvider,private val transport:GmailTransport,private val maySubmit:suspend()->Boolean={true}):GmailGateway {
 override suspend fun sendToOwnAccount(eventId:String,structuredPayload:String):SendOutcome {
  val session=try{sessions.session()}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}catch(_:Exception){null} ?: return SendOutcome.AuthorizationRequired
  val request=try {createGmailRequest(eventId,structuredPayload,session.ownEmail)}catch(_:IllegalArgumentException){return SendOutcome.Permanent("INVALID_PAYLOAD")}catch(_:org.json.JSONException){return SendOutcome.Permanent("INVALID_PAYLOAD")}
  if(!maySubmit())return SendOutcome.PolicyHeld
  val response=try{transport.post(session.accessToken,request)}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}catch(_:Exception){return SendOutcome.Unknown("SUBMISSION_UNKNOWN")}
  return when(response.status){
   in 200..299->{val id=try{(JSONObject(response.body).opt("id") as? String).orEmpty()}catch(_:org.json.JSONException){""};if(!Regex("^[A-Za-z0-9_-]{1,256}$").matches(id))SendOutcome.Unknown("ACCEPTANCE_UNCONFIRMED")else SendOutcome.Accepted(id)}
   401->SendOutcome.AuthorizationRequired
   429->SendOutcome.Retryable("RATE_LIMITED",response.retryAfterSeconds?.takeIf{it>=0}?.let{if(it>Long.MAX_VALUE/1000)Long.MAX_VALUE else it*1000})
   in 500..599->SendOutcome.Unknown("SERVER_ACCEPTANCE_UNKNOWN")
   403->if(isRateLimitRejection(response.body))SendOutcome.Retryable("RATE_LIMITED",response.retryAfterSeconds?.takeIf{it>=0}?.let{if(it>Long.MAX_VALUE/1000)Long.MAX_VALUE else it*1000}) else SendOutcome.Permanent("GMAIL_REJECTED_403")
   400,404,413->SendOutcome.Permanent("GMAIL_REJECTED_${response.status}")
   else->SendOutcome.Unknown("UNEXPECTED_RESPONSE")
  }
 }
}
internal fun isSafeEmail(email:String)=email.length<=254&&Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$").matches(email)
internal fun createGmailRequest(eventId:String,payload:String,ownEmail:String):String {
 require(eventId.isNotBlank()&&isSafeEmail(ownEmail))
 val json=JSONObject(payload)
 require(json.getString("schema")=="eventrelay.event.v1"&&json.getString("eventId")==eventId)
 val kind=json.getString("kind");require(kind=="SMS"||kind=="NOTIFICATION")
 val external=externalMailPayload(json).toString(2)
 val encodedBody=Base64.getMimeEncoder(76,"\r\n".toByteArray()).encodeToString(external.toByteArray(Charsets.UTF_8))
 val mime="From: $ownEmail\r\nTo: $ownEmail\r\nMessage-ID: ${EventIdentity.messageId(eventId,ownEmail)}\r\nSubject: [EventRelay] $kind\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n$encodedBody\r\n"
 return JSONObject().put("raw",Base64.getUrlEncoder().withoutPadding().encodeToString(mime.toByteArray(Charsets.UTF_8))).toString()
}

private fun isRateLimitRejection(body:String):Boolean=try {
 val errors=JSONObject(body).optJSONObject("error")?.optJSONArray("errors")
 errors!=null&&(0 until errors.length()).any{errors.optJSONObject(it)?.optString("reason") in setOf("rateLimitExceeded","userRateLimitExceeded")}
}catch(_:org.json.JSONException){false}
