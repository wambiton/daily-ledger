package com.personal.ledger.data

import android.app.Notification
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.personal.ledger.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object BackgroundBackup {
    val lock = Mutex()
    fun prefs(context: Context, namespace: String) = context.getSharedPreferences("backup-job-$namespace",0)
    fun name(namespace: String) = "ledger-upload-$namespace"
    fun notificationId(namespace: String) = name(namespace).hashCode()
    fun active(context: Context, namespace: String) = prefs(context,namespace).getBoolean("active",false)
    fun notification(context: Context, namespace: String, message: String, ongoing: Boolean): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("ledger-backup","电脑备份",NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getBroadcast(context,notificationId(namespace),Intent(context,CancelBackupReceiver::class.java).putExtra("namespace",namespace),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context,"ledger-backup").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("个人记账 · 电脑备份").setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open).setOngoing(ongoing).setOnlyAlertOnce(true).setAutoCancel(!ongoing)
        if(ongoing) builder.addAction(0,"取消备份",cancel)
        return builder.build()
    }
    fun notify(context: Context, namespace: String, message: String, ongoing: Boolean, deferred: Boolean = false) {
        val id = notificationId(namespace) + if(deferred || !ongoing) 1 else 0
        try { context.getSystemService(NotificationManager::class.java).notify(id,notification(context,namespace,message,ongoing)) }
        catch(_: SecurityException) { /* Status remains visible in the app. */ }
    }
    fun status(context: Context, namespace: String, text: String, active: Boolean, result: String? = null) {
        prefs(context,namespace).edit().putBoolean("active",active).putString("message",text).apply {
            if(result != null) putString("result",result)
        }.apply()
        notify(context,namespace,text,active)
    }
    fun enqueue(context: Context, namespace: String, account: JSONObject) {
        if(active(context,namespace)) return
        status(context,namespace,"正在等待电脑连接，连接后自动备份",true)
        val request = OneTimeWorkRequestBuilder<BackupUploadWorker>()
            .setInputData(workDataOf("namespace" to namespace,"database" to ComputerPair.databaseName(account)))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(namespace),ExistingWorkPolicy.REPLACE,request)
    }
    fun cancel(context: Context, namespace: String) {
        prefs(context,namespace).edit().putBoolean("active",false).putString("message","已取消后台备份，手机账目保留").apply()
        WorkManager.getInstance(context).cancelUniqueWork(name(namespace))
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(namespace))
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(namespace)+1)
    }
}

class CancelBackupReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) { intent.getStringExtra("namespace")?.let { BackgroundBackup.cancel(context,it) } }
}

class BackupUploadWorker(context: Context, parameters: WorkerParameters): CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val namespace = inputData.getString("namespace") ?: return@withContext Result.failure()
        val expected = inputData.getString("database") ?: return@withContext Result.failure()
        if(!BackgroundBackup.active(applicationContext,namespace)) return@withContext Result.success()
        applicationContext.getSystemService(NotificationManager::class.java).cancel(BackgroundBackup.notificationId(namespace)+1)
        try {
            val notification = BackgroundBackup.notification(applicationContext,namespace,"正在备份；电脑未连接时继续等待",true)
            val foreground = if(Build.VERSION.SDK_INT >= 29) ForegroundInfo(BackgroundBackup.notificationId(namespace),notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else ForegroundInfo(BackgroundBackup.notificationId(namespace),notification)
            setForeground(foreground)
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { /* System may deny a later background foreground-service start; normal scheduled work can still proceed. */ }
        // A bounded initial retry window avoids vendor batching immediately after
        // the user leaves the screen. WorkManager persists any longer wait.
        val retryDeadline = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(4)
        for(attempt in 0 until 10) {
            val result = uploadAttempt(namespace,expected)
            if(result != Result.retry()) return@withContext result
            if(!BackgroundBackup.active(applicationContext,namespace) || isStopped) return@withContext Result.success()
            if(SystemClock.elapsedRealtime() >= retryDeadline) break
            if(attempt < 9) delay(30000)
        }
        BackgroundBackup.notify(applicationContext,namespace,"电脑尚未连接，系统将继续安排后台重试",true,deferred=true)
        Result.retry()
    }

    private suspend fun uploadAttempt(namespace: String, expected: String): Result {
        return try {
            BackgroundBackup.lock.withLock {
                val vault = AccountVault(applicationContext,namespace)
                var account = vault.read() ?: throw IllegalArgumentException("请重新登录后备份")
                require(ComputerPair.databaseName(account) == expected) { "已切换账号或电脑，请重新发起备份" }
                val owner = account.getJSONObject("user").getString("id")
                val db = LedgerDatabases.acquire(applicationContext,expected)
                try {
                    val store = BackupStore(db,owner)
                    val file = AtomicFile(File(applicationContext.noBackupFilesDir,"$expected.pending.json"))
                    val pending = if(file.baseFile.exists()) JSONObject(String(file.readFully(),Charsets.UTF_8)) else store.prepare().also {
                        val stream = file.startWrite()
                        try { stream.write(it.toString().toByteArray(Charsets.UTF_8));file.finishWrite(stream) }
                        catch(e: Exception) { file.failWrite(stream);throw e }
                    }
                    if(!BackgroundBackup.active(applicationContext,namespace) || isStopped) return@withLock
                    val client = ComputerPair.client(account)
                    val body = JSONObject(pending.toString()).apply { remove("pending_ids") }
                    val response = try { client.call("backup/",body,account.getString("access_token")) }
                    catch(e: BackupApiException) {
                        if(e.status != 401) throw e
                        val fresh = client.call("auth/refresh/",JSONObject().put("refresh_token",account.getString("refresh_token")))
                        account = JSONObject(account.toString()).put("access_token",fresh.getString("access_token")).put("refresh_token",fresh.getString("refresh_token"))
                        vault.write(account)
                        client.call("backup/",body,account.getString("access_token"))
                    }
                    val ids = pending.getJSONArray("pending_ids")
                    store.acknowledge(response.getLong("revision"),(0 until ids.length()).map { ids.getString(it) })
                    file.delete()
                    BackgroundBackup.status(applicationContext,namespace,"备份成功；之后新增的修改请再次备份",false,response.toString())
                } finally { LedgerDatabases.release(expected) }
            }
            Result.success()
        } catch(e: CancellationException) { throw e }
        catch(e: BackupApiException) {
            if(e.status == 429 || e.status >= 500) {
                if(BackgroundBackup.active(applicationContext,namespace)) BackgroundBackup.status(applicationContext,namespace,"电脑尚未连接，请等候；正在后台重试",true)
                Result.retry()
            } else {
                BackgroundBackup.status(applicationContext,namespace,e.message ?: "备份失败，请重新登录或扫码",false)
                Result.failure()
            }
        } catch(_: java.io.IOException) {
            if(BackgroundBackup.active(applicationContext,namespace)) BackgroundBackup.status(applicationContext,namespace,"电脑尚未连接，请等候；正在后台重试",true)
            Result.retry()
        } catch(e: Exception) {
            BackgroundBackup.status(applicationContext,namespace,if(e is IllegalArgumentException) e.message ?: "请检查账号" else "备份未完成，手机账目保留，请重试",false)
            Result.failure()
        }
    }
}
