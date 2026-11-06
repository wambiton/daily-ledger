package com.personal.ledger.data

/** Explain saved evidence without replaying today's rules or changing trust. */
object NamingExplanation {
    fun describe(c: PaymentCandidate,rules: List<SmartNamingRule>): List<String> {
        if(c.ruleSignature.isBlank()) return listOf("此记录未保留可核对的命名规则。请以记录的处理原因和来源为准，不代表当前没有可用规则。")
        val rule=rules.singleOrNull {it.databaseName==c.databaseName && it.owner==c.owner && it.signature()==c.ruleSignature}
            ?: return listOf("原命名规则已修改、删除或不可用，无法还原当时完整条件；不会用当前规则改写这条记录。")
        fun time(m: Int)="%02d:%02d".format(m/60,m%60)
        val days=listOf("一","二","三","四","五","六","日")
        val period=if(rule.startMinute==0 && rule.endMinute==1440) "全天" else time(rule.startMinute)+"—"+time(rule.endMinute)+if(rule.startMinute>rule.endMinute) "（跨日，按开始日计算星期）" else ""
        return listOf(
            "记录关联规则：${rule.title}",
            "渠道：${rule.channel.ifBlank {"不限"}}；商户：${rule.merchant.ifBlank {"不限"}}",
            "时段：$period；星期：${rule.weekdays.sorted().joinToString("、") {days[it-1]}}",
            "优先级：${rule.priority}（数值较大优先；同级优先精确商户和渠道，仍冲突则待确认）",
            if(rule.allowAutomatic) "规则允许后续自动，但仍需明确商户的首次确认；这条说明不表示本次已经自动获准。" else "此规则只建议名称，每次需要确认。",
            "以上为与记录指纹一致的规则定义；当前已确认状态可能变化，不据此倒推当时授权。"
        )
    }
}
