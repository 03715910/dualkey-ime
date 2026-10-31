package io.github.user03715910.dualkey.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.dualkey.core.LearnedChoice
import io.github.dualkey.core.Personalization
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LearningSnapshot(val revision: Long=0,val entries: List<LearnedChoice> = emptyList())

/** Private on-device counts of confirmed choices, never a keystroke/full-text log. */
class LearningStore(context: Context,private val scope: CoroutineScope,private val enabled: ()->Boolean) {
    private val db=object: SQLiteOpenHelper(context,"dualkey_learning.db",null,1) {
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL("CREATE TABLE choices (reading TEXT NOT NULL, surface TEXT NOT NULL, uses INTEGER NOT NULL, last_used INTEGER NOT NULL, PRIMARY KEY (reading,surface))")
        }
        override fun onUpgrade(database: SQLiteDatabase,oldVersion: Int,newVersion: Int) {
            error("A learning database migration is required")
        }
    }
    private val mutex=Mutex()
    private val loaded=CompletableDeferred<Unit>()
    private val snapshot=MutableStateFlow(LearningSnapshot())
    val state=snapshot.asStateFlow()
    private val status=MutableStateFlow("学習データを準備しています…")
    val message=status.asStateFlow()
    @Volatile private var epoch=0L

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
                snapshot.value=LearningSnapshot(1,entries); status.value="学習した候補：${entries.size}件"
            } } catch (_: Exception) { status.value="学習データを読み込めませんでした。文字入力は続けられます。" }
            finally { loaded.complete(Unit) }
        }
    }
    suspend fun awaitLoaded() { loaded.await() }

    fun record(reading: String,text: String) {
        val key=Personalization.normalize(reading)
        if (!enabled() || !Personalization.valid(key,text)) return
        val capturedEpoch=epoch
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
                    snapshot.value=LearningSnapshot(old.revision+1,retained)
                    status.value="学習した候補：${retained.size}件"
                } catch (_: Exception) { status.value="学習を保存できませんでした。空き容量を確認してください。" }
            }
        }
    }

    /** Increment synchronously so already queued confirmations cannot resurrect cleared data. */
    fun clear() {
        epoch++
        scope.launch(Dispatchers.IO) {
            awaitLoaded()
            mutex.withLock {
                try {
                    db.writableDatabase.delete("choices",null,null)
                    snapshot.value=LearningSnapshot(snapshot.value.revision+1)
                    status.value="学習データを削除しました"
                } catch (_: Exception) { status.value="削除できませんでした。もう一度お試しください。" }
            }
        }
    }
}
