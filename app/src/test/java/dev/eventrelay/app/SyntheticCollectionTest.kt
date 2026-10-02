package dev.eventrelay.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],shadows=[FailOnSyntheticSchedulingShadow::class])
class SyntheticCollectionTest {
 @Test fun syntheticExerciseNeverBecomesPendingOrSchedulesOtherMailEvenIfDeliveryWasOn()=runBlocking {
  val context:Context=RuntimeEnvironment.getApplication()
  val preferences=context.getSharedPreferences("settings",Context.MODE_PRIVATE)
  preferences.edit().clear().putString("gmailOwnEmail","fake@example.test").putBoolean("gmailDeliveryEnabled",true)
   .putLong("gmailDeliveryEnabledSince",0).commit()
  val db=Room.inMemoryDatabaseBuilder(context,RelayDb::class.java).allowMainThreadQueries().build()
  val field=RelayRuntime::class.java.getDeclaredField("db").apply{isAccessible=true}
  val previous=field.get(null) as? RelayDb;field.set(null,db)
  try{
   db.dao().saveRule(StoredRule("fake-demo-rule","Fake demo","SMS","DEMO","[]"))
   RelayRuntime.collect(context,"SMS","DEMO","fake-demo-record",1700000000000,null,"Fake demo raw body",
    JSONObject().put("body","Fake demo raw body").put("synthetic",true))
   assertEquals("BLOCKED_DELIVERY",db.dao().unfinished().single().status)
   assertTrue(db.dao().pending().isEmpty())
  }finally{field.set(null,previous);db.close();preferences.edit().clear().commit()}
 }
}
@Implements(value=RelayRuntime::class,isInAndroidSdk=false)
class FailOnSyntheticSchedulingShadow {
 @Implementation fun schedule(context:Context){throw AssertionError("Synthetic exercise must never schedule delivery")}
}
