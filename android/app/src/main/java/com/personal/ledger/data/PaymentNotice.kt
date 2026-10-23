package com.personal.ledger.data

import com.personal.ledger.domain.Rules
import java.time.Instant
import java.util.UUID

data class PaymentNotice(val id: String, val platform: String, val date: String,
    val amountFen: Long?, val type: String?, val merchant: String, val note: String,
    val refund: Boolean, val excluded: Boolean, val uncertain: Boolean,
    val source: String = "notification", val transactionAt: Long = 0, val channel: String = "", val cardTail: String = "")

/** Only platform-shaped payment notices are candidates. Never store arbitrary chat text. */
object PaymentNoticeParser {
    val packages=setOf("com.tencent.mm","com.eg.android.AlipayGphone")
    fun parse(pkg: String, key: String, posted: Long, title: String, text: String): PaymentNotice? {
        if(pkg !in packages || title.length > 100 || text.length > 4000) return null
        val platform=if(pkg=="com.tencent.mm") "微信" else "支付宝"
        if(title.trim() !in setOf("微信支付","支付宝","支付成功","付款成功","收款通知","退款通知","支付宝通知")) return null
        if(text.contains("验证码") || text.contains("付款码") || text.contains("待支付") || text.contains("支付失败")) return null
        val refund=listOf("退款成功","退款到账","已退款").any{text.contains(it)}
        val expense=listOf("支付成功","付款成功","成功支付","支出","已支付","已付款").any {text.contains(it) || title==it}
        val income=listOf("收款成功","已收款","收到红包","收入","收款金额").any{text.contains(it)}
        val excluded=listOf("充值成功","提现成功","余额宝转入","余额宝转出","零钱通转入","零钱通转出","本人账户互转").any{text.contains(it)}
        if(!refund && !expense && !income && !excluded) return null
        val amounts=Regex("(?:[¥￥]\\s*([0-9]+(?:\\.[0-9]{1,2})?)(?![0-9.])|(?<![0-9.])([0-9]+(?:\\.[0-9]{1,2})?)\\s*元)")
            .findAll(text).mapNotNull {m -> runCatching {Rules.amount(m.groupValues[1].ifEmpty{m.groupValues[2]})}.getOrNull()}.toList()
        // Repeated/multiple monetary fields may be original amount, discount or balance; never guess.
        val amount=amounts.singleOrNull()
        val type=when {refund -> "income"; expense && !income -> "expense"; income && !expense -> "income"; else -> null}
        val merchant=Regex("(?:收款方|付款方|商户名称|商户|交易对方)[:：]\\s*([^\\n；;]+)").find(text)?.groupValues?.get(1)?.trim()?.take(80).orEmpty()
        val day=Instant.ofEpochMilli(posted).atZone(Rules.zone).toLocalDate().toString()
        val orderFields=if(refund) "退款单号|退款订单号" else "交易单号|交易号"
        val order=Regex("(?:$orderFields)[:：]\\s*([0-9]{16,64})(?![0-9])").find(text)?.groupValues?.get(1)
        val identity=if(order!=null) "$pkg|$order|$refund" else "$pkg|$key|$posted"
        val id=UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString()
        return PaymentNotice(id,platform,day,amount,type,merchant,("$platform · $title\n$text").take(500),refund,excluded,
            amount==null || type==null || text.contains("转账") || text.contains("余额") || text.contains("汇总"), transactionAt=posted, channel=platform)
    }
}
