package com.personal.ledger.data

import androidx.room.withTransaction
import com.personal.ledger.domain.Rules
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.UUID

class AutoEntryProcessor(private val inbox: AutoEntryDatabase) {
    companion object { val lock=Mutex() }
    suspend fun receive(notice: PaymentNotice, target: PaymentCandidate, ledger: LedgerDatabase): PaymentCandidate? = lock.withLock {
        inbox.withTransaction {
            if(inbox.auto().candidate(notice.id)!=null) return@withTransaction null
            var candidate=target.copy(source=notice.source,transactionAt=notice.transactionAt,channel=notice.channel,cardTail=notice.cardTail,transactionId=notice.transactionId,uncertain=notice.uncertain,
                status=if(notice.excluded) "ignored" else "pending",reason=if(notice.excluded) "充值、提现或本人账户互转不计收支" else "请确认分类与通知内容")
            inbox.auto().insert(candidate)
            if(notice.excluded) return@withTransaction candidate
            if(!notice.uncertain) {
                val possible=inbox.auto().nearby(candidate.databaseName,candidate.owner,candidate.transactionAt-300000,candidate.transactionAt+300000)
                    .filter { crossSourceMatch(candidate,it) }
                    .filter { inbox.auto().children(it.id).isEmpty() }
                // Time proximity alone cannot choose between separate purchases. Keep all evidence.
                if(possible.isNotEmpty() && (possible.size!=1 || !corroborates(candidate,possible.single()))) {
                    candidate=candidate.copy(reason="疑似重复：附近存在同金额的支付来源，但商户或交易身份不足以唯一确认，请核对后处理")
                    inbox.auto().update(candidate)
                    return@withTransaction candidate
                }
                for(root in possible) {
                    if(inbox.auto().children(root.id).isNotEmpty()) continue
                    var merged: PaymentCandidate?=null
                    ledger.withTransaction {
                        val existing=root.entryId?.let {ledger.ledger().entry(it)}
                        if(root.status=="booked" && (existing==null || existing.deletedAt!=null || existing.owner!=root.owner || existing.amountFen!=root.amountFen || existing.type!=root.type || existing.date!=root.date)) return@withTransaction
                        val naming=naming(root,ledger)
                        // A second source must not silently confirm a first-time naming rule.
                        if(existing==null && (root.uncertain || naming is NamingDecision.Review)) {
                            merged=candidate.copy(status="linked",mergedInto=root.id,reason="已关联同笔来源，等待原记录确认；尚未计入统计")
                            inbox.auto().update(merged!!)
                            return@withTransaction
                        }
                        val automatic=(naming as? NamingDecision.Automatic)?.rule
                        val category=automatic?.let { ledger.ledger().category(it.categoryId) } ?: categoryFor(root,ledger,true) ?: return@withTransaction
                        val entry=existing ?: write(root,ledger,category.id,root.type!!,root.amountFen!!,root.date,root.note,null,automatic?.title.orEmpty())
                        inbox.auto().update(root.copy(status="booked",entryId=entry.id,reason="已合并银行短信与支付通知，仅计一笔；可查看来源并拆分"))
                        merged=candidate.copy(status="merged",entryId=entry.id,mergedInto=root.id,reason="渠道、金额、收支、时间与商户或交易号一致且候选唯一，已合并；若是两次消费可拆分")
                        inbox.auto().update(merged!!)
                    }
                    if(merged!=null) return@withTransaction merged
                }
            }
            val naming=naming(candidate,ledger)
            val rule=when(naming) { is NamingDecision.Automatic -> naming.rule; is NamingDecision.Review -> naming.rule; else -> null }
            if(rule!=null) candidate=candidate.copy(suggestedTitle=rule.title,suggestedCategory=rule.categoryId,ruleSignature=rule.signature())
            if(naming is NamingDecision.Review) {
                candidate=candidate.copy(reason="命名规则待确认："+when(naming.reason) {
                    NamingReviewReason.FIRST_CONFIRMATION -> "首次匹配此商户，确认后按已授权规则自动记账"
                    NamingReviewReason.AUTOMATIC_NOT_ALLOWED -> "此规则仅建议名称，每次需要确认"
                    NamingReviewReason.RULE_CONFLICT -> "多条规则冲突，请自行确认名称和分类"
                    else -> "支付信息或商户不明确，请核对"
                })
                inbox.auto().update(candidate);return@withTransaction candidate
            }
            val category=rule?.let { ledger.ledger().category(it.categoryId) } ?: categoryFor(candidate,ledger,notice.source=="sms")
            ledger.withTransaction {
                val duplicate=matches(candidate,ledger)
                if(duplicate.isNotEmpty() && notice.source!="sms") candidate=candidate.copy(reason="疑似重复：当前账本已有同日期、金额和收支类型的账目")
                else if(category!=null && !notice.uncertain) {
                    val entry=write(candidate,ledger,category.id,notice.type!!,notice.amountFen!!,notice.date,notice.note,null,rule?.title.orEmpty())
                    candidate=candidate.copy(status="booked",entryId=entry.id,reason="已自动记账，可在指定账本查看或修改")
                }
            }
            inbox.auto().update(candidate)
            candidate
        }
    }
    private fun notice(c: PaymentCandidate)=PaymentNotice(c.id,c.platform,c.date,c.amountFen,c.type,c.merchant,c.note,c.refund,false,c.uncertain,
        c.source,c.transactionAt,c.channel,c.cardTail,c.transactionId)
    private suspend fun naming(c: PaymentCandidate,ledger: LedgerDatabase): NamingDecision {
        val stored=NamingRuleStore(ledger,c.databaseName,c.owner).snapshot()
        return SmartRuleMatcher.evaluate(c.databaseName,c.owner,notice(c),stored.map {it.rule},stored.flatMap {it.trusted}.toSet())
    }
    private suspend fun categoryFor(c: PaymentCandidate, ledger: LedgerDatabase, fallback: Boolean): Category? {
        val categories=ledger.ledger().categorySnapshot(c.owner)
        if(c.refund) return categories.firstOrNull {it.type=="income" && it.name=="退款"}
        if(c.type==null) return null
        for(platform in listOf(c.platform,c.channel).distinct()) {
            val mapping=inbox.auto().mapping(c.databaseName,c.owner,platform,c.merchant,c.type)
            categories.firstOrNull {it.id==mapping?.categoryId && it.type==c.type}?.let {return it}
        }
        return if(fallback) categories.firstOrNull {it.type==c.type && it.name==if(c.type=="income") "其他收入" else "其他支出"} else null
    }
    /** Pair only one bank source with one platform source, never two platform payments. */
    private fun crossSourceMatch(a: PaymentCandidate,b: PaymentCandidate): Boolean {
        if(a.id==b.id || a.transactionAt<=0 || b.transactionAt<=0 || a.mergeDisabled || b.mergeDisabled) return false
        if(a.source==b.source || setOf(a.source,b.source)!=setOf("sms","notification")) return false
        val sms=if(a.source=="sms") a else b;val platform=if(a.source=="notification") a else b
        return sms.channel in setOf("微信","支付宝") && sms.channel==platform.platform &&
            a.amountFen!=null && a.amountFen==b.amountFen && a.type!=null && a.type==b.type && a.refund==b.refund &&
            kotlin.math.abs(a.transactionAt-b.transactionAt)<=300000 &&
            (a.cardTail.isEmpty() || b.cardTail.isEmpty() || a.cardTail==b.cardTail)
    }
    /** IDs are only comparable when both sources carry a payment identity; do not fuzzy-match merchants. */
    private fun corroborates(a: PaymentCandidate,b: PaymentCandidate): Boolean {
        if(a.transactionId.isNotBlank() && b.transactionId.isNotBlank()) return a.transactionId==b.transactionId
        val merchant=a.merchant.trim()
        return merchant.isNotEmpty() && merchant==b.merchant.trim()
    }
    suspend fun split(id: String,ledger: LedgerDatabase): PaymentCandidate = lock.withLock {
        inbox.withTransaction {
            val c=requireNotNull(inbox.auto().candidate(id))
            require(c.status=="merged" && c.mergedInto!=null) {"此来源已拆分或未合并，请刷新"}
            val root=requireNotNull(inbox.auto().candidate(c.mergedInto))
            require(root.databaseName==c.databaseName && root.owner==c.owner)
            val original=requireNotNull(root.entryId?.let {ledger.ledger().entry(it)}) {"原账目已不存在"}
            require(original.owner==c.owner && original.deletedAt==null) {"请先恢复原账目，再拆分"}
            val category=requireNotNull(categoryFor(c,ledger,true))
            val entry=write(c,ledger,category.id,c.type!!,c.amountFen!!,c.date,c.note,null)
            val result=c.copy(status="booked",entryId=entry.id,mergedInto=null,mergeDisabled=true,reason="已拆分为独立账目，两笔均计入统计")
            inbox.auto().update(result)
            inbox.auto().update(root.copy(mergeDisabled=true,reason="用户已拆分合并来源，原账目保留"))
            result
        }
    }
    suspend fun matches(candidate: PaymentCandidate, db: LedgerDatabase): List<Entry> = db.ledger().entrySnapshot(candidate.owner)
        .filter{it.date==candidate.date && it.amountFen==candidate.amountFen && it.type==candidate.type && it.id!=candidate.id}

