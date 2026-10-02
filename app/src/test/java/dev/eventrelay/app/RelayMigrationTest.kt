package dev.eventrelay.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class RelayMigrationTest {
 @Test fun v1MigrationKeepsOriginalsAndTerminalHistoryWhileHoldingOldUnsentRows()=runBlocking {
  val context:Context=RuntimeEnvironment.getApplication();val name="synthetic-migration.db"
  context.deleteDatabase(name)
  val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
   .callback(object:SupportSQLiteOpenHelper.Callback(1){
    override fun onCreate(db:SupportSQLiteDatabase){
     db.execSQL("CREATE TABLE rules(id TEXT NOT NULL PRIMARY KEY,name TEXT NOT NULL,channel TEXT NOT NULL,source TEXT NOT NULL,conditions TEXT NOT NULL,enabled INTEGER NOT NULL)")
     db.execSQL("CREATE TABLE queue(id TEXT NOT NULL PRIMARY KEY,channel TEXT NOT NULL,source TEXT NOT NULL,observedAt INTEGER NOT NULL,payload TEXT NOT NULL,status TEXT NOT NULL,attempts INTEGER NOT NULL,lastError TEXT,completedAt INTEGER,gmailMessageId TEXT,destination TEXT,nextAttemptAt INTEGER NOT NULL)")
     db.execSQL("INSERT INTO rules VALUES('legacy-rule','Fake rule','SMS','FAKE','[]',1)")
     for((id,status)in listOf("queued" to "PENDING","sending" to "SENDING","done" to "ACCEPTED","unknown" to "OUTCOME_UNKNOWN"))
      db.execSQL("INSERT INTO queue VALUES(?, 'SMS','FAKE',100,'FAKE ORIGINAL RAW BODY',?,0,NULL,NULL,NULL,'fake@example.test',0)",arrayOf(id,status))
    }
    override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=error("Room owns this upgrade")
   }).build())
  helper.writableDatabase;helper.close()
  val migrated=Room.databaseBuilder(context,RelayDb::class.java,name).addMigrations(RELAY_MIGRATION_1_2).allowMainThreadQueries().build()
  try{
   assertEquals("BLOCKED_POLICY",migrated.dao().event("queued")!!.status)
   assertEquals("BLOCKED_POLICY",migrated.dao().event("sending")!!.status)
   assertEquals("ACCEPTED",migrated.dao().event("done")!!.status)
   assertEquals("OUTCOME_UNKNOWN",migrated.dao().event("unknown")!!.status)
   assertEquals("FAKE ORIGINAL RAW BODY",migrated.dao().payload("queued"))
   assertEquals("[]",migrated.dao().event("queued")!!.ruleIds)
   assertEquals(0L,migrated.dao().event("queued")!!.accountEpoch)
   assertTrue(migrated.dao().activeRules().single().enabled)
  }finally{migrated.close();context.deleteDatabase(name)}
 }
}
