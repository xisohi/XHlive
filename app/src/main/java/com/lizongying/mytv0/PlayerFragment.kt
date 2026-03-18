package com.lizongying.mytv0

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.lizongying.mytv0.data.SourceType
import com.lizongying.mytv0.databinding.PlayerBinding
import com.lizongying.mytv0.models.TVModel
import android.content.Context
import com.lizongying.mytv0.data.RtpDataSourceFactory

class PlayerFragment : Fragment() {
    private var _binding: PlayerBinding? = null
    private val binding get() = _binding!!

    private var player: ExoPlayer? = null

    private var tvModel: TVModel? = null
    private val aspectRatio = 16f / 9f

    private val handler = Handler(Looper.myLooper()!!)
    private val delayHideVolume = 2 * 1000L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = PlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updatePlayer()
        (activity as MainActivity).ready(TAG)
    }

    @OptIn(UnstableApi::class)
    fun updatePlayer() {
        if (context == null) {
            Log.e(TAG, "context == null")
            return
        }

        val ctx = requireContext()
        val playerView = binding.playerView

        // 创建渲染工厂
        val renderersFactory = DefaultRenderersFactory(ctx)

        // ========== Android 5.x (API 21-22) 特殊处理 ==========
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) {  // API 21,22 (Android 5.0, 5.1, 5.1.1)
            Log.i(TAG, "========== Android 5.x (API ${Build.VERSION.SDK_INT}) 检测到，使用兼容模式 ==========")

            // 5.x强制使用软解
            renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

            // 5.x只使用软件解码器
            renderersFactory.setMediaCodecSelector(object : MediaCodecSelector {
                override fun getDecoderInfos(
                    mimeType: String,
                    requiresSecureDecoder: Boolean,
                    requiresTunnelingDecoder: Boolean
                ): MutableList<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {

                    val allInfos = MediaCodecUtil.getDecoderInfos(
                        mimeType,
                        requiresSecureDecoder,
                        requiresTunnelingDecoder
                    )

                    Log.i(TAG, "5.x 可用解码器 for $mimeType: ${allInfos.size}")
                    allInfos.forEachIndexed { index, info ->
                        Log.i(TAG, "  [$index] ${info.name} - swOnly=${info.softwareOnly}")
                    }

                    // 只保留软件解码器
                    val swInfos = allInfos.filter { it.softwareOnly }.toMutableList()
                    return if (swInfos.isNotEmpty()) swInfos else allInfos
                }
            })
        } else {
            // Android 6.0+ 正常逻辑
            Log.i(TAG, "Android 6.0+ (API ${Build.VERSION.SDK_INT}) 使用正常模式")
            renderersFactory.setExtensionRendererMode(
                if (SP.softDecode) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
            renderersFactory.setMediaCodecSelector(PlayerMediaCodecSelector())
        }

        // 在所有版本都开启解码器回退
        renderersFactory.setEnableDecoderFallback(true)

        if (player != null) {
            player?.release()
        }

        player = ExoPlayer.Builder(ctx)
            .setRenderersFactory(renderersFactory)
            .build()

        player?.repeatMode = REPEAT_MODE_ALL
        player?.playWhenReady = true
        player?.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (playerView.measuredHeight <= 0) return
                val ratio = playerView.measuredWidth.toFloat().div(playerView.measuredHeight.toFloat())
                val layoutParams = playerView.layoutParams
                if (ratio < aspectRatio) {
                    layoutParams?.height =
                        (playerView.measuredWidth.div(aspectRatio)).toInt()
                    playerView.layoutParams = layoutParams
                } else if (ratio > aspectRatio) {
                    layoutParams?.width =
                        (playerView.measuredHeight.times(aspectRatio)).toInt()
                    playerView.layoutParams = layoutParams
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)

                if (tvModel == null) {
                    Log.e(TAG, "tvModel == null")
                    return
                }

                val tv = tvModel!!

                if (isPlaying) {
                    tv.confirmSourceType()
                    tv.confirmVideoIndex()
                    tv.setErrInfo("")
                    tv.retryTimes = 0

                    val ua = tv.getUserAgent()
                    val hasCustomUA = tv.hasCustomUserAgent()
                    Log.i(TAG, "播放成功: ${tv.tv.title}, 自定義UA: $hasCustomUA")
                } else {
                    Log.i(TAG, "${tv.tv.title} 播放停止")
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    (activity as MainActivity).onPlayEnd()
                }
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)

                if (tvModel == null) {
                    Log.e(TAG, "tvModel == null")
                    return
                }

                val tv = tvModel!!

                // 5.x特殊错误处理
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    Log.e(TAG, "5.x 播放错误: ${error.message}", error)
                    // 直接尝试下一个源
                    if (!tv.isLastVideo()) {
                        tv.nextVideo()
                        tv.setReady(true)
                        tv.retryTimes = 0
                        return
                    }
                }

                if (tv.retryTimes < tv.retryMaxTimes) {
                    var last = true
                    if (tv.getSourceTypeDefault() == SourceType.UNKNOWN) {
                        last = tv.nextSourceType()
                    }
                    tv.setReady(true)
                    if (last) {
                        tv.retryTimes++
                    }
                    Log.i(
                        TAG,
                        "retry ${tv.videoIndex.value} ${tv.getSourceTypeCurrent()} ${tv.retryTimes}/${tv.retryMaxTimes}"
                    )
                } else {
                    if (!tv.isLastVideo()) {
                        tv.nextVideo()
                        tv.setReady(true)
                        tv.retryTimes = 0
                    } else {
                        tv.setErrInfo(R.string.play_error.getString())
                    }
                }
            }
        })

        playerView.player = player
        tvModel?.let {
            play(it)
        }
    }

    @OptIn(UnstableApi::class)
    fun play(tvModel: TVModel) {
        this.tvModel?.releaseMulticastLock()
        this.tvModel = tvModel
        tvModel.setContext(requireContext())

        // 添加播放信息日志
        Log.i(TAG, "准备播放: ${tvModel.tv.title}")
        Log.i(TAG, "视频地址: ${tvModel.getVideoUrl()}")
        Log.i(TAG, "Android版本: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")

        player?.run {
            tvModel.getVideoUrl() ?: return

            while (true) {
                val last = tvModel.isLastVideo()
                val mediaItem = tvModel.getMediaItem()
                if (mediaItem == null) {
                    if (last) {
                        tvModel.setErrInfo(R.string.play_error.getString())
                        break
                    }
                    tvModel.nextVideo()
                    continue
                }
                val mediaSource = tvModel.getMediaSource()
                if (mediaSource != null) {
                    setMediaSource(mediaSource)
                } else {
                    setMediaItem(mediaItem)
                }
                prepare()
                break
            }
        }
    }

    // --- 修正后的MediaCodec选择器（用于Android 6.0+）---
    @OptIn(UnstableApi::class)
    class PlayerMediaCodecSelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): MutableList<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
            // 直接返回系统推荐的所有解码器，让ExoPlayer根据性能自动选择
            return MediaCodecUtil.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
        }
    }

    fun showVolume(visibility: Int) {
        binding.icon.visibility = visibility
        binding.volume.visibility = visibility
        hideVolume()
    }

    fun setVolumeMax(volume: Int) {
        binding.volume.max = volume
    }

    fun setVolume(progress: Int, volume: Boolean = false) {
        val context = requireContext()
        binding.volume.progress = progress
        binding.icon.setImageDrawable(
            ContextCompat.getDrawable(
                context,
                if (volume) {
                    if (progress > 0) R.drawable.volume_up_24px else R.drawable.volume_off_24px
                } else {
                    R.drawable.light_mode_24px
                }
            )
        )
    }

    fun hideVolume() {
        handler.removeCallbacks(hideVolumeRunnable)
        handler.postDelayed(hideVolumeRunnable, delayHideVolume)
    }

    fun hideVolumeNow() {
        handler.removeCallbacks(hideVolumeRunnable)
        handler.postDelayed(hideVolumeRunnable, 0)
    }

    private val hideVolumeRunnable = Runnable {
        binding.icon.visibility = View.GONE
        binding.volume.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (player?.isPlaying == false) {
            player?.prepare()
            player?.play()
        }
    }

    override fun onPause() {
        super.onPause()
        if (player?.isPlaying == true) {
            player?.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tvModel?.releaseMulticastLock()
        player?.release()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "PlayerFragment"
    }
}