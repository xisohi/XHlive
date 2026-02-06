package com.lizongying.mytv0.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.*
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * RTP 组播数据源
 */
@UnstableApi
class RtpMulticastDataSource : BaseDataSource(true), CoroutineScope {

    private val job = Job()
    override val coroutineContext: CoroutineContext = Dispatchers.IO + job

    private var multicastSocket: MulticastSocket? = null
    private var multicastAddress: InetAddress? = null

    private val packetQueue = LinkedBlockingQueue<RtpPacket>(MAX_QUEUE_SIZE)
    private val reordering = mutableListOf<RtpPacket>()

    private var currentPacket: RtpPacket? = null
    private var currentPosition: Int = 0

    private var uri: Uri? = null
    private var nextSequence: Int = 0
    private var isRtpMode: Boolean = false

    companion object {
        private const val TAG = "RtpMulticastDataSource"
        private const val MAX_QUEUE_SIZE = 4096
        private const val MAX_BUFFER_SIZE = 64
        private const val POLL_TIMEOUT_MS = 100L
        private const val TS_SYNC_BYTE = 0x47

        fun Factory(): androidx.media3.datasource.DataSource.Factory {
            return androidx.media3.datasource.DataSource.Factory { RtpMulticastDataSource() }
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        val currentUri = dataSpec.uri  // 使用局部变量避免 smart cast 问题
        uri = currentUri
        val uriString = currentUri?.toString() ?: throw IOException("URI is null")

        if (!uriString.startsWith("rtp://") && !uriString.startsWith("udp://")) {
            throw IOException("Unsupported scheme: ${currentUri.scheme}")
        }

        val address = currentUri.host ?: throw IOException("Host is null")
        val port = currentUri.port.takeIf { it != -1 } ?: 5004

        try {
            multicastAddress = InetAddress.getByName(address)
            val isMulticast = multicastAddress!!.isMulticastAddress

            multicastSocket = if (isMulticast) {
                MulticastSocket(port).apply {
                    soTimeout = 0
                    receiveBufferSize = 1024 * 1024 * 4

                    val networkInterface = getMulticastInterface()
                    if (networkInterface != null) {
                        android.util.Log.d(TAG, "Using network interface: $networkInterface")
                        this.networkInterface = networkInterface
                    }

                    joinGroup(multicastAddress)
                }
            } else {
                throw IOException("Only multicast addresses supported")
            }

            packetQueue.clear()
            reordering.clear()
            currentPacket = null
            currentPosition = 0

            launch { receiveLoop() }

            android.util.Log.i(TAG, "RTP data source opened: $uri")

        } catch (e: Exception) {
            throw IOException("Failed to open RTP: $address:$port", e)
        }

        return C.LENGTH_UNSET.toLong()
    }

    private fun getMulticastInterface(): NetworkInterface? {
        val interfaces = NetworkInterface.getNetworkInterfaces().toList()
        val priority = listOf("eth", "enp", "en", "wlan", "wlp", "wl")

        for (prefix in priority) {
            val iface = interfaces.find {
                it.isUp && !it.isLoopback && it.name.startsWith(prefix)
            }
            if (iface != null) return iface
        }

        return interfaces.find { it.isUp && !it.isLoopback }
    }

    private suspend fun receiveLoop() {
        try {
            val firstPacket = readPacketFromSocket()
            val isRtp = checkIsRtp(firstPacket)

            if (isRtp) {
                isRtpMode = true
                processRtpPacket(firstPacket)
                nextSequence = (getSequenceNumber(firstPacket) + 1) and 0xFFFF

                while (isActive) {
                    val packet = readPacketFromSocket()
                    processRtpPacket(packet)
                }
            } else {
                isRtpMode = false
                packetQueue.offer(RtpPacket(firstPacket.data, firstPacket.offset, firstPacket.length, 0))

                while (isActive) {
                    val packet = readPacketFromSocket()
                    packetQueue.offer(RtpPacket(packet.data, packet.offset, packet.length, 0))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Receive loop error: ${e.message}")
        }
    }

    private fun readPacketFromSocket(): DatagramPacket {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        multicastSocket?.receive(packet) ?: throw IOException("Socket closed")
        return packet
    }

    private fun checkIsRtp(packet: DatagramPacket): Boolean {
        val data = packet.data
        val offset = packet.offset
        if (data[offset] == TS_SYNC_BYTE.toByte()) return false
        val version = (data[offset].toInt() and 0xC0) shr 6
        return version == 2
    }

    private fun processRtpPacket(datagram: DatagramPacket) {
        val data = datagram.data
        val offset = datagram.offset
        val length = datagram.length

        if (length < 12) return

        val seqNum = getSequenceNumber(datagram)
        val cc = (data[offset].toInt() and 0x0F)
        var payloadOffset = 12 + cc * 4

        if ((data[offset].toInt() and 0x10) != 0) {
            if (length < payloadOffset + 4) return
            val extLen = ((data[offset + payloadOffset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + payloadOffset + 3].toInt() and 0xFF)
            payloadOffset += 4 + extLen * 4
        }

        val payloadLength = length - payloadOffset
        if (payloadLength <= 0) return

        val rtpPacket = RtpPacket(
            data = data,
            offset = offset + payloadOffset,
            length = payloadLength,
            sequence = seqNum
        )

        handlePacketSequence(rtpPacket)
    }

    private fun getSequenceNumber(packet: DatagramPacket): Int {
        val data = packet.data
        val offset = packet.offset
        return ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
    }

    private fun handlePacketSequence(packet: RtpPacket) {
        val cmp = compareSequence(nextSequence, packet.sequence)

        when {
            cmp == 0 -> {
                enqueuePacket(packet)
                nextSequence = (packet.sequence + 1) and 0xFFFF
                processBufferedPackets()
            }
            cmp < 0 -> {
                // 使用 compareBy 避免类型推断问题
                val index = reordering.binarySearch(packet, compareBy { it.sequence })
                val insertIndex = if (index >= 0) index else -index - 1
                reordering.add(insertIndex, packet)

                if (reordering.size >= MAX_BUFFER_SIZE) {
                    val oldest = reordering.removeAt(0)
                    enqueuePacket(oldest)
                    nextSequence = (oldest.sequence + 1) and 0xFFFF
                    processBufferedPackets()
                }
            }
        }
    }

    private fun processBufferedPackets() {
        while (reordering.isNotEmpty()) {
            val first = reordering[0]
            if (compareSequence(first.sequence, nextSequence) == 0) {
                reordering.removeAt(0)
                enqueuePacket(first)
                nextSequence = (first.sequence + 1) and 0xFFFF
            } else {
                break
            }
        }
    }

    private fun enqueuePacket(packet: RtpPacket) {
        if (!packetQueue.offer(packet)) {
            android.util.Log.w(TAG, "Queue full, dropping packet ${packet.sequence}")
            packetQueue.clear()
            reordering.clear()
        }
    }

    private fun compareSequence(seq1: Int, seq2: Int): Int {
        if (seq1 == seq2) return 0
        val diff = (seq1 - seq2) and 0xFFFF
        return if (diff in 1..0x7FFF) 1 else -1
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        var totalRead = 0

        while (totalRead < length) {
            if (currentPacket == null || currentPosition >= currentPacket!!.length) {
                currentPacket = packetQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    ?: return if (totalRead > 0) totalRead else 0
                currentPosition = 0
            }

            val packet = currentPacket!!
            val remaining = packet.length - currentPosition
            val toRead = minOf(length - totalRead, remaining)

            System.arraycopy(packet.data, packet.offset + currentPosition,
                buffer, offset + totalRead, toRead)
            currentPosition += toRead
            totalRead += toRead
        }

        return totalRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        job.cancel()

        runCatching {
            multicastAddress?.let { multicastSocket?.leaveGroup(it) }
            multicastSocket?.close()
        }

        packetQueue.clear()
        reordering.clear()
        currentPacket = null

        android.util.Log.d(TAG, "Closed")
    }

    // RtpPacket 必须是 data class 且属性可访问
    private data class RtpPacket(
        val data: ByteArray,
        val offset: Int,
        val length: Int,
        val sequence: Int
    )
}