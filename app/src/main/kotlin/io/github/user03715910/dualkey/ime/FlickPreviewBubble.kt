package io.github.user03715910.dualkey.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.view.View
import kotlin.math.min

/** Original vector bubble: five directional cells inside a rounded diamond. */
class FlickPreviewBubble(context: Context,private val values: List<String>): View(context) {
    private val dark=resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame=Path()
    private val pointer=Path()
    private val cell=RectF()
    private var selected=0
    private fun dp(value: Float)=value*resources.displayMetrics.density
    init { setLayerType(LAYER_TYPE_SOFTWARE,null); importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun select(direction: Int) { selected=direction; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left=dp(6f); val top=dp(6f); val body=min(width-dp(12f),height-dp(24f))
        val cx=width/2f; val cy=top+body/2f
        frame.reset(); frame.moveTo(cx,top); frame.lineTo(left+body,cy); frame.lineTo(cx,top+body); frame.lineTo(left,cy); frame.close()
        paint.style=Paint.Style.FILL; paint.color=if (dark) Color.rgb(40,60,69) else Color.rgb(249,253,253)
        paint.pathEffect=CornerPathEffect(dp(10f)); paint.setShadowLayer(dp(4f),0f,dp(2f),0x55000000)
        canvas.drawPath(frame,paint); paint.clearShadowLayer()
        pointer.reset(); pointer.moveTo(cx-dp(9f),top+body-dp(9f)); pointer.lineTo(cx, height-dp(3f)); pointer.lineTo(cx+dp(9f),top+body-dp(9f)); pointer.close()
        paint.pathEffect=null; canvas.drawPath(pointer,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=dp(1.5f); paint.color=Color.rgb(8,127,140); paint.pathEffect=CornerPathEffect(dp(10f))
        canvas.drawPath(frame,paint); paint.pathEffect=null; paint.style=Paint.Style.FILL
        val distance=body*.29f; val half=body*.108f
        val positions=listOf(cx to cy,cx-distance to cy,cx to cy-distance,cx+distance to cy,cx to cy+distance)
        positions.forEachIndexed { index,(x,y) ->
            cell.set(x-half,y-half,x+half,y+half)
            paint.color=if (index==selected) Color.rgb(8,127,140) else if (dark) Color.rgb(56,80,89) else Color.rgb(225,240,241)
            canvas.drawRoundRect(cell,dp(5f),dp(5f),paint)
            paint.color=if (index==selected || dark) Color.WHITE else Color.rgb(23,47,53)
            paint.textAlign=Paint.Align.CENTER
            paint.typeface=if (index==selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textSize=min(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,22f,resources.displayMetrics),half*1.55f)
            val metrics=paint.fontMetrics
            canvas.drawText(values[index],x,y-(metrics.ascent+metrics.descent)/2f,paint)
        }
    }
}
