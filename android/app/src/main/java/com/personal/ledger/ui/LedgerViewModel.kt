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

class LedgerViewModel @JvmOverloads constructor(application: Application, databaseName: String = "ledger.db") : AndroidViewModel(application) {
    private val db = Room.databaseBuilder(application, LedgerDatabase::class.java, databaseName).build()
    private val repo = LedgerRepository(db, "local-preview")
    val categories = repo.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val entries = repo.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val ready = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    init { initialize() }
    override fun onCleared() { db.close() }
    fun initialize() = runAction {
        repo.seedDefaults()
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
    private fun runAction(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: SQLiteConstraintException) { error.value = "此分类已存在，请换一个名称" }
            catch (e: IllegalArgumentException) { error.value = e.message ?: "请检查填写内容" }
            catch (_: Exception) { error.value = "保存失败，请重试；本次输入已保留" }
            finally { busy.value = false }
        }
    }
}
