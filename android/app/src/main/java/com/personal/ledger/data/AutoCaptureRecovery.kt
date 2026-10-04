package com.personal.ledger.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.work.*
import java.util.concurrent.TimeUnit

/** Repairs an already-authorized listener; never grants access or enables capture. */
object AutoCaptureRecovery {
    const val WORK = "automatic-entry-listener-check"
    private var lastAttempt = -1L

    fun granted(context: Context): Boolean {
        val component = ComponentName(context, AutoCaptureService::class.java)
        return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    fun wanted(context: Context) = AutoCapture.prefs(context).getBoolean("enabled", false) &&
        AutoCapture.target(context) != null && granted(context)

    @Synchronized
    internal fun request(context: Context, reason: String, now: Long = SystemClock.elapsedRealtime(),
                         rebind: () -> Unit = { NotificationListenerService.requestRebind(ComponentName(context, AutoCaptureService::class.java)) }): Boolean {
        if (!wanted(context) || AutoCaptureService.connected.value) return false
        if (lastAttempt >= 0 && now >= lastAttempt && now - lastAttempt < 10000) return false
        lastAttempt = now
        val prefs = AutoCapture.prefs(context)
        return try {
            rebind()
            prefs.edit().putLong("reconnect_at", System.currentTimeMillis())
                .putString("reconnect_reason", reason).remove("connection_error").apply()
            true
        } catch (_: RuntimeException) {
            prefs.edit().putString("connection_error", "系统未接受重连请求，请检查通知使用权和后台运行设置").apply()
            false
        }
    }

    fun sync(context: Context, reason: String) {
        val manager = WorkManager.getInstance(context)
        if (!wanted(context)) {
            manager.cancelUniqueWork(WORK)
            return
        }
        // A single persistent fallback, not a per-ledger job or a polling service.
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoCaptureRecoveryWorker>(15, TimeUnit.MINUTES).build())
        request(context, reason)
    }
}

class AutoCaptureRecoveryWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!AutoCaptureRecovery.wanted(applicationContext)) {
            // Revoked permission or a disabled switch must not trigger another binding.
            WorkManager.getInstance(applicationContext).cancelUniqueWork(AutoCaptureRecovery.WORK)
            return Result.success()
        }
        AutoCaptureRecovery.request(applicationContext, "后台检查")
        return Result.success()
    }
}

class AutoCaptureRecoveryReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AutoCaptureRecovery.sync(context, if (intent.action == Intent.ACTION_BOOT_COMPLETED) "手机启动" else "软件更新")
        }
    }
}
