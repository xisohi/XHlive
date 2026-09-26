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

    // 接收协程致命错误标志：非空时 read() 抛 IOException 触发播放器重试
    @Volatile
    private var receiveLoopFailed: String? = null

    companion object {
        const val DEFAULT_SOCKET_TIMEOUT_MILLIS = 15000  // 🆕 15秒超时
        const val DEFAULT_BUFFER_SIZE = 128 * 1024       // 🆕 128KB
        const val QUEUE_SIZE = 2000                       // 🆕 2000队列
        const val QUEUE_POLL_TIMEOUT_MS = 1000L           // 🆕 队列轮询超时
        const val DEFAULT_RTP_PORT = 5004                 // 🆕 缺省RTP端口
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
        // 组播源常不写端口，缺省 5004
        val port = uri?.port?.takeIf { it > 0 } ?: DEFAULT_RTP_PORT

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

        // 先选择网卡（有线>无线>其他，跳过虚拟接口）
        val iface = selectNetworkInterface()
        if (iface == null) {
            Log.w(TAG, "No suitable network interface found, using default")
        } else {
            Log.i(TAG, "Selected network interface: ${iface.name} (${iface.displayName})")
        }

        socket = MulticastSocket(port).apply {
            soTimeout = socketTimeoutMillis
            receiveBufferSize = bufferSize * 8  // 🆕 内核缓冲区1MB

            // 🆕 设置低延迟
            trafficClass = 0x10

            loopbackMode = false
            timeToLive = 32

            // 顺序不能反：必须先绑定网卡，再加入组播组，多网卡设备才能收到组播
            iface?.let {
                try {
                    setNetworkInterface(it)
                    Log.i(TAG, "Bound to interface: ${it.name}")
                } catch (e: Exception) {
                    Log.w(TAG, "setNetworkInterface failed: ${e.message}, using default")
                }
            }

            try {
                joinGroup(multicastGroup)
            } catch (e: Exception) {
                throw IOException("Failed to join multicast group: ${e.message}", e)
            }

            Log.i(TAG, "Socket buffer: ${receiveBufferSize}, trafficClass: $trafficClass")
        }
    }

    private fun startReceiving() {
        dataSourceJob = scope.launch(Dispatchers.IO) {
            while (isActive && opened) {
                try {
                    val packet = DatagramPacket(rtpBuffer, rtpBuffer.size)

                    // 注意：不能包 withTimeout —— socket.receive() 是阻塞调用，
                    // withTimeout 无法中断它，超时会抛 TimeoutCancellationException，
                    // 被当成 CancellationException break 后接收协程永久退出。
                    // socket.soTimeout(15s) 已足够，超时抛 SocketTimeoutException 正常继续。
                    socket?.receive(packet)

                    if (packet.length > 0) {
                        processRtpPacket(packet)
                    }
                } catch (e: SocketTimeoutException) {
                    // 正常超时，继续接收
                    continue
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    if (opened) {
                        Log.e(TAG, "Receive error: ${e.message}")
                        // 记录致命错误并停止接收，read() 检测到后抛 IOException 触发播放器重试
                        receiveLoopFailed = e.message ?: "receive failed"
                        break
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

        // 提取并验证MPEG-TS包（放宽校验：首包对齐0x47即可，坏包保留由解析器自行重同步）
        if (payloadLength >= tsPacketSize) {
            var start = 0
            if ((data[payloadOffset].toInt() and 0xFF) != 0x47) {
                // 首包未对齐（可能是上一个TS包的尾部残包），在前188字节内找对齐点
                start = -1
                val maxSearch = minOf(tsPacketSize, payloadLength)
                for (i in 0 until maxSearch) {
                    if ((data[payloadOffset + i].toInt() and 0xFF) == 0x47 &&
                        (payloadLength - i) >= tsPacketSize
                    ) {
                        start = i
                        break
                    }
                }
                if (start == -1) {
                    Log.w(TAG, "No TS sync in packet $seqNum, payload=$payloadLength")
                    return
                }
            }

            val alignedLength = payloadLength - start
            val tsCount = alignedLength / tsPacketSize
            val usable = tsCount * tsPacketSize

            val tsData = ByteArray(usable)
            System.arraycopy(data, payloadOffset + start, tsData, 0, usable)

            // 统计坏包（仅记录，不丢弃整段——Media3 解析器遇失步会自动重同步）
            var badPackets = 0
            for (i in 0 until usable step tsPacketSize) {
                if (tsData[i] != 0x47.toByte()) {
                    badPackets++
                }
            }
            if (badPackets > 0) {
                Log.w(TAG, "$badPackets bad TS packets in seq $seqNum (len=$usable)")
            }

            enqueueTsData(tsData, seqNum)
        } else {
            Log.w(TAG, "Payload too short: $payloadLength (seq: $seqNum), expected >= $tsPacketSize")
        }
    }

    private fun enqueueTsData(tsData: ByteArray, seqNum: Int) {
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

        // 接收协程已失败：抛 IOException 让播放器走 onPlayerError 重试
        receiveLoopFailed?.let {
            throw IOException("RTP receive loop failed: $it")
        }

        // 从队列取TS包（限时等待，避免队列空时永久阻塞）
        val tsPacket: ByteArray? = try {
            packetQueue.poll(QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
        if (tsPacket == null) {
            if (!opened) {
                return -1  // 已关闭
            }
            // 等待期间接收协程可能刚失败
            receiveLoopFailed?.let {
                throw IOException("RTP receive loop failed: $it")
            }
            return 0  // 暂时无数据，稍后重试
        }

        readBuffer = ByteBuffer.wrap(tsPacket)
        val toRead = minOf(length, tsPacket.size)
        readBuffer?.get(buffer, offset, toRead)

        bytesRead += toRead
        bytesTransferred(toRead)
        return toRead
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

    /**
     * 选择最优网卡：有线以太网 > WiFi > 其他可用接口。
     * 跳过回环与虚拟接口（tun/tap/ppp/vpn/veth/docker 等），
     * 返回 null 表示没有合适网卡（调用方回退默认接口）。
     */
    private fun selectNetworkInterface(): NetworkInterface? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val candidates = mutableListOf<NetworkInterface>()

            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue

                val name = intf.name.lowercase()
                // 跳过虚拟接口
                if (name.contains("tun") || name.contains("tap") || name.contains("ppp") ||
                    name.contains("vpn") || name.contains("veth") || name.contains("docker") ||
                    name.contains("sit") || name.contains("rmnet") || name.contains("ip6")
                ) {
                    Log.d(TAG, "Skip virtual interface: ${intf.name}")
                    continue
                }

                // 必须有 IPv4 地址
                var hasIpv4 = false
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        hasIpv4 = true
                        break
                    }
                }
                if (!hasIpv4) continue

                candidates.add(intf)
            }

            if (candidates.isEmpty()) return null

            // 有线（eth/en）优先，其次无线（wlan/wifi），其余排最后
            return candidates.sortedWith(
                compareBy(
                    { if (it.name.lowercase().contains("eth") || it.name.lowercase().contains("en")) 0 else 1 },
                    { if (it.name.lowercase().contains("wlan") || it.name.lowercase().contains("wifi")) 0 else 1 }
                )
            ).first()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate network interfaces: ${e.message}")
            return null
        }
    }

    fun getStats(): String {
        return "RTP received=$packetsReceived lost=$packetsLost queue=${packetQueue.size}/$QUEUE_SIZE"
    }
}