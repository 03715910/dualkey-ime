package io.github.user03715910.dualkey.ime

import android.content.Context
import android.graphics.Paint
import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.ceil

/** Virtualized cells: candidate count does not multiply Android View instances. */
class CandidateGrid @JvmOverloads constructor(context: Context,attrs: android.util.AttributeSet?=null): RecyclerView(context,attrs) {
    private var values=emptyList<String>()
    private var selected=-1
    private var select: (Int)->Unit={}
    private var more: ()->Unit={}
    private var loading=false
    private var canLoad=false
    private var ink=0
    private var backgroundColor=0
    private var columns=4
    private val paint=Paint().apply { textSize=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,16f,resources.displayMetrics) }
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private val manager=GridLayoutManager(context,columns)
    private val cells=object: Adapter<Cell>() {
        override fun getItemCount()=values.size+1
        override fun onCreateViewHolder(parent: ViewGroup,type: Int)=Cell(TextView(context).apply {
            isFocusable=false; textSize=16f; minHeight=dp(38); maxLines=2
            ellipsize=android.text.TextUtils.TruncateAt.END; includeFontPadding=false
            gravity=Gravity.CENTER_VERTICAL; setPadding(dp(6),dp(4),dp(6),dp(4))
            layoutParams=LayoutParams(-1,-2).apply { setMargins(dp(1),dp(1),dp(1),dp(1)) }
        })
        override fun onBindViewHolder(holder: Cell,position: Int) {
            val v=holder.label
            if (position==values.size) {
                v.text=if (loading) "${values.size}件・検索中…" else "${values.size}件"
                v.contentDescription=v.text; v.setTextColor(ink); v.setBackgroundColor(backgroundColor)
                v.setOnClickListener(null); v.isClickable=false
            } else {
                v.text=values[position]; v.contentDescription="候補 ${position+1}：${values[position]}"
                v.setTextColor(if (position==selected) android.graphics.Color.WHITE else ink)
                v.setBackgroundColor(if (position==selected) 0xff087f8c.toInt() else backgroundColor)
                v.setOnClickListener { val index=holder.bindingAdapterPosition; if (index in values.indices) select(index) }
            }
        }
    }
    private class Cell(val label: TextView): ViewHolder(label)
    init {
        contentDescription="変換候補の一覧"; layoutManager=manager; adapter=cells; itemAnimator=null
        manager.spanSizeLookup=object: GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                if (position !in values.indices) return columns
                return ceil((paint.measureText(values[position])+dp(14))/((width-2*paddingLeft).coerceAtLeast(dp(80)).toFloat()/columns))
                    .toInt().coerceIn(1,columns)
            }
        }
        addOnScrollListener(object: OnScrollListener() {
            override fun onScrolled(view: RecyclerView,dx: Int,dy: Int) {
                if (canLoad && !loading && manager.findLastVisibleItemPosition()>=values.size-8) post { more() }
            }
        })
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        columns=(w/dp(80).coerceAtLeast(1)).coerceIn(3,8); manager.spanCount=columns
    }
    fun submit(items: List<String>,choice: Int,foreground: Int,background: Int,choose: (Int)->Unit,canLoadMore: Boolean,busy: Boolean,load: ()->Unit) {
        val changed=values!=items
        val old=selected
        values=items; selected=choice; ink=foreground; backgroundColor=background; select=choose; canLoad=canLoadMore; loading=busy; more=load
        if (changed) cells.notifyDataSetChanged()
        else { if (old in values.indices) cells.notifyItemChanged(old); if (selected in values.indices) cells.notifyItemChanged(selected); cells.notifyItemChanged(values.size) }
        if (selected>=0 && selected!=old) scrollToPosition(selected)
    }
}
