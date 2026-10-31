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
        app.learning.record("かんじ","漢字"); delay(300)
        assertTrue(app.learning.state.value.entries.isEmpty())
    }
    @Test fun clearingAlsoInvalidatesAlreadyQueuedConfirmations()=runBlocking {
        repeat(100) { app.learning.record("かんじ","漢字") }
        val before=app.learning.state.value.revision
        app.learning.clear()
        withTimeout(10_000) { app.learning.state.first { it.revision>before && it.entries.isEmpty() } }
        delay(300)
        assertTrue(app.learning.state.value.entries.isEmpty())
    }
}
