package com.personal.ledger

import com.personal.ledger.data.BankSmsParser
import com.personal.ledger.domain.Rules
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class BankSmsParserTest {
    private val now = LocalDateTime.of(2026,10,13,12,0).atZone(Rules.zone).toInstant().toEpochMilli()
    private fun parse(body: String, sender: String = "95555") = BankSmsParser.parse(sender,
        "【招商银行】您账户2468于10月13日11:21$body", now - 1000, now)

    @Test fun extractsPaymentAndChannelWithoutBalance() {
        val result = requireNotNull(parse("在支付宝-测试食堂快捷支付12.30元，余额900.00元"))
        assertEquals(1230L, result.notice.amountFen)
        assertEquals("支付宝", result.channel)
        assertEquals("测试食堂", result.notice.merchant)
        assertFalse(result.notice.uncertain)
        assertFalse(result.notice.note.contains("900.00"))
        assertEquals("微信", parse("在财付通-微信支付-测试商户快捷支付5.60元")!!.channel)
        assertEquals("银行卡", parse("在测试商店消费8.00元")!!.channel)
    }
    @Test fun incomeAndRefundDoNotReduceExpenses() {
        assertEquals("income", parse("存入200.00元，余额800.00元")!!.notice.type)
        val refund = parse("退款12.30元")!!.notice
        assertTrue(refund.refund)
        assertEquals("income", refund.type)
    }
    @Test fun excludesTransfersRechargeAndWithdrawal() {
        for (body in listOf("本人账户互转支出20.00元", "充值20.00元", "提现20.00元", "取款20.00元"))
            assertTrue(requireNotNull(parse(body)).notice.excluded)
    }
    @Test fun rejectsUnknownSendersOtpAndAmbiguousAmounts() {
        assertNull(parse("消费5.00元", "10086"))
        assertNull(parse("验证码123456，消费5.00元"))
        assertNull(parse("消费失败5.00元"))
        assertNull(parse("转账20.00元"))
        assertTrue(parse("消费5.00元，支出8.00元")!!.notice.uncertain)
        assertTrue(parse("消费金额不详，余额900.00元")!!.notice.uncertain)
        assertTrue(parse("消费1.234元")!!.notice.uncertain)
    }
    @Test fun stableIdentityButSeparateIdenticalPayments() {
        val body="【招商银行】您账户2468于10月13日11:21消费5.00元"
        assertEquals(BankSmsParser.parse("95555",body,now,now), BankSmsParser.parse("95555",body,now,now+1000))
        assertNotEquals(BankSmsParser.parse("95555",body,now,now)!!.notice.id,
            BankSmsParser.parse("95555",body,now+1000,now+1000)!!.notice.id)
    }
    @Test fun resolvesYearBoundaryAndRejectsFuture() {
        val january=LocalDateTime.of(2027,1,1,0,1).atZone(Rules.zone).toInstant().toEpochMilli()
        val result=BankSmsParser.parse("95555","【招商银行】您账户2468于12月31日23:59消费5.00元",january,january)
        assertEquals("2026-12-31",result!!.notice.date)
        assertNull(BankSmsParser.parse("95555","【招商银行】您账户2468于10月14日11:21消费5.00元",now,now))
    }
}
