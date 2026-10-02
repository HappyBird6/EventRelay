package dev.eventrelay.app

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class MailPayloadTest {
 private fun event(body:String,originals:JSONObject)=JSONObject()
  .put("schema","eventrelay.event.v1").put("eventId","er1_synthetic_identifier")
  .put("kind","NOTIFICATION").put("sourceIdentity","dev.synthetic.app")
  .put("sourceRecordId","INTERNAL-RECORD-NOT-FOR-EMAIL").put("observedAtMillis",1700000001000)
  .put("observedAt","INTERNAL-RECEIPT-TIME").put("occurredAtMillis",1700000000000)
  .put("title","Synthetic title").put("body",body).put("originalFields",originals.put("appName","Synthetic App"))
 @Test fun equivalentBodyTextExpandedAndLinesAreExportedOnceWithoutInternalFields(){
  val original=event("Raw\nUnicode 한글",JSONObject().put("text","Raw\nUnicode 한글").put("bigText","Raw\nUnicode 한글")
   .put("textLines",JSONArray(listOf("Raw","Unicode 한글"))))
  val before=original.toString();val result=externalMailPayload(original)
  assertEquals(EXTERNAL_MAIL_SCHEMA,result.getString("schema"));assertEquals("Raw\nUnicode 한글",result.getString("body"))
  assertFalse(result.has("additionalText"));assertFalse(result.has("messages"))
  assertEquals("Synthetic App",result.getString("appName"));assertEquals("2023-11-14T22:13:20Z",result.getString("occurredAt"))
  listOf("sourceIdentity","sourceRecordId","observedAtMillis","observedAt","originalFields","kind").forEach{assertFalse(result.has(it))}
  assertFalse(result.toString().contains("INTERNAL-RECORD"));assertEquals(before,original.toString())
 }
 @Test fun repeatedMessagesKeepSenderTimeAndEquivalentAggregateIsRemoved(){
  val messages=JSONArray().put(JSONObject().put("text","Same message").put("sender","Fake A").put("timestamp",1700000000000))
   .put(JSONObject().put("text","Same message").put("sender","Fake B").put("timestamp",1700000000001))
  val result=externalMailPayload(event("Same message",JSONObject().put("text","Same message\nSame message").put("messages",messages)))
  assertFalse(result.has("body"));assertFalse(result.has("additionalText"));assertEquals(2,result.getJSONArray("messages").length())
  assertEquals("Fake A",result.getJSONArray("messages").getJSONObject(0).getString("sender"))
  assertEquals("Fake B",result.getJSONArray("messages").getJSONObject(1).getString("sender"))
  assertNotEquals(result.getJSONArray("messages").getJSONObject(0).getString("occurredAt"),result.getJSONArray("messages").getJSONObject(1).getString("occurredAt"))
 }
 @Test fun identicalOccurrencesAndHistoricOrderAreNotDeduplicated(){
  val message=JSONObject().put("text","Repeat").put("sender","Fake").put("timestamp",1700000000000)
  val result=externalMailPayload(event("Repeat",JSONObject().put("messages",JSONArray().put(message).put(message))
   .put("historicMessages",JSONArray().put(JSONObject().put("text","Historic").put("timestamp",1699999999000)))))
  val exported=result.getJSONArray("messages")
  assertEquals(3,exported.length());assertEquals("Repeat",exported.getJSONObject(1).getString("text"))
  assertEquals("Historic",exported.getJSONObject(2).getString("text"));assertTrue(exported.getJSONObject(2).getBoolean("historical"))
 }
 @Test fun partialAndWhitespaceDifferentRepresentationsArePreserved(){
  val result=externalMailPayload(event("Search aggregate",JSONObject().put("bigText","Long body\r\n尾")
   .put("text","Long body").put("textLines",JSONArray(listOf(" Long body ","尾")))))
  assertEquals("Long body\r\n尾",result.getString("body"))
  assertEquals("Long body",result.getJSONArray("additionalText").getJSONObject(0).getString("text"))
  assertEquals(" Long body \n尾",result.getJSONArray("additionalText").getJSONObject(1).getString("text"))
  assertFalse(result.toString().contains("Search aggregate"))
 }
 @Test fun distinctAdditionalTextAndEmptyBodyRemainLossless(){
  val result=externalMailPayload(event("",JSONObject().put("text","").put("android.subText","Unique subtitle")
   .put("android.infoText","Unique info").put("conversationTitle","Unique group")))
  assertEquals("",result.getString("body"));assertEquals(3,result.getJSONArray("additionalText").length())
 }
 @Test fun smsMultipartBodyStaysSingleExactUnicodeField(){
  val raw=" first part\r\nsecond 한글\t끝 ".repeat(10000)+"FINAL TAIL"
  val original=event(raw,JSONObject().put("sender","FAKE-SENDER").put("body",raw).put("partCount",2))
   .put("kind","SMS").put("sourceIdentity","FAKE-SENDER")
  val result=externalMailPayload(original)
  assertEquals(raw,result.getString("body"));assertEquals("FAKE-SENDER",result.getString("sender"))
  assertFalse(result.has("additionalText"));assertFalse(result.has("partCount"))
 }
}
