package com.personal.ledger.data

import org.json.JSONObject

object BackupCompatibility {
    fun requireSupported(health: JSONObject, requiredFormat: Int = 2) {
        val formats = health.optJSONArray("backup_formats")
        require(formats != null && (0 until formats.length()).any { formats.optInt(it) == requiredFormat }) {
            "本次备份未成功：电脑端版本较旧或未声明支持新版备份。请退出旧电脑程序，打开支持备份格式${requiredFormat}的新版电脑程序（含预算需0.7.0或更新版），重新扫码后再备份；手机账目保留"
        }
    }
}
