package io.github.user03715910.dualkey.data

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.util.AtomicFile
import io.github.dualkey.core.ClipboardPrivacy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ClipboardEntry(val text: String,val time: Long)

/** Opt-in, private, 24-hour history. Never includes URIs, intents, or marked-sensitive clips. */
class ClipboardHistoryStore(context: Context,private val scope: CoroutineScope,private val settings: SettingsStore) {
    private val screenshotPrefs=context.getSharedPreferences("screenshot-history",Context.MODE_PRIVATE)
    private val screenshotOrder=compareBy<RecentScreenshot>({ it.addedAt },{ ContentUris.parseId(it.uri) })
    private val screenshots=MutableStateFlow(runCatching {
        val json=JSONArray(screenshotPrefs.getString("entries","[]"))
        (0 until minOf(json.length(),100)).map { i ->
            val item=json.getJSONObject(i)
            RecentScreenshot(Uri.parse(item.getString("uri")),item.getString("mime"),item.getLong("time"))
        }
    }.getOrDefault(emptyList()))
    internal val screenshotState=screenshots.asStateFlow()
    private val file=AtomicFile(File(context.noBackupFilesDir,"clipboard-history.json"))
    private val mutex=Mutex()
    private val loaded=CompletableDeferred<Unit>()
    private val snapshot=MutableStateFlow<List<ClipboardEntry>>(emptyList())
    val state=snapshot.asStateFlow()
    private val clears=MutableStateFlow(0L)
    val clearEvents=clears.asStateFlow()
    fun current(): List<ClipboardEntry> = if (!settings.state.value.clipboardHistory) emptyList() else snapshot.value
        .filter { System.currentTimeMillis()-it.time in 0..86_400_000L }.take(settings.state.value.clipboardLimit)
    fun prune() { scope.launch(Dispatchers.IO) { loaded.await(); mutex.withLock { snapshot.value=current(); runCatching { persist() } } } }
    @Volatile private var epoch=0L
    init {
        scope.launch(Dispatchers.IO) {
            try {
                settings.awaitLoaded()
                mutex.withLock {
                    if (settings.state.value.clipboardHistory && file.baseFile.isFile) {
                        require(file.baseFile.length()<=1_000_000)
                        val array=file.openRead().bufferedReader().use { JSONArray(it.readText()) }
                        snapshot.value=(0 until minOf(array.length(),100)).map { i -> array.getJSONObject(i).let { ClipboardEntry(it.getString("text"),it.getLong("time")) } }
                            .filter { ClipboardPrivacy.retain(it.text,false) && System.currentTimeMillis()-it.time in 0..86_400_000L }
                            .distinctBy { it.text }.take(settings.state.value.clipboardLimit)
                    }
                    persist()
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { snapshot.value=emptyList(); file.delete() }
            finally { loaded.complete(Unit) }
        }
        scope.launch {
            settings.awaitLoaded(); loaded.await()
            settings.state.map { it.clipboardHistory to it.clipboardLimit }.distinctUntilChanged().collect { (enabled,limit) ->
                if (!enabled) clear() else scope.launch(Dispatchers.IO) { mutex.withLock { snapshot.value=snapshot.value.take(limit); runCatching { persist() } } }
            }
        }
    }
    // Store only MediaStore references. Thumbnail and paste bytes are read on demand.
    @Synchronized internal fun rememberScreenshots(found: List<RecentScreenshot>) {
        val options=settings.state.value
        if (!options.clipboardHistory || !options.recentScreenshots) return
        val lastTime=screenshotPrefs.getLong("last_time",0)
        val lastId=screenshotPrefs.getLong("last_id",-1)
        val added=found.filter { it.addedAt>lastTime ||
            (it.addedAt==lastTime && ContentUris.parseId(it.uri)>lastId) }
        val latest=added.maxWithOrNull(screenshotOrder) ?: return
        writeScreenshots((added+screenshots.value).distinctBy { it.uri }.sortedWith(screenshotOrder.reversed())
            .take(options.clipboardLimit),latest.addedAt,ContentUris.parseId(latest.uri))
    }

    @Synchronized internal fun removeScreenshot(entry: RecentScreenshot) {
        writeScreenshots(screenshots.value.filterNot { it==entry })
    }

    @Synchronized private fun clearScreenshots() {
        if (screenshots.value.isEmpty()) return
        writeScreenshots(emptyList(),System.currentTimeMillis(),Long.MAX_VALUE)
    }

    private fun writeScreenshots(entries: List<RecentScreenshot>,time: Long?=null,id: Long=0) {
        val json=JSONArray()
        entries.forEach { json.put(JSONObject().put("uri",it.uri.toString()).put("mime",it.mime).put("time",it.addedAt)) }
        screenshotPrefs.edit().putString("entries",json.toString()).apply {
            if (time!=null) putLong("last_time",time).putLong("last_id",id)
        }.apply()
        screenshots.value=entries
    }

    private fun persist() {
        if (!settings.state.value.clipboardHistory || snapshot.value.isEmpty()) { file.delete(); return }
        val array=JSONArray(); snapshot.value.forEach { array.put(JSONObject().put("text",it.text).put("time",it.time)) }
        val output=file.startWrite()
        try { output.write(array.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    fun add(text: String,sensitive: Boolean) {
        if (!settings.state.value.clipboardHistory || !ClipboardPrivacy.retain(text,sensitive)) return
        val generation=epoch
        scope.launch(Dispatchers.IO) { loaded.await(); mutex.withLock {
            if (generation!=epoch || !settings.state.value.clipboardHistory) return@withLock
            val now=System.currentTimeMillis()
            snapshot.value=(listOf(ClipboardEntry(text,now))+snapshot.value.filter { it.text!=text && now-it.time in 0..86_400_000L })
                .take(settings.state.value.clipboardLimit)
            try { persist() } catch (_: Exception) { snapshot.value=emptyList(); file.delete() }
        } }
    }
    fun remove(entry: ClipboardEntry) { scope.launch(Dispatchers.IO) { loaded.await(); mutex.withLock {
        snapshot.value=snapshot.value.filterNot { it==entry }; runCatching { persist() }
    } } }
    fun clear() {
        clearScreenshots()
        epoch++
        clears.value=epoch
        scope.launch(Dispatchers.IO) { loaded.await(); mutex.withLock { snapshot.value=emptyList(); file.delete() } }
    }
}
