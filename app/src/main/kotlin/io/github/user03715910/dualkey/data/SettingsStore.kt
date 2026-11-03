package io.github.user03715910.dualkey.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import io.github.dualkey.core.HardwarePolicy
import io.github.dualkey.core.ToggleShortcut
import io.github.dualkey.core.DisplayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

private val Context.store by preferencesDataStore("dualkey_settings_v1")

object Prefs {
    val initialEnglish = booleanPreferencesKey("initial_english")
    val numberRow = booleanPreferencesKey("number_row")
    val flickDp = intPreferencesKey("flick_dp")
    val keyHeight = intPreferencesKey("key_height_dp")
    val heightPercent = intPreferencesKey("keyboard_height_percent")
    val vibration = booleanPreferencesKey("vibration")
    val sound = booleanPreferencesKey("sound")
    val hardwarePolicy = stringPreferencesKey("hardware_policy")
    val rememberManual = booleanPreferencesKey("remember_manual")
    val savedManual = stringPreferencesKey("saved_manual")
    val shortcut = booleanPreferencesKey("shortcut_ctrl_alt_k")
    val displayShortcutKey = stringPreferencesKey("visibility_toggle_key")
    val displayShortcutModifiers = intPreferencesKey("visibility_toggle_modifiers")
    val ctrlSpace = booleanPreferencesKey("shortcut_ctrl_space")
    val layout = stringPreferencesKey("hardware_layout")
    val japanesePrediction = booleanPreferencesKey("japanese_prediction")
    val emojiPrediction = booleanPreferencesKey("emoji_prediction")
    val facePrediction = booleanPreferencesKey("face_prediction")
    val englishPrediction = booleanPreferencesKey("english_prediction")
    val candidateCount = intPreferencesKey("candidate_count")
    val learningEnabled = booleanPreferencesKey("japanese_learning_enabled")
    val dictionary = stringPreferencesKey("user_dictionary_json")
    val translateToEnglish = booleanPreferencesKey("translate_to_english")
    val autoCheck = booleanPreferencesKey("auto_check")
    val autoDownload = booleanPreferencesKey("auto_download")
    val wifiOnly = booleanPreferencesKey("wifi_only")
    val notifications = booleanPreferencesKey("notifications")
    val visibilityNotification = booleanPreferencesKey("visibility_notification")
    val hideImeCaption = booleanPreferencesKey("hide_ime_caption_when_hidden")
    val pendingNotificationDisplay = stringPreferencesKey("pending_notification_display")
    val japaneseLayout = stringPreferencesKey("screen_japanese_layout")
    val englishLayout = stringPreferencesKey("screen_english_layout")
    val screenEnter = stringPreferencesKey("screen_enter_behavior")
    val physicalEnter = stringPreferencesKey("physical_enter_behavior")
    val clipboardHistory = booleanPreferencesKey("clipboard_history_enabled")
    val clipboardLimit = intPreferencesKey("clipboard_history_limit")
}

data class Settings(val p: Preferences = emptyPreferences()) {
    val initialEnglish get() = p[Prefs.initialEnglish] ?: false
    val japaneseQwerty get() = p[Prefs.japaneseLayout]=="QWERTY"
    val englishFlick get() = p[Prefs.englishLayout]=="FLICK"
    val screenEnter get() = p[Prefs.screenEnter] ?: "SMART_NEWLINE"
    val physicalEnter get() = p[Prefs.physicalEnter] ?: "SMART_SEND"
    val clipboardHistory get() = p[Prefs.clipboardHistory] ?: false
    val clipboardLimit get() = (p[Prefs.clipboardLimit] ?: 20).coerceIn(5,100)
    val numberRow get() = p[Prefs.numberRow] ?: true
    val flickDp get() = (p[Prefs.flickDp] ?: 24).coerceIn(8,64)
    val keyHeight get() = (p[Prefs.keyHeight] ?: 40).coerceIn(32,80)
    val heightPercent get() = (p[Prefs.heightPercent] ?: 100).coerceIn(70,140)
    val vibration get() = p[Prefs.vibration] ?: true
    val sound get() = p[Prefs.sound] ?: false
    val policy get() = runCatching { HardwarePolicy.valueOf(p[Prefs.hardwarePolicy] ?: "AUTO") }.getOrDefault(HardwarePolicy.AUTO)
    val rememberManual get() = p[Prefs.rememberManual] ?: false
    val savedManual get() = p[Prefs.savedManual]
    val shortcut get() = p[Prefs.shortcut] ?: true
    val displayShortcut get() = ToggleShortcut(p[Prefs.displayShortcutKey] ?: "K",p[Prefs.displayShortcutModifiers] ?: (ToggleShortcut.CTRL or ToggleShortcut.ALT))
        .takeIf { it.valid } ?: ToggleShortcut()
    val ctrlSpace get() = p[Prefs.ctrlSpace] ?: true
    val jisKeyboard get() = p[Prefs.layout] != "US"
    val japanesePrediction get() = p[Prefs.japanesePrediction] ?: true
    val emojiPrediction get() = p[Prefs.emojiPrediction] ?: true
    val facePrediction get() = p[Prefs.facePrediction] ?: true
    val englishPrediction get() = p[Prefs.englishPrediction] ?: true
    val candidateCount get() = (p[Prefs.candidateCount] ?: 100).coerceIn(2,1000)
    val learningEnabled get() = p[Prefs.learningEnabled] ?: true
    val toEnglish get() = p[Prefs.translateToEnglish] ?: true
    val autoCheck get() = p[Prefs.autoCheck] ?: true
    val autoDownload get() = p[Prefs.autoDownload] ?: false
    val wifiOnly get() = p[Prefs.wifiOnly] ?: true
    val notifications get() = p[Prefs.notifications] ?: true
    val visibilityNotification get() = p[Prefs.visibilityNotification] ?: true
    val hideImeCaption get() = p[Prefs.hideImeCaption] ?: true
    val pendingNotificationDisplay get() = runCatching { DisplayMode.valueOf(p[Prefs.pendingNotificationDisplay] ?: "") }.getOrNull()
    val dictionary: Map<String,List<String>> by lazy { runCatching {
        val json = JSONObject(p[Prefs.dictionary] ?: "{}")
        json.keys().asSequence().associateWith { key -> val a = json.getJSONArray(key); (0 until a.length()).map { a.getString(it) } }
    }.getOrDefault(emptyMap()) }
}

