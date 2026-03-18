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
        if (context == null) return
        val ctx = requireContext()
        val playerView = binding.playerView

        val renderersFactory = DefaultRenderersFactory(ctx)

        // 读取用户设置的软解开关
        val isSoftDecodeEnabled = SP.softDecode

        // 设定模式：如果用户手动开启软解，则优先使用扩展渲染器（如 FFmpeg）
        renderersFactory.setExtensionRendererMode(
            if (isSoftDecodeEnabled) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        )

        // 核心逻辑：针对 Android 5.0/5.1 (API 21/22) 的精準保护
        renderersFactory.setMediaCodecSelector(object : MediaCodecSelector {
            override fun getDecoderInfos(
                mimeType: String,
                requiresSecureDecoder: Boolean,
                requiresTunnelingDecoder: Boolean
            ): MutableList<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {

                val allInfos = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)

                // 仅在 Android 5.x 上介入
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    // 如果是 H.265 (HEVC)，强制过滤硬解以防驱动崩溃
                    if (mimeType == MimeTypes.VIDEO_H265 || mimeType == "video/hevc") {
                        val swInfos = allInfos.filter { it.softwareOnly }.toMutableList()
                        Log.i(TAG, "检测到 Android 5.x 设备播放 H.265：已强制开启软解安全模式")
                        return swInfos
                    }
                    // H.264 (AVC) 保留硬解，确保低端电视播放流畅
                }

                return allInfos
            }
        })

        // 开启回退机制：如果一个解码器失败，自动尝试下一个，防止 App 闪退
        renderersFactory.setEnableDecoderFallback(true)

        player?.release()
        player = ExoPlayer.Builder(ctx).setRenderersFactory(renderersFactory).build()

        player?.repeatMode = REPEAT_MODE_ALL
        player?.playWhenReady = true

        // 设置监听器
        player?.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (playerView.measuredHeight <= 0) return
                val ratio = playerView.measuredWidth.toFloat().div(playerView.measuredHeight.toFloat())
                val layoutParams = playerView.layoutParams
                if (ratio < aspectRatio) {
                    layoutParams?.height = (playerView.measuredWidth.div(aspectRatio)).toInt()
                    playerView.layoutParams = layoutParams
                } else if (ratio > aspectRatio) {
                    layoutParams?.width = (playerView.measuredHeight.times(aspectRatio)).toInt()
                    playerView.layoutParams = layoutParams
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                if (tvModel == null) return
                val tv = tvModel!!
                if (isPlaying) {
                    tv.confirmSourceType()
                    tv.confirmVideoIndex()
                    tv.setErrInfo("")
                    tv.retryTimes = 0
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
                val tv = tvModel ?: return

                Log.e(TAG, "播放错误: ${error.message}")
                error.printStackTrace()

                if (tv.retryTimes < tv.retryMaxTimes) {
                    var last = true
                    if (tv.getSourceTypeDefault() == SourceType.UNKNOWN) {
                        last = tv.nextSourceType()
                    }
                    tv.setReady(true)
                    if (last) tv.retryTimes++
                    Log.i(TAG, "重试 ${tv.videoIndex.value} ${tv.getSourceTypeCurrent()} ${tv.retryTimes}/${tv.retryMaxTimes}")
                } else {
                    if (!tv.isLastVideo()) {
                        tv.nextVideo()
                        tv.setReady(true)
                        tv.retryTimes = 0
                    } else {
                        tv.setErrInfo(getString(R.string.play_error))
                    }
                }
            }
        })

        playerView.player = player
        tvModel?.let { play(it) }
    }

    @OptIn(UnstableApi::class)
    fun play(tvModel: TVModel) {
        this.tvModel?.releaseMulticastLock()
        this.tvModel = tvModel
        tvModel.setContext(requireContext())

        player?.run {
            val mediaItem = tvModel.getMediaItem() ?: return
            val mediaSource = tvModel.getMediaSource()
            if (mediaSource != null) setMediaSource(mediaSource) else setMediaItem(mediaItem)
            prepare()
        }
    }

    // 音量控制相关方法
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