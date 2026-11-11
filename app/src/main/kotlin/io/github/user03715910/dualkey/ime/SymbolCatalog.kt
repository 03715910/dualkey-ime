package io.github.user03715910.dualkey.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

private fun symbolsIn(range: IntRange) = range.map { it.toChar().toString() }

private enum class SymbolCategory(val label: String, val symbols: List<String>) {
    PUNCTUATION("句読点", "※ … ‥ ⋯ ⋮ ⋰ ⋱ ・ · • ‧ ･ 、 。 ， ． : ： ; ； ¡ ¿ ‽ ⁈ ⁉ ‼ ″ ′ ‴ 〃 〆 々 ゝ ゞ ヽ ヾ 〒 § ¶ † ‡ © ® ™ ℗ ℠ № ª º ‰ ‱ ⁂ ⁎".split(' ')),
    BRACKETS("括弧", "( ) （ ） [ ] ［ ］ { } ｛ ｝ < > ＜ ＞ 「 」 『 』 【 】 〔 〕 〈 〉 《 》 〖 〗 〘 〙 〚 〛 ⟨ ⟩ ⟪ ⟫ ⌈ ⌉ ⌊ ⌋ « » ‹ › ‘ ’ “ ” „ ‚".split(' ')),
    ARROWS("矢印", symbolsIn(0x2190..0x21ff) + "➔ ➜ ➝ ➞ ➟ ➠ ➡ ➢ ➣ ➤ ⟵ ⟶ ⟷ ⟸ ⟹ ⟺".split(' ')),
    MATH("数学", "+ − ± ∓ × ÷ = ≠ ≒ ≈ ≃ ≅ ≡ < > ≤ ≥ ∞ √ ∛ ∜ ∑ ∏ ∫ ∬ ∭ ∮ ∂ ∇ ∆ ∝ ∴ ∵ ∠ ⊥ ∥ ∧ ∨ ¬ ∀ ∃ ∄ ∅ ∈ ∉ ∋ ∩ ∪ ⊂ ⊃ ⊆ ⊇ ⊕ ⊖ ⊗ ⊘ ∙ ⋅ ∗ ° ′ ″ ½ ⅓ ⅔ ¼ ¾ ⅕ ⅖ ⅗ ⅘ ⅙ ⅚ ⅛ ⅜ ⅝ ⅞".split(' ')),
    NUMBERS("丸数字", listOf("⓪") + symbolsIn(0x2460..0x2473) + symbolsIn(0x3251..0x325f) +
        symbolsIn(0x32b1..0x32bf) + symbolsIn(0x2474..0x2487) + symbolsIn(0x2776..0x277f) +
        symbolsIn(0x24eb..0x24f4) + symbolsIn(0x2160..0x216b) + symbolsIn(0x2170..0x217b)),
    SHAPES("図形", "★ ☆ ♡ ♥ ♢ ♦ ♧ ♣ ♤ ♠ ☀ ☁ ☂ ☃ ☎ ☑ ☒ ✓ ✔ ✕ ✖ ✚ ✦ ✧ ✩ ✪ ✿ ❀ ❖ ♪ ♫ ♬ ♩ ♭ ♮ ♯".split(' ') +
        symbolsIn(0x25a0..0x25ff)),
    CURRENCY("通貨", "¥ ￥ $ ＄ € £ ¢ ¤ ₩ ₽ ₹ ₿ ₱ ₫ ฿ ₺ ₪ ₴ ₦ ₡ ₲ ₵ ₸ ₼ ₾ ₭ ₮ ₨ ₣ ₤ ₥ ₧ ₰".split(' ')),
    FULL_WIDTH("全角", symbolsIn(0xff01..0xff5e)),
}

internal class SymbolCatalog(
    context: Context,
    gridHeight: Int,
    private val ink: Int,
    private val keyColor: Int,
    private val functionColor: Int,
    private val feedback: (View) -> Unit,
    insert: (String) -> Unit,
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private val glyphPaint = Paint()
    private val grid = GridView(context).apply {
        contentDescription = "記号の一覧"
        numColumns = GridView.AUTO_FIT
        columnWidth = dp(39)
        horizontalSpacing = dp(2)
        verticalSpacing = dp(2)
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
        isFocusable = false
        setOnItemClickListener { parent, view, position, _ ->
            feedback(view)
            insert(parent.getItemAtPosition(position) as String)
        }
    }
    private val tabs = LinearLayout(context).apply { isBaselineAligned = false }
    private val buttons = SymbolCategory.entries.associateWith { category ->
        TextView(context).apply {
            text = category.label
            gravity = Gravity.CENTER
            textSize = 12f
            isFocusable = false
            setPadding(dp(8), 0, dp(8), 0)
            setSingleLine(true)
            setOnClickListener { feedback(this); select(category) }
            tabs.addView(this, LayoutParams(-2, dp(36), 1f))
        }
    }

    init {
        orientation = VERTICAL
        addView(HorizontalScrollView(context).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }, LayoutParams(-1, -2))
        addView(grid, LayoutParams(-1, dp(gridHeight)))
        select(SymbolCategory.PUNCTUATION)
    }

    private fun select(category: SymbolCategory) {
        buttons.forEach { (item, button) ->
            button.isSelected = item == category
            button.setTextColor(if (button.isSelected) Color.WHITE else ink)
            button.setBackgroundColor(if (button.isSelected) 0xff087f8c.toInt() else functionColor)
        }
        val symbols = category.symbols.filter(glyphPaint::hasGlyph)
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = symbols.size
            override fun getItem(position: Int) = symbols[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                val cell = recycled as? TextView ?: TextView(context).apply {
                    gravity = Gravity.CENTER
                    textSize = 20f
                    setTextColor(ink)
                    setBackgroundColor(keyColor)
                    layoutParams = AbsListView.LayoutParams(-1, dp(40))
                }
                cell.text = getItem(position)
                cell.contentDescription = "記号：${cell.text}"
                return cell
            }
        }
        grid.setSelection(0)
    }
}
