package io.github.user03715910.dualkey

import android.app.Application
import io.github.user03715910.dualkey.data.SettingsStore
import io.github.user03715910.dualkey.update.UpdateScheduler
import io.github.user03715910.dualkey.update.UpdateRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import io.github.dualkey.core.DisplayMode

class DualKeyApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settings: SettingsStore; private set
    val updates by lazy { UpdateRepository(this) }
    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this, scope)
        scope.launch { settings.awaitLoaded(); settings.state.map { it.autoCheck }.distinctUntilChanged().collect { UpdateScheduler.schedule(this@DualKeyApp, it) } }
    }
}

object ImeControl {
    val requests = MutableStateFlow<DisplayMode?>(null)
    @Volatile var inputActive = false
    fun request(mode: DisplayMode) { requests.value = mode }
}
