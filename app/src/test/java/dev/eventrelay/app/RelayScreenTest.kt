package dev.eventrelay.app

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import dev.eventrelay.core.RelayEvent
import dev.eventrelay.core.SourceKind
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** Native Canvas mock screens and fake state only. Never authorizes Google or sends mail. */
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],shadows=[NoDeliverySchedulingShadow::class,BlockedGoogleSessionProviderShadow::class,BlockedGmailTransportShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RelayScreenTest {
 private val compose=createAndroidComposeRule<MainActivity>()
 private lateinit var db:RelayDb
 private val email="synthetic-owner@example.test"
 private val rule=StoredRule("screen-rule","가짜 화면 규칙","SMS","FAKE-SENDER","[]")
 private val isolated=TestRule{base,_->object:Statement(){
  override fun evaluate(){
   val context=RuntimeEnvironment.getApplication()
   context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().clear().putString("gmailOwnEmail",email).commit()
   val runtime=RelayRuntime::class.java.getDeclaredField("db").apply{isAccessible=true}
   val previous=runtime.get(null) as? RelayDb
   db=Room.inMemoryDatabaseBuilder(context,RelayDb::class.java).allowMainThreadQueries().build();runtime.set(null,db)
   val resolve=ResolveInfo().apply{
    nonLocalizedLabel="연습 앱"
    activityInfo=ActivityInfo().apply{
     packageName="dev.synthetic.picker";name="dev.synthetic.picker.Main"
     nonLocalizedLabel="연습 앱"
     applicationInfo=ApplicationInfo().apply{packageName="dev.synthetic.picker";nonLocalizedLabel="연습 앱"}
    }
   }
   shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),resolve)
   runBlocking{
    db.dao().saveRule(rule)
    val event=RelayEvent(SourceKind.SMS,"FAKE-SENDER","FAKE-RECORD",1700000000000,1700000000001,body="SENSITIVE-FAKE-PAYLOAD")
    db.dao().enqueue(QueuedEvent("screen-event","SMS","FAKE-SENDER",1700000000001,event.toJson(),status="BLOCKED_DELIVERY",
     destination=email,ruleIds="[\"screen-rule\"]"))
   }
   try{base.evaluate()}finally{
    runtime.set(null,previous);db.close()
    context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().clear().commit()
   }
  }
 }}
 @get:Rule val rules:RuleChain=RuleChain.outerRule(isolated).around(compose)

 private fun snapshot(name:String){
  compose.waitForIdle()
  assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
  val previewWindow=ShadowDialog.getLatestDialog()?.takeIf{it.isShowing}?.window?:compose.activity.window
  val protected=previewWindow.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0
  assertTrue(protected)
  compose.runOnUiThread{previewWindow.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
  val directory=File("build/reports/ui-preview/v0.3.0");directory.mkdirs()
  File(directory,name+".png").outputStream().use{output->
   try{compose.runOnUiThread{
    val view=previewWindow.decorView
    val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
    view.draw(android.graphics.Canvas(bitmap));bitmap.compress(Bitmap.CompressFormat.PNG,100,output)
   }}finally{compose.runOnUiThread{previewWindow.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}}
  }
  assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
 }
 @Test fun homeAndRepeatedNavigationRenderWithDeliveryOffAndScreenProtection(){
  compose.onNodeWithText("EventRelay").assertExists()
  compose.onNodeWithText("Gmail 전송: 꺼짐").assertExists();snapshot("home")
  for(tab in listOf("규칙","기록","설정","홈","규칙","기록","설정","홈")){
   compose.onNodeWithTag("nav-"+tab).performClick();compose.waitForIdle()
   if(tab=="홈")compose.onNodeWithText("Gmail 전송: 꺼짐").assertExists()
  }
  assertFalse(gmailDeliveryEnabled(compose.activity))
  assertEquals(0,BlockedGoogleSessionProviderShadow.calls);assertEquals(0,BlockedGmailTransportShadow.calls)
 }
 @Test fun typedButUnaddedConditionCannotSaveBroaderRule(){
  compose.onNodeWithTag("nav-규칙").performClick()
  compose.onNodeWithTag("rule-name").performScrollTo().performTextInput("Synthetic test rule")
  compose.onNodeWithTag("rule-source").performScrollTo().performTextInput("SYNTHETIC")
  compose.onNodeWithTag("save-rule").performScrollTo().assertIsEnabled()
  compose.onNodeWithTag("rule-keyword").performScrollTo().performTextInput("EVENTRELAY_TEST")
  compose.onNodeWithTag("save-rule").performScrollTo().assertIsNotEnabled()
  compose.onNodeWithText("AND 조건 추가").performScrollTo().performClick()
  compose.onNodeWithTag("save-rule").performScrollTo().assertIsEnabled();snapshot("rules")
 }
 @Test fun appPickerCancelKeepsEmptySourceAndSelectionUsesAppLabelWithoutManualPackageInput(){
  compose.onNodeWithTag("nav-규칙").performClick()
  compose.onNodeWithTag("channel-notification").performScrollTo().performClick()
  compose.onNodeWithTag("choose-app").performScrollTo().performClick()
  compose.onNodeWithText("알림을 받을 앱 선택").assertIsDisplayed();snapshot("app-picker")
  compose.onNodeWithText("취소").performClick()
  compose.onNodeWithText("앱 선택").assertExists()
  compose.onNodeWithTag("choose-app").performScrollTo().performClick()
  compose.onNodeWithTag("app-search").performTextInput("연습")
  compose.onNodeWithTag("app-dev.synthetic.picker").performClick()
  compose.onNodeWithText("선택한 앱: 연습 앱").assertExists()
  compose.onNodeWithTag("rule-source").assertDoesNotExist()
  compose.onNodeWithTag("field-title").performScrollTo().performClick()
  compose.onNodeWithTag("channel-sms").performScrollTo().performClick()
  compose.onNodeWithTag("field-title").assertDoesNotExist()
  compose.onNodeWithTag("rule-source").assertExists()
 }
 @Test fun diagnosticsClipboardExcludesRawBodiesSenderEmailTokenAndAppList(){
  compose.onNodeWithTag("nav-설정").performClick();snapshot("settings")
  compose.onNodeWithTag("copy-diagnostics").performScrollTo().performClick()
  val clipboard=compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  val text=clipboard.primaryClip!!.getItemAt(0).text.toString()
  assertTrue(text.contains("EventRelay 진단"))
  for(sensitive in listOf(email,"SENSITIVE-FAKE-PAYLOAD","FAKE-SENDER","dev.synthetic.picker","fake-token"))assertFalse(text.contains(sensitive))
 }
 @Test fun deletionAndAccountChangeCancelKeepQueueIdentityAndAccountAcrossRepeatedDialogs(){
  compose.onNodeWithTag("nav-설정").performClick()
  repeat(2){
   compose.onNodeWithTag("delete-queue").performScrollTo().performClick()
   compose.onNodeWithText("미전송 항목 삭제 확인").assertIsDisplayed()
   compose.onNodeWithText("1개 삭제").assertExists();snapshot("delete-confirm")
   compose.onNodeWithText("취소").performClick()
   compose.onNodeWithTag("change-account").performScrollTo().performClick()
   compose.onNodeWithText("계정 변경 준비").assertIsDisplayed();compose.onNodeWithText("취소").performClick()
  }
  runBlocking{assertNotNull(db.dao().event("screen-event"))}
  assertEquals(email,compose.activity.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("gmailOwnEmail",null))
  assertFalse(gmailDeliveryEnabled(compose.activity))
 }
 @Test fun ruleDisableRequiresConfirmationAndReenableDoesNotReviveHeldQueue(){
  compose.onNodeWithTag("nav-규칙").performClick()
  compose.onNodeWithTag("rule-toggle-screen-rule").performScrollTo().performClick()
  compose.onNodeWithText("취소").performClick()
  runBlocking{assertTrue(db.dao().activeRules().any{it.id=="screen-rule"})}
  compose.onNodeWithTag("rule-toggle-screen-rule").performScrollTo().performClick()
  compose.onNodeWithTag("confirm-action").performClick()
  compose.waitUntil(5000){runBlocking{db.dao().event("screen-event")?.status=="BLOCKED_RULE"}}
  compose.onNodeWithTag("rule-toggle-screen-rule").performScrollTo().performClick()
  compose.waitUntil(5000){runBlocking{db.dao().activeRules().any{it.id=="screen-rule"}}}
  runBlocking{assertEquals("BLOCKED_RULE",db.dao().event("screen-event")!!.status)}
 }
}
