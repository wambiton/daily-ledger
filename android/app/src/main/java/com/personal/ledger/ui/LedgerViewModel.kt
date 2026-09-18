package com.personal.ledger.ui

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.personal.ledger.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

class LedgerViewModel @JvmOverloads constructor(application: Application, private val databaseName: String = "ledger.db", val owner: String = "local-preview") : AndroidViewModel(application) {
    private val db = LedgerDatabases.acquire(application, databaseName)
    private val repo = LedgerRepository(db, owner)
    val pending = repo.pendingCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val categories = repo.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val entries = repo.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trash = repo.trash.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val ready = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val fileMessage = MutableStateFlow<String?>(null)
    val importPreview = MutableStateFlow<BackupStore.Preview?>(null)
    private var importPayload: JSONObject? = null
    private var exportPayload: JSONObject? = null
    private var exportExcel = false

    fun prepareExport(excel: Boolean, from: String?, to: String?, complete: () -> Unit) = runAction {
        val start = from?.let { LocalDate.parse(it).also { d -> com.personal.ledger.domain.Rules.date(d) } }
        val end = to?.let { LocalDate.parse(it).also { d -> com.personal.ledger.domain.Rules.date(d) } }
        require(start == null || end != null && !start.isAfter(end)) { "开始日期不能晚于结束日期" }
        val payload = BackupStore(db,owner).export()
        val items = payload.getJSONArray("entries")
        val filtered = JSONArray()
        for(i in 0 until items.length()) {
            val e = items.getJSONObject(i); val day = LocalDate.parse(e.getString("date"))
            if((start == null || !day.isBefore(start)) && (end == null || !day.isAfter(end))) filtered.put(e)
        }
        payload.put("entries",filtered)
        if(!excel) require(payload.toString(2).toByteArray(Charsets.UTF_8).size <= 20*1024*1024) { "文件超过20MB，请缩小导出日期范围" }
        exportPayload = payload; exportExcel = excel
        complete()
    }
    fun writeExport(uri: Uri?) {
        val payload = exportPayload ?: return
        val excel = exportExcel
        exportPayload = null
        if(uri == null) return
        runAction {
            withContext(Dispatchers.IO) {
                requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri,"wt")) { "无法写入所选位置" }.use { out ->
                    if(excel) ExcelExport.write(out,PortableSnapshot.parse(payload,owner))
                    else out.write(payload.toString(2).toByteArray(Charsets.UTF_8))
                }
            }
            fileMessage.value = "导出成功。文件保存在刚才选择的位置。"
        }
    }
    fun readImport(uri: Uri?) {
        if(uri == null) return
        runAction {
            importPayload = null; importPreview.value = null
            val payload = withContext(Dispatchers.IO) {
                val bytes = requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use { input ->
                    val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                    while(true) { val n=input.read(chunk); if(n < 0) break
                        require(out.size()+n <= 20*1024*1024) { "备份文件不能超过20MB" }; out.write(chunk,0,n) }
                    out.toByteArray()
                }
                JSONObject(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
            }
            val preview = BackupStore(db,owner).preview(payload)
            importPayload = payload; importPreview.value = preview
        }
    }
    fun cancelImport() { importPayload = null; importPreview.value = null }
    fun confirmImport(includeDuplicates: Boolean) = runAction {
        val payload = requireNotNull(importPayload) { "请重新选择备份文件" }
        val count = BackupStore(db,owner).append(payload,if(includeDuplicates) "include" else "skip")
        cancelImport(); fileMessage.value = "已追加导入${count}笔（含回收站），原账目保留。"
    }

    init { initialize() }
    override fun onCleared() { LedgerDatabases.release(databaseName) }
    fun initialize() = runAction {
        repo.seedDefaults()
        repo.purgeExpired()
        TrashCleanup.schedule(getApplication(), databaseName, owner)
        ready.value = true
    }
    fun addCategory(type: String, name: String, complete: (Category) -> Unit) = runAction {
        complete(repo.addCategory(type, name))
    }
    fun save(id: String?, type: String, categoryId: String, amount: String, date: String, note: String, complete: () -> Unit) = runAction {
        repo.save(id, type, categoryId, amount, LocalDate.parse(date), note)
        complete()
    }
    fun delete(id: String, complete: () -> Unit) = runAction {
        repo.delete(id)
        complete()
    }
    fun restoreDeleted(id: String) = runAction { repo.restoreDeleted(id) }
    private fun runAction(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: SQLiteConstraintException) { error.value = "此分类已存在，请换一个名称" }
            catch (e: IllegalArgumentException) { error.value = e.message ?: "请检查填写内容" }
            catch (_: org.json.JSONException) { error.value = "备份文件格式不正确，原账目未被替换" }
            catch (_: java.time.format.DateTimeParseException) { error.value = "日期格式不正确，请填写如2026-09-01" }
            catch (_: Exception) { error.value = "保存失败，请重试；本次输入已保留" }
            finally { busy.value = false }
        }
    }
}
