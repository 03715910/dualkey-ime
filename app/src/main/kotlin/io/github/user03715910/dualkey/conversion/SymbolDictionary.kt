package io.github.user03715910.dualkey.conversion

import android.content.Context
import io.github.dualkey.core.Personalization
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Mozc's general symbol readings, loaded from the APK; no network or typed-text log. */
class SymbolDictionary(context: Context,scope: CoroutineScope) {
    private val snapshot=MutableStateFlow<Map<String,List<String>>>(emptyMap())
    val entries=snapshot.asStateFlow()
    init { scope.launch(Dispatchers.IO) {
        try {
            val json=context.assets.open("symbols/readings.json").bufferedReader().use { JSONObject(it.readText()) }
            snapshot.value=json.keys().asSequence().associateWith { key ->
                val values=json.getJSONArray(key); (0 until values.length()).map { values.getString(it) }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Normal conversion remains available. */ }
    } }
}
