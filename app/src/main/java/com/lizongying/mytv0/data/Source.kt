package com.lizongying.mytv0.data

import java.util.UUID

data class Source(
    var id: String? = null,
    var uri: String,
    var name: String = "",  // 新增：显示名称，空时自动从uri识别
    var ua: String = "",  // 新增：User-Agent字段
    var referrer: String = "",  // 新增 referrer 字段
    var checked: Boolean = false,
) {
    init {
        if (id.isNullOrEmpty()) {
            id = UUID.randomUUID().toString()
        }
    }
}
