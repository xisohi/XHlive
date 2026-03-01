package com.lizongying.mytv0.models

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.lizongying.mytv0.IgnoreSSLCertificate
import com.lizongying.mytv0.SP
import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.data.MulticastLockManager
import com.lizongying.mytv0.data.Program
import com.lizongying.mytv0.data.RtpDataSourceFactory
import com.lizongying.mytv0.data.Source
import com.lizongying.mytv0.data.SourceType
import com.lizongying.mytv0.data.TV
import kotlin.math.max
import kotlin.math.min

class TVModel(var tv: TV) : ViewModel() {
    private var appContext: Context? = null
    private var multicastLockManager: MulticastLockManager? = null

    // 新增：当前源的信息（包含UA和Referrer）
    private var currentSource: Source? = null

    fun setContext(context: Context) {
        this.appContext = context.applicationContext
        this.multicastLockManager = MulticastLockManager(context.applicationContext)
    }

    // 新增：设置当前源
    fun setCurrentSource(source: Source?) {
        this.currentSource = source
    }

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

    private var _program = MutableLiveData<MutableList<Program>>()
    val program: LiveData<MutableList<Program>>
        get() = _program

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

    private var userAgent = ""
    private var referrer = ""

    private var _httpDataSource: DataSource.Factory? = null
    private var _mediaItem: MediaItem? = null

    @OptIn(UnstableApi::class)
    fun getMediaItem(): MediaItem? {
        _mediaItem = getVideoUrl()?.let {
            val uri = Uri.parse(it) ?: return@let null
            val path = uri.path ?: return@let null
            val scheme = uri.scheme ?: return@let null

            IgnoreSSLCertificate.ignore()
            val defaultHttpDataSource = DefaultHttpDataSource.Factory()
            defaultHttpDataSource.setKeepPostFor302Redirects(true)
            defaultHttpDataSource.setAllowCrossProtocolRedirects(true)

            // 构建请求头
            val headers = mutableMapOf<String, String>()

            // 优先使用当前源的headers
            currentSource?.let { source ->
                if (source.ua.isNotEmpty()) {
                    headers["User-Agent"] = source.ua
                    userAgent = source.ua
                    Log.d(TAG, "Using UA from source: ${source.ua}") // 添加调试日志
                }
                if (source.referrer.isNotEmpty()) {
                    headers["Referer"] = source.referrer
                    referrer = source.referrer
                    Log.d(TAG, "Using Referrer from source: ${source.referrer}") // 添加调试日志
                }
            }

            // 如果TV有自己的headers，合并进去
            tv.headers?.let { tvHeaders ->
                tvHeaders.forEach { (key, value) ->
                    if (!headers.containsKey(key)) {
                        headers[key] = value
                        when {
                            key.equals("user-agent", ignoreCase = true) -> {
                                userAgent = value
                                Log.d(TAG, "Using UA from TV: $value")
                            }
                            key.equals("referer", ignoreCase = true) || key.equals("referrer", ignoreCase = true) -> {
                                referrer = value
                                Log.d(TAG, "Using Referrer from TV: $value")
                            }
                        }
                    }
                }
            }

            // 设置所有请求头
            if (headers.isNotEmpty()) {
                defaultHttpDataSource.setDefaultRequestProperties(headers)
                Log.d(TAG, "Setting headers: $headers")
            } else {
                Log.d(TAG, "No headers set")
            }

            _httpDataSource = defaultHttpDataSource

            sourceTypeList = when {
                path.lowercase().endsWith(".m3u8") -> listOf(SourceType.HLS)
                path.lowercase().endsWith(".mpd") -> listOf(SourceType.DASH)
                scheme.lowercase() == "rtsp" -> listOf(SourceType.RTSP)
                scheme.lowercase() == "rtmp" -> listOf(SourceType.RTMP)
                scheme.lowercase() == "rtp" -> listOf(SourceType.RTP)
                else -> listOf(SourceType.HLS, SourceType.PROGRESSIVE)
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

        return when (getSourceTypeCurrent()) {
            SourceType.HLS -> {
                if (_httpDataSource == null) return null
                HlsMediaSource.Factory(_httpDataSource!!).createMediaSource(mediaItem)
            }
            SourceType.RTSP -> {
                val factory = if (userAgent.isNotEmpty()) {
                    RtspMediaSource.Factory().setUserAgent(userAgent)
                } else {
                    RtspMediaSource.Factory()
                }
                factory.createMediaSource(mediaItem)
            }
            SourceType.RTMP -> {
                val rtmpDataSource = RtmpDataSource.Factory()
                ProgressiveMediaSource.Factory(rtmpDataSource)
                    .createMediaSource(mediaItem)
            }
            SourceType.RTP -> {
                val ctx = appContext ?: return null
                multicastLockManager?.acquire()
                val rtpDataSource = RtpDataSourceFactory(ctx)
                ProgressiveMediaSource.Factory(rtpDataSource)
                    .createMediaSource(mediaItem)
            }
            SourceType.DASH -> {
                if (_httpDataSource == null) return null
                DashMediaSource.Factory(_httpDataSource!!).createMediaSource(mediaItem)
            }
            SourceType.PROGRESSIVE -> {
                if (_httpDataSource == null) return null
                ProgressiveMediaSource.Factory(_httpDataSource!!)
                    .createMediaSource(mediaItem)
            }
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
        sourceTypeList = listOf(SourceType.UNKNOWN)

        return isLastVideo()
    }

    fun update(t: TV) {
        tv = t
    }

    init {
        _videoIndex.value = max(0, min(tv.uris.size - 1, tv.videoIndex))
        _like.value = SP.getLike(tv.id)
        _program.value = mutableListOf()
    }

    companion object {
        private const val TAG = "TVModel"
    }
}