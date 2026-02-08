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
    private var networkInterface: NetworkInterface? = null

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

    companion object {
        const val DEFAULT_SOCKET_TIMEOUT_MILLIS = 15000  // 🆕 15秒超时
        const val DEFAULT_BUFFER_SIZE = 128 * 1024       // 🆕 128KB
        const val QUEUE_SIZE = 2000                       // 🆕 2000队列
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
        val port = uri?.port ?: throw IOException("URI port is null")

        try {
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

            joinGroup(multicastGroup)

            // 🆕 绑定到具体网卡
            val localAddr = getLocalIpAddress()
            localAddr?.let {
                setInterface(it)
                Log.i(TAG, "Bound to interface: ${it.hostAddress}")
            }

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

                    withTimeout(socketTimeoutMillis.toLong()) {
                        socket?.receive(packet)
                    }

                    if (packet.length > 0) {
                        processRtpPacket(packet)
                    }
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    if (opened) {
                        Log.e(TAG, "Receive error: ${e.message}")
                        delay(10)
                    }
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

        // 提取并验证MPEG-TS包
        if (payloadLength > 0 && payloadLength % tsPacketSize == 0) {
            val tsData = ByteArray(payloadLength)
            System.arraycopy(data, payloadOffset, tsData, 0, payloadLength)

            // 验证所有TS包同步字节(0x47)
            var valid = true
            var firstError = -1
            for (i in 0 until payloadLength step tsPacketSize) {
                if (tsData[i] != 0x47.toByte()) {
                    valid = false
                    if (firstError == -1) firstError = i
                }
            }

            if (valid) {
                // 入队（增加超时时间）
                val offered = packetQueue.offer(tsData, 200, TimeUnit.MILLISECONDS)
                if (offered) {
                    packetsReceived++
                    // 每100包输出一次统计
                    if (packetsReceived % 100 == 0) {
                        Log.i(TAG, "Stats: received=$packetsReceived, lost=$packetsLost, queue=${packetQueue.size}/$QUEUE_SIZE")
                    }
                } else {
                    Log.w(TAG, "Queue full, dropped packet $seqNum")
                }
            } else {
                Log.w(TAG, "Invalid TS sync at offset $firstError, packet $seqNum, payload=$payloadLength")
            }
        } else {
            Log.w(TAG, "Invalid payload length: $payloadLength (seq: $seqNum), expected multiple of $tsPacketSize")
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

        // 🆕 从队列获取新TS包（增加等待时间）
        return try {
            val tsPacket = packetQueue.take()
                ?: run {
                    if (opened) {
                        // 队列空但仍在播放，返回0等待更多数据
                        return 0
                    }
                    return -1  // 已关闭
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
    }

    private fun getLocalIpAddress(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                // 跳过回环和虚拟接口
                if (intf.isLoopback || !intf.isUp) continue

                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        Log.i(TAG, "Found interface: ${intf.name}, IP: ${addr.hostAddress}")
                        return addr
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP: ${e.message}")
        }
        return InetAddress.getByName("0.0.0.0")
    }

    fun getStats(): String {
        return "RTP received=$packetsReceived lost=$packetsLost queue=${packetQueue.size}/$QUEUE_SIZE"
    }
}