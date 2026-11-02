package io.github.user03715910.dualkey.settings

import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.dualkey.core.ToggleShortcut
import io.github.user03715910.dualkey.ime.HardwareShortcuts

/** Manual controls also allow configuration before a physical keyboard is connected. */
object ShortcutDialog {
    fun show(activity: AppCompatActivity,initial: ToggleShortcut,save: (ToggleShortcut)->Unit): AlertDialog {
        val ui=SettingsUi(activity)
        val body=ui.column().apply { setPadding(ui.dp(20),ui.dp(8),ui.dp(20),ui.dp(8)) }
        body.addView(ui.label("同じキーで通常表示と完全非表示を交互に切り替えます。",15f))
        val modifiers=listOf("Ctrl" to ToggleShortcut.CTRL,"Alt" to ToggleShortcut.ALT,"Shift" to ToggleShortcut.SHIFT,"Meta" to ToggleShortcut.META)
        val checks=modifiers.map { (name,flag) -> CheckBox(activity).apply {
            text=name; setTextColor(ui.ink); isChecked=initial.modifiers and flag!=0
            tag="shortcut_modifier_${name.lowercase()}"
            body.addView(this,LinearLayout.LayoutParams(-1,ui.dp(44)))
        } }
        body.addView(ui.label("文字・数字・Fキー",14f))
        val key=Spinner(activity).apply {
            tag="shortcut_key_spinner"; contentDescription="ショートカットの主キー"
            adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,ToggleShortcut.keys)
            setSelection(ToggleShortcut.keys.indexOf(initial.key))
            body.addView(this,LinearLayout.LayoutParams(-1,ui.dp(48)))
        }
        val preview=ui.label("",17f,bold=true).apply { tag="shortcut_preview" }.also(body::addView)
        var armed=false
        val capture=ui.button(body,"キーボードから登録") { armed=true; preview.text="使いたいキーの組合せを押してください" }.apply { tag="shortcut_capture" }
        body.addView(ui.label("文字・数字にはCtrl／Alt／Metaのいずれかを組み合わせます。Androidや他のアプリが先に使う組合せは届かないことがあります。",13f,secondary=true))
        val scroll=ScrollView(activity).apply { addView(body) }
        val dialog=AlertDialog.Builder(activity).setTitle("表示／非表示のショートカット")
            .setView(scroll).setPositiveButton("保存",null).setNegativeButton("キャンセル",null)
            .setNeutralButton("初期値に戻す",null).create()
        fun current()=ToggleShortcut(ToggleShortcut.keys[key.selectedItemPosition],checks.indices.fold(0) { value,i ->
            value or if (checks[i].isChecked) modifiers[i].second else 0
        })
        fun render() {
            val value=current()
            preview.text=if (value.valid) value.label else "文字・数字にはCtrl／Alt／Metaが必要です"
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled=value.valid
        }
        fun fill(value: ToggleShortcut) {
            checks.indices.forEach { checks[it].isChecked=value.modifiers and modifiers[it].second!=0 }
            key.setSelection(ToggleShortcut.keys.indexOf(value.key)); armed=false; render()
        }
        checks.forEach { it.setOnCheckedChangeListener { _,_ -> armed=false; render() } }
        key.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) { armed=false; render() }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value=current(); if (value.valid) { save(value); dialog.dismiss() }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { fill(ToggleShortcut()) }
            render()
        }
        dialog.setOnKeyListener { _,code,event ->
            if (!armed || code==KeyEvent.KEYCODE_BACK || code==KeyEvent.KEYCODE_ESCAPE) return@setOnKeyListener false
            if (event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0 && !KeyEvent.isModifierKey(code)) {
                val value=ToggleShortcut(HardwareShortcuts.key(code),HardwareShortcuts.modifiers(event))
                if (value.key in ToggleShortcut.keys) fill(value)
                else preview.text="文字・数字・F1〜F12のいずれかを押してください"
            }
            true
        }
        dialog.show()
        capture.contentDescription="ショートカットを物理キーボードから登録"
        return dialog
    }
}
