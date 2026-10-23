package com.personal.ledger.data

import com.personal.ledger.domain.Rules
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Receipt metadata is separate from the time of the underlying transaction. */
data class BankSmsNotice(val notice: PaymentNotice, val transactionAt: Long,
    val channel: String, val cardTail: String)

/** Only the bank sender and supported bank transaction templates are accepted. */
object BankSmsParser {
    fun parse(sender: String, text: String, sentAt: Long, receivedAt: Long): BankSmsNotice? {
        if (sender.trim().removePrefix("+86") != "95555" ||
            !text.startsWith("【招商银行】") || text.length > 2000 || sentAt <= 0 || receivedAt <= 0) return null
        if (listOf("验证码", "动态码", "校验码", "失败", "未成功", "待支付", "申请", "优惠", "额度提升").any(text::contains)) return null
        val card = Regex("您账户([0-9]{4})(?![0-9])").find(text)?.groupValues?.get(1) ?: return null
        val stamp = Regex("于([0-9]{1,2})月([0-9]{1,2})日\\s*([0-9]{1,2}):([0-9]{2})").find(text) ?: return null
        val received = Instant.ofEpochMilli(receivedAt).atZone(Rules.zone)
        // Choose the nearest valid year, including a delayed New Year's Eve message.
        val transaction = (received.year - 1..received.year + 1).mapNotNull { year ->
            runCatching { LocalDateTime.of(year, stamp.groupValues[1].toInt(), stamp.groupValues[2].toInt(),
                stamp.groupValues[3].toInt(), stamp.groupValues[4].toInt()).atZone(Rules.zone) }.getOrNull()
        }.minByOrNull { kotlin.math.abs(ChronoUnit.SECONDS.between(it, received)) } ?: return null
        if (transaction.isAfter(received.plusMinutes(5)) || transaction.isBefore(received.minusDays(7))) return null
        val body = text.substring(stamp.range.last + 1)
        val excluded = listOf("本人账户互转", "本人账户转账", "充值", "提现", "取现", "取款").any(body::contains)
        val refund = listOf("退款", "退货", "消费撤销").any(body::contains)
        val income = refund || listOf("存入", "收入", "工资入账").any(body::contains)
        val expense = listOf("快捷支付", "消费", "支出").any(body::contains) && !refund
        if (!excluded && !income && !expense) return null
        // Match only a transaction verb immediately before its amount; never use balance.
        val values = Regex("(?:快捷支付|消费撤销|消费|支出|退款|退货|存入|收入|工资入账|充值|提现|取现|取款)(?:人民币)?\\s*([0-9]+(?:\\.[0-9]{1,2})?)(?![0-9.])元")
            .findAll(body).mapNotNull { runCatching { Rules.amount(it.groupValues[1]) }.getOrNull() }.toList()
        val amount = values.singleOrNull()
        val type = when { income && !expense -> "income"; expense && !income -> "expense"; else -> null }
        val channel = when { body.contains("微信支付") || body.contains("财付通") -> "微信"
            body.contains("支付宝") -> "支付宝"; else -> "银行卡" }
        val merchant = Regex("^在(.+?)(?:快捷支付|消费|支出)").find(body)?.groupValues?.get(1)
            ?.removePrefix("财付通-微信支付-")?.removePrefix("微信支付-")?.removePrefix("支付宝-")?.trim()?.take(80).orEmpty()
        val id = UUID.nameUUIDFromBytes("bank-sms|95555|$sentAt|$text".toByteArray(Charsets.UTF_8)).toString()
        // Keep transaction evidence only; balances and unrelated message text are not persisted.
        val note = "招商银行 · 尾号$card · $channel\n${transaction.toLocalDateTime()} · ${if(refund) "退款" else if(income) "收入" else "消费"} · $merchant"
        return BankSmsNotice(PaymentNotice(id, "招商银行短信", transaction.toLocalDate().toString(), amount,
            type, merchant, note, refund, excluded, amount == null || type == null,
            source="sms", transactionAt=transaction.toInstant().toEpochMilli(), channel=channel, cardTail=card),
            transaction.toInstant().toEpochMilli(), channel, card)
    }
}
