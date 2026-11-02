package io.github.user03715910.dualkey

import android.app.Application
import io.github.user03715910.dualkey.data.SettingsStore
import io.github.user03715910.dualkey.data.LearningStore
import io.github.user03715910.dualkey.conversion.JapaneseDictionary
import io.github.user03715910.dualkey.conversion.SemanticDictionary
import io.github.user03715910.dualkey.update.UpdateScheduler
import io.github.user03715910.dualkey.update.UpdateRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import io.github.dualkey.core.DisplayMode
import io.github.user03715910.dualkey.ime.KeyboardVisibilityNotification
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.util.concurrent.atomic.AtomicLong

class DualKeyApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settings: SettingsStore; private set
    val updates by lazy { UpdateRepository(this) }
    lateinit var japaneseDictionary: JapaneseDictionary; private set
    lateinit var semanticDictionary: SemanticDictionary; private set
    lateinit var learning: LearningStore; private set
    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this, scope)
        learning = LearningStore(this,scope) { settings.isLoaded && settings.state.value.learningEnabled }
        japaneseDictionary = JapaneseDictionary(this, scope) { settings.state.value.dictionary }
        semanticDictionary = SemanticDictionary(this,scope)
        scope.launch { settings.awaitLoaded(); settings.state.map { it.autoCheck }.distinctUntilChanged().collect { UpdateScheduler.schedule(this@DualKeyApp, it) } }
        scope.launch {
            settings.awaitLoaded()
            combine(settings.state,ImeControl.state) { s,v -> Triple(s.visibilityNotification,s.pendingNotificationDisplay,v) }
                .distinctUntilChanged().collect { (enabled,_,_) ->
                    if (!enabled) ImeControl.requests.value?.takeIf { it.notification }?.let { ImeControl.requests.compareAndSet(it,null) }
                    KeyboardVisibilityNotification.refresh(this@DualKeyApp)
                }
        }
        contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),false,
            object: ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { KeyboardVisibilityNotification.refresh(this@DualKeyApp) }
            })
    }
}

object ImeControl {
    data class DisplayRequest(val mode: DisplayMode,val notification: Boolean,val id: Long)
    data class UiState(val editorActive: Boolean=false,val keyboardVisible: Boolean=false,val mode: DisplayMode=DisplayMode.HIDDEN)
    private val sequence=AtomicLong()
    val requests = MutableStateFlow<DisplayRequest?>(null)
    val state=MutableStateFlow(UiState())
    @Volatile var inputActive = false
    fun request(mode: DisplayMode,notification: Boolean=false) { requests.value = DisplayRequest(mode,notification,sequence.incrementAndGet()) }
}
