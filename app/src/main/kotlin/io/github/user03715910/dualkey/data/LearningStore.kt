package io.github.user03715910.dualkey.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.dualkey.core.LearnedChoice
import io.github.dualkey.core.Personalization
import io.github.dualkey.core.LearnedContinuation
import io.github.dualkey.core.NextWordPrediction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LearningSnapshot(val revision: Long=0,val entries: List<LearnedChoice> = emptyList(),val continuations: List<LearnedContinuation> = emptyList())

/** Private on-device counts of confirmed choices, never a keystroke/full-text log. */
class LearningStore(context: Context,private val scope: CoroutineScope,databaseName: String="dualkey_learning.db",private val enabled: ()->Boolean) {
    private val db=object: SQLiteOpenHelper(context,databaseName,null,2) {
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL("CREATE TABLE choices (reading TEXT NOT NULL, surface TEXT NOT NULL, uses INTEGER NOT NULL, last_used INTEGER NOT NULL, PRIMARY KEY (reading,surface))")
            addContinuations(database)
        }
        override fun onUpgrade(database: SQLiteDatabase,oldVersion: Int,newVersion: Int) {
            if (oldVersion<2) addContinuations(database)
        }
    }
    private fun addContinuations(database: SQLiteDatabase) = database.execSQL(
        "CREATE TABLE continuations (context TEXT NOT NULL, surface TEXT NOT NULL, reading TEXT NOT NULL, uses INTEGER NOT NULL, last_used INTEGER NOT NULL, PRIMARY KEY (context,surface))")
    private val mutex=Mutex()
    private val loaded=CompletableDeferred<Unit>()
    private val snapshot=MutableStateFlow(LearningSnapshot())
    val state=snapshot.asStateFlow()
    private val status=MutableStateFlow("学習データを準備しています…")
    val message=status.asStateFlow()
    @Volatile private var epoch=0L
    val generation get()=epoch

    init {
        scope.launch(Dispatchers.IO) {
            try { mutex.withLock {
                val entries=mutableListOf<LearnedChoice>()
                db.readableDatabase.query("choices",null,null,null,null,null,"uses DESC, last_used DESC","5000").use { cursor ->
                    while (cursor.moveToNext()) {
                        val choice=LearnedChoice(cursor.getString(0),cursor.getString(1),cursor.getInt(2),cursor.getLong(3))
                        if (Personalization.valid(choice.reading,choice.text)) entries.add(choice)
                    }
                }
                val continuations=mutableListOf<LearnedContinuation>()
                db.readableDatabase.query("continuations",null,null,null,null,null,"uses DESC, last_used DESC","5000").use { cursor ->
                    while (cursor.moveToNext()) {
                        val choice=LearnedContinuation(cursor.getString(0),cursor.getString(1),cursor.getString(2),cursor.getInt(3),cursor.getLong(4))
                        if (NextWordPrediction.valid(choice)) continuations.add(choice)
                    }
                }
                snapshot.value=LearningSnapshot(1,entries,continuations); updateStatus()
            } } catch (_: Exception) { status.value="学習データを読み込めませんでした。文字入力は続けられます。" }
            finally { loaded.complete(Unit) }
        }
    }
    suspend fun awaitLoaded() { loaded.await() }
    fun close() { db.close() }

    fun record(reading: String,text: String,expectedGeneration: Long=epoch) {
        val key=Personalization.normalize(reading)
        if (expectedGeneration!=epoch || !enabled() || !Personalization.valid(key,text)) return
        val capturedEpoch=expectedGeneration
        scope.launch(Dispatchers.IO) {
            awaitLoaded()
            mutex.withLock {
                if (capturedEpoch!=epoch || !enabled()) return@withLock
                try {
                    val old=snapshot.value
                    val previous=old.entries.firstOrNull { it.reading==key && it.text==text }
                    val choice=LearnedChoice(key,text,((previous?.count ?: 0)+1).coerceAtMost(1_000_000),System.currentTimeMillis())
                    val values=ContentValues().apply {
                        put("reading",key); put("surface",text); put("uses",choice.count); put("last_used",choice.lastUsed)
                    }
                    db.writableDatabase.insertWithOnConflict("choices",null,values,SQLiteDatabase.CONFLICT_REPLACE).also { check(it!=-1L) }
                    val retained=(old.entries.filterNot { it.reading==key && it.text==text }+choice)
                        .sortedWith(compareByDescending<LearnedChoice> { it.count }.thenByDescending { it.lastUsed }).take(5000)
                    if (old.entries.size>=5000 && previous==null) db.writableDatabase.execSQL(
                        "DELETE FROM choices WHERE rowid NOT IN (SELECT rowid FROM choices ORDER BY uses DESC,last_used DESC LIMIT 5000)")
                    snapshot.value=old.copy(revision=old.revision+1,entries=retained)
                    updateStatus()
                } catch (_: Exception) { status.value="学習を保存できませんでした。空き容量を確認してください。" }
            }
        }
    }

    fun recordContinuation(context: String,reading: String,text: String,expectedGeneration: Long=epoch) {
        val key=Personalization.normalize(reading)
        if (expectedGeneration!=epoch || !enabled() || !NextWordPrediction.valid(LearnedContinuation(context,text,key,1,0))) return
        val capturedEpoch=expectedGeneration
        scope.launch(Dispatchers.IO) {
            awaitLoaded()
            mutex.withLock {
                if (capturedEpoch!=epoch || !enabled()) return@withLock
                try {
                    val old=snapshot.value
                    val previous=old.continuations.firstOrNull { it.context==context && it.text==text }
                    val choice=LearnedContinuation(context,text,key,((previous?.count ?: 0)+1).coerceAtMost(1_000_000),System.currentTimeMillis())
                    val values=ContentValues().apply {
                        put("context",context); put("surface",text); put("reading",key); put("uses",choice.count); put("last_used",choice.lastUsed)
                    }
                    check(db.writableDatabase.insertWithOnConflict("continuations",null,values,SQLiteDatabase.CONFLICT_REPLACE)!=-1L)
                    val retained=(old.continuations.filterNot { it.context==context && it.text==text }+choice)
                        .sortedWith(compareByDescending<LearnedContinuation> { it.count }.thenByDescending { it.lastUsed }).take(5000)
                    if (old.continuations.size>=5000 && previous==null) db.writableDatabase.execSQL(
                        "DELETE FROM continuations WHERE rowid NOT IN (SELECT rowid FROM continuations ORDER BY uses DESC,last_used DESC LIMIT 5000)")
                    snapshot.value=old.copy(revision=old.revision+1,continuations=retained); updateStatus()
                } catch (_: Exception) { status.value="学習を保存できませんでした。文字入力は続けられます。" }
            }
        }
    }
    private fun updateStatus() { status.value="学習した候補：${snapshot.value.entries.size}件／続きの組合せ：${snapshot.value.continuations.size}件" }

    /** Increment synchronously so already queued confirmations cannot resurrect cleared data. */
    fun clear() {
        epoch++
        scope.launch(Dispatchers.IO) {
            awaitLoaded()
            mutex.withLock {
                try {
                    val database=db.writableDatabase
                    database.beginTransaction()
                    try { database.delete("choices",null,null); database.delete("continuations",null,null); database.setTransactionSuccessful() }
                    finally { database.endTransaction() }
                    snapshot.value=LearningSnapshot(snapshot.value.revision+1)
                    status.value="学習データを削除しました"
                } catch (_: Exception) { status.value="削除できませんでした。もう一度お試しください。" }
            }
        }
    }
}
