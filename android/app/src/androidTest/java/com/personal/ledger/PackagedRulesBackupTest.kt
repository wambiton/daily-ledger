package com.personal.ledger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.ledger.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate
import java.util.UUID

/** Explicit isolated EXE fixture; retains every synthetic ledger. */
class PackagedRulesBackupTest {
    @Test fun rulesAndTrustRoundTripToPackagedComputer() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        require(args.containsKey("packaged_server"))
        val app=ApplicationProvider.getApplicationContext<Context>()
        val client=BackupClient(args.getString("packaged_server")!!,args.getString("packaged_pair"))
        suspend fun login(email: String)=client.call("auth/login/",JSONObject().put("email",email).put("password","12345678"))
        val account=login(args.getString("packaged_email")!!)
        val token=account.getString("access_token")
        val owner=account.getJSONObject("user").getString("id")
        val name="packaged-rules-${UUID.randomUUID()}.db"
        val db=LedgerDatabases.acquire(app,name)
        try {
            val repo=LedgerRepository(db,owner);repo.seedDefaults()
            val cat=db.ledger().categorySnapshot(owner).first {it.type=="expense"}
            val entry=repo.save(null,"expense",cat.id,"12.34",LocalDate.of(2025,1,2),"模拟联调",title="早餐")
            val store=NamingRuleStore(db,name,owner)
            store.save(SmartNamingRule(UUID.randomUUID().toString(),name,owner,"早餐",cat.id,channel="支付宝",allowAutomatic=true))
            val notice=PaymentNotice(UUID.randomUUID().toString(),"支付宝","2025-01-02",1234,"expense","模拟食堂","模拟联调",false,false,false,transactionAt=System.currentTimeMillis(),channel="支付宝")
            assertTrue(store.recordConfirmation(notice))
            val original=BackupStore(db,owner).export()
            assertEquals(5,original.getInt("format_version"))
            suspend fun upload(base: Int,payload: JSONObject)=client.call("backup/",JSONObject().put("operation_id",UUID.randomUUID().toString()).put("base_revision",base).put("payload",payload),token)
            assertEquals(1,upload(0,original).getInt("revision"))
            repo.save(entry.id,"expense",cat.id,"24.68",LocalDate.of(2025,1,2),"模拟第二版",title="午餐")
            assertEquals(2,upload(1,BackupStore(db,owner).export()).getInt("revision"))
            val before=db.ledger().allEntries(owner);val pending=db.ledger().pending(owner)
            val history=client.call("backup/history/",token=token).getJSONArray("items")
            assertEquals(2,history.length())
            val old=client.call("backup/download/?revision=1",token=token).getJSONObject("payload")
            assertEquals(PortableSnapshot.parse(original,owner),PortableSnapshot.parse(old,owner))
            val book=HistoryRecovery.create(app,old,1,"模拟EXE历史")
            val copyName=book.getString("database");val copyOwner=book.getString("owner")
            val copy=LedgerDatabases.acquire(app,copyName)
            try {
                assertEquals(1234L,copy.ledger().allEntries(copyOwner).single().amountFen)
                val restored=NamingRuleStore(copy,copyName,copyOwner).snapshot().single()
                assertEquals("早餐",restored.rule.title)
                assertEquals(1,restored.trusted.size)
                assertEquals(copyName,restored.rule.databaseName)
                assertEquals(copyOwner,restored.rule.owner)
                assertTrue(SmartRuleMatcher.evaluate(copyName,copyOwner,notice,listOf(restored.rule),restored.trusted) is NamingDecision.Automatic)
            } finally {LedgerDatabases.release(copyName)}
            assertEquals(before,db.ledger().allEntries(owner));assertEquals(pending,db.ledger().pending(owner))
            assertEquals(2,client.call("backup/latest/",token=token).getInt("revision"))
            val other=login(args.getString("packaged_other_email")!!).getString("access_token")
            assertEquals(0,client.call("backup/history/",token=other).getJSONArray("items").length())
            val error=runCatching {client.call("backup/download/?revision=1",token=other)}.exceptionOrNull()
            assertTrue(error is BackupApiException && error.status==404)
        } finally {LedgerDatabases.release(name)}
    }
}
