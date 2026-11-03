package io.github.user03715910.dualkey.settings

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.view.*
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.github.dualkey.core.*
import io.github.user03715910.dualkey.*
import io.github.user03715910.dualkey.data.*
import io.github.user03715910.dualkey.translation.TranslationEngine
import io.github.user03715910.dualkey.ime.KeyboardVisibilityNotification
import io.github.user03715910.dualkey.update.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

class MainActivity : AppCompatActivity() {
    private enum class Page { HOME, SETTINGS, UPDATES, INPUT, HARDWARE, CONVERSION, TRANSLATION, ABOUT }
    private val app get()=application as DualKeyApp
    private lateinit var ui: SettingsUi
    private lateinit var pageContent: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var testInput: EditText?=null
    private var page=Page.HOME
    private var uiReady=false
    private var updateStatus: TextView?=null
    private var updateDetails: TextView?=null
    private var updatePrimary: Button?=null
    private var updateCheck: Button?=null
    private var updateProgress: ProgressBar?=null
    private var homeUpdateStatus: TextView?=null
    private var homeUpdateAction: Button?=null
    private var keyboardStatus: TextView?=null
    private var installWhenReady=false
    private var awaitingPermissionResult=false
    private var installInFlight=false
    private var promptOnAvailable=false
    private var promptedVersion=0L
    private var lastCheckAttempt=0L
    private var checkJob: Job?=null
    private var updateDialog: AlertDialog?=null
    private var permissionDialog: AlertDialog?=null
    private var shortcutDialog: AlertDialog?=null
    private val permissionLauncher=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val requested=awaitingPermissionResult && installWhenReady && page==Page.UPDATES
        awaitingPermissionResult=false
        installWhenReady=requested && packageManager.canRequestPackageInstalls()
        lastCheckAttempt=SystemClock.elapsedRealtime()
        if (uiReady) { renderUpdate(app.updates.state.value); resumeInstallationFlow() }
    }
    private val translator=TranslationEngine()
    private fun dp(n: Int)=ui.dp(n)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui=SettingsUi(this)
        installWhenReady=savedInstanceState?.getBoolean("installWhenReady") ?: false
        awaitingPermissionResult=savedInstanceState?.getBoolean("awaitingPermissionResult") ?: false
        promptedVersion=savedInstanceState?.getLong("promptedVersion") ?: 0L
        page=runCatching { Page.valueOf(savedInstanceState?.getString("page") ?: "HOME") }.getOrDefault(Page.HOME)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        WindowInsetsControllerCompat(window,window.decorView).apply {
            val light=resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK!=android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars=light; isAppearanceLightNavigationBars=light
        }
        setContentView(R.layout.activity_main)
        val root=findViewById<LinearLayout>(R.id.app_root)
        root.setBackgroundColor(ui.paper)
        findViewById<TextView>(R.id.app_title).setTextColor(ui.ink)
        findViewById<View>(R.id.app_navigation).setBackgroundColor(ui.surface)
        scroll=findViewById(R.id.page_scroll)
        pageContent=findViewById(R.id.page_content)
        content=pageContent
        ViewCompat.setOnApplyWindowInsetsListener(root) { v,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime=insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,ime.bottom))
            findViewById<View>(R.id.app_navigation).visibility=if (insets.isVisible(WindowInsetsCompat.Type.ime())) View.GONE else View.VISIBLE
            insets
        }
        listOf(R.id.nav_home to Page.HOME,R.id.nav_settings to Page.SETTINGS,R.id.nav_updates to Page.UPDATES).forEach { (id,target) ->
            findViewById<TextView>(id).setOnClickListener { selectPage(target) }
        }
        onBackPressedDispatcher.addCallback(this,object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page!=Page.HOME) selectPage(if (page in listOf(Page.SETTINGS,Page.UPDATES)) Page.HOME else Page.SETTINGS)
                else { isEnabled=false; onBackPressedDispatcher.onBackPressed(); isEnabled=true }
            }
        })
        lifecycleScope.launch {
            app.settings.awaitLoaded()
            uiReady=true
            if (savedInstanceState==null) {
                if (intent.getBooleanExtra("settings",false)) page=Page.SETTINGS
                if (intent.getBooleanExtra("updates",false)) page=Page.UPDATES
                if (intent.getBooleanExtra("restore_keyboard",false)) page=Page.HOME
            }
            promptOnAvailable=app.settings.state.value.autoCheck && page!=Page.UPDATES && !intent.getBooleanExtra("restore_keyboard",false)
            selectPage(page)
            launch { app.updates.state.collect { renderUpdate(it) } }
            launch { app.learning.message.collect { message -> pageContent.findViewWithTag<TextView>("learning_status")?.text=message } }
            if (!app.updates.state.value.busy) app.updates.restoreCached()
            resumeInstallationFlow()
            if (savedInstanceState==null && intent.getBooleanExtra("restore_keyboard",false)) restoreKeyboard()
            if (app.settings.state.value.autoCheck && !awaitingPermissionResult && !installWhenReady) requestCheck(true)
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (!uiReady) return
        when {
            intent.getBooleanExtra("restore_keyboard",false) -> { selectPage(Page.HOME); restoreKeyboard() }
            intent.getBooleanExtra("updates",false) -> { selectPage(Page.UPDATES); if (!app.updates.state.value.busy) lifecycleScope.launch { app.updates.restoreCached() } }
            intent.getBooleanExtra("settings",false) -> selectPage(Page.SETTINGS)
            app.settings.state.value.autoCheck && !awaitingPermissionResult && !installWhenReady -> requestCheck(true)
        }
    }
    override fun onStart() {
        super.onStart()
        if (uiReady && app.settings.state.value.autoCheck && !awaitingPermissionResult && !installWhenReady) requestCheck(true)
    }
    override fun onResume() {
        super.onResume()
        KeyboardVisibilityNotification.refresh(this)
        if (uiReady) { refreshKeyboardStatus(); resumeInstallationFlow(); maybeShowUpdatePrompt() }
    }
    override fun onPostResume() { super.onPostResume(); if (uiReady) resumeInstallationFlow() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && uiReady) { resumeInstallationFlow(); maybeShowUpdatePrompt() }
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("page",page.name); out.putBoolean("installWhenReady",installWhenReady)
        out.putBoolean("awaitingPermissionResult",awaitingPermissionResult); out.putLong("promptedVersion",promptedVersion)
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() { updateDialog?.dismiss(); permissionDialog?.dismiss(); shortcutDialog?.dismiss(); translator.close(); super.onDestroy() }
    private fun text(value: String,size: Float=14f)=ui.label(value,size,secondary=size<16).also(content::addView)
    private fun button(label: String,primary: Boolean=false,action: ()->Unit)=ui.button(content,label,primary,action)
    private fun section(title: String,subtitle: String?=null,body: ()->Unit) {
        val parent=content; content=ui.card(parent,title,subtitle)
        body(); content=parent
    }
    private fun heading(title: String,subtitle: String) {
        content.addView(ui.label(title,24f,bold=true)); text(subtitle).setPadding(0,0,0,dp(16))
    }
    private fun <T> save(key: Preferences.Key<T>,value: T) { lifecycleScope.launch { app.settings.set(key,value) } }
    private fun toggle(label: String,key: Preferences.Key<Boolean>,value: Boolean,extra: ((Boolean)->Unit)?=null) {
        content.addView(SwitchCompat(this).apply {
            text=label; textSize=16f; setTextColor(ui.ink); minHeight=dp(58); setPadding(0,dp(10),0,dp(10)); isChecked=value
            setOnCheckedChangeListener { _,checked -> save(key,checked); extra?.invoke(checked) }
        },LinearLayout.LayoutParams(-1,-2))
    }
    private fun slider(label: String,key: Preferences.Key<Int>,value: Int,min: Int,max: Int,unit: String="") {
        val row=ui.row(content)
        row.addView(ui.label(label,16f),LinearLayout.LayoutParams(0,-2,1f))
        val valueLabel=ui.label("$value$unit",15f,bold=true).also { row.addView(it) }
        content.addView(SeekBar(this).apply {
            this.max=max-min; progress=value-min; contentDescription=label
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?,p: Int,fromUser: Boolean) { valueLabel.text="${p+min}$unit" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar) { save(key,s.progress+min) }
            })
        },LinearLayout.LayoutParams(-1,dp(44)))
    }
    private fun selection(label: String,labels: List<String>,index: Int,select: (Int)->Unit) {
        text(label,16f)
        button(labels[index]) {
            AlertDialog.Builder(this).setTitle(label).setSingleChoiceItems(labels.toTypedArray(),index) { dialog,which ->
                select(which); dialog.dismiss(); lifecycleScope.launch { delay(100); selectPage(page) }
            }.setNegativeButton("閉じる",null).show()
        }
    }
    private fun selectPage(target: Page) {
        if (target!=Page.UPDATES) { installWhenReady=false; awaitingPermissionResult=false }
        if (target==Page.HOME && page==Page.HOME && pageContent.childCount>0) { renderUpdate(app.updates.state.value); return }
        if (target==Page.UPDATES) promptOnAvailable=false
        if (page!=target || target!=Page.HOME) hideKeyboard()
        page=target; pageContent.removeAllViews(); content=pageContent
        testInput=null; updateStatus=null; updateDetails=null; updatePrimary=null; updateCheck=null; updateProgress=null
        homeUpdateStatus=null; homeUpdateAction=null; keyboardStatus=null
        if (target !in listOf(Page.HOME,Page.SETTINGS,Page.UPDATES)) button("‹ 設定に戻る") { selectPage(Page.SETTINGS) }
        when (target) {
            Page.HOME -> home()
            Page.SETTINGS -> settingsMenu()
            Page.UPDATES -> updates()
            Page.INPUT -> inputSettings()
            Page.HARDWARE -> hardwareSettings()
            Page.CONVERSION -> conversionSettings()
            Page.TRANSLATION -> translationSettings()
            Page.ABOUT -> about()
        }
        scroll.post { scroll.scrollTo(0,0) }
        renderUpdate(app.updates.state.value); refreshKeyboardStatus()
    }
    private fun home() {
        heading("あなたのキーボード","フリックと物理キーボードを、ひとつに。")
        section("アプリの更新") {
            homeUpdateStatus=text("")
            homeUpdateAction=button("更新を確認") { selectPage(Page.UPDATES); if (app.updates.state.value.release==null) requestCheck(false,true) else startUpdate() }
        }
        section("キーボードの準備") {
            keyboardStatus=text("",16f)
            button("1. キーボードを有効にする") { openIntent(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS)) }
            button("2. 使用するキーボードを選択",true) { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
            text("初回だけAndroidの設定が開きます。")
        }
        section("入力を試す","ここでサイズや打ち心地を確認できます。") {
            testInput=EditText(this).apply {
                hint="ここをタップして入力"; textSize=18f; setTextColor(ui.ink); setHintTextColor(ui.muted)
                minLines=2; maxLines=5; minHeight=dp(96)
                inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                background=ui.shape(ui.paper,12,true); setPadding(dp(14),dp(12),dp(14),dp(12))
                tag="test_input"; isSaveEnabled=false; id=View.generateViewId()
            }.also { content.addView(it,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) }) }
            button("画面キーボードを表示",true) { restoreKeyboard() }
            val parent=content; val row=ui.row(parent); content=row
            button("省スペース") { ImeControl.request(DisplayMode.COMPACT); restoreEditorFocus() }.layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply { topMargin=dp(8); marginEnd=dp(4) }
            button("隠す") { ImeControl.request(DisplayMode.HIDDEN); hideKeyboard() }.layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply { topMargin=dp(8); marginStart=dp(4) }
            content=parent
            button("入力とサイズを調整") { selectPage(Page.INPUT) }
        }
    }
    private fun settingsMenu() {
        heading("設定","使いたい項目を選んでください。")
        listOf(
            Triple("入力とサイズ","言語・数字行・キーボードの高さ",Page.INPUT),
            Triple("物理キーボード","接続時の表示・ショートカット",Page.HARDWARE),
            Triple("変換と辞書","予測候補・ユーザー辞書",Page.CONVERSION),
            Triple("翻訳","日英の切り替え・モデルの管理",Page.TRANSLATION),
            Triple("アプリ更新","更新の確認・自動ダウンロード",Page.UPDATES),
            Triple("アプリについて","バージョン・プライバシー・ライセンス",Page.ABOUT)
        ).forEach { (title,detail,target) ->
            ui.card(content,"$title  ›",detail).apply {
                tag="settings_${target.name.lowercase()}"; isClickable=true; isFocusable=true; minimumHeight=dp(80)
                background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22087F8C),ui.shape(ui.surface),null)
                setOnClickListener { selectPage(target) }
            }
        }
    }
    private fun inputSettings() {
        val s=app.settings.state.value
        heading("入力とサイズ","お好みの大きさと操作感に。")
        section("画面キーボードの入力方式") {
            selection("日本語の配列",listOf("12キーフリック","ローマ字QWERTY"),if (s.japaneseQwerty) 1 else 0) { save(Prefs.japaneseLayout,if (it==1) "QWERTY" else "FLICK") }
            selection("英語の配列",listOf("QWERTY","12キーフリック"),if (s.englishFlick) 1 else 0) { save(Prefs.englishLayout,if (it==1) "FLICK" else "QWERTY") }
            text("日英は下段の「abc／日本語」で切り替えます。画面配列の設定は外付けキーボードに影響しません。")
        }
        section("Enterキー") {
            selection("画面キーボード",listOf("チャット・複数行は改行","入力先のアクション","常に改行（通常の文章欄）"),listOf("SMART_NEWLINE","ACTION","NEWLINE").indexOf(s.screenEnter).coerceAtLeast(0)) { save(Prefs.screenEnter,listOf("SMART_NEWLINE","ACTION","NEWLINE")[it]) }
            selection("物理キーボード",listOf("送信対応欄は送信","入力先のアクション","改行（通常の文章欄）"),listOf("SMART_SEND","ACTION","NEWLINE").indexOf(s.physicalEnter).coerceAtLeast(0)) { save(Prefs.physicalEnter,listOf("SMART_SEND","ACTION","NEWLINE")[it]) }
            text("未確定の日本語はまず確定します。物理Shift＋Enterは改行です。送信は入力先がSENDを通知する欄で行います。LINEなどの実際の対応はアプリ側の設定・仕様によります。検索・URL・パスワード欄では入力先のアクションを優先します。")
        }
        section("貼り付けと履歴") {
            toggle("クリップボード履歴を保存",Prefs.clipboardHistory,s.clipboardHistory)
            text("画像・GIF・動画は貼り付け操作でのみ読み込みます。履歴ON時は貼り付けたメディアを最大10分表示します。")
            slider("履歴の最大件数",Prefs.clipboardLimit,s.clipboardLimit,5,100)
            text("初期状態はOFFです。ONにすると、DualKeyの通常入力中に取得できた文字列を端末内だけに保存します。履歴は読込・表示時に24時間以内へ整理します。OFFにすると保存済み履歴も削除します。")
            text("機密フラグ付きのコピー、パスワード・学習禁止欄、メール・URL・長い数字列などは履歴へ保存しません。機密と通知されない文章を完全に判別することはできないため、秘密の文章を扱うときはOFFにしてください。通常の貼り付けは履歴OFFでも使えます。")
            button("クリップボード履歴を全削除") {
                AlertDialog.Builder(this).setMessage("保存したクリップボード履歴を削除しますか？")
                    .setPositiveButton("削除") { _,_ -> app.clipboard.clear(); toast("履歴を削除しました") }.setNegativeButton("戻る",null).show()
            }
        }
        section("削除の長押し") {
            text("未確定の文字列はまとめて削除します。確定後はカーソル直前の句読点区切りを削除し、選択範囲があれば選択範囲を優先します。改行直後は改行だけ、パスワード・数字欄や範囲を確認できない長文は1文字ずつ削除します。")
        }
        section("大きさ") {
            slider("キーの高さ",Prefs.keyHeight,s.keyHeight,32,80," dp")
            slider("全体の倍率",Prefs.heightPercent,s.heightPercent,70,140," %")
            text("標準は40dp・100%。横画面では高さを自動調整します。")
            button("標準の大きさに戻す") { lifecycleScope.launch {
                app.settings.set(Prefs.keyHeight,40); app.settings.set(Prefs.heightPercent,100)
                app.settings.state.first { it.keyHeight==40 && it.heightPercent==100 }; selectPage(Page.INPUT)
            } }
        }
        section("入力とフィードバック") {
            toggle("初期モードを英語にする",Prefs.initialEnglish,s.initialEnglish)
            toggle("数字専用行を表示",Prefs.numberRow,s.numberRow)
            slider("フリック開始距離",Prefs.flickDp,s.flickDp,8,64," dp")
            toggle("キー操作時の振動",Prefs.vibration,s.vibration)
            toggle("キー操作音",Prefs.sound,s.sound)
        }
        button("入力を試す",true) { selectPage(Page.HOME); restoreKeyboard() }
    }
    private fun hardwareSettings() {
        val s=app.settings.state.value
        heading("物理キーボード","Bluetooth・USBキーボードの設定です。")
        section("画面の表示") {
            selection("接続時の表示",listOf("自動で隠す","常に通常表示","省スペース表示","完全非表示"),s.policy.ordinal) { save(Prefs.hardwarePolicy,HardwarePolicy.entries[it].name) }
            toggle("手動で選んだ表示を次回も使う",Prefs.rememberManual,s.rememberManual)
            text("接続を解除すると通常表示に戻ります。完全非表示からは、ホームの表示ボタンで復帰できます。")
            toggle("完全非表示時は下部バーも隠す",Prefs.hideImeCaption,s.hideImeCaption)
            text("対応するAndroidでは、キーボード切替と下向き矢印のバーも隠します。通常表示へ戻すと復帰します。機種・OSによっては端末側の表示設定も必要です。")
        }
        section("通知から切り替え") {
            toggle("表示切替の通知を使う",Prefs.visibilityNotification,s.visibilityNotification) { enabled ->
                if (enabled) requestNotificationPermission()
                else lifecycleScope.launch { app.settings.clearNotificationDisplay() }
            }
            text("通知の「表示／非表示を切替」を押すだけで切り替えます。別の画面を開かず、元の入力欄で続けられます。通知パネルが残る場合は閉じてください。入力欄がないときは、次にタップする入力欄で反映します。")
            button("通知の許可・設定") { KeyboardVisibilityNotification.ensureChannel(this); requestNotificationPermission(true) }
            if (!KeyboardVisibilityNotification.permitted(this)) text("表示切替の通知を使うには、Androidの通知を許可してください。")
            text("この通知はアプリ更新の通知とは別にON／OFFできます。入力した文章は通知へ表示しません。")
        }
        section("ショートカット") {
            toggle("表示／非表示ショートカットを使う",Prefs.shortcut,s.shortcut)
            button("キーの組合せを変更（${s.displayShortcut.label}）") {
                shortcutDialog=ShortcutDialog.show(this,s.displayShortcut) { value -> lifecycleScope.launch {
                    app.settings.setDisplayShortcut(value)
                    app.settings.state.first { it.displayShortcut==value }
                    selectPage(Page.HARDWARE)
                } }
            }.tag="configure_visibility_shortcut"
            text("${s.displayShortcut.label} を押すたびに、通常表示と完全非表示を切り替えます。表示用・非表示用のキーを別々に覚える必要はありません。")
            toggle("Ctrl + Space で日英切り替え",Prefs.ctrlSpace,s.ctrlSpace)
            text("Space／変換キーで縦の候補一覧、Space・矢印で選択、Enterで確定。確定後の続きの予測はTabで選び、Enterで追加できます。Shift＋Tabで前の候補へ戻ります。")
            text("半角／全角キーで日英を切り替えます。日本語モードの記号は「？」「！」などの全角になり、Backspaceは表示された文字を1文字ずつ消します。端末が先に使うキーは画面操作で代用できます。")
            selection("半角／全角キーの互換動作",listOf("日本語配列（半角／全角）","英語配列（`を入力）"),if (s.jisKeyboard) 0 else 1) { save(Prefs.layout,if (it==0) "JIS" else "US") }
            text("日本語配列では、半角／全角キーが「`」として届く機器にも対応します。英語配列の機器で「`」を使う場合は、英語配列を選んでください。実際の文字配列は次のAndroid設定で合わせます。")
            button("Androidのキーボード配列設定") { openIntent(Intent(AndroidSettings.ACTION_HARD_KEYBOARD_SETTINGS)) }
        }
    }
    private fun conversionSettings() {
        val s=app.settings.state.value
        heading("変換と辞書","候補の表示と、よく使う単語を管理。")
        section("予測候補") {
            toggle("日本語の予測候補",Prefs.japanesePrediction,s.japanesePrediction)
            toggle("絵文字の候補",Prefs.emojiPrediction,s.emojiPrediction)
            toggle("顔文字の候補",Prefs.facePrediction,s.facePrediction)
            toggle("英語の予測候補",Prefs.englishPrediction,s.englishPrediction)
            slider("最初に検索する候補数",Prefs.candidateCount,maxOf(64,s.candidateCount),64,1000)
            text("最初の候補は64件以上です。「一覧」はスクロールで続きを自動追加します。検索できる候補は最大10,000件です。")
        }
        section("変換の学習") {
            toggle("よく使う日本語の変換を学習",Prefs.learningEnabled,s.learningEnabled)
            text("一般辞書の語句・定型表現と文法の接続を予測の基礎にします。学習は辞書で確認できた単語・語句へ分割し、読み・表記・使用回数・最終使用時刻と隣接する語の組合せだけを端末内に保存します。任意の入力文を丸ごと登録しません。頻度・文脈・辞書順位を合わせて候補を並べます。パスワードや学習禁止の入力欄では保存・利用しません。")
            text(app.learning.message.value).tag="learning_status"
            button("学習データを削除") {
                AlertDialog.Builder(this).setTitle("学習データを削除しますか？")
                    .setMessage("学習した候補・続きの組合せ・使用回数を削除します。手動で登録したユーザー辞書は残ります。")
                    .setPositiveButton("削除") { _,_ -> app.learning.clear() }.setNegativeButton("戻る",null).show()
            }
        }
        section("ユーザー辞書","追加した単語は端末内に保存します。") {
            text("登録した読み：${s.dictionary.size}件",16f)
            button("単語を追加",true) { addWord() }
            button("登録した単語を確認・編集・削除") { dictionary() }
            text("約120万組の読みと表記、約5万字の単漢字に対応するオフライン辞書を使用します。字形の表示は端末のフォントにも依存します。")
            text(app.japaneseDictionary.message.value)
        }
    }
    private fun translationSettings() {
        val s=app.settings.state.value
        heading("翻訳","日本語と英語を端末内で翻訳します。")
        section("翻訳方向") { toggle("初期値を日本語 → 英語にする",Prefs.translateToEnglish,s.toEnglish) }
        section("翻訳モデル","初回はWi-Fiで約30MBのモデルを取得します。") {
            val status=text("モデルの状態を確認しています…")
            lifecycleScope.launch { status.text=if (translator.ready()) "ダウンロード済み・オフラインで利用できます" else "モデルは未取得です" }
            button("モデルをダウンロード",true) { lifecycleScope.launch {
                status.text="モデルを取得しています…"
                try { translator.download(app.settings.state.value.toEnglish); status.text="モデルの準備ができました" }
                catch (e: CancellationException) { throw e } catch (_: Exception) { status.text="取得できませんでした。Wi-Fi接続を確認してください。" }
            } }
            button("モデルを削除") { lifecycleScope.launch {
                try { translator.delete(); status.text="モデルを削除しました" }
                catch (e: CancellationException) { throw e } catch (_: Exception) { status.text="削除できませんでした。もう一度お試しください。" }
            } }
            text("キーボードの「翻訳」から結果を確認して入力します。パスワード欄では使えません。")
        }
    }
    private fun updates() {
        val s=app.settings.state.value
        heading("アプリ更新","この画面からダウンロードして更新できます。")
        section("現在のバージョン  ${BuildConfig.VERSION_NAME}") {
            updateStatus=text("",17f)
            updateProgress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                max=100; visibility=View.GONE; contentDescription="更新の進行状況"
            }.also { content.addView(it,LinearLayout.LayoutParams(-1,dp(8)).apply { topMargin=dp(12); bottomMargin=dp(12) }) }
            updatePrimary=button("今すぐ更新を確認",true) {
                if (app.updates.state.value.release==null) requestCheck(false,true) else startUpdate()
            }.apply { tag="update_primary" }
            updateDetails=text("")
            updateCheck=button("更新情報を再確認") { requestCheck(false,true) }
            text("最後はAndroidの確認画面でインストールします。設定と辞書は引き継がれます。")
        }
        section("自動確認とダウンロード") {
            toggle("起動時に更新を確認",Prefs.autoCheck,s.autoCheck)
            toggle("自動ダウンロード",Prefs.autoDownload,s.autoDownload)
            toggle("Wi-Fi接続時のみ",Prefs.wifiOnly,s.wifiOnly)
            toggle("アプリ外の更新通知",Prefs.notifications,s.notifications) { if (it) requestNotificationPermission() }
            text("更新は起動時と約12時間ごとに確認します。")
            if (Build.VERSION.SDK_INT>=33 && !NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                button("通知を許可する") { requestNotificationPermission() }
                text("アプリ外の通知にはAndroidの許可が必要です。起動時の画面内案内は、許可がなくても表示します。")
            }
        }
        section("履歴と配布サイト") {
            button("更新履歴を見る") { openWebsite("#history") }
            button("ダウンロードサイトを開く") { openWebsite() }
        }
    }
    private fun about() {
        heading("アプリについて","DualKey IME ${BuildConfig.VERSION_NAME}")
        section("入力とプライバシー") {
            text("入力文章を独自サーバーへ送信しません。キー操作の履歴は記録しません。パスワード欄では予測・学習・翻訳・未確定プレビューを無効にします。",16f)
            text("通信はアプリ更新、翻訳モデル取得、Google ML Kitの利用状況・診断通信に使います。変換と翻訳文は端末内で処理します。学習ON時は確定した語句の読み・表記・使用回数・最終使用時刻と、前後の確定語句の組合せを端末内に保存します。「変換と辞書」で停止・削除できます。")
            text("クリップボード履歴は初期OFFです。ONのときに入力中に取得できたコピーを、件数と期間を制限してバックアップ対象外の端末内領域へ保存します。秘密を完全には自動判別できません。「入力とサイズ」で停止・全削除できます。貼り付けや履歴を開くだけでは、削除済みのコピーを再登録しません。")
            text("アンインストールすると設定と辞書が消えます。更新はアンインストールせずに行ってください。")
        }
        section("利用技術と動作確認") {
            text("Android 8.0以上 / バージョン ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
            text("Mozcの公開辞書データを使う独自の変換処理です。Mozc本体ではなく、長文や固有名詞の変換には限界があります。Samsung実機・Bluetooth/USBの実接続は追加確認が必要です。")
            text("英語とカタカナの追加候補には、James William BREEN と EDRDG のJMdictを加工した辞書（CC BY-SA 4.0）を使用しています。複数の意味を持つ語では訳語を確認してください。")
            button("ライセンスを表示") {
                val notices=listOf("NOTICE.txt","japanese/LICENSE-MOZC.txt","japanese/LICENSE-DICTIONARY.txt","japanese/LICENSE-UNICODE.txt","semantic/LICENSE-JMDICT.txt","semantic/LICENSE-CC-BY-SA-4.0.txt","symbols/LICENSE","expressions/LICENSE-CLDR.txt")
                    .joinToString("\n\n") { name -> assets.open(name).bufferedReader().use { it.readText() } }
                AlertDialog.Builder(this).setTitle("ライセンス・利用技術").setMessage(notices).setPositiveButton("閉じる",null).show()
            }
            button("配布サイト") { openWebsite() }
        }
    }
    private fun refreshKeyboardStatus() {
        if (!uiReady) { return }
        val imm=getSystemService(InputMethodManager::class.java)
        val enabled=imm.enabledInputMethodList.any { it.packageName==packageName }
        val selected=AndroidSettings.Secure.getString(contentResolver,AndroidSettings.Secure.DEFAULT_INPUT_METHOD).orEmpty().startsWith("$packageName/")
        keyboardStatus?.apply {
            text=when {
                selected -> "✓ DualKey IMEを使用中です"
                enabled -> "有効です。使用するキーボードを選んでください。"
                else -> "初回の設定を行ってください。"
            }
            setTextColor(if (selected) ui.accent else ui.ink)
        }
    }
    private fun hideKeyboard() {
        testInput?.let { editor ->
            editor.clearFocus()
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken,0)
        }
        if (::pageContent.isInitialized) { pageContent.requestFocus() }
    }
    private fun restoreKeyboard() { if (page!=Page.HOME) selectPage(Page.HOME); ImeControl.request(DisplayMode.FULL); restoreEditorFocus() }
    private fun restoreEditorFocus() { testInput?.let { editor -> editor.requestFocus(); editor.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(editor,0) },250) } }
    private fun renderUpdate(state: UpdateState) {
        if (!uiReady) return
        if (state.failure) installWhenReady=false
        val hasUpdate=state.release!=null
        val message=if (state.message=="最新バージョンを使用しています") "最新バージョンです" else state.message
        val nav=listOf(R.id.nav_home to (page==Page.HOME),R.id.nav_settings to (page !in listOf(Page.HOME,Page.UPDATES)),R.id.nav_updates to (page==Page.UPDATES))
        nav.forEach { (id,selected) -> findViewById<TextView>(id).apply {
            isSelected=selected; setTextColor(if (selected) ui.accent else ui.muted); setTypeface(typeface,if (selected) Typeface.BOLD else Typeface.NORMAL)
            background=if (selected) ui.shape(ui.soft,12) else null
        } }
        findViewById<TextView>(R.id.nav_updates).text=if (hasUpdate) "更新 ●" else "更新"
        homeUpdateStatus?.text=when { state.busy -> message+(state.progress?.let { " $it%" } ?: ""); hasUpdate -> "新しいバージョン ${state.release!!.versionName} があります"; else -> "現在 ${BuildConfig.VERSION_NAME}\n$message" }
        homeUpdateAction?.text=when { state.busy -> "進行状況を見る"; state.apk!=null -> "インストールして更新"; hasUpdate -> "更新する"; else -> "更新を確認" }
        updateStatus?.text=if (state.apk!=null && !state.busy && !packageManager.canRequestPackageInstalls())
            "インストールの許可が必要です"
            else message+(state.progress?.let { " $it%" } ?: "")
        updateDetails?.apply {
            text=state.release?.let { r -> "${BuildConfig.VERSION_NAME} → ${r.versionName}\n${"%.1f".format(r.sizeBytes/1048576.0)} MB・${r.releaseDate}\n\n"+r.releaseNotes.take(8).joinToString("\n")+(if (r.required) "\n推奨される重要な更新です。入力は継続できます。" else "") }.orEmpty()
            visibility=if (hasUpdate) View.VISIBLE else View.GONE
        }
        updateProgress?.apply { visibility=if (state.busy) View.VISIBLE else View.GONE; isIndeterminate=state.progress==null; progress=state.progress ?: 0 }
        updatePrimary?.apply { isEnabled=!state.busy && !installInFlight; alpha=if (isEnabled) 1f else .55f; text=when { state.busy -> "処理中…"; state.apk!=null -> "インストールして更新"; hasUpdate -> "ダウンロードして更新"; else -> "今すぐ更新を確認" } }
        updateCheck?.apply { visibility=if (hasUpdate) View.VISIBLE else View.GONE; isEnabled=!state.busy }
        if (installWhenReady && state.apk!=null && !state.busy) resumeInstallationFlow()
        maybeShowUpdatePrompt()
    }
    private fun requestCheck(prompt: Boolean,force: Boolean=false) {
        if (prompt) promptOnAvailable=true
        if (!uiReady || app.updates.state.value.busy || checkJob?.isActive==true) return
        val now=SystemClock.elapsedRealtime()
        if (!force && now-lastCheckAttempt<10_000 && lastCheckAttempt!=0L) { if (prompt) maybeShowUpdatePrompt(); return }
        lastCheckAttempt=now; promptOnAvailable=prompt
        checkJob=lifecycleScope.launch {
            val info=app.updates.check()
            if (info!=null) {
                if (prompt && app.settings.state.value.notifications) {
                    val notices=getSharedPreferences("update_notices",MODE_PRIVATE)
                    if (notices.getLong("version",0)!=info.versionCode && NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled()) {
                        UpdateScheduler.notify(this@MainActivity,"${info.versionName} を利用できます。タップして更新できます")
                        notices.edit().putLong("version",info.versionCode).apply()
                    }
                }
                if (app.settings.state.value.autoDownload && app.updates.state.value.apk==null) UpdateScheduler.download(this@MainActivity,app.settings.state.value.wifiOnly)
            }
            maybeShowUpdatePrompt()
        }
    }
    private fun maybeShowUpdatePrompt() {
        if (!uiReady || !hasWindowFocus() || !app.settings.state.value.autoCheck || page==Page.UPDATES || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || isFinishing || installWhenReady || awaitingPermissionResult) return
        val state=app.updates.state.value; val release=state.release ?: return
        if (!promptOnAvailable || promptedVersion==release.versionCode || updateDialog?.isShowing==true || testInput?.hasFocus()==true) return
        promptedVersion=release.versionCode
        updateDialog=AlertDialog.Builder(this).setTitle("新しいバージョンがあります")
            .setMessage("${BuildConfig.VERSION_NAME} → ${release.versionName}\n${"%.1f".format(release.sizeBytes/1048576.0)} MB\n\n"+release.releaseNotes.take(6).joinToString("\n"))
            .setPositiveButton(if (state.busy) "進行状況を見る" else "更新する") { _,_ ->
                selectPage(Page.UPDATES); if (!app.updates.state.value.busy) startUpdate()
            }.setNegativeButton("あとで") { _,_ -> promptOnAvailable=false }
            .setOnDismissListener { updateDialog=null }.create().also { it.show() }
    }
    private fun startUpdate() {
        val state=app.updates.state.value
        if (state.busy || state.release==null) return
        promptOnAvailable=false
        if (page!=Page.UPDATES) selectPage(Page.UPDATES)
        if (state.apk!=null) { installWhenReady=true; resumeInstallationFlow(); return }
        if (app.settings.state.value.wifiOnly && !UpdateScheduler.isWifi(this)) {
            AlertDialog.Builder(this).setTitle("Wi-Fi接続を確認してください")
                .setMessage("Wi-Fi限定の設定が有効です。接続後にもう一度更新するか、今回だけ別の回線でダウンロードできます。")
                .setPositiveButton("今回だけダウンロード") { _,_ -> beginDownload(state.release) }
                .setNegativeButton("あとで",null).show()
        } else beginDownload(state.release)
    }
    private fun beginDownload(release: ReleaseInfo) {
        installWhenReady=true
        // Application scope keeps the transfer alive across Activity recreation/rotation.
        app.scope.launch {
            try {
                if (app.updates.download(release)!=null) UpdateScheduler.notify(this@MainActivity,"更新の準備ができました。タップしてインストールできます")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { app.updates.state.value=UpdateState("更新を取得できませんでした。もう一度お試しください。",release,failure=true) }
        }
    }
    private fun resumeInstallationFlow() {
        if (!uiReady || !hasWindowFocus() || page!=Page.UPDATES || testInput?.hasFocus()==true || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || installInFlight || permissionDialog?.isShowing==true) return
        if (awaitingPermissionResult) return
        if (!installWhenReady) return
        val state=app.updates.state.value
        if (state.busy || state.apk==null || state.release==null) return
        installInFlight=true
        lifecycleScope.launch { try { install(state.apk,state.release) } finally { installInFlight=false; updatePrimary?.isEnabled=!app.updates.state.value.busy } }
    }
    private suspend fun install(file: File,info: ReleaseInfo) {
        hideKeyboard()
        try {
            withContext(Dispatchers.IO) { ApkSafety(this@MainActivity).verify(file,info) }
            if (!installWhenReady || page!=Page.UPDATES || !hasWindowFocus() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
            if (!packageManager.canRequestPackageInstalls()) {
                permissionDialog=AlertDialog.Builder(this).setTitle("アプリからの更新を許可")
                    .setMessage("次のAndroid設定でDualKey IMEからのインストールを許可してください。この画面に戻ると、Androidの更新確認へ進みます。")
                    .setPositiveButton("設定を開く") { _,_ ->
                        awaitingPermissionResult=true
                        try { permissionLauncher.launch(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName"))) }
                        catch (_: ActivityNotFoundException) { awaitingPermissionResult=false; installWhenReady=false; toast("Android設定からインストールの許可を確認してください") }
                    }
                    .setNegativeButton("あとで") { _,_ -> installWhenReady=false }
                    .setOnCancelListener { installWhenReady=false }
                    .setOnDismissListener { permissionDialog=null }.create().also { it.show() }
                return
            }
            installWhenReady=false
            val uri=FileProvider.getUriForFile(this,"$packageName.updates",file)
            openIntent(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { installWhenReady=false; updateStatus?.text="インストールを開始できませんでした。更新情報を再確認してください。"; toast("APKの検証またはインストールを開始できませんでした") }
    }
    private fun addWord(oldReading: String?=null,oldWord: String?=null) {
        val box=ui.column().apply { setPadding(dp(20),dp(8),dp(20),0) }
        val reading=EditText(this).apply { hint="よみ（ひらがな）" }; val word=EditText(this).apply { hint="単語" }
        reading.setText(oldReading.orEmpty()); word.setText(oldWord.orEmpty())
        box.addView(reading); box.addView(word)
        val dialog=AlertDialog.Builder(this).setTitle(if (oldReading==null) "ユーザー辞書に追加" else "ユーザー辞書を編集").setView(box)
            .setPositiveButton(if (oldReading==null) "追加" else "保存",null).setNegativeButton("キャンセル",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val r=reading.text.toString().trim(); val w=word.text.toString().trim()
            if (!r.matches(Regex("[ぁ-ゖー]{1,50}")) || w.isEmpty() || w.length>100) { toast("よみはひらがな50文字以内、単語は100文字以内で入力してください"); return@setOnClickListener }
            lifecycleScope.launch { if (oldReading!=null && oldWord!=null) app.settings.editWord(oldReading,oldWord,r,w) else app.settings.addWord(r,w); dialog.dismiss(); selectPage(Page.CONVERSION) }
        } }; dialog.show()
    }
    private fun dictionary() {
        val words=app.settings.state.value.dictionary
        if (words.isEmpty()) { toast("登録した単語はありません"); return }
        val entries=words.toSortedMap().flatMap { (reading,values) -> values.map { reading to it } }
        AlertDialog.Builder(this).setTitle("編集する単語を選択").setItems(entries.map { "${it.first}：${it.second}" }.toTypedArray()) { _,i ->
            val (reading,word)=entries[i]
            AlertDialog.Builder(this).setMessage("$reading：$word")
                .setPositiveButton("編集") { _,_ -> addWord(reading,word) }
                .setNeutralButton("削除") { _,_ -> lifecycleScope.launch { app.settings.editWord(reading,word,"",""); selectPage(Page.CONVERSION) } }
                .setNegativeButton("閉じる",null).show()
        }.setNegativeButton("閉じる",null).show()
    }
    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if (requestCode==301) {
            KeyboardVisibilityNotification.refresh(this)
            if (uiReady && page==Page.HARDWARE) selectPage(Page.HARDWARE)
        }
    }
    private fun requestNotificationPermission(openSettingsIfGranted: Boolean=false) {
        if (Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            if (getPreferences(MODE_PRIVATE).getBoolean("notification_requested",false) && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                openIntent(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE,packageName))
            } else {
                getPreferences(MODE_PRIVATE).edit().putBoolean("notification_requested",true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),301)
            }
        } else if (openSettingsIfGranted) openIntent(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE,packageName))
    }
    private fun openWebsite(fragment: String="") { if (BuildConfig.WEBSITE_URL.isNotBlank()) openIntent(Intent(Intent.ACTION_VIEW,Uri.parse(BuildConfig.WEBSITE_URL+fragment))) }
    private fun openIntent(intent: Intent) { try { startActivity(intent) } catch (_: ActivityNotFoundException) { toast("この端末では画面を開けません。Android設定から操作してください") } }
    private fun toast(message: String) { Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
}
