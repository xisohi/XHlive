package com.lizongying.mytv0.models

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.lizongying.mytv0.SP
import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.data.MulticastLockManager
import com.lizongying.mytv0.data.RtpDataSourceFactory
import com.lizongying.mytv0.data.SourceType
import com.lizongying.mytv0.data.TV
import com.lizongying.mytv0.requests.HttpClient
import kotlin.math.max
import kotlin.math.min

class TVModel(var tv: TV) : ViewModel() {
    private var appContext: Context? = null
    private var multicastLockManager: MulticastLockManager? = null

    fun setContext(context: Context) {
        this.appContext = context.applicationContext
        this.multicastLockManager = MulticastLockManager(context.applicationContext)
    }

    // 🆕 释放组播锁（切换频道或销毁时调用）
    fun releaseMulticastLock() {
        multicastLockManager?.release()
    }

    var retryTimes = 0
    var retryMaxTimes = 10
    var programUpdateTime = 0L

    private var _groupIndex = 0
    val groupIndex: Int
        get() = if (SP.showAllChannels || _groupIndex == 0) _groupIndex else _groupIndex - 1

    fun setGroupIndex(index: Int) {
        _groupIndex = index
    }

    fun getGroupIndexInAll(): Int {
        return _groupIndex
    }

    var listIndex = 0

    private var sourceTypeList: List<SourceType> =
        listOf(
            SourceType.UNKNOWN,
        )
    private var sourceTypeIndex = 0

    private val _errInfo = MutableLiveData<String>()
    val errInfo: LiveData<String>
        get() = _errInfo

    fun setErrInfo(info: String) {
        _errInfo.value = info
    }

    private var _epg = MutableLiveData<List<EPG>>()
    val epg: LiveData<List<EPG>>
        get() = _epg
    val epgValue: List<EPG>
        get() = _epg.value ?: emptyList()

    fun setEpg(epg: List<EPG>) {
        _epg.value = epg
    }

    private val _videoIndex = MutableLiveData<Int>()
    val videoIndex: LiveData<Int>
        get() = _videoIndex
    val videoIndexValue: Int
        get() = _videoIndex.value ?: 0

    fun getVideoUrl(): String? {
        if (videoIndexValue >= tv.uris.size) {
            return null
        }

        return tv.uris[videoIndexValue]
    }

    private val _like = MutableLiveData<Boolean>()
    val like: LiveData<Boolean>
        get() = _like

    fun setLike(liked: Boolean) {
        _like.value = liked
    }

    private val _ready = MutableLiveData<Boolean>()
    val ready: LiveData<Boolean>
        get() = _ready

    fun setReady(retry: Boolean = false) {
        if (!retry) {
            setErrInfo("")
            retryTimes = 0

            _videoIndex.value = max(0, min(tv.uris.size - 1, tv.videoIndex))
            sourceTypeIndex =
                max(0, min(sourceTypeList.size - 1, sourceTypeList.indexOf(tv.sourceType)))
        }
        _ready.value = true
    }

    // 添加默认UA常量 - 放在 companion object 中
    private var userAgent = DEFAULT_USER_AGENT
    private var allHeaders: Map<String, String>? = null

    private var _httpDataSource: DataSource.Factory? = null
    private var _mediaItem: MediaItem? = null

    /**
     * 获取当前频道的User-Agent
     */
    fun getUserAgent(): String {
        return userAgent
    }

    /**
     * 获取当前频道的所有请求头
     */
    fun getAllHeaders(): Map<String, String> {
        return allHeaders ?: emptyMap()
    }

    /**
     * 检查是否有自定义UA
     */
    fun hasCustomUserAgent(): Boolean {
        return userAgent != DEFAULT_USER_AGENT
    }

