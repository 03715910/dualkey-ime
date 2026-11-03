package io.github.user03715910.dualkey.conversion

import android.content.Context
import io.github.dualkey.core.ExpressionCandidates
import io.github.dualkey.core.ExpressionIndex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class ExpressionDictionary(context: Context,scope: CoroutineScope) {
    private val snapshot=MutableStateFlow(ExpressionCandidates())
    val entries=snapshot.asStateFlow()
    init { scope.launch(Dispatchers.IO) {
        try {
            val words=linkedMapOf<String,List<String>>()
            val symbols=linkedMapOf<String,List<String>>()
            val ja=linkedMapOf<String,List<String>>()
            val en=linkedMapOf<String,List<String>>()
            val faces=linkedMapOf<String,List<String>>()
            context.assets.open("expressions/aliases.tsv").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                    val fields=line.split('\t')
                    if (fields.size==4) {
                        val map=when (fields[0]) {
                            "symbol" -> symbols
                            "word" -> words
                            "face" -> faces
                            else -> if (fields[1]=="en") en else ja
                        }
                        fields[2].split('|').forEach { key -> map[key]=map[key].orEmpty()+fields[3].split('|') }
                    }
                }
            }
            fun load(lang: String, into: MutableMap<String,List<String>>) {
                val json=context.assets.open("expressions/emoji-$lang.json").bufferedReader().use { JSONObject(it.readText()) }
                json.keys().forEach { key ->
                    val array=json.getJSONArray(key)
                    into[key]=(into[key].orEmpty()+(0 until array.length()).map { array.getString(it) }).distinct()
                }
            }
            load("ja",ja); load("en",en)
            snapshot.value=ExpressionCandidates(symbols,ExpressionIndex(ja),ExpressionIndex(en),ExpressionIndex(faces),words)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Optional dictionary. */ }
    } }
}
