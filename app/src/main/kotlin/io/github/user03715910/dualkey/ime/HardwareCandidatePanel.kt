package io.github.user03715910.dualkey.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
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

/** A non-focusable subwindow attached to the IME: no overlay or system window permission. */
class HardwareCandidatePanel(private val context: Context,private val choose: (Int)->Unit) {
    private val manager=context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout?=null
    private var anchor: FloatArray?=null
    var status="idle"; private set
    private fun dp(value: Int)=(value*context.resources.displayMetrics.density).toInt()

    fun cursor(info: CursorAnchorInfo) {
        val points=floatArrayOf(info.insertionMarkerHorizontal,info.insertionMarkerTop,
            info.insertionMarkerHorizontal,info.insertionMarkerBottom)
        if (points.any { !it.isFinite() }) { anchor=null; return }
        info.matrix.mapPoints(points)
        anchor=points.takeIf { it.all(Float::isFinite) && info.insertionMarkerFlags and CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION!=0 }
    }
    fun clearCursor() { anchor=null }
    fun dismiss() { root?.let { runCatching { manager.removeViewImmediate(it) } }; root=null }

    fun show(token: IBinder?,values: List<String>,selected: Int,nextWord: Boolean,keyboardTop: Int?): Boolean {
        if (token==null || values.isEmpty() || selected !in values.indices) { status="no token or selection"; dismiss(); return false }
        val metrics=context.resources.displayMetrics
        val bounds=if (Build.VERSION.SDK_INT>=30) manager.currentWindowMetrics.bounds else null
        val screenWidth=bounds?.width() ?: metrics.widthPixels
        val screenHeight=bounds?.height() ?: metrics.heightPixels
        val safeTop=dp(32)
        val safeBottom=(keyboardTop ?: (screenHeight-dp(40))).coerceAtLeast(safeTop+dp(160))
        val width=minOf(dp(288),screenWidth-dp(16))
        val pageSize=((safeBottom-safeTop-dp(64))/dp(36)).coerceIn(1,7)
        val pageStart=selected/pageSize*pageSize
        val dark=context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
        val ink=if (dark) Color.rgb(235,241,244) else Color.rgb(24,46,54)
        fun background(color: Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(7).toFloat() }
        val panel=LinearLayout(context).apply {
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
        panel.addView(label("${if (nextWord) "続きの予測" else "変換候補"}   ${selected+1} / ${values.size}",12f),LinearLayout.LayoutParams(-1,dp(25)))
        for (index in pageStart until minOf(pageStart+pageSize,values.size)) {
            panel.addView(label(values[index],18f).apply {
                contentDescription="物理候補：${values[index]}"; isSelected=index==selected
                if (isSelected) background=background(if (dark) Color.rgb(47,105,121) else Color.rgb(208,234,244))
                setOnClickListener { choose(index) }
            },LinearLayout.LayoutParams(-1,dp(36)))
        }
        panel.addView(label(if (nextWord) "Tab / 矢印で選択   Enterで追加" else "Space / 矢印で選択   Enterで確定",11f),LinearLayout.LayoutParams(-1,dp(27)))
        panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(safeBottom-safeTop,View.MeasureSpec.AT_MOST))
        val height=panel.measuredHeight
        val point=anchor
        val x=(point?.get(0)?.toInt() ?: dp(12)).coerceIn(dp(8),maxOf(dp(8),screenWidth-width-dp(8)))
        val below=(point?.get(3)?.toInt()?.plus(dp(6)) ?: (safeBottom-height-dp(8)))
        val y=(if (below+height<=safeBottom) below else (point?.get(1)?.toInt() ?: safeBottom)-height-dp(6))
            .coerceIn(safeTop,maxOf(safeTop,safeBottom-height))
        val params=WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
            this.token=token; gravity=Gravity.TOP or Gravity.LEFT; this.x=x; this.y=y
            softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setTitle("DualKey hardware candidates")
            if (Build.VERSION.SDK_INT>=30) setFitInsetsTypes(0)
        }
        return try {
            val existing=root
            if (existing==null) { manager.addView(panel,params); root=panel }
            else {
                existing.removeAllViews(); existing.background=panel.background
                while (panel.childCount>0) {
                    val child=panel.getChildAt(0); panel.removeViewAt(0); existing.addView(child)
                }
                manager.updateViewLayout(existing,params)
            }
            status="attached=${root?.isAttachedToWindow}, shown=${root?.isShown}"; true
        }
        catch (e: WindowManager.BadTokenException) { status=e.toString(); false }
        catch (e: SecurityException) { status=e.toString(); false }
    }
}
