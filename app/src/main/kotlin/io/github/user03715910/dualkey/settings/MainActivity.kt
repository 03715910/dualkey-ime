package io.github.user03715910.dualkey.settings

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.*
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.lifecycleScope
import io.github.dualkey.core.*
import io.github.user03715910.dualkey.*
import io.github.user03715910.dualkey.data.*
import io.github.user03715910.dualkey.translation.TranslationEngine
import io.github.user03715910.dualkey.update.*
import kotlinx.coroutines.*
import java.io.File

class MainActivity : AppCompatActivity() {
    private val app get()=application as DualKeyApp
    private lateinit var content: LinearLayout
    private lateinit var testInput: EditText
    private lateinit var updateStatus: TextView
    private lateinit var updateDetails: TextView
    private lateinit var downloadButton: Button
    private lateinit var installButton: Button
    private var pendingInstall=false
    private val translator=TranslationEngine()
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingInstall=savedInstanceState?.getBoolean("pendingInstall") ?: false
        WindowCompat.setDecorFitsSystemWindows(window,false)
        val light=resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window,window.decorView).apply { isAppearanceLightStatusBars=light; isAppearanceLightNavigationBars=light }
        lifecycleScope.launch {
            app.settings.awaitLoaded(); buildScreen()
            launch { app.updates.state.collect { renderUpdate(it) } }
            app.updates.restoreCached()
            if (app.settings.state.value.autoCheck) {
                val p=getSharedPreferences("update_timing",MODE_PRIVATE)
                val now=System.currentTimeMillis()
                if (now-p.getLong("last_attempt",0)>3_600_000) { p.edit().putLong("last_attempt",now).apply(); checkUpdates() }
            }
            if (intent.getBooleanExtra("restore_keyboard",false)) restoreKeyboard()
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if (::testInput.isInitialized && intent.getBooleanExtra("restore_keyboard",false)) restoreKeyboard() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("pendingInstall",pendingInstall); super.onSaveInstanceState(outState) }
    override fun onResume() {
        super.onResume()
        if (pendingInstall && packageManager.canRequestPackageInstalls()) {
            pendingInstall=false
            lifecycleScope.launch { app.updates.state.value.let { s -> if (s.apk!=null && s.release!=null) install(s.apk,s.release) } }
        }
    }
    override fun onDestroy() { translator.close(); super.onDestroy() }
    private fun text(value: String, size: Float=16f)=TextView(this).apply {
        text=value; textSize=size; setPadding(0,dp(5),0,dp(5)); setTextIsSelectable(true)
    }.also { content.addView(it) }
    private fun title(value: String) { text(value,22f).setPadding(0,dp(24),0,dp(7)) }
    private fun button(label: String, action: ()->Unit)=Button(this).apply {
        text=label; isAllCaps=false; setOnClickListener { action() }
    }.also { content.addView(it,LinearLayout.LayoutParams(-1,-2)) }
    private fun <T> save(key: Preferences.Key<T>,value: T) { lifecycleScope.launch { app.settings.set(key,value) } }
    private fun toggle(label: String, key: Preferences.Key<Boolean>, value: Boolean, extra: ((Boolean)->Unit)?=null) {
        content.addView(androidx.appcompat.widget.SwitchCompat(this).apply {
            text=label; textSize=16f; minHeight=dp(56); isChecked=value
            setOnCheckedChangeListener { _,checked -> save(key,checked); extra?.invoke(checked) }
        },LinearLayout.LayoutParams(-1,-2))
    }
    private fun slider(label: String,key: Preferences.Key<Int>,value: Int,min: Int,max: Int) {
        val caption=text("$label：$value")
        content.addView(SeekBar(this).apply {
            this.max=max-min; progress=value-min
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?,p: Int,fromUser: Boolean) { caption.text="$label：${p+min}" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar) { save(key,s.progress+min) }
            })
        },LinearLayout.LayoutParams(-1,dp(48)))
    }
    private fun selection(label: String,labels: List<String>,index: Int,select: (Int)->Unit) {
        text(label)
        content.addView(Spinner(this).apply {
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,labels)
            setSelection(index)
            var previous=index
            onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?,v: View?,position: Int,id: Long) { if (position!=previous) { previous=position; select(position) } }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        },LinearLayout.LayoutParams(-1,dp(52)))
    }
    private fun buildScreen() {
        val scroll=ScrollView(this)
        content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; isFocusableInTouchMode=true }
        scroll.addView(content); setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
            scroll.setPadding(bars.left+dp(18),bars.top,bars.right+dp(18),bars.bottom+dp(24)); insets
        }
        val s=app.settings.state.value
        title("DualKey IME")
        text("タッチと物理キーボードを、ひとつの入力に。",18f)
        text("バージョン ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}） / Android 8.0以上")
        button("1. キーボードを有効にする") { openIntent(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS)) }
        button("2. 使用するキーボードを選択") { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        text("Androidの入力方法の警告を確認し、信頼できる場合にDualKeyを有効にしてください。入力文章をログに保存しません。")
        testInput=EditText(this).apply {
            hint="ここをタップして入力を試す"; minLines=2; maxLines=5
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            tag="test_input"
            isSaveEnabled=false; id=View.generateViewId()
        }
        content.addView(testInput,LinearLayout.LayoutParams(-1,-2))
        button("画面キーボードを通常表示に戻す") { restoreKeyboard() }
        button("省スペース表示にする") { ImeControl.request(DisplayMode.COMPACT); toast("次に入力欄をタップすると省スペース表示になります") }
        button("完全非表示にする") { ImeControl.request(DisplayMode.HIDDEN); toast("復帰は通常表示ボタン・Ctrl+Alt+K・クイック設定から行えます") }
        text("完全非表示からは、このアプリを開き「通常表示に戻す」を押せます。入力先にフォーカスが必要なため、この画面では上のテスト欄を開きます。通知パネルの編集から「DualKeyを表示」タイルも追加できます。")

        title("入力設定")
        toggle("初期モードを英語にする",Prefs.initialEnglish,s.initialEnglish)
        toggle("数字専用行を表示",Prefs.numberRow,s.numberRow)
        slider("フリック開始距離（dp）",Prefs.flickDp,s.flickDp,8,64)
        slider("キーの高さ（dp）",Prefs.keyHeight,s.keyHeight,32,80)
        slider("キーボード全体の倍率（%）",Prefs.heightPercent,s.heightPercent,70,140)
        text("横画面などでは画面に収まる高さへ自動調整します。")
        toggle("キー操作時の振動",Prefs.vibration,s.vibration)
        toggle("キー操作音",Prefs.sound,s.sound)

        title("物理キーボード設定")
        selection("物理キーボード接続時の画面表示",listOf("自動（接続で完全非表示）","常に通常表示","省スペース表示","完全非表示"),s.policy.ordinal) {
            save(Prefs.hardwarePolicy,HardwarePolicy.entries[it].name)
        }
        toggle("前回の手動選択を次の接続時も記憶",Prefs.rememberManual,s.rememberManual)
        toggle("Ctrl + Alt + K で表示を切り替え",Prefs.shortcut,s.shortcut)
        toggle("Ctrl + Space で日本語・英語切り替え",Prefs.ctrlSpace,s.ctrlSpace)
        text("切断時は通常表示に復帰します。半角/全角・英数・かなキーにも対応します。OSがショートカットを先に処理する端末では、アプリ画面やタイルをご利用ください。配列とShift/Caps LockはAndroidが生成するキー情報に従います。")
        button("Androidの物理キーボード設定") { openIntent(Intent(AndroidSettings.ACTION_HARD_KEYBOARD_SETTINGS)) }

        title("変換設定")
        toggle("日本語の予測候補",Prefs.japanesePrediction,s.japanesePrediction)
        toggle("英語の予測候補",Prefs.englishPrediction,s.englishPrediction)
        slider("最大候補数",Prefs.candidateCount,s.candidateCount,2,30)
        text("現在は端末内の小規模辞書を使った簡易変換です。Mozcは未導入です。一般的な日本語IMEと同等の辞書量・文節解析はありません。")
        button("ユーザー辞書に単語を追加") { addWord() }
        button("ユーザー辞書を確認・削除") { dictionary() }

        title("翻訳設定")
        toggle("翻訳方向の初期値：日本語 → 英語",Prefs.translateToEnglish,s.toEnglish)
        text("Google ML Kitによる端末内翻訳です。初回は約30MBの日本語モデルをWi-Fiで取得します。翻訳結果をプレビューしてから確定します。パスワード欄では使用できません。")
        button("翻訳モデルをダウンロード（Wi-Fi）") { lifecycleScope.launch {
            toast("モデルを取得します。Wi-Fi接続でしばらくお待ちください")
            try { translator.download(app.settings.state.value.toEnglish); toast("モデルの準備ができました") }
            catch (e: CancellationException) { throw e } catch (_: Exception) { toast("モデルを取得できませんでした。Wi-Fi接続を確認してください") }
        } }
        button("翻訳モデルを削除") { lifecycleScope.launch {
            try { translator.delete(); toast("日本語の翻訳モデルを削除しました") }
            catch (e: CancellationException) { throw e } catch (_: Exception) { toast("モデルを削除できませんでした") }
        } }
        button("Google翻訳について") { openIntent(Intent(Intent.ACTION_VIEW,Uri.parse("https://cloud.google.com/translate"))) }

        title("アプリ更新")
        text("現在：${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）")
        toggle("自動更新確認（約12時間ごと）",Prefs.autoCheck,s.autoCheck)
        toggle("APKを自動ダウンロード",Prefs.autoDownload,s.autoDownload)
        toggle("Wi-Fi接続時のみダウンロード",Prefs.wifiOnly,s.wifiOnly)
        toggle("更新通知",Prefs.notifications,s.notifications) { if (it) requestNotificationPermission() }
        if (Build.VERSION.SDK_INT>=33) button("Androidの通知を許可する") { requestNotificationPermission() }
        updateStatus=text("")
        updateDetails=text("")
        button("今すぐ更新を確認") { lifecycleScope.launch { checkUpdates() } }
        downloadButton=button("更新APKをダウンロード") { downloadUpdate() }
        installButton=button("検証済みAPKをインストール") {
            val state=app.updates.state.value
            if (state.apk!=null && state.release!=null) lifecycleScope.launch { install(state.apk,state.release) }
        }
        button("ダウンロードサイトを開く") { openWebsite() }
        button("更新履歴を表示") {
            val release=app.updates.state.value.release
            if (release!=null) AlertDialog.Builder(this).setTitle(release.versionName).setMessage(release.releaseNotes.joinToString("\n")).setPositiveButton("閉じる",null).show()
            else if (BuildConfig.WEBSITE_URL.isNotBlank()) openIntent(Intent(Intent.ACTION_VIEW,Uri.parse(BuildConfig.WEBSITE_URL+"#history")))
            else toast("配布サイトの公開後に利用できます。0.1.0：初期開発版")
        }
        text("自動処理は更新検出と設定に応じたAPK取得までです。インストールはボタンを押してAndroid標準の確認を行います。定期処理の時刻はOSが調整します。設定・ユーザー辞書は同じ署名・アプリIDでの更新時に保持されます。")

        title("アプリ情報・プライバシー")
        text("入力文章を独自サーバーへ送信しません。キー入力をログや履歴に保存しません。パスワード欄では予測・翻訳・未確定プレビューを無効にします。ユーザー辞書は追加操作をした単語だけを端末内に保存します。")
        text("通信はアプリ更新、翻訳モデル取得、およびGoogle ML Kitの利用状況・診断通信に使われます。ML Kit翻訳に入力文章の送信は不要です。端末のバックアップ対象からは除外しています。アプリをアンインストールすると保存データが消えます。")
        button("ライセンス・利用技術") {
            val notice=assets.open("NOTICE.txt").bufferedReader().use { it.readText() }
            AlertDialog.Builder(this).setTitle("ライセンス・利用技術").setMessage(notice).setPositiveButton("閉じる",null).show()
        }
        text("Samsungの「画面キーボードを表示」やセキュリティ設定、他アプリの入力実装により動作が変わる場合があります。Bluetooth/USB接続と更新は実機での確認が必要です。")
        renderUpdate(app.updates.state.value)
    }
    private fun restoreKeyboard() {
        ImeControl.request(DisplayMode.FULL)
        testInput.requestFocus()
        testInput.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(testInput,0) },250)
    }
    private fun renderUpdate(state: UpdateState) {
        if (!::updateStatus.isInitialized) return
        updateStatus.text=state.message+(state.progress?.let { " $it%" } ?: "")
        updateDetails.text=state.release?.let { r -> "現在 ${BuildConfig.VERSION_NAME} → ${r.versionName}\n${r.releaseDate} / ${"%.1f".format(r.sizeBytes/1048576.0)} MB\n"+r.releaseNotes.joinToString("\n")+(if (r.required) "\n配布元は必須更新を指定しています。入力機能は継続できます。" else "") }.orEmpty()
        downloadButton.isEnabled=state.release!=null && !state.busy
        installButton.isEnabled=state.apk!=null && !state.busy
    }
    private suspend fun checkUpdates() {
        val info=app.updates.check()
        if (info!=null && app.settings.state.value.autoDownload) UpdateScheduler.download(this,app.settings.state.value.wifiOnly)
    }
    private fun downloadUpdate() {
        val info=app.updates.state.value.release ?: return
        if (app.settings.state.value.wifiOnly && !UpdateScheduler.isWifi(this)) { toast("Wi-Fiに接続してください。設定でWi-Fi限定を解除することもできます"); return }
        lifecycleScope.launch { app.updates.download(info) }
    }
    private suspend fun install(file: File,info: ReleaseInfo) {
        if (ImeControl.inputActive && !testInput.hasFocus()) { toast("入力を終了してから更新ボタンを押してください"); return }
        testInput.clearFocus(); content.requestFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(testInput.windowToken,0)
        try {
            withContext(Dispatchers.IO) { ApkSafety(this@MainActivity).verify(file,info) }
            if (!packageManager.canRequestPackageInstalls()) {
                pendingInstall=true
                AlertDialog.Builder(this).setTitle("インストールの許可")
                    .setMessage("次のAndroid設定でDualKey IMEからのインストールを許可してください。戻ると検証済みAPKの確認画面へ進みます。")
                    .setPositiveButton("設定を開く") { _,_ -> openIntent(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName"))) }
                    .setNegativeButton("あとで") { _,_ -> pendingInstall=false }.show()
                return
            }
            val uri=FileProvider.getUriForFile(this,"$packageName.updates",file)
            openIntent(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { toast("インストールを開始できません。${e.message.orEmpty().take(100)}") }
    }
    private fun addWord() {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),0,dp(20),0) }
        val reading=EditText(this).apply { hint="よみ（ひらがな）" }
        val word=EditText(this).apply { hint="単語" }
        box.addView(reading); box.addView(word)
        val dialog=AlertDialog.Builder(this).setTitle("ユーザー辞書に追加").setView(box).setPositiveButton("追加",null).setNegativeButton("キャンセル",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val r=reading.text.toString().trim(); val w=word.text.toString().trim()
            if (!r.matches(Regex("[ぁ-ゖー]{1,50}")) || w.isEmpty() || w.length>100) { toast("よみはひらがな50文字以内、単語は100文字以内で入力してください"); return@setOnClickListener }
            lifecycleScope.launch { app.settings.addWord(r,w); dialog.dismiss(); toast("単語を追加しました") }
        } }; dialog.show()
    }
    private fun dictionary() {
        val words=app.settings.state.value.dictionary
        if (words.isEmpty()) { toast("登録した単語はありません"); return }
        val keys=words.keys.sorted()
        AlertDialog.Builder(this).setTitle("削除する読みを選択").setItems(keys.map { "$it：${words[it]?.joinToString(" / ")}" }.toTypedArray()) { _,i ->
            AlertDialog.Builder(this).setMessage("「${keys[i]}」の登録を削除しますか？").setPositiveButton("削除") { _,_ -> lifecycleScope.launch { app.settings.removeWord(keys[i]); toast("削除しました") } }.setNegativeButton("戻る",null).show()
        }.setNegativeButton("閉じる",null).show()
    }
    private fun requestNotificationPermission() { if (Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),301) }
    private fun openWebsite() { if (BuildConfig.WEBSITE_URL.isBlank()) toast("配布サイトはまだ公開されていません") else openIntent(Intent(Intent.ACTION_VIEW,Uri.parse(BuildConfig.WEBSITE_URL))) }
    private fun openIntent(intent: Intent) { try { startActivity(intent) } catch (_: ActivityNotFoundException) { toast("この端末では設定画面を開けません。Android設定から操作してください") } }
    private fun toast(message: String) { Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
}
