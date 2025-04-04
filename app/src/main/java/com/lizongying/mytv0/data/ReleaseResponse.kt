package com.lizongying.mytv0.data

import com.google.gson.annotations.SerializedName


data class ReleaseResponse(
    @SerializedName("version_code")
    val version_code: Long,  // 必须与服务端JSON的key一致

    @SerializedName("version_name")
    val version_name: String,

    @SerializedName("release_notes")
    val release_notes: String,

    @SerializedName("apk_name")
    val apk_name: String,

    @SerializedName("apk_url")
    val apk_url: String
)