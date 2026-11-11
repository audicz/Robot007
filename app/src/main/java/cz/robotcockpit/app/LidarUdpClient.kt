package cz.robotcockpit.app

import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.concurrent.thread

data class LidarUdpStats(
    val rxPackets: Long = 0,
    val completedScans: Long = 0,
    val droppedScans: Long = 0,
    val badPackets: Long = 0,
    val lastPacketAtMs: Long = 0,
    val rpm: Int = 0,
    val dutyPct: Int = 0
)

/**
 * Receives the exact D9 scan fragments emitted by lidarbridge.yaml over Wi-Fi UDP.
 *
 * UDP payload: 185 bytes = D9, sequence, fragment(0..3), rpm/2, duty%, 90 x uint16 mm.
 * Four fragments form one 360-degree revolution. No ESP-NOW support is required on Android.
 */
class LidarUdpClient(
    private val onFrame: (LidarFrame) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onStats: (LidarUdpStats) -> Unit
) {
    companion object {
        const val PORT = 18512
        private const val PACKET_SIZE = 185
        private const val FRAGMENTS = 4
        private const val BINS_PER_FRAGMENT = 90
        private const val BINS = 360
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    private var worker: Thread? = null

    private val rangesCm = IntArray(BINS)
    private val received = BooleanArray(FRAGMENTS)
    private var activeSequence = -1
    private var receivedCount = 0
    private var rpm = 0
    private var dutyPct = 0
    private var rxPackets = 0L
    private var completedScans = 0L
    private var droppedScans = 0L
    private var badPackets = 0L
    private var lastPacketAtMs = 0L
    private var announcedRx = false

    @Synchronized
    fun start() {
        if (running) return
        running = true
        postStatus("Naslouchám Wi-Fi UDP :$PORT · čekám na LDS D9")
        worker = thread(name = "robot007-lidar-udp", isDaemon = true) {
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.broadcast = true
                s.bind(InetSocketAddress(PORT))
                s.soTimeout = 1000
                socket = s
                val buffer = ByteArray(2048)
                while (running) {
                    try {
                        val datagram = DatagramPacket(buffer, buffer.size)
                        s.receive(datagram)
                        val payload = datagram.data.copyOfRange(datagram.offset, datagram.offset + datagram.length)
                        accept(payload)
                    } catch (_: java.net.SocketTimeoutException) {
                        val stale = synchronized(this) {
                            lastPacketAtMs == 0L || System.currentTimeMillis() - lastPacketAtMs > 2000L
                        }
                        if (stale && announcedRx) {
                            announcedRx = false
                            postStatus("UDP bez čerstvých dat · čekám na LDS D9")
                        }
                    }
                }
                s.close()
            } catch (e: Exception) {
                if (running) postStatus("UDP příjem selhal: ${e.message}")
            } finally {
                socket = null
                running = false
            }
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        worker = null
        resetAssembly()
        announcedRx = false
        postStatus("UDP příjem zastaven")
    }

    fun close() = stop()

    @Synchronized
    private fun accept(packet: ByteArray) {
        if (packet.size != PACKET_SIZE || (packet[0].toInt() and 0xff) != 0xD9) {
            badPackets++
            publishStats()
            return
        }
        val sequence = packet[1].toInt() and 0xff
        val fragment = packet[2].toInt() and 0xff
        if (fragment !in 0 until FRAGMENTS) {
            badPackets++
            publishStats()
            return
        }

        if (activeSequence != sequence) {
            if (receivedCount in 1 until FRAGMENTS) droppedScans++
            resetAssembly()
            activeSequence = sequence
        }

        rxPackets++
        lastPacketAtMs = System.currentTimeMillis()
        rpm = (packet[3].toInt() and 0xff) * 2
        dutyPct = packet[4].toInt() and 0xff

        if (!received[fragment]) {
            received[fragment] = true
            receivedCount++
            val start = fragment * BINS_PER_FRAGMENT
            for (i in 0 until BINS_PER_FRAGMENT) {
                val lo = packet[5 + i * 2].toInt() and 0xff
                val hi = packet[6 + i * 2].toInt() and 0xff
                val mm = lo or (hi shl 8)
                rangesCm[start + i] = if (mm in 1..12000) (mm + 5) / 10 else 0
            }
        }

        if (!announcedRx) {
            announcedRx = true
            postStatus("UDP RX AKTIVNÍ · LDS D9")
        }

        if (receivedCount == FRAGMENTS) {
            val frame = LidarFrame(rangesCm.copyOf(), rpm, System.currentTimeMillis())
            completedScans++
            main.post { onFrame(frame) }
            // Keep received[] set until a new sequence arrives; duplicate fragments
            // from the completed revolution cannot create a second scan.
        }
        publishStats()
    }

    private fun resetAssembly() {
        received.fill(false)
        rangesCm.fill(0)
        receivedCount = 0
    }

    private fun publishStats() {
        val snapshot = LidarUdpStats(rxPackets, completedScans, droppedScans, badPackets, lastPacketAtMs, rpm, dutyPct)
        main.post { onStats(snapshot) }
    }

    private fun postStatus(message: String) {
        main.post { onStatus(message) }
    }
}
