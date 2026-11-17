package io.github.user03715910.dualkey.ime

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.*
import android.widget.PopupWindow
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.abs

class FlickKey(context: Context, private val values: List<String>, private val threshold: Float,
    private val feedback: (View) -> Unit, private val input: (String) -> Unit, private val previewEnabled: Boolean = true,
    label: String = values[0], description: String = "${values[0]}行") : AppCompatTextView(context) {
    private var downX = 0f
    private var downY = 0f
    private var direction = 0
    private var activePointer = -1
    private var popup: PopupWindow? = null
    private var preview: FlickPreviewBubble? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable {
        if (activePointer>=0 && direction==0 && performLongClick()) finishGesture()
    }
    init {
        // Keep the resting key legible and compact; directions appear while touching.
        text = label
        textSize = 22f
        includeFontPadding = false
        setSingleLine(true)
        gravity = Gravity.CENTER
        isClickable = true
        contentDescription = (listOf(description) + listOf("左","上","右","下").zip(values.drop(1))
            .filter { it.second.isNotEmpty() }.map { (name,value) -> "$name$value" }).joinToString("。")
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                popup?.dismiss(); popup=null; preview=null
                activePointer = event.getPointerId(0)
                downX = event.x; downY = event.y; direction = 0
                isPressed = true; feedback(this)
                parent.requestDisallowInterceptTouchEvent(true)
                if (isLongClickable) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                if (previewEnabled) {
                    val bubble=FlickPreviewBubble(context,values)
                    preview=bubble
                    val density=resources.displayMetrics.density
                    val bubbleWidth=(148*density).toInt(); val bubbleHeight=(160*density).toInt()
                    popup = PopupWindow(bubble,bubbleWidth,bubbleHeight,false).apply {
                    isTouchable = false
                    isClippingEnabled = false
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    val p = IntArray(2); getLocationOnScreen(p)
                    val inWindow=IntArray(2); getLocationInWindow(inWindow)
                    val margin=(4*density).toInt()
                    val x=(p[0]+this@FlickKey.width/2-bubbleWidth/2).coerceIn(margin,(resources.displayMetrics.widthPixels-bubbleWidth-margin).coerceAtLeast(margin))
                    val y=(p[1]-bubbleHeight+(6*density).toInt()).coerceAtLeast(margin)
                    if (android.os.Build.VERSION.SDK_INT>=29) {
                        setIsLaidOutInScreen(true)
                        setIsClippedToScreen(true)
                        showAtLocation(this@FlickKey,Gravity.NO_GRAVITY,x,y)
                    } else {
                        // Older application-panel popups use the IME window's origin.
                        showAtLocation(this@FlickKey,Gravity.NO_GRAVITY,x-(p[0]-inWindow[0]),y-(p[1]-inWindow[1]))
                    }
                    }
                }
                updatePreview()
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointer)
                if (index < 0) return true
                val x=event.getX(index); val y=event.getY(index)
                if (isLongClickable && maxOf(abs(x-downX),abs(y-downY))>touchSlop) removeCallbacks(longPress)
                val next = directionAt(x,y)
                if (next != direction) { direction = next; updatePreview() }
            }
            MotionEvent.ACTION_UP -> {
                if (activePointer<0) return true
                val releasedDirection=directionAt(event.x,event.y)
                finishGesture()
                direction=releasedDirection
                performClick()
                direction=0
            }
            MotionEvent.ACTION_CANCEL -> finishGesture()
            MotionEvent.ACTION_POINTER_DOWN -> if (isLongClickable) finishGesture()
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex)==activePointer) finishGesture()
        }
        return true
    }
    private fun finishGesture() {
        removeCallbacks(longPress)
        popup?.dismiss(); popup=null; preview=null
        isPressed=false; direction=0; activePointer=-1
    }
    private fun updatePreview() { preview?.select(direction) }
    private fun directionAt(x: Float,y: Float): Int {
        val dx=x-downX; val dy=y-downY
        return if (maxOf(abs(dx),abs(dy))<threshold) 0
            else if (abs(dx)>abs(dy)) { if (dx<0) 1 else 3 } else { if (dy<0) 2 else 4 }
    }
    override fun performClick(): Boolean { super.performClick(); values[direction].takeIf { it.isNotEmpty() }?.let(input); return true }
    override fun onDetachedFromWindow() { finishGesture(); super.onDetachedFromWindow() }
}
