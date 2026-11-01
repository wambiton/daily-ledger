package com.personal.ledger.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.work.*
import kotlinx.coroutines.CancellationException

object AutoEntryActions {
    const val NAME="entry_name"
    fun button(context: Context,id: String,action: String,label: String): NotificationCompat.Action {
        val intent=Intent(context,AutoEntryActionReceiver::class.java)
            .setAction(action).setData(Uri.Builder().scheme("ledger-action").authority(action).appendPath(id).build())
            .putExtra("candidate_id",id)
        val mutable=action=="rename"
        val flags=PendingIntent.FLAG_UPDATE_CURRENT or
            (if(mutable && Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else if(mutable) 0 else PendingIntent.FLAG_IMMUTABLE)
        val pending=PendingIntent.getBroadcast(context,0,intent,flags)
        return NotificationCompat.Action.Builder(0,label,pending).apply {
            setAuthenticationRequired(true)
            if(mutable) addRemoteInput(RemoteInput.Builder(NAME).setLabel("账目名称（最多40字）").build())
        }.build()
    }
}

class AutoEntryActionReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val action=intent.action ?: return
        if(action !in setOf("confirm","rename","ignore")) return
        val id=intent.getStringExtra("candidate_id")?.takeIf {it.isNotBlank()} ?: return
        val title=if(action=="rename") RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AutoEntryActions.NAME)?.toString() ?: return else ""
        // Bound input before WorkManager serialization; the processor validates the 40-character limit.
        if(title.length>160) return
        val request=OneTimeWorkRequestBuilder<AutoEntryActionWorker>()
            .setInputData(workDataOf("id" to id,"action" to action,"title" to title)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("payment-action/$id",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
    }
}

class AutoEntryActionWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val inbox=AutoEntryDatabase.get(applicationContext)
        val id=inputData.getString("id") ?: return Result.failure()
        val c=inbox.auto().candidate(id) ?: return Result.success()
        if(!AutoCapture.validName(c.databaseName) || !applicationContext.getDatabasePath(c.databaseName).exists()) return Result.failure()
        val ledger=LedgerDatabases.acquire(applicationContext,c.databaseName)
        return try {
            val result=AutoEntryProcessor(inbox).notificationAction(id,inputData.getString("action").orEmpty(),ledger,c.databaseName,inputData.getString("title"))
            val manager=applicationContext.getSystemService(android.app.NotificationManager::class.java)
            manager.cancel(id,8100)
            if(result!=null) {
                inbox.auto().children(result.id).forEach {manager.cancel(it.id,8100)}
                if(result.status=="ignored") manager.cancel(result.id,8100) else AutoCapture.notifyFromLedger(applicationContext,result,ledger)
                if(result.status=="booked") BudgetNotifications.send(applicationContext,c.databaseName,BudgetRepository(ledger,c.owner).reconcile())
            }
            Result.success()
        } catch(e: CancellationException) {throw e}
        catch(_: Exception) {
            val fresh=inbox.auto().candidate(id)
            if(fresh!=null && fresh.status=="pending") AutoCapture.notify(applicationContext,fresh.copy(reason="操作未完成，请打开应用核对；名称最多40字"))
            Result.failure()
        } finally {LedgerDatabases.release(c.databaseName)}
    }
}
