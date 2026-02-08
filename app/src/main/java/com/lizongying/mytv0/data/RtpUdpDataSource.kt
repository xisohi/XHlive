package com.lizongying.mytv0.data

import android.content.Context
import android.net.Uri
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
 * RTP over UDP组播数据源
 * 支持URI: rtp://239.9.9.9:9999 或 udp://239.9.9.9:9999
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

    private val rtpBuffer = ByteArray(2048)
    private val tsPacketSize = 188
    private val rtpHeaderSize = 12

    private val packetQueue = ArrayBlockingQueue<ByteArray>(500)
    private var readBuffer: ByteBuffer? = null

    private var dataSourceJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var bytesRead = 0L
    private var packetsReceived = 0
    private var opened = false

    companion object {
        const val DEFAULT_SOCKET_TIMEOUT_MILLIS = 8000
        const val DEFAULT_BUFFER_SIZE = 32 * 1024

        @JvmStatic
        fun create(context: Context): RtpUdpDataSource {
            return RtpUdpDataSource(context, DEFAULT_SOCKET_TIMEOUT_MILLIS, DEFAULT_BUFFER_SIZE)
        }
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val scheme = uri?.scheme?.lowercase()
        if (scheme != "rtp" && scheme != "udp") {
            throw IOException("Unsupported scheme: $scheme, expected rtp:// or udp://")
        }

        val host = uri?.host ?: throw IOException("URI host is null")
        val port = uri?.port ?: throw IOException("URI port is null")

        try {
            setupMulticastSocket(host, port)
            startReceiving()
            opened = true
            transferStarted(dataSpec)
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
            receiveBufferSize = bufferSize * 4
            joinGroup(multicastGroup)
            timeToLive = 32
        }
    }

    private fun startReceiving() {
        dataSourceJob = scope.launch(Dispatchers.IO) {
            while (isActive && opened) {
                try {
                    val packet = DatagramPacket(rtpBuffer, rtpBuffer.size)
                    withTimeout(socketTimeoutMillis.toLong()) { socket?.receive(packet) }
                    if (packet.length > 0) processRtpPacket(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    if (opened) delay(10)
                }
            }
        }
    }

    private fun processRtpPacket(packet: DatagramPacket) {
        val data = packet.data
        val length = packet.length
        val offset = packet.offset

        if (length < rtpHeaderSize) return

        // RTP版本检查
        val version = (data[offset].toInt() ushr 6) and 0x03
        if (version != 2) return

        val padding = (data[offset].toInt() ushr 5) and 0x01
        val extension = (data[offset].toInt() ushr 4) and 0x01
        val csrcCount = data[offset].toInt() and 0x0F

        var payloadOffset = offset + rtpHeaderSize + (csrcCount * 4)

        if (extension == 1 && length >= payloadOffset + 4) {
            val extLength = ((data[payloadOffset + 2].toInt() and 0xFF) shl 8) or
                    (data[payloadOffset + 3].toInt() and 0xFF)
            payloadOffset += 4 + (extLength * 4)
        }

        var payloadLength = length - (payloadOffset - offset)
        if (padding == 1 && length > 0) {
            val paddingSize = data[offset + length - 1].toInt() and 0xFF
            if (paddingSize < payloadLength) payloadLength -= paddingSize
        }

        // 提取MPEG-TS包（188字节，同步字节0x47）
        if (payloadLength > 0 && payloadLength % tsPacketSize == 0) {
            val tsData = ByteArray(payloadLength)
            System.arraycopy(data, payloadOffset, tsData, 0, payloadLength)

            // 验证同步字节
            var valid = true
            for (i in 0 until payloadLength step tsPacketSize) {
                if (tsData[i] != 0x47.toByte()) { valid = false; break }
            }

            if (valid) {
                packetQueue.offer(tsData, 100, TimeUnit.MILLISECONDS)
                packetsReceived++
            }
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        // 先读缓冲区
        readBuffer?.let { rb ->
            if (rb.hasRemaining()) {
                val toRead = minOf(length, rb.remaining())
                rb.get(buffer, offset, toRead)
                if (!rb.hasRemaining()) readBuffer = null
                return toRead
            }
        }

        // 从队列取新包
        return try {
            val tsPacket = packetQueue.poll(500, TimeUnit.MILLISECONDS)
                ?: return if (opened) 0 else -1

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
        opened = false
        transferEnded()
        dataSourceJob?.cancel()

        try { multicastGroup?.let { socket?.leaveGroup(it) } } catch (_: Exception) {}
        socket?.close()
        socket = null

        packetQueue.clear()
        readBuffer = null
        bytesRead = 0
        packetsReceived = 0
    }
}