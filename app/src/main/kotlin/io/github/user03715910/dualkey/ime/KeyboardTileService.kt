package io.github.user03715910.dualkey.ime

import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.user03715910.dualkey.settings.MainActivity

class KeyboardTileService : TileService() {
    override fun onStartListening() { super.onStartListening(); qsTile?.apply { state=Tile.STATE_ACTIVE; label="DualKeyを表示"; updateTile() } }
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated") // The PendingIntent overload only exists on API 34+.
    override fun onClick() {
        super.onClick()
        val intent=Intent(this,MainActivity::class.java).putExtra("restore_keyboard",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT>=34) startActivityAndCollapse(PendingIntent.getActivity(this,21,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        else startActivityAndCollapse(intent)
    }
}
