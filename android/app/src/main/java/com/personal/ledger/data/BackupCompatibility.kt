package com.personal.ledger.data

import org.json.JSONObject

object BackupCompatibility {
    fun requireSupported(health: JSONObject) {
        val formats = health.optJSONArray("backup_formats")
        require(formats != null && (0 until formats.length()).any { formats.optInt(it) == 2 }) {
            "本次备份未成功：电脑端版本较旧或未声明支持新版备份。请退出旧电脑程序，打开0.6.1或更新版，重新扫码后再备份；手机账目保留"
        }
    }
}
