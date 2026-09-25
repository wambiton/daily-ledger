package com.personal.ledger.ui

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.withTransaction
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

class LedgerViewModel @JvmOverloads constructor(application: Application, val databaseName: String = "ledger.db", val owner: String = "local-preview") : AndroidViewModel(application) {
    private val db = LedgerDatabases.acquire(application, databaseName)
    private val repo = LedgerRepository(db, owner)
    val pending = repo.pendingCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val pendingEntries = db.ledger().pendingEntries(owner).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val backupState = db.ledger().observeBackupState(owner).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val categories = repo.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val entries = repo.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trash = repo.trash.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val budgets = db.ledger().budgets(owner).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val budgetNotices = db.ledger().notices(owner).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val budgetWarnings = MutableStateFlow<List<String>>(emptyList())
    val budgetDraft = MutableStateFlow<BudgetPlan?>(null)
    private var renewalSource: BudgetPlan? = null
    private val budgetRepo = BudgetRepository(db,owner)
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
        cancelImport(); fileMessage.value = "已追加导入${count}笔（含回收站），原账目保留。预算计划未合并。"
    }

    init {
        initialize()
        viewModelScope.launch {
            combine(db.ledger().entries(owner),db.ledger().budgets(owner)) { _, _ -> Unit }.collect {
                refreshBudgets()
            }
        }
    }
    suspend fun refreshBudgets() {
        try {
            budgetWarnings.value=budgetRepo.inheritDue(java.time.YearMonth.from(com.personal.ledger.domain.Rules.today()))
            BudgetNotifications.send(getApplication(),databaseName,budgetRepo.reconcile())
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { budgetWarnings.value=listOf("预算提醒检查暂未完成，请重新打开账本重试") }
    }
    fun newBudget(previous: BudgetPlan? = null, copy: Boolean = false) {
        renewalSource=previous
        val start=previous?.let { java.time.YearMonth.parse(it.endMonth).plusMonths(1).toString() }
            ?: java.time.YearMonth.from(com.personal.ledger.domain.Rules.today()).toString()
        budgetDraft.value=if(previous != null && copy) budgetRepo.nextPeriod(previous) else
            BudgetPlan(java.util.UUID.randomUUID().toString(),owner,"",start,start,1)
    }
    fun editBudget(plan: BudgetPlan) { renewalSource=null; budgetDraft.value=plan }
    fun cancelBudget() { budgetDraft.value=null; renewalSource=null }
    fun saveBudget(plan: BudgetPlan) = runAction {
        db.withTransaction {
            budgetRepo.save(plan)
            renewalSource?.let { budgetRepo.dismissRenewal(it) }
        }
        cancelBudget()
    }
    fun dismissBudgetRenewal(plan: BudgetPlan) = runAction { budgetRepo.dismissRenewal(plan) }

    override fun onCleared() { LedgerDatabases.release(databaseName) }
    fun initialize() = runAction {
        repo.seedDefaults()
        repo.purgeExpired()
        TrashCleanup.schedule(getApplication(), databaseName, owner)
        BudgetNotifications.schedule(getApplication(),databaseName,owner)
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
