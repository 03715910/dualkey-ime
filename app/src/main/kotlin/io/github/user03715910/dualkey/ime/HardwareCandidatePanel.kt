package io.github.user03715910.dualkey.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** A non-focusable subwindow attached to the IME. */
class HardwareCandidatePanel(private val context: Context, private val choose: (Int)->Unit) {
    private val manager=context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout?=null
    private var caret: Rect?=null
    private var editor: Rect?=null
    private data class Page(val values: List<String>, val selected: Int, val nextWord: Boolean,
        val rows: Int, val labels: Boolean, val dark: Boolean)
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

    fun show(host: View, values: List<String>, selected: Int, nextWord: Boolean, keyboardTop: Int?) {
        val token=host.windowToken
        if (token==null || selected !in values.indices) { dismiss(); return }
        val visible=Rect().also(host::getWindowVisibleDisplayFrame)
        val origin: Rect
        val safe: Rect
        if (Build.VERSION.SDK_INT>=30) {
            val metrics=manager.currentWindowMetrics
            origin=metrics.bounds
            val insets=ViewCompat.getRootWindowInsets(host) ?: WindowInsetsCompat.toWindowInsetsCompat(metrics.windowInsets)
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val nav=insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
            safe=Rect(origin.left+maxOf(bars.left,nav.left),origin.top+bars.top,
                origin.right-maxOf(bars.right,nav.right),origin.bottom-maxOf(bars.bottom,nav.bottom))
        } else {
            origin=Rect(0,0,visible.right,visible.bottom)
            safe=Rect(origin)
        }
        if (!visible.isEmpty && !safe.intersect(visible)) safe.setEmpty()
        if (keyboardTop!=null) safe.bottom=minOf(safe.bottom,keyboardTop)
        safe.inset(dp(8),dp(6))

        val width=minOf(dp(288),safe.width())
        val rowHeight=dp(36)
        val labelsHeight=dp(25)+dp(27)
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
        val preferredHeight=minOf(values.size,7)*rowHeight+labelsHeight+padding
        val placeBelow=below>=preferredHeight || (above<preferredHeight && below>=above)
        val available=if (placeBelow) below else above
        if (safe.isEmpty || width<dp(80) || available<minHeight) {
            status="no space around editor"; dismiss(); return
        }
        val labels=available>=minHeight+labelsHeight
        val rows=((available-padding-(if (labels) labelsHeight else 0))/rowHeight).coerceIn(1,7)
        val pageStart=selected/rows*rows
        val height=minOf(rows,values.size-pageStart)*rowHeight+padding+(if (labels) labelsHeight else 0)
        val x=(cursor?.left ?: field?.left ?: safe.left).coerceIn(safe.left,safe.right-width)
        // Without geometry from the editor, keep the fallback away from the bottom input field.
        val y=if (avoid==null) safe.top else if (placeBelow) avoid.bottom+gap else avoid.top-gap-height
        val bounds=Rect(x,y,x+width,y+height).apply { offset(-origin.left,-origin.top) }
        val dark=context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
        val nextPage=Page(values,selected,nextWord,rows,labels,dark)
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
            val panel=root ?: LinearLayout(context)
            if (page!=nextPage) render(panel,nextPage)
            if (root==null) manager.addView(panel,params)
            else if (frame!=bounds) manager.updateViewLayout(panel,params)
            root=panel; frame=bounds; page=nextPage; attachedToken=token
            status="attached=${panel.isAttachedToWindow}, shown=${panel.isShown}"
        } catch (e: WindowManager.BadTokenException) {
            dismiss(); status=e.toString()
        } catch (e: SecurityException) {
            dismiss(); status=e.toString()
        }
    }

    private fun render(panel: LinearLayout, page: Page) {
        val (values,selected,nextWord,rows,labels,dark)=page
        val pageStart=selected/rows*rows
        val ink=if (dark) Color.rgb(235,241,244) else Color.rgb(24,46,54)
        fun background(color: Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(7).toFloat() }
        panel.apply {
            removeAllViews()
            orientation=LinearLayout.VERTICAL; setPadding(dp(5),dp(4),dp(5),dp(4))
            background=background(if (dark) Color.rgb(34,47,54) else Color.WHITE)
            elevation=dp(8).toFloat(); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription="物理キーボードの候補一覧"
        }
        fun label(value: String,size: Float)=TextView(context).apply {
            text=value; textSize=size; setTextColor(ink); gravity=Gravity.CENTER_VERTICAL
            includeFontPadding=false; setPadding(dp(8),0,dp(8),0)
            isFocusable=false; setSingleLine(true); ellipsize=TextUtils.TruncateAt.END
        }
        if (labels) panel.addView(label("${if (nextWord) "続きの予測" else "変換候補"}   ${selected+1} / ${values.size}",12f),LinearLayout.LayoutParams(-1,dp(25)))
        for (index in pageStart until minOf(pageStart+rows,values.size)) {
            panel.addView(label(values[index],18f).apply {
                contentDescription="物理候補：${values[index]}"; isSelected=index==selected
                if (isSelected) background=background(if (dark) Color.rgb(47,105,121) else Color.rgb(208,234,244))
                setOnClickListener { choose(index) }
            },LinearLayout.LayoutParams(-1,dp(36)))
        }
        if (labels) panel.addView(label(if (nextWord) "Tab / 矢印で選択   Enterで追加" else "Space / 矢印で選択   Enterで確定",11f),LinearLayout.LayoutParams(-1,dp(27)))
    }
}
