package cz.robotcockpit.app.brain

import cz.robotcockpit.app.core.LidarScan
import kotlin.math.PI

/**
 * Parser for Xiaomi Mijia 1S SDJQR01 LDS UART packets documented by the
 * supplied lidarbridge.yaml: 22-byte frames, 0xFA header, 0xA0..0xF9 index,
 * four distance/quality samples per frame and the 15-bit checksum.
 *
 * It accepts a raw UART byte stream. It does NOT implement BLE/GATT transport.
 */
class XiaomiLdsUartParser(
    private val minRangeMeters: Float = 0.08f,
    private val maxRangeMeters: Float = 12.0f
) {
    companion object {
        private const val FRAME_SIZE = 22
        private const val HEADER = 0xFA
        private const val FIRST_INDEX = 0xA0
        private const val LAST_INDEX = 0xF9
        private const val PACKETS_PER_REVOLUTION = 90
        private const val POINTS_PER_PACKET = 4
    }

    private val frame = ByteArray(FRAME_SIZE)
    private var frameSize = 0
    private val ranges = FloatArray(360) { Float.NaN }
    private val received = BooleanArray(PACKETS_PER_REVOLUTION)
    private var receivedCount = 0
    var lastMotorRpm: Float? = null
        private set
    var validPacketCount: Long = 0
        private set
    var invalidPacketCount: Long = 0
        private set

    /** Feed arbitrary chunks; returns a scan only after all 90 packets arrive. */
    @Synchronized
    fun feed(bytes: ByteArray): List<LidarScan> {
        val scans = ArrayList<LidarScan>()
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            if (frameSize == 0) {
                if (value == HEADER) frame[frameSize++] = byte
                continue
            }
            frame[frameSize++] = byte
            if (frameSize < FRAME_SIZE) continue

            if (!isValidFrame(frame)) {
                invalidPacketCount++
                // Re-sync on a possible header within the rejected frame.
                val nextHeader = (1 until FRAME_SIZE).firstOrNull {
                    (frame[it].toInt() and 0xFF) == HEADER
                }
                if (nextHeader == null) {
                    frameSize = 0
                } else {
                    val remaining = FRAME_SIZE - nextHeader
                    System.arraycopy(frame, nextHeader, frame, 0, remaining)
                    frameSize = remaining
                }
                continue
            }

            validPacketCount++
            val packetIndex = (frame[1].toInt() and 0xFF) - FIRST_INDEX
            if (packetIndex == 0 && receivedCount > 0) {
                // Index 0 marks the start of a new revolution, including after
                // an incomplete revolution with dropped UART packets.
                received.fill(false)
                receivedCount = 0
                ranges.fill(Float.NaN)
            }
            lastMotorRpm = readU16(frame, 2) / 64.0f
            if (!received[packetIndex]) {
                for (sample in 0 until POINTS_PER_PACKET) {
                    val offset = 4 + sample * 4
                    val distanceMm = readU16(frame, offset)
                    val quality = readU16(frame, offset + 2)
                    val range = distanceMm / 1000.0f
                    val pointIndex = packetIndex * POINTS_PER_PACKET + sample
                    ranges[pointIndex] = if (quality > 0 &&
                        range >= minRangeMeters && range <= maxRangeMeters) range else Float.NaN
                }
                received[packetIndex] = true
                receivedCount++
            }
            frameSize = 0

            if (receivedCount == PACKETS_PER_REVOLUTION) {
                scans += LidarScan(
                    angleMin = 0.0,
                    angleIncrement = (2.0 * PI) / 360.0,
                    rangeMin = minRangeMeters.toDouble(),
                    rangeMax = maxRangeMeters.toDouble(),
                    rangesMeters = ranges.copyOf(),
                    timestampNanos = System.nanoTime()
                )
                received.fill(false)
                receivedCount = 0
                ranges.fill(Float.NaN)
            }
        }
        return scans
    }

    @Synchronized
    fun reset() {
        frameSize = 0
        received.fill(false)
        receivedCount = 0
        ranges.fill(Float.NaN)
        lastMotorRpm = null
    }

    private fun isValidFrame(data: ByteArray): Boolean {
        if ((data[0].toInt() and 0xFF) != HEADER) return false
        val index = data[1].toInt() and 0xFF
        if (index !in FIRST_INDEX..LAST_INDEX) return false
        var sum = 0
        for (offset in 0 until 20 step 2) sum += readU16(data, offset)
        val expected = (sum and 0x7FFF) or 0x8000
        return readU16(data, 20) == expected
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
