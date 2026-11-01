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
    private val expandedScroll = root.findViewById<ScrollView>(R.id.candidate_expanded_scroll)
    private val expandedCandidates = root.findViewById<LinearLayout>(R.id.candidate_expanded)
    private var candidateExpanded = false
    private var expandedVisibleCount=50
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
    private fun updateBody() {
        val translating=translationPanel.visibility==View.VISIBLE
        numberRow.visibility=if (showNumbers && !translating && !candidateExpanded) View.VISIBLE else View.GONE
        keys.visibility=if (showKeys && !translating && !candidateExpanded) View.VISIBLE else View.GONE
        expandedScroll.visibility=if (candidateExpanded && !translating) View.VISIBLE else View.GONE
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
        isClickable = true
        setOnClickListener { feedback(this); callback(action) }
    }
    fun rebuild(settings: Settings, english: Boolean, shift: Int, mode: DisplayMode, symbols: Boolean, sensitive: Boolean) {
        candidateExpanded=false
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
        toolbar.addView(button(if (english) "EN" else "日本語", "mode", true,
            description=if (english) "日本語に切り替え" else "英語に切り替え"))
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
            listOf("! @ # $ % ^ & * ( )", "- _ = + [ ] { } / \\", "、 。 ー ・ 「 」 ？ ！ ： ；").forEach { line -> row(line.split(' ').map { it to it }) }
            row(listOf("戻る" to "symbols", "空白" to "space", "⌫" to "delete", "↵" to "enter"))
        } else if (english) {
            listOf("qwertyuiop","asdfghjkl").forEach { line -> row(line.map { val s = if (shift>0) it.uppercase() else it.toString(); s to s }) }
            row(listOf((if (shift==2) "⇪" else "⇧") to "shift") + "zxcvbnm".map { val s = if (shift>0) it.uppercase() else it.toString(); s to s } + ("⌫" to "delete"))
            row(listOf("記号" to "symbols", "," to ",", "Space" to "space", "." to ".", "↵" to "enter"))
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
    fun candidates(values: List<String>, selected: Int, select: (Int) -> Unit, canLoadMore: Boolean=false, loading: Boolean=false) {
        if (values.take(3)!=previousCandidates.take(3)) expandedVisibleCount=50
        previousCandidates=values
        candidateStrip.removeAllViews()
        expandedCandidates.removeAllViews()
        candidateExpand.visibility=if (values.isEmpty()) View.GONE else View.VISIBLE
        if (values.isEmpty()) candidateExpanded=false
        candidateExpand.setOnClickListener {
            feedback(candidateExpand)
            candidateExpanded=!candidateExpanded
            if (candidateExpanded) populateExpanded(values,selected,select,canLoadMore,loading)
            expandedScroll.layoutParams.height=dp(if (showKeys) 4*(keyHeight+4)+(if (showNumbers) numberHeight+4 else 0) else 160)
            expandedScroll.scrollTo(0,0); updateBody()
        }
        if (values.isEmpty()) renderEmptyCandidates()
        val first=if (selected>=12) maxOf(0,selected-4) else 0
        (first until minOf(values.size,first+12)).forEach { i -> val s=values[i]; candidateStrip.addView(TextView(context).apply {
            text=s; textSize=17f; includeFontPadding=false; setSingleLine(true); setTextColor(if (selected==i) Color.WHITE else ink); gravity=Gravity.CENTER
            setPadding(dp(14),0,dp(14),0)
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.MATCH_PARENT)
            if (selected==i) setBackgroundColor(Color.rgb(8,127,140))
            isFocusable=false; setOnClickListener { select(i) }
            if (selected==i) post { (candidateStrip.parent as HorizontalScrollView).smoothScrollTo(left,0) }
        }) }
        if (candidateExpanded) populateExpanded(values,selected,select,canLoadMore,loading)
        updateBody()
    }
    private fun populateExpanded(values: List<String>, selected: Int, select: (Int)->Unit, canLoadMore: Boolean, loading: Boolean) {
        expandedCandidates.removeAllViews()
        if (selected>=expandedVisibleCount) expandedVisibleCount=((selected/50)+1)*50
        val availableWidth=((root.width.takeIf { it>0 } ?: context.resources.displayMetrics.widthPixels)-root.paddingLeft-root.paddingRight).coerceAtLeast(1)
        val columns=(availableWidth/dp(80).coerceAtLeast(1)).coerceIn(3,6)
        val cellWidth=availableWidth.toFloat()/columns
        val grid=GridLayout(context).apply {
            columnCount=columns; isFocusable=false
            layoutParams=LinearLayout.LayoutParams(-1,-2)
        }
        var row=0
        var column=0
        values.take(expandedVisibleCount).forEachIndexed { index, value ->
            val item=TextView(context).apply {
                    text=value; contentDescription="候補 ${index+1}：$value"; textSize=16f; setTextColor(if (selected==index) Color.WHITE else ink)
                    gravity=Gravity.CENTER_VERTICAL; minHeight=dp(38); maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END; includeFontPadding=false
                    setPadding(dp(6),dp(4),dp(6),dp(4)); isFocusable=false
                    setBackgroundColor(if (selected==index) Color.rgb(8,127,140) else keyColor)
                    setOnClickListener { candidateExpanded=false; updateBody(); select(index) }
            }
            // Short words share a row; phrases reserve enough adjacent cells.
            // Measuring in pixels also respects the user's font-size setting.
            val span=kotlin.math.ceil((item.paint.measureText(value)+dp(14))/cellWidth).toInt().coerceIn(1,columns)
            if (column+span>columns) { row++; column=0 }
            grid.addView(item,GridLayout.LayoutParams(
                GridLayout.spec(row,GridLayout.FILL),GridLayout.spec(column,span,GridLayout.FILL)
            ).apply {
                width=((column+span)*cellWidth).toInt()-(column*cellWidth).toInt()-2*dp(1)
                height=GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(dp(1),dp(1),dp(1),dp(1))
            })
            column+=span
            if (column==columns) { row++; column=0 }
        }
        expandedCandidates.addView(grid)
        expandedCandidates.addView(TextView(context).apply {
            text="${minOf(expandedVisibleCount,values.size)} / ${values.size}件"; textSize=12f; setTextColor(ink)
            gravity=Gravity.CENTER; setPadding(0,dp(6),0,dp(6))
        })
        if (expandedVisibleCount<values.size || canLoadMore || loading) expandedCandidates.addView(TextView(context).apply {
            text=when { loading -> "候補を検索しています…"; expandedVisibleCount<values.size -> "次の50件を表示"; else -> "さらに候補を検索" }
            contentDescription="さらに候補を表示"; textSize=16f; setTextColor(ink); gravity=Gravity.CENTER
            minHeight=dp(48); setPadding(dp(8),dp(8),dp(8),dp(8)); setBackgroundColor(functionColor); isEnabled=!loading; isFocusable=false
            setOnClickListener {
                if (expandedVisibleCount<values.size) { expandedVisibleCount+=50; populateExpanded(values,selected,select,canLoadMore,false) }
                else callback("more_candidates")
            }
        },LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(2),dp(4),dp(2),dp(8)) })
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
}
