package io.github.user03715910.dualkey.update

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import io.github.user03715910.dualkey.*
import io.github.user03715910.dualkey.R
import io.github.user03715910.dualkey.settings.MainActivity
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

object UpdateScheduler {
    fun schedule(context: Context, enabled: Boolean) {
        val wm=WorkManager.getInstance(context)
        if (!enabled || BuildConfig.METADATA_URL.isBlank()) { wm.cancelUniqueWork("update-check"); return }
        val request=PeriodicWorkRequestBuilder<UpdateCheckWorker>(12,TimeUnit.HOURS,2,TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        wm.enqueueUniquePeriodicWork("update-check",ExistingPeriodicWorkPolicy.KEEP,request)
    }
    fun download(context: Context, wifiOnly: Boolean) {
        val request=OneTimeWorkRequestBuilder<ApkDownloadWorker>().setConstraints(Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("update-download",ExistingWorkPolicy.KEEP,request)
    }
    fun notify(context: Context, message: String) {
        val app=context.applicationContext as DualKeyApp
        if (!app.settings.state.value.notifications) return
        if (android.os.Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        val nm=context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("updates","アプリ更新",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=PendingIntent.getActivity(context,11,Intent(context,MainActivity::class.java).putExtra("updates",true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n=NotificationCompat.Builder(context,"updates").setSmallIcon(R.drawable.ic_dualkey).setContentTitle("DualKey IMEの更新")
            .setContentText(message).setContentIntent(intent).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(context).notify(10,n) }
    }
    fun isWifi(context: Context): Boolean {
        val cm=context.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
    }
}

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app=applicationContext as DualKeyApp
        // Read DataStore rather than an initial in-memory default in a newly started process.
        app.settings.awaitLoaded()
        if (!app.settings.state.value.autoCheck) return Result.success()
        return try {
            val info=app.updates.check()
            if (info!=null) {
                UpdateScheduler.notify(applicationContext,"${info.versionName} を利用できます")
                if (app.settings.state.value.autoDownload) UpdateScheduler.download(applicationContext,app.settings.state.value.wifiOnly)
            }
            Result.success()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
    }
}
class ApkDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app=applicationContext as DualKeyApp
        app.settings.awaitLoaded()
        val settings=app.settings.state.value
        if (!settings.autoDownload || !settings.autoCheck) return Result.success()
        if (settings.wifiOnly && !UpdateScheduler.isWifi(applicationContext)) return Result.retry()
        val info=app.updates.cachedRelease() ?: return Result.success()
        return try {
            if (app.updates.download(info)!=null) { UpdateScheduler.notify(applicationContext,"検証済みAPKの準備ができました。タップして更新できます"); Result.success() }
            else if (runAttemptCount<2) Result.retry() else Result.failure()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { Result.failure() }
    }
}
