package cz.robotcockpit.app.lidar

/**
 * MAROS V24.7.2 D3 transport: 23 notifications × 20 bytes = 360 one-degree
 * bins. Each range byte is quantized in 5 cm units; zero means no return.
 */
data class MarosLdsScan(
    val sequence: Int,
    val rpm: Int,
    val rangesMeters: FloatArray,
    val receivedAtNanos: Long = System.nanoTime()
) {
    val validPoints: Int get() = rangesMeters.count { it.isFinite() }
    val nearestMeters: Float? get() = rangesMeters.filter { it.isFinite() }.minOrNull()
}

class MarosD3ScanAssembler {
    companion object {
        const val PACKET_SIZE = 20
        const val FRAGMENT_COUNT = 23
        const val SERVICE_UUID = "a71d0001-5b0e-4a6f-9c7a-0d6a5b3f2e10"
        const val CHARACTERISTIC_UUID = "a71d0002-5b0e-4a6f-9c7a-0d6a5b3f2e10"
        private const val MAGIC = 0xD3
    }

    private var activeSequence = -1
    private var startedAtMs = 0L
    private var rpm = 0
    private val seen = BooleanArray(FRAGMENT_COUNT)
    private val ranges = FloatArray(360) { Float.NaN }
    private var fragmentCount = 0
    var acceptedPackets: Long = 0
        private set
    var rejectedPackets: Long = 0
        private set
    var completedScans: Long = 0
        private set

    @Synchronized
    fun accept(packet: ByteArray, nowMs: Long = android.os.SystemClock.elapsedRealtime()): MarosLdsScan? {
        if (packet.size != PACKET_SIZE || (packet[0].toInt() and 0xFF) != MAGIC) {
            rejectedPackets++
            return null
        }
        val sequence = packet[1].toInt() and 0xFF
        val fragment = packet[2].toInt() and 0xFF
        if (fragment !in 0 until FRAGMENT_COUNT) {
            rejectedPackets++
            return null
        }

        if (activeSequence != sequence || (startedAtMs != 0L && nowMs - startedAtMs > 1200L)) {
            resetAssembly()
            activeSequence = sequence
            startedAtMs = nowMs
            rpm = (packet[3].toInt() and 0xFF) * 2
        }
        if (seen[fragment]) return null

        val firstBin = fragment * 16
        val count = if (fragment == FRAGMENT_COUNT - 1) 8 else 16
        for (i in 0 until count) {
            val value = packet[4 + i].toInt() and 0xFF
            ranges[firstBin + i] = if (value == 0) Float.NaN else value * 0.05f
        }
        seen[fragment] = true
        fragmentCount++
        acceptedPackets++

        if (fragmentCount != FRAGMENT_COUNT) return null
        completedScans++
        val result = MarosLdsScan(sequence, rpm, ranges.copyOf())
        resetAssembly()
        return result
    }

    @Synchronized
    fun reset() {
        resetAssembly()
        activeSequence = -1
        startedAtMs = 0L
    }

    private fun resetAssembly() {
        seen.fill(false)
        ranges.fill(Float.NaN)
        fragmentCount = 0
    }
}
