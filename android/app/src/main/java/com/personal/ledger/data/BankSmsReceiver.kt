package com.personal.ledger.data

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Receives new SMS only. No READ_SMS permission or historical provider query. */
class BankSmsReceiver: BroadcastReceiver() {
    companion object {
        fun granted(context: Context) = ContextCompat.checkSelfPermission(context,Manifest.permission.RECEIVE_SMS)==PackageManager.PERMISSION_GRANTED
        fun enabled(context: Context) = granted(context) && AutoCapture.prefs(context).getBoolean("sms_enabled",false)
    }
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action!=Telephony.Sms.Intents.SMS_RECEIVED_ACTION || !enabled(context)) return
        val prefs=AutoCapture.prefs(context)
        if(!prefs.getBoolean("enabled",false)) return
        val target=AutoCapture.target(context) ?: return
        val received=System.currentTimeMillis()
        if(received < prefs.getLong("sms_started_at",Long.MAX_VALUE)) return
        val messages=runCatching {Telephony.Sms.Intents.getMessagesFromIntent(intent)}.getOrNull() ?: return
        if(messages.isEmpty() || messages.size>20) return
        val sender=messages.first().originatingAddress ?: return
        if(messages.any {it.originatingAddress!=sender}) return
        // Concatenate multipart segments before parsing; never retain unrelated or OTP text.
        val body=messages.joinToString("") {it.messageBody.orEmpty()}.take(2001)
        val parsed=BankSmsParser.parse(sender,body,messages.first().timestampMillis,received) ?: return
        prefs.edit().putLong("recognized_sms",received).apply()
        val pending=goAsync()
        thread(name="bank-sms-enqueue") {
            try { AutoCapture.enqueue(context.applicationContext,parsed.notice,target).result.get(8,TimeUnit.SECONDS) }
            catch(_: Exception) {prefs.edit().putString("error","银行短信未能加入处理队列，请核对本次消费是否已记账").apply()}
            finally {pending.finish()}
        }
    }
}
