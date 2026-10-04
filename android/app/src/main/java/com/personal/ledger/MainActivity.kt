package com.personal.ledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.ledger.ui.LedgerTheme
import com.personal.ledger.ui.LedgerHost

class MainActivity : ComponentActivity() {
    private val autoRequest=androidx.compose.runtime.mutableLongStateOf(0)
    override fun onResume() {
        super.onResume()
        com.personal.ledger.data.AutoCaptureRecovery.sync(this,"返回应用")
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if(intent.action=="com.personal.ledger.AUTO_ENTRY") autoRequest.longValue=System.nanoTime()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(intent.action=="com.personal.ledger.AUTO_ENTRY") autoRequest.longValue=System.nanoTime()
        setContent { LedgerTheme {
            LedgerHost(autoRequest.longValue)
        } }
    }
}
