package com.personal.ledger.ui

import android.app.Application
import android.content.SharedPreferences
import kotlinx.coroutines.sync.withLock
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
    private val pairVault = AccountVault(app, "$vaultName-pair")
    private val carryVault = AccountVault(app, "$vaultName-carry")
    private val phoneBooks = PhoneLedgers(app,vaultName)
    val savedBooks = MutableStateFlow<List<JSONObject>>(emptyList())
    val selectedBook = MutableStateFlow<JSONObject?>(null)
    val pendingPair = MutableStateFlow<JSONObject?>(null)
    val account = MutableStateFlow<JSONObject?>(null)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow("")
    val server = MutableStateFlow(app.getSharedPreferences("backup-settings-$vaultName", 0).getString("server", "") ?: "")
    val lastBackup = MutableStateFlow("尚未备份到电脑")
    val waiting = MutableStateFlow(false)
    val duplicateCount = MutableStateFlow(0)
    private var pendingImport: JSONObject? = null
    private var action: Job? = null
    val uploading = MutableStateFlow(BackgroundBackup.active(app,vaultName))
    private val jobPrefs = BackgroundBackup.prefs(app,vaultName)
    private val jobListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        uploading.value = prefs.getBoolean("active",false)
        message.value = prefs.getString("message","") ?: ""
        prefs.getString("result",null)?.let { runCatching { lastBackup.value = info(JSONObject(it)) } }
    }
    override fun onCleared() { jobPrefs.unregisterOnSharedPreferenceChangeListener(jobListener);super.onCleared() }
    fun cancelUpload() { BackgroundBackup.cancel(app,vaultName);uploading.value = false }

    init {
        jobPrefs.registerOnSharedPreferenceChangeListener(jobListener)
        if(uploading.value) message.value = jobPrefs.getString("message", "正在后台等待电脑") ?: ""
        try {
            account.value = vault.read()
            account.value?.let { phoneBooks.remember(phoneBooks.descriptor(it)) }
            val carried = carryVault.read()?.apply { if(!has("label")) put("label","扫码前的账号账本") }
            carried?.let { phoneBooks.remember(it) }
            selectedBook.value = phoneBooks.selected() ?: carried?.takeIf { account.value == null }
            selectedBook.value?.let { phoneBooks.select(it) }
            savedBooks.value = phoneBooks.books()
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
    fun databaseName(a: JSONObject) = ComputerPair.databaseName(a)
    fun selectBook(book: JSONObject) {
        if(busy.value || uploading.value) return
        phoneBooks.select(book); selectedBook.value = book
        message.value = "正在查看手机保存的账本，可继续记账；未连接账号时不会上传"
    }
    fun selectOriginalBook() = selectBook(JSONObject().put("database",localDatabaseName).put("owner","local-preview").put("label","原本机账本"))
    fun selectAccountBook() { phoneBooks.select(null); selectedBook.value = null }
    fun viewingAccount(): Boolean = account.value?.let { a -> selectedBook.value?.getString("database")?.let { it == databaseName(a) } ?: true } ?: false
    fun dismissPair() { pendingPair.value = null }
    fun pairComputer(contents: String) = run {
        val candidate = ComputerPair.parse(contents)
        val verified = ComputerPair.client(candidate).call("computer/")
        require(verified.getString("computer_id") == candidate.getString("computer_id")) { "电脑身份不匹配，请重新扫码" }
        if(account.value != null && account.value!!.optString("computer_id") != candidate.getString("computer_id")) {
            pendingPair.value = candidate
            message.value = "请确认是否更换电脑，当前手机账本将保留"
        } else applyPair(candidate)
    }
    fun confirmPair() = run {
        val candidate = requireNotNull(pendingPair.value)
        applyPair(candidate)
        pendingPair.value = null
    }
    private suspend fun applyPair(candidate: JSONObject) {
        BackgroundBackup.lock.withLock {
        // A background upload may have rotated the session while this screen stayed open.
        val old = vault.read()
        if(old != null && old.optString("computer_id") == candidate.getString("computer_id")) {
            val updated = JSONObject(old.toString()).put("database_name",databaseName(old))
                .put("server",candidate.getString("server")).put("pair_key",candidate.getString("pair_key"))
            saveAccount(updated)
            message.value = "电脑连接地址已更新，原账号和账目保留"
        } else {
            cancelUpload()
            val retained = old?.let { phoneBooks.descriptor(it) } ?: selectedBook.value
            if(retained != null) {
                carryVault.write(retained);phoneBooks.remember(retained);phoneBooks.select(retained);selectedBook.value = retained
            }
            vault.clear(); account.value = null
            savedBooks.value = phoneBooks.books()
            message.value = "电脑已配对，尚未登录或备份。原账本仍可查看和记账，请登录后备份"
        }
        pairVault.write(candidate)
        server.value = candidate.getString("server")
        app.getSharedPreferences("backup-settings-$vaultName",0).edit().putString("server",server.value).apply()
        }
    }
    private fun owner(a: JSONObject) = a.getJSONObject("user").getString("id")
    private fun saveAccount(a: JSONObject) { vault.write(a); account.value = a;phoneBooks.remember(phoneBooks.descriptor(a));savedBooks.value = phoneBooks.books() }
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
        val normalized = BackupClient.normalize(address)
        val paired = pairVault.read()?.takeIf { it.getString("server") == normalized }
        val client = BackupClient(normalized, paired?.getString("pair_key"))
        server.value = client.base
        app.getSharedPreferences("backup-settings-$vaultName", 0).edit().putString("server", client.base).apply()
        return client
    }
    fun sendCode(address: String, email: String, purpose: String) = run {
        client(address).call("auth/code/", JSONObject().put("email", email).put("purpose", purpose))
        message.value = "验证码已发送，10分钟内有效；60秒后可重新发送"
    }
    fun signIn(address: String, email: String, password: String, code: String, mode: String, newPasswordAgain: String? = null, complete: () -> Unit = {}) = run {
        require(mode in listOf("login", "register", "reset"))
        require(password.codePointCount(0, password.length) >= 8) { "密码至少需要8个字符，不限制字符类型" }
        require(mode != "reset" || password == newPasswordAgain) { "两次输入的新密码不一致，请重新确认" }
        if(mode != "login") require(code.matches(Regex("[0-9]{6}"))) { "请输入邮箱收到的6位验证码" }
        val client = client(address)
        val body = JSONObject().put("email", email).put("password", password).put("code", code)
        // Registration is not replayed automatically: a lost response may already have created the account.
        val result = if(mode == "login") waitForComputer { client.call("auth/login/", body) }
            else { waitForComputer { client.call("health/") }; client.call("auth/$mode/", body) }
        if(mode == "reset") {
            require(result.optString("status") == "password_reset") { "电脑端版本较旧，无法确认密码修改结果，请先更新电脑端" }
            message.value = "密码已重置，请使用新密码登录当前电脑上的账号"
        }
        else {
            result.put("server", client.base)
            pairVault.read()?.takeIf { it.getString("server") == client.base }?.let {
                result.put("computer_id",it.getString("computer_id")).put("pair_key",it.getString("pair_key"))
            }
            val carry = carryVault.read()
            val retained = carry?.takeIf { it.optString("owner")==owner(result) }
                ?: phoneBooks.books().firstOrNull { it.optString("owner")==owner(result) }
            retained?.let { result.put("database_name",it.getString("database")) }
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
                    val payload = registrationPayload()
                    store.importLocal(payload)
                    message.value = "注册成功，已带入${payload.getJSONArray("entries").length()}笔本机账目；请点击备份到电脑"
                } else message.value = if(dirty && metadata.getLong("revision") > (state?.cursor ?: 0L))
                    "登录成功。手机有未备份修改，已保留；电脑另有更新版本，请勿覆盖上传" else "登录成功，账号账目已就绪"
                if(db.ledger().backupState(owner(result)) == null) db.ledger().setBackupState(SyncState(owner(result)))
                saveAccount(result)
                selectAccountBook()
                carryVault.clear()
            } finally { LedgerDatabases.release(name) }
        }
        complete()
    }
    private suspend fun authorized(path: String, body: JSONObject? = null): JSONObject {
        val a = requireNotNull(account.value) { "请先登录" }
        val client = ComputerPair.client(a)
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
    fun upload() {
        val current = account.value ?: return
        if(busy.value || uploading.value) return
        if(!viewingAccount()) { message.value = "当前查看的是另一份手机账本，请先追加导入当前账号再备份";return }
        uploading.value = true
        BackgroundBackup.enqueue(app,vaultName,current)
    }
    private suspend fun registrationPayload(): JSONObject {
        val carry = carryVault.read() ?: return localPayload()
        val name = carry.getString("database")
        val db = LedgerDatabases.acquire(app,name)
        try { return BackupStore(db,carry.getString("owner")).export() }
        finally { LedgerDatabases.release(name) }
    }
    private suspend fun localPayload(): JSONObject {
        val original = LedgerDatabases.acquire(app, localDatabaseName)
        try { return BackupStore(original, "local-preview").export() }
        finally { LedgerDatabases.release(localDatabaseName) }
    }
    fun dismissImport() { pendingImport = null; duplicateCount.value = 0 }
    fun importLocal(allowDuplicates: Boolean = false, fromSelected: Boolean = false) = run {
        val payload = if(allowDuplicates) requireNotNull(pendingImport) { "请重新选择导入" } else if(fromSelected) {
            val selected = requireNotNull(selectedBook.value)
            require(selected.getString("database") != account.value?.let { databaseName(it) }) { "已经是当前账号账本，无需导入" }
            val name = selected.getString("database")
            val source = LedgerDatabases.acquire(app,name)
            try { BackupStore(source,selected.getString("owner")).export() } finally { LedgerDatabases.release(name) }
        } else localPayload()
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
        cancelUpload()
        BackgroundBackup.lock.withLock {
        val a = vault.read()
        if(a != null) {
            try { ComputerPair.client(a).call("auth/logout/", JSONObject().put("refresh_token", a.getString("refresh_token"))) }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { /* Local sign-out remains possible while the PC is off. */ }
        }
        a?.let { phoneBooks.remember(phoneBooks.descriptor(it)) }
        dismissImport(); vault.clear(); account.value = null
        val original=JSONObject().put("database",localDatabaseName).put("owner","local-preview").put("label","原本机账本")
        phoneBooks.select(original);selectedBook.value=original;savedBooks.value = phoneBooks.books()
        message.value = "已退出，账号账本仍可从手机账本列表打开，无需联网"
        }
    }
}
