package io.github.user03715910.dualkey.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import io.github.dualkey.core.HardwarePolicy
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
    val ctrlSpace = booleanPreferencesKey("shortcut_ctrl_space")
    val layout = stringPreferencesKey("hardware_layout")
    val japanesePrediction = booleanPreferencesKey("japanese_prediction")
    val englishPrediction = booleanPreferencesKey("english_prediction")
    val candidateCount = intPreferencesKey("candidate_count")
    val learningEnabled = booleanPreferencesKey("japanese_learning_enabled")
    val dictionary = stringPreferencesKey("user_dictionary_json")
    val translateToEnglish = booleanPreferencesKey("translate_to_english")
    val autoCheck = booleanPreferencesKey("auto_check")
    val autoDownload = booleanPreferencesKey("auto_download")
    val wifiOnly = booleanPreferencesKey("wifi_only")
    val notifications = booleanPreferencesKey("notifications")
}

data class Settings(val p: Preferences = emptyPreferences()) {
    val initialEnglish get() = p[Prefs.initialEnglish] ?: false
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
    val ctrlSpace get() = p[Prefs.ctrlSpace] ?: true
    val japanesePrediction get() = p[Prefs.japanesePrediction] ?: true
    val englishPrediction get() = p[Prefs.englishPrediction] ?: true
    val candidateCount get() = (p[Prefs.candidateCount] ?: 100).coerceIn(2,1000)
    val learningEnabled get() = p[Prefs.learningEnabled] ?: true
    val toEnglish get() = p[Prefs.translateToEnglish] ?: true
    val autoCheck get() = p[Prefs.autoCheck] ?: true
    val autoDownload get() = p[Prefs.autoDownload] ?: false
    val wifiOnly get() = p[Prefs.wifiOnly] ?: true
    val notifications get() = p[Prefs.notifications] ?: true
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
    suspend fun addWord(reading: String, word: String) {
        context.store.edit {
            val json = JSONObject(it[Prefs.dictionary] ?: "{}")
            val existing = json.optJSONArray(reading) ?: JSONArray()
            val list = (0 until existing.length()).map { i -> existing.getString(i) }
            json.put(reading, JSONArray((list + word).distinct().take(30)))
            it[Prefs.dictionary] = json.toString()
        }
    }
    suspend fun removeWord(reading: String) { context.store.edit { val j = JSONObject(it[Prefs.dictionary] ?: "{}"); j.remove(reading); it[Prefs.dictionary] = j.toString() } }
}