    suspend fun confirm(candidate: PaymentCandidate, ledger: LedgerDatabase, categoryId: String, type: String, amount: String,
        date: String, note: String, remember: Boolean, replaceId: String?=null, title: String?=null): PaymentCandidate = lock.withLock {
        confirmLocked(candidate,ledger,categoryId,type,amount,date,note,remember,replaceId,title)
    }
    private suspend fun confirmLocked(candidate: PaymentCandidate, ledger: LedgerDatabase, categoryId: String, type: String, amount: String,
        date: String, note: String, remember: Boolean, replaceId: String?, title: String?): PaymentCandidate {
        val fresh=requireNotNull(inbox.auto().candidate(candidate.id))
        require(fresh.status=="pending") {"此记录已处理，请刷新"}
        val value=Rules.amount(amount);Rules.date(LocalDate.parse(date));Rules.note(note)
        val chosenTitle=Rules.title(title ?: fresh.suggestedTitle)
        val entry=ledger.withTransaction {
            val entry=write(fresh,ledger,categoryId,type,value,date,note,replaceId,chosenTitle)
            val decision=naming(fresh,ledger)
            val current=(decision as? NamingDecision.Review)?.rule
            if(current!=null && current.signature()==fresh.ruleSignature && current.title==chosenTitle && current.categoryId==categoryId &&
                type==fresh.type && value==fresh.amountFen && date==fresh.date && replaceId==null &&
                entry.title==chosenTitle && entry.categoryId==categoryId && entry.deletedAt==null)
                NamingRuleStore(ledger,fresh.databaseName,fresh.owner).recordConfirmation(notice(fresh))
            entry
        }
        val saved=fresh.copy(status="booked",entryId=entry.id,reason="已确认入账")
        inbox.withTransaction {
            inbox.auto().update(saved)
            inbox.auto().children(fresh.id).filter {it.status=="linked"}.forEach {
                inbox.auto().update(it.copy(status="merged",entryId=entry.id,reason="原记录已确认，同笔来源仅计一笔"))
            }
        }
        if(remember && fresh.merchant.isNotBlank()) inbox.auto().map(MerchantCategory(fresh.databaseName,fresh.owner,fresh.platform,fresh.merchant,type,categoryId))
        return saved
    }
    suspend fun ignore(id: String) = lock.withLock {
        val candidate=requireNotNull(inbox.auto().candidate(id))
        require(candidate.status=="pending")
        inbox.withTransaction {
            inbox.auto().update(candidate.copy(status="ignored",reason="用户选择不入账"))
            inbox.auto().children(id).filter {it.status=="linked"}.forEach {
                inbox.auto().update(it.copy(status="ignored",reason="原记录已选择不入账"))
            }
        }
    }
    /** Restore only an explicitly ignored root; never write a ledger entry or grant trust. */
    suspend fun restoreIgnored(id: String, ledger: LedgerDatabase, databaseName: String): PaymentCandidate = lock.withLock {
        inbox.withTransaction {
            val root=requireNotNull(inbox.auto().candidate(id))
            require(root.databaseName==databaseName) {"原账本不一致"}
            require(root.status=="ignored" && root.mergedInto==null && root.reason=="用户选择不入账") {"仅支持恢复手动忽略的原记录，请刷新列表"}
            val children=inbox.auto().children(id)
            val sources=listOf(root)+children
            require(children.all {it.status=="ignored" && it.databaseName==root.databaseName && it.owner==root.owner}) {"关联来源已变更，请先核对"}
            val imported=ledger.ledger().importedSources(root.owner)
            require(sources.all {it.entryId==null && ledger.ledger().entry(it.id)==null && it.id !in imported}) {"存在已处理账目，请先核对原账本"}
            val restored=root.copy(status="pending",reason="已恢复，请在应用内核对后确认；尚未入账",uncertain=true,ruleSignature="")
            inbox.auto().update(restored)
            children.forEach {inbox.auto().update(it.copy(status="linked",reason="原记录已恢复，等待人工确认；尚未入账"))}
            restored
        }
    }
    /** Notification commands use saved evidence, never the currently displayed ledger. */
    suspend fun notificationAction(id: String, action: String, ledger: LedgerDatabase,
        databaseName: String, title: String?=null): PaymentCandidate? = lock.withLock {
        require(action in setOf("confirm","rename","ignore"))
        val selected=inbox.auto().candidate(id) ?: return@withLock null
        val fresh=if(selected.status=="linked") {
            val root=selected.mergedInto?.let {inbox.auto().candidate(it)} ?: return@withLock selected
            require(root.databaseName==selected.databaseName && root.owner==selected.owner)
            root
        } else selected
        require(fresh.databaseName==databaseName) {"原账本不一致"}
        if(fresh.status!="pending") return@withLock fresh
        // A previous write may have survived a crash before the inbox status was committed.
        // Do not rename or ignore an entry already written by that attempt.
        val existing=ledger.ledger().entry(fresh.id)
        if(existing!=null) {
            require(existing.owner==fresh.owner)
            val saved=fresh.copy(status="booked",entryId=existing.id,reason="账目已保存，请在应用内查看")
            inbox.withTransaction {
                inbox.auto().update(saved)
                inbox.auto().children(fresh.id).filter {it.status=="linked"}.forEach {
                    inbox.auto().update(it.copy(status="merged",entryId=existing.id,reason="原记录已保存，同笔来源仅计一笔"))
                }
            }
            return@withLock saved
        }
        if(action=="rename") {
            val saved=fresh.copy(suggestedTitle=Rules.title(requireNotNull(title)),reason="名称已修改，尚未入账，请确认")
            inbox.auto().update(saved)
            return@withLock saved
        }
        if(action=="ignore") {
            val saved=fresh.copy(status="ignored",reason="用户选择不入账")
            inbox.withTransaction {
                inbox.auto().update(saved)
                inbox.auto().children(fresh.id).filter {it.status=="linked"}.forEach {
                    inbox.auto().update(it.copy(status="ignored",reason="原记录已选择不入账"))
                }
            }
            return@withLock saved
        }
        val decision=naming(fresh,ledger)
        val rule=when(decision) {
            is NamingDecision.Review -> decision.rule.takeIf {
                decision.reason in setOf(NamingReviewReason.FIRST_CONFIRMATION,NamingReviewReason.AUTOMATIC_NOT_ALLOWED)
            }
            is NamingDecision.Automatic -> decision.rule
            else -> null
        }
        val category=rule?.let {ledger.ledger().category(it.categoryId)}
        if(fresh.uncertain || fresh.amountFen==null || fresh.amountFen<=0 || fresh.type!="expense" ||
            rule==null || rule.signature()!=fresh.ruleSignature || category==null || category.owner!=fresh.owner ||
            category.type!=fresh.type || matches(fresh,ledger).isNotEmpty()) {
            val saved=fresh.copy(reason="请打开应用核对金额、分类、规则或疑似重复记录，尚未入账")
            inbox.auto().update(saved)
            return@withLock saved
        }
        confirmLocked(fresh,ledger,category.id,fresh.type,
            java.math.BigDecimal.valueOf(fresh.amountFen,2).toPlainString(),fresh.date,fresh.note,false,null,fresh.suggestedTitle)
    }
    /** Deterministic entry ID and imported source survive a crash between the two databases. */
    private suspend fun write(c: PaymentCandidate,db: LedgerDatabase,category: String,type: String,amount: Long,date: String,note: String,replace: String?,title: String=""): Entry = db.withTransaction {
        val dao=db.ledger()
        dao.entry(c.id)?.let { existing -> require(existing.owner==c.owner);return@withTransaction existing }
        require(c.id !in dao.importedSources(c.owner)) {"此通知对应账目已处理，不能重复入账"}
        val cat=requireNotNull(dao.category(category));require(cat.owner==c.owner && cat.type==type) {"请选择此账本对应的收支分类"}
        Rules.date(LocalDate.parse(date));Rules.note(note);require(amount>0)
        if(replace!=null) {
            val old=requireNotNull(dao.entry(replace));require(old.owner==c.owner && old.deletedAt==null && old.date==date && old.amountFen==amount && old.type==type) {"待替换账目已变更，请重新确认"}
            LedgerRepository(db,c.owner).delete(replace)
        }
        val now=System.currentTimeMillis()
        val entry=Entry(c.id,c.owner,type,category,amount,date,note,now,now,title=Rules.title(title))
        dao.upsertEntry(entry)
        dao.enqueue(PendingOperation(UUID.randomUUID().toString(),c.owner,entry.id,"entry","upsert",0,"{}",now))
        dao.markImported(ImportedSource(c.owner,c.id))
        entry
    }
}
