package com.personal.ledger.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONObject

@Composable
fun LedgerHost() {
    val backup: BackupViewModel = viewModel()
    val account by backup.account.collectAsStateWithLifecycle()
    val networkBusy by backup.busy.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as Application
    val name = account?.let { backup.databaseName(it) } ?: "ledger.db"
    val owner = account?.getJSONObject("user")?.getString("id") ?: "local-preview"
    key(name) {
        val ledger: LedgerViewModel = viewModel(key = name, factory = object: ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T: ViewModel> create(modelClass: Class<T>): T = LedgerViewModel(app, name, owner) as T
        })
        LedgerApp(ledger, externalBusy = networkBusy, accountContent = { AccountScreen(backup) })
    }
}

@Composable
fun AccountScreen(vm: BackupViewModel) {
    val account by vm.account.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val status by vm.lastBackup.collectAsStateWithLifecycle()
    val savedServer by vm.server.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf(savedServer) }
    var email by rememberSaveable { mutableStateOf("") }
    // Passwords/codes do not enter saved state or persistent preferences.
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("login") }
    var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("账号与电脑备份", style = MaterialTheme.typography.titleLarge)
        Text("电脑开启且服务可连接时，手动上传或恢复。手机日常记账无需电脑在线。", style = MaterialTheme.typography.bodySmall)
        if(account == null) {
            OutlinedTextField(address, { address = it }, label = { Text("电脑服务地址") }, placeholder = { Text("https://你的备份地址") },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("server-address"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("login" to "登录", "register" to "注册", "reset" to "找回密码").forEach { (value, label) ->
                    FilterChip(selected = mode == value, onClick = { mode = value; password = ""; code = "" }, enabled = !busy,
                        label = { Text(label) }, modifier = Modifier.testTag("auth-mode-$value"))
                }
            }
            OutlinedTextField(email, { email = it }, label = { Text("QQ邮箱") }, singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("account-email"))
            OutlinedTextField(password, { password = it }, label = { Text(if(mode == "reset") "新密码（12—128位）" else "密码（12—128位）") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("account-password"))
            if(mode != "login") {
                OutlinedTextField(code, { code = it }, label = { Text("邮箱验证码") }, singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("account-code"))
                OutlinedButton(onClick = { vm.sendCode(address, email, if(mode == "register") "register" else "reset") }, enabled = !busy) { Text("发送验证码") }
            }
            Button(onClick = { vm.signIn(address, email, password, code, mode); password = ""; code = "" }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("account-submit")) { Text(when(mode) { "register" -> "注册并登录"; "reset" -> "重置密码"; else -> "登录账号" }) }
            Text("登录后使用独立账号账本。现有未登录账目仍保留，可登录后手动导入。", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(account!!.getJSONObject("user").getString("email"), modifier = Modifier.testTag("signed-in-email"))
            Text(account!!.getString("server"), style = MaterialTheme.typography.bodySmall)
            Text(status, modifier = Modifier.testTag("backup-status"))
            OutlinedButton(onClick = { vm.inspect() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("查看电脑备份") }
            Button(onClick = { vm.upload() }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("upload-backup")) { Text("备份到电脑") }
            OutlinedButton(onClick = { confirmation = "restore" }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("restore-backup")) { Text("从电脑恢复") }
            OutlinedButton(onClick = { confirmation = "import" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导入原本机账目") }
            TextButton(onClick = { vm.logout() }, enabled = !busy) { Text("退出账号，查看原本机账本") }
        }
        if(busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在处理，请稍候…") }
        if(message.isNotEmpty()) Text(message, modifier = Modifier.testTag("account-message"))
    }
    confirmation?.let { action -> AlertDialog(onDismissRequest = { confirmation = null },
        title = { Text(if(action == "restore") "恢复当前账号账目？" else "导入原本机账目？") },
        text = { Text(if(action == "restore") "电脑最新备份将替换当前账号手机账目。尚未上传的修改不会合并；恢复前会保留一份本机副本。其他账号和原本机账本不变。" else "将原未登录账本复制到当前账号。仅允许当前账号为空且未备份时导入，原账本保留。") },
        confirmButton = { TextButton(onClick = { confirmation = null; if(action == "restore") vm.restore() else vm.importLocal() }, modifier = Modifier.testTag("confirm-backup-action")) { Text("确认") } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } }) }
}
