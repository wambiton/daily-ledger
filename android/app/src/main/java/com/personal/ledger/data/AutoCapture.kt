package com.personal.ledger.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.personal.ledger.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

object AutoCapture {
    fun prefs(context: Context)=context.getSharedPreferences("automatic-entry-settings",Context.MODE_PRIVATE)
    fun validName(name: String)=name.isNotBlank() && File(name).name==name && !name.contains('/') && !name.contains('\\') && name.endsWith(".db")
    fun target(context: Context): Triple<String,String,String>? {
        val p=prefs(context);val db=p.getString("database","").orEmpty();val owner=p.getString("owner","").orEmpty()
        return if(validName(db)&&owner.isNotBlank()) Triple(db,owner,p.getString("label","指定账本").orEmpty()) else null
    }
    fun notify(context: Context,c: PaymentCandidate) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("ledger-auto","自动记账结果",NotificationManager.IMPORTANCE_DEFAULT))
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val intent=Intent(context,MainActivity::class.java).setAction("com.personal.ledger.AUTO_ENTRY").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending=PendingIntent.getActivity(context,8100,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text=if(c.status=="booked") "${c.platform}账目已保存到${c.bookLabel}，点击检查或修改" else "${c.platform}支付信息待确认，尚未计入统计和预算"
        try { manager.notify(c.id,8100,NotificationCompat.Builder(context,"ledger-auto").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if(c.status=="booked") "已自动记账" else "待确认账目").setContentText(text)
            .setContentIntent(pending).setAutoCancel(true).build()) } catch(_: SecurityException) {}
    }
}

class AutoCaptureService: NotificationListenerService() {
    companion object {val connected=MutableStateFlow(false)}
    override fun onListenerConnected() {
        super.onListenerConnected()
        connected.value=true
        AutoCapture.prefs(this).edit().putLong("connected_at",System.currentTimeMillis()).remove("connection_error").apply()
        AutoCaptureRecovery.sync(this,"系统已连接")
    }
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected.value=false
        AutoCapture.prefs(this).edit().putLong("disconnected_at",System.currentTimeMillis()).apply()
        AutoCaptureRecovery.sync(this,"监听断开")
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        AutoCaptureRecovery.sync(this,"移出最近任务")
    }
    override fun onDestroy() {
        connected.value=false
        AutoCapture.prefs(this).edit().putLong("disconnected_at",System.currentTimeMillis()).apply()
        super.onDestroy()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if(sbn.packageName !in PaymentNoticeParser.packages) return
        val p=AutoCapture.prefs(this)
        if(!p.getBoolean("enabled",false) || sbn.postTime < p.getLong("started_at",Long.MAX_VALUE)) return
        if(sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val target=AutoCapture.target(this) ?: return
        val platform=if(sbn.packageName=="com.tencent.mm") "微信" else "支付宝"
        p.edit().putLong("seen_$platform",System.currentTimeMillis()).apply()
        val extras=sbn.notification.extras
        val title=extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text=extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf{it.isNotBlank()}
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val n=PaymentNoticeParser.parse(sbn.packageName,sbn.key,sbn.postTime,title,text) ?: return
        p.edit().putLong("recognized_$platform",System.currentTimeMillis()).apply()
        val input=workDataOf("id" to n.id,"db" to target.first,"owner" to target.second,"label" to target.third,
            "platform" to n.platform,"date" to n.date,"amount" to (n.amountFen ?: -1),"type" to (n.type ?: ""),"merchant" to n.merchant,
            "note" to n.note,"refund" to n.refund,"excluded" to n.excluded,"uncertain" to n.uncertain)
        WorkManager.getInstance(this).enqueueUniqueWork("payment-notice/${n.id}",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutoCaptureWorker>().setInputData(input).build())
    }
}

class AutoCaptureWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        fun text(key: String)=inputData.getString(key).orEmpty()
        val name=text("db");if(!AutoCapture.validName(name)) return Result.failure()
        val n=PaymentNotice(text("id"),text("platform"),text("date"),inputData.getLong("amount",-1).takeIf{it>0},text("type").takeIf{it.isNotEmpty()},text("merchant"),text("note"),inputData.getBoolean("refund",false),inputData.getBoolean("excluded",false),inputData.getBoolean("uncertain",true))
        val c=PaymentCandidate(n.id,name,text("owner"),text("label"),n.platform,n.date,n.amountFen,n.type,n.merchant,n.note,n.refund,"pending","")
        val inbox=AutoEntryDatabase.get(applicationContext)
        if(!applicationContext.getDatabasePath(name).exists()) {
            inbox.auto().insert(c.copy(reason="原指定账本暂不可用，请保留此记录并恢复账本"));return Result.success()
        }
        if(!AutoCapture.prefs(applicationContext).getBoolean("enabled",false)) {
            inbox.auto().insert(c.copy(reason="接收后已关闭自动记账，请手动确认"));return Result.success()
        }
        val ledger=LedgerDatabases.acquire(applicationContext,name)
        return try {
            LedgerRepository(ledger,c.owner).seedDefaults()
            val result=AutoEntryProcessor(inbox).receive(n,c,ledger)
            if(result!=null && result.status!="ignored") AutoCapture.notify(applicationContext,result)
            BudgetNotifications.send(applicationContext,name,BudgetRepository(ledger,c.owner).reconcile())
            Result.success()
        } catch(e: CancellationException) {throw e}
        catch(_: Exception) {
            inbox.auto().insert(c.copy(reason="处理未完成，记录已保留，请确认"))
            AutoCapture.prefs(applicationContext).edit().putString("error","有支付信息处理未完成，请查看待确认列表").apply()
            Result.failure()
        } finally {LedgerDatabases.release(name)}
    }
}
