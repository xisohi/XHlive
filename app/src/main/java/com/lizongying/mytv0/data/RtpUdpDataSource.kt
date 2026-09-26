package com.lizongying.mytv0.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.*
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * RTP over UDP组播数据源 - 优化版
 * 支持RTP/TS流：UDP包 → RTP头(12字节) → MPEG-TS包(188字节) → H.264视频 + MP2/AAC音频
 */
@UnstableApi
class RtpUdpDataSource private constructor(
    private val context: Context,
    private val socketTimeoutMillis: Int,
    private val bufferSize: Int
) : BaseDataSource(true) {

    private var uri: Uri? = null
    private var multicastGroup: InetAddress? = null
    private var socket: MulticastSocket? = null

    // RTP解包相关
    private val rtpBuffer = ByteArray(8192)  // 🆕 增大到8KB
    private val tsPacketSize = 188
    private val rtpHeaderSize = 12

    // 数据队列（增大缓冲区）
    private val packetQueue = ArrayBlockingQueue<ByteArray>(QUEUE_SIZE)
    private var readBuffer: ByteBuffer? = null

    // 协程
    private var dataSourceJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 统计和序列号追踪
    private var bytesRead = 0L
    private var packetsReceived = 0
    private var packetsLost = 0
    private var lastSequenceNumber = -1
    private var opened = false

    // 接收循环致命错误：非空时说明流已不可恢复，read() 应抛异常触发重试
    @Volatile
    private var receiveLoopFailed: Throwable? = null

    companion object {
        const val DEFAULT_SOCKET_TIMEOUT_MILLIS = 15000  // 🆕 15秒超时
        const val DEFAULT_BUFFER_SIZE = 128 * 1024       // 🆕 128KB
        const val QUEUE_SIZE = 2000                       // 🆕 2000队列
        const val QUEUE_POLL_TIMEOUT_MS = 1000L           // read() 取包超时，避免无限阻塞
        const val DEFAULT_RTP_PORT = 5004                 // 组播默认端口
        private const val TAG = "RtpUdpDataSource"

        @JvmStatic
        fun create(context: Context): RtpUdpDataSource {
            return RtpUdpDataSource(context, DEFAULT_SOCKET_TIMEOUT_MILLIS, DEFAULT_BUFFER_SIZE)
        }
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val uriString = uri.toString()
        if (!uriString.startsWith("rtp://") && !uriString.startsWith("udp://")) {
            throw IOException("Unsupported scheme: ${uri?.scheme}, expected rtp:// or udp://")
        }

        val host = uri?.host ?: throw IOException("URI host is null")
        val port = uri?.port.takeIf { it != -1 } ?: DEFAULT_RTP_PORT

        try {
            receiveLoopFailed = null
            setupMulticastSocket(host, port)
            startReceiving()
            opened = true
            transferStarted(dataSpec)
            Log.i(TAG, "Opened RTP source: $host:$port, bufferSize=$bufferSize")
            return C.LENGTH_UNSET.toLong()
        } catch (e: Exception) {
            throw IOException("Failed to open RTP/UDP source: ${e.message}", e)
        }
    }

    @Throws(IOException::class)
    private fun setupMulticastSocket(host: String, port: Int) {
        multicastGroup = InetAddress.getByName(host)

        if (!multicastGroup!!.isMulticastAddress) {
            throw IOException("Address $host is not a multicast address")
        }

        socket = MulticastSocket(port).apply {
            soTimeout = socketTimeoutMillis
            receiveBufferSize = bufferSize * 8  // 🆕 内核缓冲区1MB

            // 🆕 设置低延迟
            trafficClass = 0x10

            // 必须先绑定网卡再加入组播组：多网卡设备上 join 会落在默认网卡，
            // 顺序颠倒会导致组播帧收不到（setInterface 已废弃，用 setNetworkInterface）
            val iface = getMulticastNetworkInterface()
            if (iface != null) {
                setNetworkInterface(iface)
                Log.i(TAG, "Bound to interface: ${iface.name}")
            }

            joinGroup(multicastGroup)

            loopbackMode = false
            timeToLive = 32

            Log.i(TAG, "Socket buffer: ${receiveBufferSize}, trafficClass: $trafficClass")
        }
    }

    private fun startReceiving() {
        dataSourceJob = scope.launch(Dispatchers.IO) {
            while (isActive && opened) {
                try {
                    val packet = DatagramPacket(rtpBuffer, rtpBuffer.size)

                    // socket 自身带 15s soTimeout，不再用 withTimeout 包裹阻塞调用：
                    // withTimeout 无法中断阻塞的 receive()，超时边界会把 SocketTimeoutException
                    // 转成 TimeoutCancellationException，导致接收协程被当成取消而永久退出
                    socket?.receive(packet)

                    if (packet.length > 0) {
                        processRtpPacket(packet)
                    }
                } catch (e: CancellationException) {
                    // 协程真正被取消（close 或切台）
                    break
                } catch (e: SocketTimeoutException) {
                    // 15s 无数据：继续等待
                    continue
                } catch (e: Exception) {
                    if (opened) {
                        // 记录致命错误，让 read() 抛异常以触发播放器重试
                        receiveLoopFailed = e
                        Log.e(TAG, "Receive loop terminated: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    /**
     * 处理RTP包：解包RTP头，提取MPEG-TS数据，检测丢包
     */
    private fun processRtpPacket(packet: DatagramPacket) {
        val data = packet.data
        val length = packet.length
        val offset = packet.offset

        if (length < rtpHeaderSize) {
            Log.w(TAG, "Packet too small: $length < $rtpHeaderSize")
            return
        }

        // 解析RTP头
        val version = (data[offset].toInt() ushr 6) and 0x03
        if (version != 2) {
            Log.w(TAG, "Invalid RTP version: $version")
            return
        }

        val padding = (data[offset].toInt() ushr 5) and 0x01
        val extension = (data[offset].toInt() ushr 4) and 0x01
        val csrcCount = data[offset].toInt() and 0x0F

        // 🆕 获取序列号
        val seqNum = ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)

        val timestamp = ((data[offset + 4].toInt() and 0xFF) shl 24) or
                ((data[offset + 5].toInt() and 0xFF) shl 16) or
                ((data[offset + 6].toInt() and 0xFF) shl 8) or
                (data[offset + 7].toInt() and 0xFF)

        // 计算Payload偏移
        var payloadOffset = offset + rtpHeaderSize + (csrcCount * 4)

        // 跳过扩展头
        if (extension == 1 && length >= payloadOffset + 4) {
            val extLength = ((data[payloadOffset + 2].toInt() and 0xFF) shl 8) or
                    (data[payloadOffset + 3].toInt() and 0xFF)
            payloadOffset += 4 + (extLength * 4)
        }

        // 计算Payload长度
        var payloadLength = length - (payloadOffset - offset)
        if (padding == 1 && length > 0) {
            val paddingSize = data[offset + length - 1].toInt() and 0xFF
            if (paddingSize < payloadLength) {
                payloadLength -= paddingSize
            }
        }

        // 🆕 检测丢包
        if (lastSequenceNumber != -1) {
            val expected = (lastSequenceNumber + 1) and 0xFFFF
            val gap = (seqNum - expected) and 0xFFFF
            if (gap > 0 && gap < 1000) {  // 检测到丢包
                packetsLost += gap
                if (gap > 1) {  // 只记录严重丢包
                    Log.w(TAG, "Packet loss: expected $expected, got $seqNum, lost $gap, total lost: $packetsLost")
                }
            }
        }
        lastSequenceNumber = seqNum

        // 提取MPEG-TS包：对齐首包同步字节，容忍尾部不完整包与个别坏包（不再整包丢弃）
        if (payloadLength > 0) {
            // 对齐到第一个同步字节 0x47
            var alignOffset = 0
            while (alignOffset < payloadLength && data[payloadOffset + alignOffset] != 0x47.toByte()) {
                alignOffset++
            }
            val alignedLength = payloadLength - alignOffset
            if (alignedLength < tsPacketSize) {
                Log.w(TAG, "No TS sync found in payload, packet $seqNum")
                return
            }

            // 按188分片，逐片校验0x47，坏片丢弃；尾部不足188的残包丢弃
            val validPackets = alignedLength / tsPacketSize
            val tsData = ByteArray(validPackets * tsPacketSize)
            var written = 0
            for (i in 0 until validPackets) {
                val src = payloadOffset + alignOffset + i * tsPacketSize
                if (data[src] == 0x47.toByte()) {
                    System.arraycopy(data, src, tsData, written, tsPacketSize)
                    written += tsPacketSize
                }
            }

            if (written == 0) {
                Log.w(TAG, "All TS packets invalid, packet $seqNum")
                return
            }

            // 无坏包时直接复用 tsData，避免额外拷贝
            val validData = if (written == tsData.size) tsData else tsData.copyOf(written)

            // 入队（带超时）
            val offered = packetQueue.offer(validData, 200, TimeUnit.MILLISECONDS)
            if (offered) {
                packetsReceived++
                // 每100包输出一次统计
                if (packetsReceived % 100 == 0) {
                    Log.i(TAG, "Stats: received=$packetsReceived, lost=$packetsLost, queue=${packetQueue.size}/$QUEUE_SIZE")
                }
            } else {
                Log.w(TAG, "Queue full, dropped packet $seqNum")
            }
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        // 先读取缓冲区剩余数据
        readBuffer?.let { rb ->
            if (rb.hasRemaining()) {
                val toRead = minOf(length, rb.remaining())
                rb.get(buffer, offset, toRead)
                if (!rb.hasRemaining()) {
                    readBuffer = null
                }
                return toRead
            }
        }

        // 从队列获取新TS包：带超时 poll，避免流中断/接收循环退出后无限阻塞
        return try {
            val tsPacket = packetQueue.poll(QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: run {
                    // 接收循环已死 → 抛异常让播放器触发重试
                    receiveLoopFailed?.let { throw IOException("Receive loop failed: ${it.message}", it) }
                    // 未关闭但暂时无数据，返回0等待更多数据
                    return if (opened) 0 else -1
                }

            readBuffer = ByteBuffer.wrap(tsPacket)
            val toRead = minOf(length, tsPacket.size)
            readBuffer?.get(buffer, offset, toRead)

            bytesRead += toRead
            bytesTransferred(toRead)
            toRead
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (opened) 0 else -1
        }
    }

    override fun getUri(): Uri? = uri

    @Throws(IOException::class)
    override fun close() {
        Log.i(TAG, "Closing RTP source, stats: received=$packetsReceived, lost=$packetsLost, bytes=$bytesRead")
        opened = false
        transferEnded()

        dataSourceJob?.cancel()
        dataSourceJob = null

        try {
            multicastGroup?.let { group ->
                socket?.leaveGroup(group)
            }
        } catch (_: Exception) {}

        socket?.close()
        socket = null

        packetQueue.clear()
        readBuffer = null
        bytesRead = 0
        packetsReceived = 0
        packetsLost = 0
        lastSequenceNumber = -1
        receiveLoopFailed = null
    }

    private fun getMulticastNetworkInterface(): NetworkInterface? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            // 优先级：有线 > 无线 > 其他；跳过回环/虚拟/VPN 接口
            val priority = listOf("eth", "enp", "en", "wlan", "wlp", "wl")
            for (prefix in priority) {
                val iface = interfaces.find {
                    it.isUp && !it.isLoopback && !isVirtualInterface(it) && it.name.startsWith(prefix)
                }
                if (iface != null) {
                    Log.i(TAG, "Selected interface: ${iface.name}")
                    return iface
                }
            }
            val fallback = interfaces.find { it.isUp && !it.isLoopback && !isVirtualInterface(it) }
            fallback?.let { Log.i(TAG, "Fallback interface: ${it.name}") }
            return fallback
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get network interface: ${e.message}")
            return null
        }
    }

    private fun isVirtualInterface(intf: NetworkInterface): Boolean {
        val name = intf.name.lowercase()
        return name.startsWith("tun") || name.startsWith("tap") ||
                name.startsWith("ppp") || name.startsWith("vpn") ||
                name.contains("virtual") || name.contains("veth") ||
                name.contains("docker") || name.startsWith("lo")
    }

    fun getStats(): String {
        return "RTP received=$packetsReceived lost=$packetsLost queue=${packetQueue.size}/$QUEUE_SIZE"
    }
}