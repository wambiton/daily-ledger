package com.personal.ledger.domain

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

object Rules {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    fun today(): LocalDate = LocalDate.now(zone)
    fun amount(text: String): Long {
        require(Regex("[0-9]{1,9}(\\.[0-9]{1,2})?").matches(text)) { "请输入最多两位小数的金额" }
        return BigDecimal(text).movePointRight(2).longValueExact().also {
            require(it in 1..99_999_999_999L) { "金额必须大于零且不超过 999999999.99" }
        }
    }
    fun money(fen: Long): String = BigDecimal.valueOf(fen, 2).toPlainString()
    fun category(name: String): String = name.trim().also {
        require(it.codePointCount(0, it.length) in 1..20) { "分类名称需要 1—20 个字" }
    }
    fun note(value: String) { require(value.codePointCount(0, value.length) <= 500) { "备注不能超过 500 字" } }
    fun date(value: LocalDate) { require(!value.isAfter(today())) { "暂不支持未来日期" } }
}
