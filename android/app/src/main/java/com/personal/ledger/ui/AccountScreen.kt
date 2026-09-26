package com.personal.ledger.ui

import android.app.Application
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONObject

@Composable
fun LedgerHost(autoOpenRequest: Long = 0) {
    val backup: BackupViewModel = viewModel()
    val account by backup.account.collectAsStateWithLifecycle()
    val selectedBook by backup.selectedBook.collectAsStateWithLifecycle()
    val networkBusy by backup.busy.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as Application
    val name = selectedBook?.getString("database") ?: account?.let { backup.databaseName(it) } ?: "ledger.db"
    val owner = selectedBook?.getString("owner") ?: account?.getJSONObject("user")?.getString("id") ?: "local-preview"
    key(name) {
        val ledger: LedgerViewModel = viewModel(key = name, factory = object: ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T: ViewModel> create(modelClass: Class<T>): T = LedgerViewModel(app, name, owner) as T
        })
        LedgerApp(ledger, autoOpenRequest=autoOpenRequest, externalBusy = networkBusy, accountContent = { AccountScreen(backup) })
    }
}

@Composable
fun AccountScreen(vm: BackupViewModel) {
    val account by vm.account.collectAsStateWithLifecycle()
    val books by vm.savedBooks.collectAsStateWithLifecycle()
    val selected by vm.selectedBook.collectAsStateWithLifecycle()
    val pendingPair by vm.pendingPair.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val uploading by vm.uploading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.upload() }
    val message by vm.message.collectAsStateWithLifecycle()
    val waiting by vm.waiting.collectAsStateWithLifecycle()
    val status by vm.lastBackup.collectAsStateWithLifecycle()
    val uploadResult by vm.uploadResult.collectAsStateWithLifecycle()
    val savedServer by vm.server.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf(savedServer) }
    LaunchedEffect(savedServer) { address = savedServer }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { vm.pairComputer(it) }
    }
    var email by rememberSaveable { mutableStateOf("") }
    // Passwords/codes do not enter saved state or persistent preferences.
    var password by remember { mutableStateOf("") }
    var passwordAgain by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showPasswordAgain by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("login") }
    var chooseImport by remember { mutableStateOf(false) }
    var showBooks by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionBanner(account?.getJSONObject("user")?.getString("email") ?: "我的本机账本", "认真记下生活，安心保存每一笔", "person")
        Text("账号与电脑备份", style = MaterialTheme.typography.titleMedium)
        if(uploading) Text("备份任务进行中：电脑未连接时继续在后台等候。以成功提示为准。")
        if(waiting) Text("等待电脑连接，手机账目仍然保留。")
        Text("当前账本：${selected?.optString("label") ?: account?.getJSONObject("user")?.getString("email") ?: "原本机账本"}",modifier=Modifier.testTag("current-phone-book"))
        OutlinedButton(onClick={ showBooks=true },enabled=!busy && !uploading,modifier=Modifier.fillMaxWidth().testTag("choose-phone-book")) { Text("查看 / 切换手机账本") }
        Text("手动备份到电脑；换机登录自动加载已备份账目。日常记账无需电脑在线。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描电脑备份程序的二维码").setBeepEnabled(false)) },
            enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("pair-computer")) { Text("扫码连接电脑") }
        if(account == null) {
            OutlinedTextField(address, { address = it }, label = { Text("电脑服务地址") }, placeholder = { Text("https://你的备份地址") },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("server-address"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("login" to "登录", "register" to "注册", "reset" to "找回密码").forEach { (value, label) ->
                    FilterChip(selected = mode == value, onClick = { mode = value; password = ""; passwordAgain = ""; showPassword = false; showPasswordAgain = false; code = "" }, enabled = !busy,
                        label = { Text(label) }, modifier = Modifier.testTag("auth-mode-$value"))
                }
            }
            OutlinedTextField(email, { email = it }, label = { Text("QQ邮箱") }, singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("account-email"))
            OutlinedTextField(password, { password = it }, label = { Text(if(mode == "reset") "新密码（至少8位）" else "密码（至少8位）") },
                visualTransformation = if(showPassword) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true, enabled = !busy,
                trailingIcon = { TextButton(onClick = { showPassword = !showPassword },modifier = Modifier.testTag("toggle-password")) { Text(if(showPassword) "隐藏" else "显示") } },
                modifier = Modifier.fillMaxWidth().testTag("account-password"))
            if(mode == "reset") OutlinedTextField(passwordAgain,{ passwordAgain = it },label = { Text("再次输入新密码") },
                visualTransformation = if(showPasswordAgain) VisualTransformation.None else PasswordVisualTransformation(),singleLine = true,enabled = !busy,
                trailingIcon = { TextButton(onClick = { showPasswordAgain = !showPasswordAgain },modifier = Modifier.testTag("toggle-password-confirm")) { Text(if(showPasswordAgain) "隐藏" else "显示") } },
                modifier = Modifier.fillMaxWidth().testTag("account-password-confirm"))
            if(mode != "login") {
                OutlinedTextField(code, { code = it }, label = { Text("邮箱验证码") }, singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("account-code"))
                OutlinedButton(onClick = { vm.sendCode(address, email, if(mode == "register") "register" else "reset") }, enabled = !busy) { Text("发送验证码") }
            }
            Button(onClick = { vm.signIn(address, email, password, code, mode, passwordAgain) { password = ""; passwordAgain = ""; showPassword = false; showPasswordAgain = false; code = "" } }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("account-submit")) { Text(when(mode) { "register" -> "注册并登录"; "reset" -> "重置密码"; else -> "登录账号" }) }
            Text("新注册账号为空账本。登录后可从手机账本预览、勾选并追加导入，源账本始终保留。", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(account!!.getJSONObject("user").getString("email"), modifier = Modifier.testTag("signed-in-email"))
            Text(account!!.getString("server"), style = MaterialTheme.typography.bodySmall)
            Text("电脑最近成功备份（历史记录，不代表本次上传）",style=MaterialTheme.typography.labelLarge)
            Text(status, modifier = Modifier.testTag("backup-status"))
            Text("本次备份状态",style=MaterialTheme.typography.labelLarge)
            Text(uploadResult,modifier=Modifier.testTag("upload-result"))
            Button(onClick = {
                if(Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.upload()
            }, enabled = !busy && !uploading && vm.viewingAccount(), modifier = Modifier.fillMaxWidth().testTag("upload-backup")) { Text("备份到电脑") }
            if(!vm.viewingAccount()) {
                Text("当前查看的是另一份手机账本。追加导入当前账号后，再切回账号账本备份。")
                OutlinedButton(onClick={ vm.selectedBook.value?.let { vm.previewBookImport(it) } },enabled=!busy && !uploading,modifier=Modifier.fillMaxWidth().testTag("import-selected-book")) { Text("将这份手机账本追加导入当前账号") }
                TextButton(onClick={ vm.selectAccountBook() },modifier=Modifier.testTag("return-account-book")) { Text("返回当前账号账本") }
            }
            OutlinedButton(onClick = { chooseImport = true }, enabled = !busy && !uploading, modifier = Modifier.fillMaxWidth().testTag("import-local")) { Text("从手机账本导入") }
            if(uploading) OutlinedButton(onClick = { vm.cancelUpload() },modifier = Modifier.testTag("cancel-upload")) { Text("取消后台备份") }
            TextButton(onClick = { vm.logout() }, enabled = !busy) { Text("退出账号，查看原本机账本") }
        }
        if(busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在处理，请稍候…") }
        if(waiting) TextButton(onClick = { vm.cancelWaiting() }, modifier = Modifier.testTag("cancel-waiting")) { Text("取消等候") }
        if(message.isNotEmpty()) Text(message, modifier = Modifier.testTag("account-message"))
    }
    if(showBooks) AlertDialog(onDismissRequest={ showBooks=false },title={ Text("手机保存的账本") },
        text={ Column {
            Text("切换只改变当前查看的账本，不删除账目，不会自动上传。")
            TextButton(onClick={ vm.selectOriginalBook();showBooks=false },modifier=Modifier.testTag("choose-original-book")) { Text("原本机账本") }
            if(account!=null) TextButton(onClick={ vm.selectAccountBook();showBooks=false }) { Text("当前登录账号账本") }
            books.filter { it.optString("owner")!="local-preview" }.forEachIndexed { index,book ->
                TextButton(onClick={ vm.selectBook(book);showBooks=false },modifier=Modifier.testTag("choose-saved-book-$index")) { Text(book.optString("label","手机账号账本")) }
            }
        } },confirmButton={ TextButton(onClick={ showBooks=false }) { Text("关闭") } })
    if(pendingPair!=null) AlertDialog(onDismissRequest={ vm.dismissPair() },title={ Text("连接另一台电脑？") },
        text={ Text("手机当前账目会保留，并继续显示在原账本中。连接后需要登录该电脑的账号；扫码本身不会备份。可随时从“查看 / 切换手机账本”找回原账本。") },
        confirmButton={ TextButton(onClick={ vm.confirmPair() },enabled=!busy,modifier=Modifier.testTag("confirm-pair")) { Text("保留账本并连接") } },
        dismissButton={ TextButton(onClick={ vm.dismissPair() }) { Text("取消") } })
    BookImportDialogs(vm,chooseImport) { chooseImport = false }
}
