package io.github.user03715910.dualkey.ime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import io.github.dualkey.core.DisplayMode
import io.github.user03715910.dualkey.R
import io.github.user03715910.dualkey.data.Settings

class KeyboardUi(private val context: Context, private val callback: (String) -> Unit,
    private val feedback: (View) -> Unit) {
    val root = LayoutInflater.from(context).inflate(R.layout.ime_root, null) as LinearLayout
    private val toolbar = root.findViewById<LinearLayout>(R.id.toolbar)
    private val candidateStrip = root.findViewById<LinearLayout>(R.id.candidate_strip)
    private val numberRow = root.findViewById<LinearLayout>(R.id.number_row)
    private val keys = root.findViewById<LinearLayout>(R.id.key_area)
    val translationPanel: LinearLayout = root.findViewById(R.id.translation_panel)
    private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink = if (dark) Color.rgb(233,241,243) else Color.rgb(23,47,53)
    private val keyColor = if (dark) Color.rgb(48,65,72) else Color.WHITE
    private var keyHeight = 52
    private var showNumbers=false
    private var showKeys=false
    private fun updateBody() {
        val translating=translationPanel.visibility==View.VISIBLE
        numberRow.visibility=if (showNumbers && !translating) View.VISIBLE else View.GONE
        keys.visibility=if (showKeys && !translating) View.VISIBLE else View.GONE
    }
    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    init { root.setBackgroundColor(if (dark) Color.rgb(22,35,41) else Color.rgb(220,234,236)) }
    private fun decorate(v: TextView) {
        v.setTextColor(ink); v.isFocusable = false
        v.background = GradientDrawable().apply { setColor(keyColor); cornerRadius = dp(8).toFloat() }
        v.layoutParams = LinearLayout.LayoutParams(0,dp(keyHeight),1f).apply { setMargins(dp(2),dp(2),dp(2),dp(2)) }
    }
    private fun button(label: String, action: String, small: Boolean = false) = TextView(context).apply {
        text = label; gravity = Gravity.CENTER; textSize = if (small) 13f else 19f
        decorate(this)
        if (small) layoutParams.height = dp(38)
        isClickable = true
        setOnClickListener { feedback(this); callback(action) }
    }
    fun rebuild(settings: Settings, english: Boolean, shift: Int, mode: DisplayMode, symbols: Boolean, sensitive: Boolean) {
        // Keep the keyboard within 65% of the current display, including landscape.
        val maxRow = ((context.resources.configuration.screenHeightDp * .65f - 100) / 6).toInt().coerceAtLeast(28)
        keyHeight = (settings.keyHeight * settings.heightPercent / 100).coerceAtMost(maxRow)
        toolbar.removeAllViews()
        toolbar.addView(button(if (english) "EN" else "あ", "mode", true))
        toolbar.addView(button(if (mode == DisplayMode.COMPACT) "全表示" else "省スペース", if (mode == DisplayMode.COMPACT) "full" else "compact", true))
        toolbar.addView(button("隠す", "hide", true))
        if (!sensitive) toolbar.addView(button("翻訳", "translate", true))
        toolbar.addView(button("設定", "settings", true))
        toolbar.addView(button("🌐", "picker", true))
        numberRow.removeAllViews(); keys.removeAllViews()
        showNumbers=mode==DisplayMode.FULL && settings.numberRow
        showKeys=mode==DisplayMode.FULL
        updateBody()
        if (mode != DisplayMode.FULL) return
        "1234567890".forEach { numberRow.addView(button(it.toString(),it.toString())) }
        fun row(items: List<Pair<String,String>>) { keys.addView(LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL; items.forEach { addView(button(it.first,it.second)) } }) }
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
            groups.forEach { group -> keys.addView(LinearLayout(context).apply {
                orientation=LinearLayout.HORIZONTAL
                group.forEach { values -> addView(FlickKey(context,values,dp(settings.flickDp).toFloat(),feedback,callback,!sensitive).also(::decorate)) }
            }) }
            keys.addView(LinearLayout(context).apply {
                orientation=LinearLayout.HORIZONTAL
                addView(button("記号", "symbols"))
                addView(FlickKey(context,listOf("わ","を","ん","ー","〜"),dp(settings.flickDp).toFloat(),feedback,callback,!sensitive).also(::decorate))
                addView(button("⌫", "delete"))
            })
            row(listOf("゛゜小" to "modify", "、。" to "punctuation", "空白/変換" to "space", "↵" to "enter"))
        }
    }
    fun candidates(values: List<String>, selected: Int, select: (Int) -> Unit) {
        candidateStrip.removeAllViews()
        if (values.isEmpty()) {
            candidateStrip.addView(TextView(context).apply { text="DualKey IME"; textSize=13f; setTextColor(ink); gravity=Gravity.CENTER_VERTICAL; setPadding(dp(8),0,0,0) })
        }
        values.forEachIndexed { i,s -> candidateStrip.addView(TextView(context).apply {
            text=s; textSize=18f; setTextColor(if (selected==i) Color.WHITE else ink); gravity=Gravity.CENTER
            setPadding(dp(14),0,dp(14),0)
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.MATCH_PARENT)
            if (selected==i) setBackgroundColor(Color.rgb(8,127,140))
            isFocusable=false; setOnClickListener { select(i) }
            if (selected==i) post { (candidateStrip.parent as HorizontalScrollView).smoothScrollTo(left,0) }
        }) }
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
