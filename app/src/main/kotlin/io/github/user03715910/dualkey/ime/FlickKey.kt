package io.github.user03715910.dualkey.ime

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.abs

class FlickKey(context: Context, private val values: List<String>, private val threshold: Float,
    private val feedback: (View) -> Unit, private val input: (String) -> Unit, private val previewEnabled: Boolean = true) : AppCompatTextView(context) {
    private var downX = 0f
    private var downY = 0f
    private var direction = 0
    private var activePointer = -1
    private var popup: PopupWindow? = null
    private var preview: TextView? = null
    init {
        // Keep the resting key legible and compact; directions appear while touching.
        text = values[0]
        textSize = 22f
        includeFontPadding = false
        setSingleLine(true)
        gravity = Gravity.CENTER
        isClickable = true
        contentDescription = "${values[0]}行。左${values[1]}、上${values[2]}、右${values[3]}、下${values[4]}"
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointer = event.getPointerId(0)
                downX = event.x; downY = event.y; direction = 0
                isPressed = true; feedback(this)
                parent.requestDisallowInterceptTouchEvent(true)
                val label = TextView(context).apply {
                    textSize = 25f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.rgb(8,127,140)); cornerRadius = 16f }
                }
                preview = label
                val size = (96 * resources.displayMetrics.density).toInt()
                if (previewEnabled) popup = PopupWindow(label, size, size, false).apply {
                    isTouchable = false
                    isClippingEnabled = true
                    val p = IntArray(2); getLocationOnScreen(p)
                    showAtLocation(this@FlickKey, Gravity.NO_GRAVITY, p[0] + this@FlickKey.width/2 - size/2, (p[1]-size).coerceAtLeast(0))
                }
                updatePreview()
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointer)
                if (index < 0) return true
                val dx = event.getX(index)-downX; val dy = event.getY(index)-downY
                val next = if (maxOf(abs(dx), abs(dy)) < threshold) 0 else if (abs(dx)>abs(dy)) { if (dx<0) 1 else 3 } else { if (dy<0) 2 else 4 }
                if (next != direction) { direction = next; updatePreview() }
            }
            MotionEvent.ACTION_UP -> { popup?.dismiss(); popup = null; isPressed = false; performClick(); direction = 0; activePointer = -1 }
            MotionEvent.ACTION_CANCEL -> { popup?.dismiss(); popup = null; isPressed = false; direction = 0; activePointer = -1 }
        }
        return true
    }
    private fun updatePreview() { preview?.text = "${values[2]}\n${values[1]}  ${values[direction]}  ${values[3]}\n${values[4]}" }
    override fun performClick(): Boolean { super.performClick(); values[direction].takeIf { it.isNotEmpty() }?.let(input); return true }
    override fun onDetachedFromWindow() { popup?.dismiss(); popup = null; super.onDetachedFromWindow() }
}
