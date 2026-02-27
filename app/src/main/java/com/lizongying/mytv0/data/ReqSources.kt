package com.lizongying.mytv0.data

data class ReqSources(
    var sourceId: String,
)

data class ReqSourceAdd(
    val id: String,
    var uri: String,
    val name: String = "",  // 新增 name 字段
    val ua: String = "",  // 新增 UA 字段
    val referrer: String = ""  // 新增 referrer 字段
)