    @OptIn(UnstableApi::class)
    fun getMediaItem(): MediaItem? {
        _mediaItem = getVideoUrl()?.let {
            val uri = Uri.parse(it) ?: return@let null
            val path = uri.path ?: return@let null
            val scheme = uri.scheme ?: return@let null

            val okHttpDataSource = OkHttpDataSource.Factory(HttpClient.okHttpClient)

            // 处理headers，包括UA
            tv.headers?.let { headers ->
                // 保存所有headers供外部使用
                allHeaders = headers

                // 设置默认请求属性
                okHttpDataSource.setDefaultRequestProperties(headers)

                // 提取User-Agent
                headers.forEach { (key, value) ->
                    if (key.equals("user-agent", ignoreCase = true)) {
                        userAgent = value
                        return@forEach
                    }
                }
            }

            _httpDataSource = okHttpDataSource

            sourceTypeList = if (path.lowercase().endsWith(".m3u8")) {
                listOf(SourceType.HLS)
            } else if (path.lowercase().endsWith(".mpd")) {
                listOf(SourceType.DASH)
            } else if (scheme.lowercase() == "rtsp") {
                listOf(SourceType.RTSP)
            } else if (scheme.lowercase() == "rtmp") {
                listOf(SourceType.RTMP)
            } else if (scheme.lowercase() == "rtp") {
                listOf(SourceType.RTP)
            } else {
                listOf(SourceType.HLS, SourceType.PROGRESSIVE)
            }

            MediaItem.fromUri(it)
        }
        return _mediaItem
    }

    fun getSourceTypeDefault(): SourceType {
        return tv.sourceType
    }

    fun getSourceTypeCurrent(): SourceType {
        sourceTypeIndex = max(0, min(sourceTypeList.size - 1, sourceTypeIndex))
        return sourceTypeList[sourceTypeIndex]
    }

    fun nextSourceType(): Boolean {
        sourceTypeIndex = (sourceTypeIndex + 1) % sourceTypeList.size

        return sourceTypeIndex == sourceTypeList.size - 1
    }

    fun confirmSourceType() {
        // TODO save default sourceType
        tv.sourceType = getSourceTypeCurrent()
    }

    fun confirmVideoIndex() {
        tv.videoIndex = videoIndexValue
    }

    @OptIn(UnstableApi::class)
    fun getMediaSource(): MediaSource? {
        if (sourceTypeList.isEmpty()) {
            return null
        }

        if (_mediaItem == null) {
            return null
        }
        val mediaItem = _mediaItem!!

        if (_httpDataSource == null) {
            return null
        }
        val httpDataSource = _httpDataSource!!

        return when (getSourceTypeCurrent()) {
            SourceType.HLS -> HlsMediaSource.Factory(httpDataSource).createMediaSource(mediaItem)
            SourceType.RTSP -> if (userAgent.isEmpty()) {
                RtspMediaSource.Factory().createMediaSource(mediaItem)
            } else {
                RtspMediaSource.Factory().setUserAgent(userAgent).createMediaSource(mediaItem)
            }

            SourceType.RTMP -> {
                val rtmpDataSource = RtmpDataSource.Factory()
                ProgressiveMediaSource.Factory(rtmpDataSource)
                    .createMediaSource(mediaItem)
            }

            SourceType.RTP -> {
                val ctx = appContext ?: return null
                multicastLockManager?.acquire()  // 🆕 获取组播锁

                val rtpDataSource = RtpDataSourceFactory(ctx)
                ProgressiveMediaSource.Factory(rtpDataSource)
                    .createMediaSource(mediaItem)
            }

            SourceType.DASH -> DashMediaSource.Factory(httpDataSource).createMediaSource(mediaItem)
            SourceType.PROGRESSIVE -> ProgressiveMediaSource.Factory(httpDataSource)
                .createMediaSource(mediaItem)

            else -> null
        }
    }

    fun isLastVideo(): Boolean {
        return videoIndexValue == tv.uris.size - 1
    }

    fun nextVideo(): Boolean {
        if (tv.uris.isEmpty()) {
            return false
        }

        _videoIndex.value = (videoIndexValue + 1) % tv.uris.size
        sourceTypeList = listOf(
            SourceType.UNKNOWN,
        )

        return isLastVideo()
    }

    fun update(t: TV) {
        tv = t
    }

    init {
        _videoIndex.value = max(0, min(tv.uris.size - 1, tv.videoIndex))
        _like.value = SP.getLike(tv.id)
    }

    companion object {
        private const val TAG = "TVModel"
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}