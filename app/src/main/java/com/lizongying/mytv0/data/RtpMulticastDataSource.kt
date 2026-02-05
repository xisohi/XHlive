package com.lizongying.mytv0.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * RTP/TS 组播数据源
 * 接收 RTP 包，提取 MPEG-TS 负载，输出连续 TS 字节流
 * 支持 H264 + MP2/AAC 的 IPTV 直播流
 */
@UnstableApi
class RtpMulticastDataSource : BaseDataSource(true) {

    private var socket: DatagramSocket? = null
    private var multicastSocket: MulticastSocket? = null
    private var groupAddress: InetAddress? = null

    // TS 包队列（188字节/包）
    private val tsPacketQueue = ArrayBlockingQueue<ByteArray>(2048)
    private var currentTsBuffer: ByteArray? = null
    private var currentPosition: Int = 0

    private var receiveThread: Thread? = null
    private var isReceiving = false
    private var uri: Uri? = null

    // 统计信息
    private var packetsReceived = 0L
    private var packetsDropped = 0L

    companion object {
        private const val TAG = "RtpMulticastDataSource"
        private const val RTP_HEADER_MIN_SIZE = 12
        private const val RTP_BUFFER_SIZE = 4096  // 最大 RTP 包大小
        private const val SO_TIMEOUT_MS = 15000   // 15秒超时
        private const val TS_PACKET_SIZE = 188
        private const val TS_SYNC_BYTE = 0x47
        private const val POLL_TIMEOUT_MS = 100L  // 队列轮询超时
        fun Factory(): androidx.media3.datasource.DataSource.Factory {
            return androidx.media3.datasource.DataSource.Factory { RtpMulticastDataSource() }
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val uriString = uri?.toString() ?: throw IOException("URI is null")  // 这一行修复空安全

        if (!uriString.startsWith("rtp://") && !uriString.startsWith("udp://")) {
            throw IOException("Unsupported scheme: ${uri?.scheme}, only rtp:// or udp:// supported")
        }

        val address = dataSpec.uri.host ?: throw IOException("Host is null")
        val port = dataSpec.uri.port.takeIf { it != -1 } ?: 5004

        try {
            val inetAddress = InetAddress.getByName(address)
            val isMulticast = inetAddress.isMulticastAddress

            socket = if (isMulticast) {
                multicastSocket = MulticastSocket(port).apply {
                    soTimeout = SO_TIMEOUT_MS
                    // 4MB 接收缓冲区，防止丢包
                    receiveBufferSize = 1024 * 1024 * 4
                    joinGroup(inetAddress)
                    // 设置组播循环（如果需要接收本机发出的流）
                    loopbackMode = false
                }
                groupAddress = inetAddress
                multicastSocket
            } else {
                DatagramSocket(port).apply {
                    soTimeout = SO_TIMEOUT_MS
                    receiveBufferSize = 1024 * 1024 * 2
                }
            }

            // 清空队列
            tsPacketQueue.clear()
            currentTsBuffer = null
            currentPosition = 0
            packetsReceived = 0
            packetsDropped = 0

            // 启动接收线程
            isReceiving = true
            startReceiveThread()

        } catch (e: Exception) {
            throw IOException("Failed to open RTP connection to $address:$port", e)
        }

        return C.LENGTH_UNSET.toLong()
    }

    private fun startReceiveThread() {
        receiveThread = Thread {
            val packetBuffer = ByteArray(RTP_BUFFER_SIZE)

            while (isReceiving && !Thread.currentThread().isInterrupted) {
                try {
                    val packet = DatagramPacket(packetBuffer, packetBuffer.size)
                    socket?.receive(packet) ?: break

                    packetsReceived++
                    processRtpPacket(packet)

                } catch (e: SocketException) {
                    if (isReceiving) {
                        // 异常关闭
                    }
                    break
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }.apply {
            name = "RtpReceiveThread-${System.currentTimeMillis()}"
            isDaemon = true
            start()
        }
    }

    private fun processRtpPacket(packet: DatagramPacket) {
        val data = packet.data
        val offset = packet.offset
        val length = packet.length

        if (length < RTP_HEADER_MIN_SIZE) return

        // 解析 RTP 头部
        val version = (data[offset].toInt() shr 6) and 0x03
        if (version != 2) return // 只支持 RTP v2

        val cc = (data[offset].toInt() shr 4) and 0x0F
        val hasMarker = ((data[offset + 1].toInt() shr 7) and 0x01) != 0
        val payloadType = (data[offset + 1].toInt() and 0x7F)

        // 序列号（用于丢包检测）
        val sequenceNumber = ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)

        // 计算 RTP 头部长度
        var headerLength = RTP_HEADER_MIN_SIZE + cc * 4

        // 处理 RTP 扩展头
        if (((data[offset].toInt() shr 4) and 0x01) != 0) {
            if (length < headerLength + 4) return
            val extensionLength = ((data[offset + headerLength + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + headerLength + 3].toInt() and 0xFF)
            headerLength += 4 + extensionLength * 4
        }

        // 提取 TS 负载
        val payloadOffset = offset + headerLength
        val payloadLength = length - headerLength

        if (payloadLength < TS_PACKET_SIZE) return

        // 复制负载数据（避免引用整个 packetBuffer）
        val payload = ByteArray(payloadLength)
        System.arraycopy(data, payloadOffset, payload, 0, payloadLength)

        // 提取 TS 包并放入队列
        extractTsPackets(payload)
    }

    private fun extractTsPackets(payload: ByteArray) {
        var pos = 0

        while (pos + TS_PACKET_SIZE <= payload.size) {
            // 快速查找同步字节
            while (pos < payload.size && payload[pos] != TS_SYNC_BYTE.toByte()) {
                pos++
            }

            if (pos + TS_PACKET_SIZE > payload.size) break

            // 验证同步字节
            if (payload[pos] == TS_SYNC_BYTE.toByte()) {
                val tsPacket = ByteArray(TS_PACKET_SIZE)
                System.arraycopy(payload, pos, tsPacket, 0, TS_PACKET_SIZE)

                // 放入队列，如果队列满则丢弃最旧的数据
                if (!tsPacketQueue.offer(tsPacket)) {
                    tsPacketQueue.poll()
                    packetsDropped++
                    tsPacketQueue.offer(tsPacket)
                }
            }

            pos += TS_PACKET_SIZE
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        var totalBytesRead = 0

        while (totalBytesRead < length) {
            // 当前缓冲区耗尽，获取下一个
            if (currentTsBuffer == null || currentPosition >= (currentTsBuffer?.size ?: 0)) {
                // 阻塞等待 TS 包，但有时间限制避免永久阻塞
                currentTsBuffer = tsPacketQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                currentPosition = 0

                if (currentTsBuffer == null) {
                    // 超时无数据
                    return if (totalBytesRead > 0) totalBytesRead else 0
                }
            }

            val data = currentTsBuffer!!
            val remaining = data.size - currentPosition
            val bytesToRead = minOf(length - totalBytesRead, remaining)

            System.arraycopy(data, currentPosition, buffer, offset + totalBytesRead, bytesToRead)
            currentPosition += bytesToRead
            totalBytesRead += bytesToRead
        }

        return totalBytesRead
    }

    override fun getUri(): Uri? = uri

    fun getStats(): RtpStats {
        return RtpStats(packetsReceived, packetsDropped, tsPacketQueue.size)
    }

    data class RtpStats(
        val received: Long,
        val dropped: Long,
        val queueSize: Int
    )

    override fun close() {
        isReceiving = false

        receiveThread?.interrupt()
        try {
            receiveThread?.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        receiveThread = null

        if (groupAddress != null) {
            try {
                multicastSocket?.leaveGroup(groupAddress)
            } catch (e: Exception) {
                // 忽略
            }
        }

        socket?.close()
        socket = null
        multicastSocket = null

        tsPacketQueue.clear()
        currentTsBuffer = null
        currentPosition = 0

        android.util.Log.d(TAG, "Close: received=$packetsReceived, dropped=$packetsDropped")
    }
}