class SettingsStore(private val context: Context, scope: CoroutineScope) {
    private val ready=CompletableDeferred<Unit>()
    val isLoaded get() = ready.isCompleted
    private val snapshot=MutableStateFlow(Settings())
    val state: StateFlow<Settings> = snapshot.asStateFlow()
    init { scope.launch {
        context.store.data.catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }.collect {
            snapshot.value=Settings(it); ready.complete(Unit)
        }
    } }
    suspend fun awaitLoaded() { ready.await() }
    suspend fun <T> set(key: Preferences.Key<T>, value: T) { context.store.edit { it[key] = value } }
    suspend fun setDisplayShortcut(value: ToggleShortcut) {
        require(value.valid)
        context.store.edit { it[Prefs.displayShortcutKey]=value.key; it[Prefs.displayShortcutModifiers]=value.modifiers }
    }
    suspend fun toggleNotificationDisplay(currentlyVisible: Boolean): DisplayMode? {
        var target: DisplayMode?=null
        context.store.edit {
            if (it[Prefs.visibilityNotification]==false) return@edit
            val pending=runCatching { DisplayMode.valueOf(it[Prefs.pendingNotificationDisplay] ?: "") }.getOrNull()
            val visible=pending?.let { mode -> mode!=DisplayMode.HIDDEN } ?: currentlyVisible
            target=if (visible) DisplayMode.HIDDEN else DisplayMode.FULL
            it[Prefs.pendingNotificationDisplay]=target!!.name
        }
        return target
    }
    suspend fun clearNotificationDisplay(expected: DisplayMode?=null) {
        context.store.edit { if (expected==null || it[Prefs.pendingNotificationDisplay]==expected.name) it.remove(Prefs.pendingNotificationDisplay) }
    }
    suspend fun consumeNotificationDisplay(): DisplayMode? {
        var target: DisplayMode?=null
        context.store.edit {
            if (it[Prefs.visibilityNotification]!=false) target=runCatching { DisplayMode.valueOf(it[Prefs.pendingNotificationDisplay] ?: "") }.getOrNull()
            it.remove(Prefs.pendingNotificationDisplay)
        }
        return target
    }
    suspend fun addWord(reading: String, word: String) {
        context.store.edit {
            val json = JSONObject(it[Prefs.dictionary] ?: "{}")
            val existing = json.optJSONArray(reading) ?: JSONArray()
            val list = (0 until existing.length()).map { i -> existing.getString(i) }
            json.put(reading, JSONArray((list + word).distinct()))
            it[Prefs.dictionary] = json.toString()
        }
    }
    suspend fun removeWord(reading: String) { context.store.edit { val j = JSONObject(it[Prefs.dictionary] ?: "{}"); j.remove(reading); it[Prefs.dictionary] = j.toString() } }
    suspend fun editWord(oldReading: String,oldWord: String,reading: String,word: String) { context.store.edit {
        val json=JSONObject(it[Prefs.dictionary] ?: "{}")
        val old=json.optJSONArray(oldReading) ?: JSONArray()
        val kept=(0 until old.length()).map { old.getString(it) }.filterNot { value -> value==oldWord }
        if (kept.isEmpty()) json.remove(oldReading) else json.put(oldReading,JSONArray(kept))
        if (reading.isNotEmpty() && word.isNotEmpty()) {
            val current=json.optJSONArray(reading) ?: JSONArray()
            json.put(reading,JSONArray(((0 until current.length()).map { current.getString(it) }+word).distinct()))
        }
        it[Prefs.dictionary]=json.toString()
    } }
}
