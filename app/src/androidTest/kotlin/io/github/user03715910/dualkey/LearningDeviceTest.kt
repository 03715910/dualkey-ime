package io.github.user03715910.dualkey

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.user03715910.dualkey.data.LearningStore
import io.github.user03715910.dualkey.data.Prefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearningDeviceTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DualKeyApp
    @Before fun prepare()=runBlocking<Unit> {
        check(android.os.Build.MODEL.contains("sdk_gphone") || android.os.Build.FINGERPRINT.contains("generic"))
        app.settings.awaitLoaded(); app.settings.set(Prefs.learningEnabled,true)
        app.settings.state.first { it.learningEnabled }; app.learning.awaitLoaded()
        val before=app.learning.state.value.revision; app.learning.clear()
        withTimeout(10_000) { app.learning.state.first { it.revision>before && it.entries.isEmpty() } }
    }
    @After fun finish()=runBlocking { app.settings.set(Prefs.learningEnabled,false); app.learning.clear() }
    @Test fun frequenciesPersistInThePrivateDatabase()=runBlocking {
        app.learning.record("かんじ","漢字")
        repeat(3) { app.learning.record("かんじ","幹事") }
        app.learning.record("よし","𠮷")
        withTimeout(10_000) { app.learning.state.first { it.entries.sumOf { e -> e.count }==5 } }
        val readerScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        try {
            val reopened=LearningStore(app,readerScope) { true }; reopened.awaitLoaded()
            assertEquals(3,reopened.state.value.entries.first { it.text=="幹事" }.count)
            assertEquals(1,reopened.state.value.entries.first { it.text=="𠮷" }.count)
        } finally { readerScope.cancel() }
    }
    @Test fun disabledLearningDoesNotWrite()=runBlocking {
        app.settings.set(Prefs.learningEnabled,false); app.settings.state.first { !it.learningEnabled }
        app.learning.record("かんじ","漢字"); app.learning.recordContinuation("続きを","おねがいします","お願いします"); delay(300)
        assertTrue(app.learning.state.value.entries.isEmpty())
        assertTrue(app.learning.state.value.continuations.isEmpty())
    }
    @Test fun clearingAlsoInvalidatesAlreadyQueuedConfirmations()=runBlocking {
        repeat(100) { app.learning.record("かんじ","漢字"); app.learning.recordContinuation("続きを","おねがいします","お願いします") }
        val before=app.learning.state.value.revision
        app.learning.clear()
        withTimeout(10_000) { app.learning.state.first { it.revision>before && it.entries.isEmpty() } }
        delay(300)
        assertTrue(app.learning.state.value.entries.isEmpty())
        assertTrue(app.learning.state.value.continuations.isEmpty())
    }
    @Test fun oldDatabaseUpgradesWithoutLosingLearnedChoices()=runBlocking {
        val name="continuation-migration-test.db"
        app.deleteDatabase(name)
        app.openOrCreateDatabase(name,0,null).use { database ->
            database.execSQL("CREATE TABLE choices (reading TEXT NOT NULL, surface TEXT NOT NULL, uses INTEGER NOT NULL, last_used INTEGER NOT NULL, PRIMARY KEY (reading,surface))")
            database.execSQL("INSERT INTO choices VALUES ('かんじ','漢字',7,100)")
            database.version=1
        }
        val readerScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val store=LearningStore(app,readerScope,name) { true }
        try {
            store.awaitLoaded(); assertEquals(7,store.state.value.entries.single().count)
            repeat(3) { store.recordContinuation("続きを","みせてください","見せてください") }
            withTimeout(10_000) { store.state.first { it.continuations.singleOrNull()?.count==3 } }
            assertEquals("漢字",store.state.value.entries.single().text)
            val second=LearningStore(app,readerScope,name) { true }
            try {
                second.awaitLoaded(); assertEquals(3,second.state.value.continuations.single().count)
                assertEquals(7,second.state.value.entries.single().count)
            } finally { second.close() }
        } finally { readerScope.cancel(); store.close(); app.deleteDatabase(name) }
    }
}
