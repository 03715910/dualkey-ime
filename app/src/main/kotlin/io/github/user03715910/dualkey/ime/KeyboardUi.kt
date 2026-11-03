package io.github.user03715910.dualkey.ime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.res.Configuration
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.dualkey.core.DisplayMode
import io.github.dualkey.core.Candidate
import io.github.dualkey.core.CandidateKind
import io.github.user03715910.dualkey.R
import io.github.user03715910.dualkey.data.Settings

class KeyboardUi(private val context: Context, private val callback: (String) -> Unit,
    private val feedback: (View) -> Unit) {
    val root = LayoutInflater.from(context).inflate(R.layout.ime_root, null) as LinearLayout
    private val toolbar = root.findViewById<LinearLayout>(R.id.toolbar)
    private val candidateStrip = root.findViewById<LinearLayout>(R.id.candidate_strip)
    private val candidateExpand = root.findViewById<TextView>(R.id.candidate_expand)
    private val compositionAttribution = root.findViewById<ImageView>(R.id.composition_attribution)
    private var googleText: String? = null
    private val expandedScroll = root.findViewById<CandidateGrid>(R.id.candidate_expanded_scroll)
    private enum class CandidateMode(val label: String) {
        ALL("すべて"), EXACT("絞り込み"), EMOJI("絵文字"), FACE("顔文字")
    }
    private var candidateMode=CandidateMode.ALL
    private val candidateTabs=LinearLayout(context).apply {
        layoutParams=LinearLayout.LayoutParams(-1,dp(38))
        visibility=View.GONE
    }.also { root.addView(it,root.indexOfChild(expandedScroll)+1) }
    private val clipboardPreview=LinearLayout(context).apply {
        layoutParams=LinearLayout.LayoutParams(-1,dp(40))
        visibility=View.GONE
    }.also { root.addView(it,root.indexOfChild(toolbar)+1) }
    private val screenshotPreview=LinearLayout(context).apply {
        layoutParams=LinearLayout.LayoutParams(-1,dp(40))
        visibility=View.GONE
    }.also { root.addView(it,root.indexOfChild(clipboardPreview)+1) }
    private var candidateExpanded = false
    private var previousCandidates=emptyList<String>()
    private var dictionaryNotice = ""
    private val numberRow = root.findViewById<LinearLayout>(R.id.number_row)
    private val keys = root.findViewById<LinearLayout>(R.id.key_area)
    val translationPanel: LinearLayout = root.findViewById(R.id.translation_panel)
    private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink = if (dark) Color.rgb(233,241,243) else Color.rgb(23,47,53)
    private val keyColor = if (dark) Color.rgb(48,65,72) else Color.WHITE
    private val functionColor = if (dark) Color.rgb(39,54,61) else Color.rgb(236,242,242)
    private var keyHeight = 40
    private var numberHeight = 30
    private var englishMode = false
    private var showNumbers=false
    private var showKeys=false
    private var haptics=true
    private fun updateBody() {
        val translating=translationPanel.visibility==View.VISIBLE
        numberRow.visibility=if (showNumbers && !translating && !candidateExpanded) View.VISIBLE else View.GONE
        keys.visibility=if (showKeys && !translating && !candidateExpanded) View.VISIBLE else View.GONE
        expandedScroll.visibility=if (candidateExpanded && !translating) View.VISIBLE else View.GONE
        candidateTabs.visibility=expandedScroll.visibility
        candidateExpand.text=if (candidateExpanded) "閉じる" else "一覧"
        candidateExpand.contentDescription=if (candidateExpanded) "変換候補の一覧を閉じる" else "変換候補を一覧表示"
        if (googleText!=null) {
            candidateExpand.visibility=View.GONE
            candidateStrip.removeAllViews()
            candidateStrip.addView(TextView(context).apply {
                text=googleText; textSize=17f; setTextColor(ink); gravity=Gravity.CENTER_VERTICAL; setSingleLine(true)
                includeFontPadding=false; setPadding(dp(8),0,dp(8),0)
                layoutParams=LinearLayout.LayoutParams(-2,-1)
            })
        }
    }
    fun googleComposition(text: String?) {
        googleText=text
        compositionAttribution.visibility=if (text!=null) View.VISIBLE else View.GONE
        if (text!=null) candidateExpanded=false
    }
    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    init {
        root.setBackgroundColor(if (dark) Color.rgb(22,35,41) else Color.rgb(220,234,236))
        toolbar.isBaselineAligned=false
        numberRow.isBaselineAligned=false
        candidateExpand.setTextColor(ink)
        candidateExpand.background=GradientDrawable().apply {
            setColor(if (dark) Color.rgb(22,35,41) else Color.rgb(220,234,236))
            setStroke(dp(1),if (dark) Color.rgb(91,119,127) else Color.rgb(158,187,191)); cornerRadius=dp(5).toFloat()
        }
        candidateExpand.setSingleLine(true)
        candidateExpand.isFocusable=false
        // The IME's own navigation controls are exposed as caption-bar insets on
        // recent Android versions. Reserve system bars, never the IME's own inset.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val safe=(ViewCompat.getRootWindowInsets(view) ?: insets)
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(4)+safe.left,dp(4),dp(4)+safe.right,dp(4)+safe.bottom)
            insets
        }
        root.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { ViewCompat.requestApplyInsets(v) }
            override fun onViewDetachedFromWindow(v: View) {}
        })
    }
    private fun decorate(v: TextView) {
        v.setTextColor(ink); v.isFocusable = false; v.includeFontPadding=false
        v.isHapticFeedbackEnabled=haptics
        v.setPadding(0,0,0,0)
        v.setAutoSizeTextTypeUniformWithConfiguration(12,if (v is FlickKey) 22 else 19,1,TypedValue.COMPLEX_UNIT_SP)
        val shape=GradientDrawable().apply { setColor(keyColor); cornerRadius = dp(7).toFloat() }
        v.background = RippleDrawable(ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x22087F8C),shape,null)
        v.layoutParams = LinearLayout.LayoutParams(0,dp(keyHeight),1f).apply { setMargins(dp(2),dp(2),dp(2),dp(2)) }
    }
    private fun button(label: String, action: String, small: Boolean = false, function: Boolean = false,
        description: String = label) = TextView(context).apply {
        text = label; contentDescription=description; gravity = Gravity.CENTER; textSize = if (small) 13f else if (function) 16f else 19f
        decorate(this)
        setSingleLine(true)
        if (small) {
            layoutParams.height = dp(32)
            setAutoSizeTextTypeUniformWithConfiguration(10,13,1,TypedValue.COMPLEX_UNIT_SP)
            background=RippleDrawable(ColorStateList.valueOf(0x22087F8C),null,null)
        } else if (function) {
            setAutoSizeTextTypeUniformWithConfiguration(11,16,1,TypedValue.COMPLEX_UNIT_SP)
            background=RippleDrawable(ColorStateList.valueOf(0x22087F8C),GradientDrawable().apply {
                setColor(functionColor); cornerRadius=dp(7).toFloat()
            },null)
        }
        if (action=="shift") {
            contentDescription=when (label) { "⇪" -> "Shift：大文字固定"; "⬆" -> "Shift：次の1文字を大文字"; else -> "Shift：小文字" }
            if (label!="⇧") setTextColor(Color.rgb(8,127,140))
        }
        if (action=="mode") contentDescription=if (englishMode) "日本語に切り替え" else "英語に切り替え"
        isClickable = true
        setOnClickListener { feedback(this); callback(action) }
        if (action=="delete" || action=="paste") setOnLongClickListener {
            callback(if (action=="delete") "delete_chunk" else "clipboard_history"); true
        }
    }
    fun rebuild(settings: Settings, english: Boolean, shift: Int, mode: DisplayMode, symbols: Boolean, sensitive: Boolean) {
        candidateExpanded=false
        haptics=settings.vibration
        englishMode=english
        // Four key rows in either language. Give digits their own shorter height.
        val screen=context.resources.configuration
        val landscape=screen.orientation==Configuration.ORIENTATION_LANDSCAPE
        val budget=screen.screenHeightDp*(if (landscape) .65f else .42f)
        val desiredNumber=(30*settings.heightPercent/100).coerceIn(22,42)
        val fixedHeight=36+32+8+16+(if (settings.numberRow) desiredNumber+4 else 0)
        val maxRow=((budget-fixedHeight)/4).toInt().coerceAtLeast(24)
        keyHeight = (settings.keyHeight * settings.heightPercent / 100).coerceIn(24,maxRow)
        numberHeight=desiredNumber.coerceAtMost((keyHeight-4).coerceAtLeast(18))
        toolbar.removeAllViews()
        if (mode==DisplayMode.COMPACT) toolbar.addView(TextView(context).apply {
            text=if (english) "EN" else "日本語"; textSize=12f; setTextColor(ink); gravity=Gravity.CENTER; isFocusable=false
            contentDescription="入力モード：$text"; layoutParams=LinearLayout.LayoutParams(dp(44),dp(32))
        })
        toolbar.addView(button("貼り付け", "paste", true,description="貼り付け。長押しで履歴"))
        toolbar.addView(button(if (mode == DisplayMode.COMPACT) "全表示" else "縮小", if (mode == DisplayMode.COMPACT) "full" else "compact", true,
            description=if (mode == DisplayMode.COMPACT) "画面キーボードを全表示" else "省スペース表示"))
        if (!sensitive) toolbar.addView(button("翻訳", "translate", true))
        toolbar.addView(button("⚙", "settings", true, description="設定"))
        numberRow.removeAllViews(); keys.removeAllViews()
        showNumbers=mode==DisplayMode.FULL && settings.numberRow
        showKeys=mode==DisplayMode.FULL
        if (previousCandidates.isEmpty()) renderEmptyCandidates()
        updateBody()
        if (mode != DisplayMode.FULL) return
        "1234567890".forEach { numberRow.addView(button(it.toString(),it.toString()).apply {
            layoutParams.height=dp(numberHeight); setAutoSizeTextTypeUniformWithConfiguration(12,17,1,TypedValue.COMPLEX_UNIT_SP)
        }) }
        fun row(items: List<Pair<String,String>>) { keys.addView(LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL; isBaselineAligned=false; items.forEach { addView(button(it.first,it.second)) } }) }
        if (symbols) {
            listOf("! @ # $ % ^ & * ( )", "- _ = + [ ] { } / \\", "、 。 ー 〜 ～ ・ 「 」 ？ ！").forEach { line -> row(line.split(' ').map { it to it }) }
            row(listOf("戻る" to "symbols", "空白" to "space", "⌫" to "delete", "↵" to "enter"))
        } else if ((english && !settings.englishFlick) || (!english && settings.japaneseQwerty)) {
            listOf("qwertyuiop","asdfghjkl").forEach { line -> row(line.map { val s=if (shift>0) it.uppercase() else it.toString(); s to s }) }
            val label=when (shift) { 2 -> "⇪"; 1 -> "⬆"; else -> "⇧" }
            row(listOf(label to "shift")+"zxcvbnm".map { val s=if (shift>0) it.uppercase() else it.toString(); s to s }+("⌫" to "delete"))
            row(listOf((if (english) "日本語" else "abc") to "mode", "記号" to "symbols", (if (english) "," else "カナ/英") to (if (english) "," else "cycle_script"), (if (english) "Space" else "空白/変換") to "space", "." to ".", "↵" to "enter"))
            keys.getChildAt(2).let { it as LinearLayout }.getChildAt(0).contentDescription=when (shift) { 2 -> "Shift：大文字固定"; 1 -> "Shift：次の1文字を大文字"; else -> "Shift：小文字" }
        } else if (english) {
            fun flick(values: List<String>,label: String,description: String) =
                FlickKey(context,values,dp(settings.flickDp).toFloat(),feedback,callback,!sensitive,label,description).also(::decorate)
            fun alphabet(group: String): View {
                val label=if (shift>0) group.uppercase() else group
                return flick(label.map(Char::toString)+List(5-label.length){""},label,"${label[0]}行")
            }
            fun controls(vararg cells: View) {
                keys.addView(LinearLayout(context).apply { isBaselineAligned=false; cells.forEach { addView(it) } })
            }
            controls(flick(listOf("'", "\"", "`", ":", ";"),"'\"", "引用符"),
                flick(listOf("@","_","-","+","&"),"@_-","アットマーク"),alphabet("abc"),alphabet("def"),
                button("⌫","delete",function=true,description="削除"))
            controls(button("◀","cursor_left",function=true,description="カーソルを左へ"),
                alphabet("ghi"),alphabet("jkl"),alphabet("mno"),button("▶","cursor_right",function=true,description="カーソルを右へ"))
            controls(button("記号","symbols",function=true,description="記号入力"),
                alphabet("pqrs"),alphabet("tuv"),alphabet("wxyz"),button("空白","space",function=true))
            val shiftKey=button(when (shift) { 2 -> "A/A ⇪"; 1 -> "A/a"; else -> "a/A" },"shift",function=true).apply {
                contentDescription=when (shift) { 2 -> "Shift：大文字固定"; 1 -> "Shift：次の1文字を大文字"; else -> "Shift：小文字" }
            }
            controls(button("日本語","mode",function=true),shiftKey,
                flick(listOf("/","(",")","[","]"),"/( )","スラッシュ・括弧"),
                flick(listOf(".",",","?","!","…"),".,!?","句読点"),
                button("↵","enter",function=true,description="確定・改行"))
        } else {
            val groups = listOf(
                listOf(listOf("あ","い","う","え","お"),listOf("か","き","く","け","こ"),listOf("さ","し","す","せ","そ")),
                listOf(listOf("た","ち","つ","て","と"),listOf("な","に","ぬ","ね","の"),listOf("は","ひ","ふ","へ","ほ")),
                listOf(listOf("ま","み","む","め","も"),listOf("や","ゃ","ゆ","ゅ","よ"),listOf("ら","り","る","れ","ろ")))
            val left=listOf(Triple("カナ/英","cycle_script","表記を切り替え：カタカナ・英語・ひらがな"),Triple("◀","cursor_left","カーソルを左へ"),Triple("記号","symbols","記号入力"))
            val right=listOf(Triple("⌫","delete","削除"),Triple("▶","cursor_right","カーソルを右へ"),Triple("空白","space","空白・変換"))
            groups.forEachIndexed { index,group -> keys.addView(LinearLayout(context).apply {
                orientation=LinearLayout.HORIZONTAL; isBaselineAligned=false
                addView(button(left[index].first,left[index].second,function=true,description=left[index].third).apply {
                    if (index==0) isEnabled=!sensitive
                })
                group.forEach { values -> addView(FlickKey(context,values,dp(settings.flickDp).toFloat(),feedback,callback,!sensitive).also(::decorate)) }
                addView(button(right[index].first,right[index].second,function=true,description=right[index].third))
            }) }
            keys.addView(LinearLayout(context).apply {
                orientation=LinearLayout.HORIZONTAL; isBaselineAligned=false
                addView(button("abc", "mode",function=true,description="英語に切り替え"))
                addView(button("小゛゜", "modify",function=true,description="濁点・半濁点・小文字"))
                addView(FlickKey(context,listOf("わ","を","ん","ー","〜"),dp(settings.flickDp).toFloat(),feedback,callback,!sensitive).also(::decorate))
                addView(FlickKey(context,listOf("、","。","？","！","・"),dp(settings.flickDp).toFloat(),feedback,callback,!sensitive).also(::decorate))
                addView(button("↵", "enter",function=true,description="確定・改行"))
            })
        }
    }
    fun candidates(values: List<String>, selected: Int, select: (Int) -> Unit, canLoadMore: Boolean=false, loading: Boolean=false, records: List<Candidate> = emptyList(), reading: String = "") {
        val changed=values.take(3)!=previousCandidates.take(3)
        previousCandidates=values
        candidateStrip.removeAllViews()
        candidateExpand.visibility=if (values.isEmpty() && !candidateExpanded) View.GONE else View.VISIBLE
        if (values.isEmpty()) {
            if (!loading) candidateExpanded=false
            expandedScroll.submit(emptyList(),-1,ink,keyColor,{},false,false,{})
        }
        candidateExpand.setOnClickListener {
            feedback(candidateExpand); candidateExpanded=!candidateExpanded
            expandedScroll.layoutParams.height=dp(if (context.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE) 128 else 200)
            if (candidateExpanded) {
                candidateMode=CandidateMode.ALL
                populateExpanded(values,selected,select,canLoadMore,loading,records,reading)
                expandedScroll.scrollToPosition(0)
            }
            updateBody()
            if (candidateExpanded) callback("expand_candidates")
        }
        values.take(20).forEachIndexed { index,value -> candidateStrip.addView(TextView(context).apply {
            text=value; textSize=17f; setTextColor(if (selected==index) Color.WHITE else ink); includeFontPadding=false
            gravity=Gravity.CENTER_VERTICAL; setPadding(dp(10),0,dp(10),0); isFocusable=false
            setBackgroundColor(if (selected==index) Color.rgb(8,127,140) else Color.TRANSPARENT)
            layoutParams=LinearLayout.LayoutParams(-2,-1); setOnClickListener { select(index) }
        }) }
        if (values.isEmpty()) renderEmptyCandidates()
        if (candidateExpanded) {
            populateExpanded(values,selected,select,canLoadMore,loading,records,reading)
            if (changed) expandedScroll.scrollToPosition(0)
        }
        updateBody()
    }
    private fun populateExpanded(values: List<String>,selected: Int,select: (Int)->Unit,canLoadMore: Boolean,
        loading: Boolean,records: List<Candidate>,reading: String) {
        val indices=if (candidateMode==CandidateMode.ALL) values.indices.toList() else records.indices.filter { index ->
            val record=records[index]
            when (candidateMode) {
                CandidateMode.ALL -> true
                CandidateMode.EXACT -> reading.isNotEmpty() && record.directlyMatches(reading)
                CandidateMode.EMOJI -> record.kind==CandidateKind.EMOJI
                CandidateMode.FACE -> record.kind==CandidateKind.FACE
            }
        }
        expandedScroll.submit(indices.map(values::get),indices.indexOf(selected),ink,keyColor,{ index ->
            candidateExpanded=false
            updateBody()
            select(indices[index])
        },canLoadMore,loading) { callback("more_candidates") }
        candidateTabs.removeAllViews()
        for (mode in CandidateMode.entries) {
            candidateTabs.addView(button(mode.label,"noop",small=true).apply {
                layoutParams=LinearLayout.LayoutParams(0,-1,1f)
                isSelected=mode==candidateMode
                setTextColor(if (isSelected) Color.WHITE else ink)
                setBackgroundColor(if (isSelected) Color.rgb(8,127,140) else functionColor)
                setOnClickListener {
                    candidateMode=mode
                    populateExpanded(values,selected,select,canLoadMore,loading,records,reading)
                    expandedScroll.scrollToPosition(0)
                }
            })
        }
    }
    fun dictionaryStatus(message: String) {
        dictionaryNotice=if (message=="日本語辞書の準備完了") "" else message
        if (previousCandidates.isEmpty()) renderEmptyCandidates()
        updateBody()
    }
    private fun renderEmptyCandidates() {
        candidateStrip.removeAllViews()
        if (!englishMode && dictionaryNotice.isNotEmpty()) candidateStrip.addView(TextView(context).apply {
            text=dictionaryNotice; textSize=12f; setTextColor(ink); gravity=Gravity.CENTER_VERTICAL; includeFontPadding=false
            setSingleLine(true); ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(dp(8),0,0,0)
        })
    }
    fun showTranslation(message: String, actions: List<Pair<String,()->Unit>>, attribution: Boolean = false) {
        translationPanel.removeAllViews(); translationPanel.visibility=View.VISIBLE
        updateBody()
        translationPanel.addView(ScrollView(context).apply {
            layoutParams=LinearLayout.LayoutParams(-1,dp(120))
            addView(TextView(context).apply { text=message; textSize=16f; setTextColor(ink); setPadding(dp(8),dp(8),dp(8),dp(8)) })
        })
        if (attribution) translationPanel.addView(ImageView(context).apply {
            setImageResource(R.drawable.google_translate_attribution); setBackgroundColor(Color.WHITE)
            contentDescription="powered by Google Translate"; scaleType=ImageView.ScaleType.FIT_CENTER
            layoutParams=LinearLayout.LayoutParams(dp(170),dp(28)).apply { setMargins(dp(8),0,0,dp(4)) }
            setOnClickListener { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://translate.google.com/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        })
        translationPanel.addView(LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL; actions.forEach { (label,action) ->
            addView(Button(context).apply { text=label; isFocusable=false; layoutParams=LinearLayout.LayoutParams(0,dp(48),1f); setOnClickListener { action() } })
        } })
    }
    fun hideTranslation() { translationPanel.visibility=View.GONE; translationPanel.removeAllViews(); updateBody() }
    fun showClipboardPreview(bitmap: android.graphics.Bitmap?,label: String?,paste: ()->Unit = {},dismiss: ()->Unit = {},screenshot: Boolean=false) {
        val clipboardPreview=if (screenshot) screenshotPreview else this.clipboardPreview
        clipboardPreview.removeAllViews()
        clipboardPreview.visibility=if (label==null) View.GONE else View.VISIBLE
        if (label==null) return
        if (bitmap!=null) clipboardPreview.addView(ImageView(context).apply {
            layoutParams=LinearLayout.LayoutParams(dp(44),-1)
            scaleType=ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
            contentDescription=if (screenshot) "撮ったスクショのプレビュー" else "コピーしたメディアのプレビュー"
            setOnClickListener { paste() }
        })
        clipboardPreview.addView(button(label,"noop",small=true).apply {
            layoutParams=LinearLayout.LayoutParams(0,-1,1f)
            setOnClickListener { paste() }
        })
        clipboardPreview.addView(button("×","noop",small=true).apply {
            layoutParams=LinearLayout.LayoutParams(dp(44),-1)
            contentDescription=if (screenshot) "スクショのプレビューを閉じる" else "コピープレビューを閉じる"
            setOnClickListener { dismiss() }
        })
    }
    fun showClipboard(entries: List<io.github.user03715910.dualkey.data.ClipboardEntry>,enabled: Boolean,
        paste: (io.github.user03715910.dualkey.data.ClipboardEntry)->Unit,remove: (io.github.user03715910.dualkey.data.ClipboardEntry)->Unit,clear: ()->Unit,close: ()->Unit,
        media: List<io.github.user03715910.dualkey.data.MediaHistoryEntry>, limit: Int,
        pasteMedia: (io.github.user03715910.dualkey.data.MediaHistoryEntry)->Unit,
        removeMedia: (io.github.user03715910.dualkey.data.MediaHistoryEntry)->Unit) {
        translationPanel.removeAllViews(); translationPanel.visibility=View.VISIBLE; candidateExpanded=false; updateBody()
        translationPanel.addView(TextView(context).apply {
            text=if (!enabled) "履歴保存はOFFです。設定の「入力とサイズ」で有効にできます。" else if (entries.isEmpty() && media.isEmpty()) "保存した履歴はありません（文字は24時間以内）" else "クリップボード履歴・新しい順"
            textSize=13f; setTextColor(ink); setPadding(dp(8),dp(4),dp(8),dp(4))
        })
        val ordered=(entries.mapIndexed { index,entry -> index to entry.time } +
            media.mapIndexed { index,entry -> entries.size+index to entry.copiedAt }).sortedByDescending { it.second }.take(limit)
        val list=androidx.recyclerview.widget.RecyclerView(context).apply {
            layoutParams=LinearLayout.LayoutParams(-1,dp(128)); isFocusable=false
            layoutManager=androidx.recyclerview.widget.LinearLayoutManager(context); itemAnimator=null
        }
        list.adapter=object: androidx.recyclerview.widget.RecyclerView.Adapter<ClipboardCell>() {
            override fun getItemCount()=ordered.size
            override fun onCreateViewHolder(parent: ViewGroup,type: Int): ClipboardCell {
                val row=LinearLayout(context).apply { isBaselineAligned=false; layoutParams=androidx.recyclerview.widget.RecyclerView.LayoutParams(-1,-2) }
                val label=TextView(context).apply { textSize=16f; setTextColor(ink); minHeight=dp(44); maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(dp(8),dp(4),dp(8),dp(4)); isFocusable=false }
                val preview=ImageView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                val delete=button("削除","noop",true)
                row.addView(preview,LinearLayout.LayoutParams(dp(44),dp(44)))
                row.addView(label,LinearLayout.LayoutParams(0,-2,1f)); row.addView(delete,LinearLayout.LayoutParams(dp(56),dp(44)))
                return ClipboardCell(row,label,delete,preview)
            }
            override fun onBindViewHolder(holder: ClipboardCell,adapterPosition: Int) {
                val position=ordered[adapterPosition].first
                if (position<entries.size) {
                    val entry=entries[position]; holder.label.text=entry.text; holder.label.contentDescription="履歴 ${position+1}：${entry.text}"
                    holder.preview.setImageDrawable(null); holder.preview.visibility=View.GONE; holder.preview.setOnClickListener(null)
                    holder.label.setOnClickListener { paste(entry) }; holder.delete.contentDescription="履歴 ${position+1}を削除"
                    holder.delete.setOnClickListener { remove(entry) }
                } else {
                    val entry=media[position-entries.size]
                    val kind=when { entry.media.mime=="image/gif" -> "GIF"; entry.media.mime.startsWith("video/") -> "動画"; else -> "画像" }
                    holder.label.text="$kind · ${entry.media.mime.substringAfter('/').uppercase()}"
                    holder.label.contentDescription="メディア履歴：$kind"
                    holder.preview.setImageBitmap(entry.preview); holder.preview.visibility=if (entry.preview!=null) View.VISIBLE else View.GONE
                    holder.preview.setOnClickListener { pasteMedia(entry) }
                    holder.label.setOnClickListener { pasteMedia(entry) }
                    holder.delete.contentDescription="メディア履歴 ${position-entries.size+1}を削除"
                    holder.delete.setOnClickListener { removeMedia(entry) }
                }
            }
        }
        translationPanel.addView(list)
        translationPanel.addView(LinearLayout(context).apply {
            isBaselineAligned=false
            addView(button("閉じる","noop",true).apply { setOnClickListener { close() } })
            addView(button("全削除","noop",true).apply { isEnabled=enabled && (entries.isNotEmpty() || media.isNotEmpty()); setOnClickListener {
                text="削除する"; setOnClickListener { clear() }
            } })
        })
    }
    private class ClipboardCell(row: View,val label: TextView,val delete: TextView,val preview: ImageView): androidx.recyclerview.widget.RecyclerView.ViewHolder(row)
}
