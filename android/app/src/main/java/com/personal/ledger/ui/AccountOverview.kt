package com.personal.ledger.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.ledger.R

@Composable
fun AccountOverview(vm: BackupViewModel, onManage: () -> Unit) {
    val account by vm.account.collectAsStateWithLifecycle()
    val selected by vm.selectedBook.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val uploading by vm.uploading.collectAsStateWithLifecycle()
    val waiting by vm.waiting.collectAsStateWithLifecycle()
    val result by vm.uploadResult.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.upload() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionBanner(account?.getJSONObject("user")?.getString("email") ?: "我的本机账本",
            "认真记下生活，安心保存每一笔", "person", R.drawable.winter_profile)
        Text("当前账本：${selected?.optString("label") ?: account?.getJSONObject("user")?.getString("email") ?: "原本机账本"}",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("overview-book"))
        if (account != null) {
            Text(if (waiting) "等待电脑连接" else if (uploading) "备份任务进行中" else result,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("overview-result"))
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.upload()
            }, enabled = !busy && !uploading && vm.viewingAccount(), modifier = Modifier.fillMaxWidth().testTag("overview-upload")) {
                Text(if (uploading) "正在备份…" else "备份到电脑")
            }
            if (!vm.viewingAccount()) Text("当前是其他手机账本，请在账本管理中切回账号账本后备份。", style = MaterialTheme.typography.bodySmall)
            if (uploading) TextButton(onClick = { vm.cancelUpload() }) { Text("取消后台备份") }
        }
        OutlinedButton(onClick = onManage, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("open-account-management")) {
            Text(if (account == null) "登录 / 注册与连接电脑" else "账号、连接与账本管理  ›")
        }
        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
