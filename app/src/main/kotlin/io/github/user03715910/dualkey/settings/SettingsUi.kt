package io.github.user03715910.dualkey.settings

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.widget.AppCompatButton

/** Native view styling shared by the home, settings and update screens. */
class SettingsUi(private val context: Context) {
    private val dark=context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
    val paper=Color.parseColor(if (dark) "#14252A" else "#F3F7F6")
    val surface=Color.parseColor(if (dark) "#20383E" else "#FFFFFF")
    val ink=Color.parseColor(if (dark) "#E4F2F0" else "#173B40")
    val muted=Color.parseColor(if (dark) "#B5CDCD" else "#53696C")
    val accent=Color.parseColor(if (dark) "#79DCD3" else "#087F8C")
    val onAccent=Color.parseColor(if (dark) "#103439" else "#FFFFFF")
    val soft=Color.parseColor(if (dark) "#2A4B50" else "#E6F3F1")
    val line=Color.parseColor(if (dark) "#456168" else "#D6E3E0")
    fun dp(value: Int)=(value*context.resources.displayMetrics.density).toInt()
    fun shape(color: Int, radius: Int=16, stroke: Boolean=false)=GradientDrawable().apply {
        setColor(color); cornerRadius=dp(radius).toFloat(); if (stroke) setStroke(dp(1),line)
    }
    fun column()=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    fun label(value: String,size: Float=16f,secondary: Boolean=false,bold: Boolean=false)=TextView(context).apply {
        text=value; textSize=size; setTextColor(if (secondary) muted else ink)
        if (bold) setTypeface(typeface,Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(),1f)
        setPadding(0,dp(4),0,dp(4))
    }
    fun card(parent: LinearLayout,title: String,subtitle: String?=null): LinearLayout {
        val box=column().apply {
            background=shape(surface); setPadding(dp(16),dp(14),dp(16),dp(14))
            layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(14) }
        }
        parent.addView(box)
        if (title.isNotBlank()) box.addView(label(title,18f,bold=true))
        if (!subtitle.isNullOrBlank()) box.addView(label(subtitle,14f,secondary=true))
        return box
    }
    fun button(parent: LinearLayout,label: String,primary: Boolean=false,action: ()->Unit)=AppCompatButton(context).apply {
        text=label; isAllCaps=false; textSize=15f; minHeight=dp(50); minimumWidth=0
        backgroundTintList=null
        background=RippleDrawable(ColorStateList.valueOf(0x22087F8C),shape(if (primary) accent else soft,12),null)
        setTextColor(if (primary) onAccent else ink); setPadding(dp(12),dp(10),dp(12),dp(10))
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) }
        setOnClickListener { action() }
    }.also(parent::addView)
    fun row(parent: LinearLayout)=LinearLayout(context).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
    }.also { parent.addView(it,LinearLayout.LayoutParams(-1,-2)) }
    fun divider(parent: LinearLayout) { parent.addView(View(context).apply { setBackgroundColor(line) },LinearLayout.LayoutParams(-1,dp(1)).apply { topMargin=dp(10); bottomMargin=dp(6) }) }
}
