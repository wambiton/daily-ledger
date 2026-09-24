package com.personal.ledger.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.personal.ledger.MainActivity
import com.personal.ledger.domain.Rules
import java.time.YearMonth
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

object BudgetNotifications {
    fun allowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel("ledger-budget")?.importance != NotificationManager.IMPORTANCE_NONE

    fun send(context: Context, database: String, notices: List<BudgetNotice>) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("ledger-budget","预算提醒",NotificationManager.IMPORTANCE_DEFAULT))
        if(!allowed(context)) return
        notices.forEach { notice ->
            val intent=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification=NotificationCompat.Builder(context,"ledger-budget").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("个人记账 · 预算提醒").setContentText(notice.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notice.message)).setContentIntent(intent).setAutoCancel(true).build()
            try { manager.notify("$database/${notice.owner}/${notice.key}",7200,notification) } catch(_: SecurityException) { /* In-app notice remains available. */ }
        }
    }
    fun schedule(context: Context, database: String, owner: String) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("budget/$database/$owner",ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BudgetWorker>(12,TimeUnit.HOURS).setInputData(workDataOf("database" to database,"owner" to owner)).build())
    }
}

class BudgetWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val name=inputData.getString("database") ?: return Result.failure()
        val owner=inputData.getString("owner") ?: return Result.failure()
        if(!applicationContext.getDatabasePath(name).exists()) return Result.success()
        val db=LedgerDatabases.acquire(applicationContext,name)
        return try {
            val repo=BudgetRepository(db,owner)
            repo.inheritDue(YearMonth.from(Rules.today()))
            BudgetNotifications.send(applicationContext,name,repo.reconcile())
            Result.success()
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { Result.retry() }
        finally { LedgerDatabases.release(name) }
    }
}
