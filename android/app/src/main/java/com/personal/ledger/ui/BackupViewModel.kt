package com.personal.ledger.ui

import android.app.Application
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.personal.ledger.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

class BackupViewModel @JvmOverloads constructor(application: Application, private val vaultName: String = "account", private val localDatabaseName: String = "ledger.db"): AndroidViewModel(application) {
    private val app = application
    private val vault = AccountVault(app, vaultName)
    val account = MutableStateFlow<JSONObject?>(null)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow("")
    val server = MutableStateFlow(app.getSharedPreferences("backup-settings-$vaultName", 0).getString("server", "") ?: "")
    val lastBackup = MutableStateFlow("尚未备份到电脑")
    val waiting = MutableStateFlow(false)
    val duplicateCount = MutableStateFlow(0)
    private var pendingImport: JSONObject? = null
    private var action: Job? = null
    init {
        try {
            account.value = vault.read()
            account.value?.let { a -> viewModelScope.launch {
                val name = databaseName(a)
                val db = LedgerDatabases.acquire(app, name)
                try {
                    val state = db.ledger().backupState(owner(a))
                    if(state != null && state.cursor > 0) lastBackup.value = "上次电脑备份版本 ${state.cursor} · ${java.util.Date(state.lastSuccessAt ?: 0)}"
                } finally { LedgerDatabases.release(name) }
            } }
        } catch(_: Exception) { message.value = "登录凭据无法读取，请重新登录；本地账目仍保留" }
    }
    fun databaseName(a: JSONObject) = "account-${AccountVault.databaseKey(a.getString("server"), a.getJSONObject("user").getString("id"))}.db"
    private fun owner(a: JSONObject) = a.getJSONObject("user").getString("id")
    private fun saveAccount(a: JSONObject) { vault.write(a); account.value = a }
    private fun run(block: suspend () -> Unit) {
        if(busy.value) return
        busy.value = true; message.value = ""
        action = viewModelScope.launch {
            try { block() }
            catch(e: CancellationException) { throw e }
            catch(e: BackupApiException) { message.value = e.message ?: "服务请求失败" }
            catch(e: IllegalArgumentException) { message.value = e.message ?: "请检查填写内容" }
            catch(_: Exception) { message.value = "无法连接电脑或操作失败，请确认电脑服务已开启后重试；手机账目仍保留" }
            finally { busy.value = false; waiting.value = false }
        }
    }
    fun cancelWaiting() { action?.cancel(); message.value = "已取消等候，手机账目仍保留" }
    private suspend fun <T> waitForComputer(block: suspend () -> T): T {
        while(true) {
            try { return block().also { waiting.value = false } }
            catch(e: BackupApiException) {
                if(e.status != 503) throw e
                waiting.value = true
                message.value = "电脑尚未连接，请等候；服务恢复后会自动继续"
                delay(5000)
            }
            catch(e: java.io.IOException) {
                waiting.value = true
                message.value = "电脑尚未连接，请等候；连接恢复后会自动继续"
                delay(5000)
            }
        }
    }
    private fun client(address: String): BackupClient {
        val client = BackupClient(address)
        server.value = client.base
        app.getSharedPreferences("backup-settings-$vaultName", 0).edit().putString("server", client.base).apply()
        return client
    }
    fun sendCode(address: String, email: String, purpose: String) = run {
        client(address).call("auth/code/", JSONObject().put("email", email).put("purpose", purpose))
        message.value = "验证码已发送，10分钟内有效；60秒后可重新发送"
    }
    fun signIn(address: String, email: String, password: String, code: String, mode: String, complete: () -> Unit = {}) = run {
        require(mode in listOf("login", "register", "reset"))
        require(password.codePointCount(0, password.length) >= 8) { "密码至少需要8个字符，不限制字符类型" }
        if(mode != "login") require(code.matches(Regex("[0-9]{6}"))) { "请输入邮箱收到的6位验证码" }
        val client = client(address)
        val body = JSONObject().put("email", email).put("password", password).put("code", code)
        // Registration is not replayed automatically: a lost response may already have created the account.
        val result = if(mode == "login") waitForComputer { client.call("auth/login/", body) }
            else { waitForComputer { client.call("health/") }; client.call("auth/$mode/", body) }
        if(mode == "reset") message.value = "密码已重置，请使用新密码登录"
        else {
            result.put("server", client.base)
            complete()
            suspend fun fetch(path: String): JSONObject = waitForComputer {
                try { client.call(path, token = result.getString("access_token")) }
                catch(e: BackupApiException) {
                    if(e.status != 401) throw e
                    val fresh = client.call("auth/refresh/", JSONObject().put("refresh_token", result.getString("refresh_token")))
                    result.put("access_token", fresh.getString("access_token")).put("refresh_token", fresh.getString("refresh_token"))
                    client.call(path, token = result.getString("access_token"))
                }
            }
            val metadata = fetch("backup/latest/")
            val name = databaseName(result)
            val db = LedgerDatabases.acquire(app, name)
            try {
                val store = BackupStore(db, owner(result))
                val state = db.ledger().backupState(owner(result))
                val rows = db.ledger().entrySnapshot(owner(result))
                val dirty = db.ledger().pending(owner(result)).any { it.entityType == "entry" } || pendingFile(result).baseFile.exists()
                if(metadata.getLong("revision") > (state?.cursor ?: 0L) && !dirty && (state != null || rows.isEmpty())) {
                    val snapshot = fetch("backup/download/")
                    store.restore(snapshot.getJSONObject("payload"), snapshot.getLong("revision"))
                    lastBackup.value = info(snapshot)
                } else lastBackup.value = info(metadata)
                if(mode == "register") {
                    val payload = localPayload()
                    store.importLocal(payload)
                    message.value = "注册成功，已带入${payload.getJSONArray("entries").length()}笔本机账目；请点击备份到电脑"
                } else message.value = if(dirty && metadata.getLong("revision") > (state?.cursor ?: 0L))
                    "登录成功。手机有未备份修改，已保留；电脑另有更新版本，请勿覆盖上传" else "登录成功，账号账目已就绪"
                if(db.ledger().backupState(owner(result)) == null) db.ledger().setBackupState(SyncState(owner(result)))
                saveAccount(result)
            } finally { LedgerDatabases.release(name) }
        }
        complete()
    }
    private suspend fun authorized(path: String, body: JSONObject? = null): JSONObject {
        val a = requireNotNull(account.value) { "请先登录" }
        val client = BackupClient(a.getString("server"))
        try { return client.call(path, body, a.getString("access_token")) }
        catch(e: BackupApiException) {
            if(e.status != 401) throw e
            val fresh = client.call("auth/refresh/", JSONObject().put("refresh_token", a.getString("refresh_token")))
            val updated = JSONObject(a.toString()).put("access_token", fresh.getString("access_token")).put("refresh_token", fresh.getString("refresh_token"))
            saveAccount(updated)
            return client.call(path, body, updated.getString("access_token"))
        }
    }
    private fun info(result: JSONObject): String = if(result.getLong("revision") == 0L) "这个账号尚无电脑备份" else
        "电脑备份版本 ${result.getLong("revision")} · ${result.getInt("entry_count")} 笔\n${result.getString("created_at")}"
    fun inspect() = run { lastBackup.value = info(authorized("backup/latest/")) }
    private suspend fun <T> store(block: suspend (BackupStore, JSONObject, LedgerDatabase) -> T): T {
        val a = requireNotNull(account.value) { "请先登录" }
        val name = databaseName(a)
        val db = LedgerDatabases.acquire(app, name)
        try { return block(BackupStore(db, owner(a)), a, db) } finally { LedgerDatabases.release(name) }
    }
    private fun pendingFile(a: JSONObject) = AtomicFile(File(app.noBackupFilesDir, databaseName(a) + ".pending.json"))
    private fun write(file: AtomicFile, value: JSONObject) {
        val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch(e: Exception) { file.failWrite(stream); throw e }
    }
    fun upload() = run {
        store { store, a, _ ->
            val file = pendingFile(a)
            val pending = withContext(Dispatchers.IO) {
                if(file.baseFile.exists()) JSONObject(String(file.readFully(), Charsets.UTF_8)) else store.prepare().also { write(file, it) }
            }
            val response = authorized("backup/", JSONObject(pending.toString()).apply { remove("pending_ids") })
            val ids = pending.getJSONArray("pending_ids")
            store.acknowledge(response.getLong("revision"), (0 until ids.length()).map { ids.getString(it) })
            withContext(Dispatchers.IO) { file.delete() }
            lastBackup.value = info(response)
            message.value = "备份成功。若上传期间又有修改，请再次备份"
        }
    }
    private suspend fun localPayload(): JSONObject {
        val original = LedgerDatabases.acquire(app, localDatabaseName)
        try { return BackupStore(original, "local-preview").export() }
        finally { LedgerDatabases.release(localDatabaseName) }
    }
    fun dismissImport() { pendingImport = null; duplicateCount.value = 0 }
    fun importLocal(allowDuplicates: Boolean = false) = run {
        val payload = if(allowDuplicates) requireNotNull(pendingImport) { "请重新选择导入" } else localPayload()
        require(payload.getJSONArray("entries").length() > 0) { "原本机账本没有账目" }
        store { store, _, _ ->
            try {
                val count = store.importLocal(payload, allowDuplicates)
                dismissImport()
                message.value = "已追加${count}笔本机账目，请点击备份到电脑；原账本保留"
            } catch(e: BackupStore.DuplicateImport) {
                pendingImport = payload
                duplicateCount.value = e.count
            }
        }
    }
    fun logout() = run {
        val a = account.value
        if(a != null) {
            try { BackupClient(a.getString("server")).call("auth/logout/", JSONObject().put("refresh_token", a.getString("refresh_token"))) }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { /* Local sign-out remains possible while the PC is off. */ }
        }
        dismissImport(); vault.clear(); account.value = null; message.value = "已退出，账号账目仍保存在手机；重新登录可继续使用"
    }
}
