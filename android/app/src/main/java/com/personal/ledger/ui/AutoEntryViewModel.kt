package com.personal.ledger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.ledger.data.*
import com.personal.ledger.domain.Rules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

class AutoEntryViewModel(app: Application): AndroidViewModel(app) {
    private val inbox=AutoEntryDatabase.get(app)
    val rows=inbox.auto().candidates().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val error=MutableStateFlow<String?>(null)
    val busy=MutableStateFlow(false)
    data class Review(val candidate: PaymentCandidate,val categories: List<Category>,val matches: List<Entry>,val entry: Entry?,val sources: List<PaymentCandidate> = emptyList())
    val review=MutableStateFlow<Review?>(null)
    fun open(c: PaymentCandidate) = action {
        withLedger(c) {db ->
            val fresh=requireNotNull(inbox.auto().candidate(c.id))
            val root=fresh.mergedInto?.let {inbox.auto().candidate(it)} ?: fresh
            val sources=(listOf(root)+inbox.auto().children(root.id)).filter {it.owner==c.owner && it.databaseName==c.databaseName}
            review.value=Review(fresh,db.ledger().categorySnapshot(c.owner),AutoEntryProcessor(inbox).matches(fresh,db),fresh.entryId?.let {db.ledger().entry(it)},sources)
        }
    }
    fun save(type: String,category: String,amount: String,date: String,note: String,remember: Boolean,replace: String?) = action {
        val selected=requireNotNull(review.value);val c=selected.candidate
        withLedger(c) {db ->
            if(c.status=="booked" || c.status=="merged") {
                val e=requireNotNull(selected.entry) {"原账目已不存在"};require(e.owner==c.owner)
                LedgerRepository(db,c.owner).save(e.id,type,category,amount,LocalDate.parse(date),note)
            } else AutoEntryProcessor(inbox).confirm(c,db,category,type,amount,date,note,remember,replace)
            BudgetNotifications.send(getApplication(),c.databaseName,BudgetRepository(db,c.owner).reconcile())
        }
        review.value=null
    }
    fun split(id: String) = action {
        val c=requireNotNull(inbox.auto().candidate(id))
        withLedger(c) {db ->
            AutoEntryProcessor(inbox).split(id,db)
            BudgetNotifications.send(getApplication(),c.databaseName,BudgetRepository(db,c.owner).reconcile())
        }
        review.value=null
    }
    fun ignore(id: String) = action {AutoEntryProcessor(inbox).ignore(id);review.value=null}
    private suspend fun withLedger(c: PaymentCandidate,block: suspend (LedgerDatabase)->Unit) {
        require(AutoCapture.validName(c.databaseName) && getApplication<Application>().getDatabasePath(c.databaseName).exists()) {"此记录原指定账本暂不可用"}
        val db=LedgerDatabases.acquire(getApplication(),c.databaseName)
        try {block(db)} finally {LedgerDatabases.release(c.databaseName)}
    }
    private fun action(block: suspend ()->Unit) {
        if(busy.value) return
        busy.value=true;error.value=null
        viewModelScope.launch {
            try {block()} catch(e: CancellationException) {throw e}
            catch(e: Exception) {error.value=e.message ?: "操作未完成，原账目保留"}
            finally {busy.value=false}
        }
    }
}
