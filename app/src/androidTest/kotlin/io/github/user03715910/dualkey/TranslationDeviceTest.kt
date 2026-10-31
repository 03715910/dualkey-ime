package io.github.user03715910.dualkey

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.user03715910.dualkey.translation.TranslationEngine
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranslationDeviceTest {
    @Test fun downloadsModelAndTranslatesBothDirections() = runBlocking {
        val engine=TranslationEngine()
        try {
            withTimeout(240_000) { engine.download(true) }
            assertTrue(engine.ready())
            val english=engine.translate("こんにちは",true)
            assertTrue("Expected English translation, got $english",english.any { it in 'a'..'z' || it in 'A'..'Z' })
            val japanese=engine.translate("Hello",false)
            assertTrue("Expected Japanese translation, got $japanese",japanese.any { it in 'ぁ'..'ヿ' || it in '一'..'龯' })
        } finally { engine.close() }
    }
}
