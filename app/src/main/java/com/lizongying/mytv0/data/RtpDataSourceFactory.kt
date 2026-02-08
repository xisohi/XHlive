package com.lizongying.mytv0.data

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource

/**
 * RTP/UDP组播数据源工厂
 */
@UnstableApi
class RtpDataSourceFactory(private val context: Context) : DataSource.Factory {
    override fun createDataSource(): DataSource = RtpUdpDataSource.create(context)
}