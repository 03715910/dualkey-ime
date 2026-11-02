package io.github.user03715910.dualkey.ime

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.dualkey.core.DisplayMode
import io.github.user03715910.dualkey.*
import kotlinx.coroutines.*

/** Local control notification. Contains no editor names, entered text, or network operations. */
object KeyboardVisibilityNotification {
    const val CHANNEL="keyboard_visibility"
    const val ID=30
    const val TOGGLE="io.github.user03715910.dualkey.TOGGLE_VISIBILITY"
    fun selected(context: Context)=Settings.Secure.getString(context.contentResolver,Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.substringBefore('/')==context.packageName
    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"キーボードの表示切り替え",NotificationManager.IMPORTANCE_LOW).apply {
                description="通知から画面キーボードを表示・非表示にします"; setShowBadge(false)
            })
    }
    fun permitted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return false
        val manager=context.getSystemService(NotificationManager::class.java)
        return NotificationManagerCompat.from(context).areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance!=NotificationManager.IMPORTANCE_NONE
    }
    fun refresh(context: Context) {
        val app=context.applicationContext as DualKeyApp
        val manager=NotificationManagerCompat.from(context)
        if (!app.settings.isLoaded || !app.settings.state.value.visibilityNotification || !selected(context) || !permitted(context)) {
            manager.cancel(ID); return
        }
        // Keep the runtime permission check at the posting site as well as in the UI helper.
        if (Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        ensureChannel(context)
        val state=ImeControl.state.value
        val pending=app.settings.state.value.pendingNotificationDisplay
        val message=when {
            pending!=null && !state.editorActive -> "次の入力欄で${if (pending==DisplayMode.HIDDEN) "非表示" else "表示"}にします"
            !state.editorActive -> "入力欄がないときは、次の入力欄で反映します"
            state.keyboardVisible -> "画面キーボードを表示中・タップで隠す"
            else -> "画面キーボードを非表示中・タップで表示"
        }
        val action=PendingIntent.getBroadcast(context,30,Intent(context,KeyboardVisibilityReceiver::class.java).setAction(TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_dualkey)
            .setContentTitle("DualKey：キーボード表示切替").setContentText(message)
            .setContentIntent(action).setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(NotificationCompat.Action.Builder(R.drawable.ic_dualkey,"表示／非表示を切替",action)
                .setShowsUserInterface(false).setAuthenticationRequired(true).build()).build()
        runCatching { manager.notify(ID,notification) }
    }
}

class KeyboardVisibilityReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if (intent.action!=KeyboardVisibilityNotification.TOGGLE || context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val result=goAsync()
        val app=context.applicationContext as DualKeyApp
        app.scope.launch {
            try {
                withTimeout(5000) {
                    app.settings.awaitLoaded()
                    if (!KeyboardVisibilityNotification.selected(context)) return@withTimeout
                    val target=app.settings.toggleNotificationDisplay(ImeControl.state.value.keyboardVisible) ?: return@withTimeout
                    ImeControl.request(target,notification=true)
                    KeyboardVisibilityNotification.refresh(context)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* A control notification must never stop ordinary input. */ }
            finally { result.finish() }
        }
    }
}
