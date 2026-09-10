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

class BackupViewModel @JvmOverloads constructor(application: Application, private val vaultName: String = "account"): AndroidViewModel(application) {
    private val app = application
    private val vault = AccountVault(app, vaultName)
    val account = MutableStateFlow<JSONObject?>(null)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow("")
    val server = MutableStateFlow(app.getSharedPreferences("backup-settings-$vaultName", 0).getString("server", "") ?: "")
    val lastBackup = MutableStateFlow("尚未查看电脑备份")
    init {
        try { account.value = vault.read() } catch(_: Exception) { message.value = "登录凭据无法读取，请重新登录；本地账目仍保留" }
    }
    fun databaseName(a: JSONObject) = "account-${AccountVault.databaseKey(a.getString("server"), a.getJSONObject("user").getString("id"))}.db"
    private fun owner(a: JSONObject) = a.getJSONObject("user").getString("id")
    private fun saveAccount(a: JSONObject) { vault.write(a); account.value = a }
    private fun run(block: suspend () -> Unit) {
        if(busy.value) return
        busy.value = true; message.value = ""
        viewModelScope.launch {
            try { block() }
            catch(e: CancellationException) { throw e }
            catch(e: BackupApiException) { message.value = e.message ?: "服务请求失败" }
            catch(e: IllegalArgumentException) { message.value = e.message ?: "请检查填写内容" }
            catch(_: Exception) { message.value = "无法连接电脑或操作失败，请确认电脑服务已开启后重试；手机账目仍保留" }
            finally { busy.value = false }
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
    fun signIn(address: String, email: String, password: String, code: String, mode: String) = run {
        require(mode in listOf("login", "register", "reset"))
        val client = client(address)
        val result = client.call("auth/$mode/", JSONObject().put("email", email).put("password", password).put("code", code))
        if(mode == "reset") message.value = "密码已重置，请使用新密码登录"
        else {
            result.put("server", client.base)
            saveAccount(result)
            lastBackup.value = "请点击查看电脑备份"
            message.value = "登录成功。原本机账本保留，可手动导入当前账号"
        }
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
    fun restore() = run {
        val result = authorized("backup/download/")
        store { store, a, _ ->
            withContext(Dispatchers.IO) {
                // Retain a local safety snapshot before replacing this account's records.
                write(AtomicFile(File(app.noBackupFilesDir, databaseName(a) + ".before-restore-${System.currentTimeMillis()}.json")), store.export())
                store.restore(result.getJSONObject("payload"), result.getLong("revision"))
                pendingFile(a).delete()
            }
            lastBackup.value = info(result)
            message.value = "恢复成功。恢复前的本机副本已保留"
        }
    }
    fun importLocal() = run {
        store { store, a, db ->
            require((db.ledger().backupState(owner(a))?.cursor ?: 0L) == 0L) { "此账号已恢复或备份过，不能覆盖导入" }
            val original = LedgerDatabases.acquire(app, "ledger.db")
            try {
                val payload = BackupStore(original, "local-preview").export()
                require(payload.getJSONArray("entries").length() > 0) { "原本机账本没有账目" }
                store.restore(payload, 0, requireEmpty = true)
                message.value = "本机账目已复制到当前账号，请点击备份到电脑；原账本保留"
            } finally { LedgerDatabases.release("ledger.db") }
        }
    }
    fun logout() = run {
        val a = account.value
        if(a != null) {
            try { BackupClient(a.getString("server")).call("auth/logout/", JSONObject().put("refresh_token", a.getString("refresh_token"))) }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { /* Local sign-out remains possible while the PC is off. */ }
        }
        vault.clear(); account.value = null; message.value = "已退出，账号账目仍保存在手机；重新登录可继续使用"
    }
}
