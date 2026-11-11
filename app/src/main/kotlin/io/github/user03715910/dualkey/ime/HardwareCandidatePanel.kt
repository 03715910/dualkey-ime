package io.github.user03715910.dualkey.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.CursorAnchorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.dualkey.core.Candidate

/** A non-focusable subwindow attached to the IME. */
internal class HardwareCandidatePanel(
    private val context: Context,
    private val choose: (Candidate) -> Unit,
    private val changeMode: (CandidateMode) -> Unit,
    private val more: () -> Unit,
) {
    private val manager=context.getSystemService(WindowManager::class.java)
    private var root: Content?=null
    private var caret: Rect?=null
    private var editor: Rect?=null
    private data class Page(val values: List<Candidate>, val selected: Int, val title: String,
        val mode: CandidateMode?, val showTabs: Boolean, val showTitle: Boolean, val dark: Boolean, val canLoadMore: Boolean)
    private var page: Page?=null
    private var frame: Rect?=null
    private var attachedToken: IBinder?=null
    var status="idle"; private set

    private fun dp(value: Int)=(value*context.resources.displayMetrics.density).toInt()

    fun cursor(info: CursorAnchorInfo) {
        fun screenRect(bounds: RectF): Rect? {
            val mapped=RectF(bounds)
            info.matrix.mapRect(mapped)
            if (!listOf(mapped.left,mapped.top,mapped.right,mapped.bottom).all(Float::isFinite)) return null
            return Rect().also {
                mapped.roundOut(it)
                // A caret can have zero width.
                if (it.left==it.right) it.right++
            }.takeUnless { it.isEmpty }
        }
        editor=if (Build.VERSION.SDK_INT>=33) info.editorBoundsInfo?.editorBounds?.let(::screenRect) else null
        caret=if (info.insertionMarkerFlags and CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION!=0) {
            val x=info.insertionMarkerHorizontal
            screenRect(RectF(x,info.insertionMarkerTop,x,info.insertionMarkerBottom))
        } else null
    }

    fun clearCursor() { caret=null; editor=null }

    fun dismiss() {
        root?.let { runCatching { manager.removeViewImmediate(it) } }
        root=null; page=null; frame=null; attachedToken=null
    }

    fun show(host: View, values: List<Candidate>, selected: Int, title: String, mode: CandidateMode?,
        keyboardTop: Int?, thinHost: Boolean, canLoadMore: Boolean,
    ) {
        val token=host.windowToken
        if (token==null || !host.isAttachedToWindow) { dismiss(); return }
        val visible=Rect().also(host::getWindowVisibleDisplayFrame)
        val origin: Rect
        val safe: Rect
        if (Build.VERSION.SDK_INT>=30) {
            val metrics=manager.currentWindowMetrics
            origin=metrics.bounds
            val insets=if (thinHost) WindowInsetsCompat.toWindowInsetsCompat(metrics.windowInsets)
                else ViewCompat.getRootWindowInsets(host) ?: WindowInsetsCompat.toWindowInsetsCompat(metrics.windowInsets)
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val nav=insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
            safe=Rect(origin.left+maxOf(bars.left,nav.left),origin.top+bars.top,
                origin.right-maxOf(bars.right,nav.right),origin.bottom-maxOf(bars.bottom,nav.bottom))
        } else {
            @Suppress("DEPRECATION")
            val size=Point().also(manager.defaultDisplay::getRealSize)
            origin=Rect(0,0,size.x,size.y)
            safe=if (visible.height()>dp(80)) Rect(visible) else Rect(origin)
        }
        if (!thinHost && !visible.isEmpty && !safe.intersect(visible)) safe.setEmpty()
        if (keyboardTop!=null) safe.bottom=minOf(safe.bottom,keyboardTop)
        safe.inset(dp(8),dp(6))

        val width=minOf(dp(288),safe.width())
        val rowHeight=dp(36)
        val titleHeight=dp(25)
        val tabsHeight=dp(36)
        val padding=dp(4)*2
        val gap=dp(6)
        val minHeight=rowHeight+padding
        val cursor=caret?.takeIf { Rect.intersects(safe,it) }
        // Keep the whole field clear when possible, or just the active line in a tall editor.
        val field=editor?.takeIf { Rect.intersects(safe,it) &&
            maxOf(it.top-safe.top,safe.bottom-it.bottom)>=minHeight+gap }
        val avoid=field ?: cursor
        val above=if (avoid!=null) avoid.top-gap-safe.top else 0
        val below=if (avoid!=null) safe.bottom-avoid.bottom-gap else safe.height()
        val bodyHeight=values.size.coerceIn(1,7)*rowHeight
        val preferredHeight=bodyHeight+titleHeight+padding+(if (mode!=null) tabsHeight else 0)
        val placeBelow=below>=preferredHeight || (above<preferredHeight && below>=above)
        val available=if (placeBelow) below else above
        if (safe.isEmpty || width<dp(80) || available<minHeight) {
            status="no space around editor"; dismiss(); return
        }
        val showTabs=mode!=null && available>=minHeight+tabsHeight
        val tabsSpace=if (showTabs) tabsHeight else 0
        val showTitle=available>=minHeight+tabsSpace+titleHeight
        val height=minOf(available,bodyHeight+padding+tabsSpace+(if (showTitle) titleHeight else 0))
        val x=(cursor?.left ?: field?.left ?: safe.left).coerceIn(safe.left,safe.right-width)
        // Without geometry from the editor, keep the fallback away from the bottom input field.
        val y=if (avoid==null) safe.top else if (placeBelow) avoid.bottom+gap else avoid.top-gap-height
        val bounds=Rect(x,y,x+width,y+height).apply { offset(-origin.left,-origin.top) }
        val dark=context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
        val nextPage=Page(values,selected,title,mode,showTabs,showTitle,dark,canLoadMore)
        if (attachedToken!=token) dismiss()
        if (frame==bounds && page==nextPage) return
        val params=WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
            this.token=token; gravity=Gravity.TOP or Gravity.LEFT; this.x=bounds.left; this.y=bounds.top
            softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setTitle("DualKey hardware candidates")
            if (Build.VERSION.SDK_INT>=30) {
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        try {
            val panel=root ?: Content()
            if (page!=nextPage) panel.render(nextPage,page)
            if (root==null) manager.addView(panel,params)
            else if (frame!=bounds) manager.updateViewLayout(panel,params)
            root=panel; frame=bounds; page=nextPage; attachedToken=token
            val anchor=when {
                field!=null -> "editor"
                cursor!=null -> "caret"
                else -> "fallback"
            }
            status="attached=${panel.isAttachedToWindow}, shown=${panel.isShown}, anchor=$anchor"
        } catch (e: WindowManager.BadTokenException) {
            dismiss(); status=e.toString()
        } catch (e: SecurityException) {
            dismiss(); status=e.toString()
        } catch (e: IllegalArgumentException) {
            dismiss(); status=e.toString()
        }
    }

    private fun roundedBackground(color: Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(7).toFloat() }

    private inner class Content : LinearLayout(context) {
        private lateinit var current: Page
        private val heading=label(12f)
        private val empty=label(14f).apply { text="この種類の候補はありません" }
        private val list=ListView(context)
        private val tabs=LinearLayout(context)
        private val rowAccessibility=object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host,info)
                info.isSelected=host.isActivated
            }
        }
        private val buttons=CandidateMode.entries.associateWith { mode ->
            label(11f).apply {
                text=mode.label; contentDescription="物理候補の種類：${mode.label}"
                gravity=Gravity.CENTER
                setPadding(0,0,0,0)
                setSingleLine()
                setOnClickListener { changeMode(mode) }
                tabs.addView(this,LayoutParams(0,-1,1f))
            }
        }
        private val rows=object : BaseAdapter() {
            override fun getCount()=current.values.size
            override fun getItem(position: Int)=current.values[position]
            override fun getItemId(position: Int)=position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                ((convertView as? TextView) ?: label(17f).apply {
                    minHeight=dp(36)
                    setPadding(dp(8),dp(4),dp(8),dp(4))
                    accessibilityDelegate=rowAccessibility
                }).apply {
                    val candidate=getItem(position)
                    tag=candidate
                    text=candidate.text
                    contentDescription="物理候補：$text"
                    setTextColor(ink(current.dark))
                    highlightRow(this,position)
                }
        }

        init {
            orientation=LinearLayout.VERTICAL; setPadding(dp(5),dp(4),dp(5),dp(4))
            elevation=dp(8).toFloat(); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription="物理キーボードの候補一覧"
            addView(heading,LayoutParams(-1,dp(25)))
            addView(FrameLayout(context).apply {
                addView(list,FrameLayout.LayoutParams(-1,-1))
                addView(empty,FrameLayout.LayoutParams(-1,-1))
            },LayoutParams(-1,0,1f))
            addView(tabs,LayoutParams(-1,dp(36)))
            list.divider=null
            list.isFocusable=false
            list.setOnItemClickListener { _,row,_,_ -> (row.tag as? Candidate)?.let(choose) }
            list.setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                    if (!::current.isInitialized || !current.canLoadMore || visible==0 || first+visible<total) return
                    val page=current
                    view.post { if (isAttachedToWindow && current===page) more() }
                }
            })
        }

        fun render(page: Page, previous: Page?) {
            current=page
            background=roundedBackground(if (page.dark) Color.rgb(34,47,54) else Color.WHITE)
            heading.text=if (page.selected in page.values.indices) "${page.title}   ${page.selected+1} / ${page.values.size}" else page.title
            heading.visibility=if (page.showTitle) VISIBLE else GONE
            heading.setTextColor(ink(page.dark))
            empty.visibility=if (page.values.isEmpty()) VISIBLE else GONE
            empty.setTextColor(ink(page.dark))
            tabs.visibility=if (page.showTabs) VISIBLE else GONE
            buttons.forEach { (mode,button) ->
                button.isSelected=mode==page.mode
                button.setTextColor(ink(page.dark))
                button.background=if (button.isSelected) roundedBackground(highlight(page.dark)) else null
            }
            if (list.adapter==null) list.adapter=rows
            else if (previous==null || previous.values!=page.values || previous.dark!=page.dark) rows.notifyDataSetChanged()
            else if (previous.selected!=page.selected) {
                // Arrow keys change the highlight without rebinding the scrolling list.
                for (index in 0 until list.childCount) {
                    highlightRow(list.getChildAt(index),list.firstVisiblePosition+index)
                }
                list.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            }
            if (previous?.mode!=page.mode || previous?.title!=page.title) list.setSelection(maxOf(0,page.selected))
            else if (page.selected>=0 && page.selected !in list.firstVisiblePosition..list.lastVisiblePosition)
                list.setSelection(page.selected)
        }

        private fun highlightRow(row: View, position: Int) {
            row.isActivated=position==current.selected
            row.background=if (row.isActivated) roundedBackground(highlight(current.dark)) else null
        }

        private fun label(size: Float)=TextView(context).apply {
            textSize=size; gravity=Gravity.CENTER_VERTICAL
            includeFontPadding=false; setPadding(dp(8),0,dp(8),0)
            isFocusable=false
        }
        private fun ink(dark: Boolean)=if (dark) Color.rgb(235,241,244) else Color.rgb(24,46,54)
        private fun highlight(dark: Boolean)=if (dark) Color.rgb(47,105,121) else Color.rgb(208,234,244)
    }
}
