package cz.robotcockpit.app.brain

import kotlin.math.roundToInt

/** Xiaomi LDS BLE bridge protocol V24.7.2: D3, 23 x 20-byte notifications. */
data class LidarScan(val sequence: Int, val rpm: Int, val rangesMeters: FloatArray, val receivedAtMs: Long)

data class WheelOdometry(
    val sequence: Int, val leftTicks: Int, val rightTicks: Int,
    val gyroZRadPerSec: Float, val dtMs: Int
)

class LidarBridgeProtocol {
    private var sequence = -1
    private var rpm = 0
    private val ranges = FloatArray(360)
    private val received = BooleanArray(23)
    private var receivedCount = 0

    @Synchronized fun accept(frame: ByteArray, nowMs: Long = System.currentTimeMillis()): LidarScan? {
        if (frame.size != 20) return null
        return when (frame[0].toInt() and 0xFF) {
            0xD3 -> acceptScanFragment(frame, nowMs)
            else -> null
        }
    }

    private fun acceptScanFragment(b: ByteArray, nowMs: Long): LidarScan? {
        val seq = b[1].toInt() and 0xFF
        val part = b[2].toInt() and 0xFF
        if (part !in 0..22) return null
        if (seq != sequence) {
            sequence = seq; rpm = (b[3].toInt() and 0xFF) * 2
            received.fill(false); receivedCount = 0
        }
        if (received[part]) return null
        val start = part * 16
        val count = if (part == 22) 8 else 16
        for (i in 0 until count) {
            val units = b[4 + i].toInt() and 0xFF
            ranges[start + i] = if (units == 0) Float.NaN else units * 0.05f
        }
        received[part] = true; receivedCount++
        if (receivedCount != 23) return null
        return LidarScan(sequence, rpm, ranges.copyOf(), nowMs)
    }

    @Synchronized fun reset() {
        sequence = -1; received.fill(false); receivedCount = 0
        ranges.fill(Float.NaN)
    }

    companion object {
        /** D4 optional odometry frame, CRC16-CCITT over bytes 0..15. */
        fun parseOdometry(b: ByteArray): WheelOdometry? {
            if (b.size != 20 || (b[0].toInt() and 0xFF) != 0xD4) return null
            val expected = (b[16].toInt() and 0xFF) or ((b[17].toInt() and 0xFF) shl 8)
            if (crc16Ccitt(b, 0, 16) != expected) return null
            fun i32(o: Int) = (b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8) or
                ((b[o+2].toInt() and 255) shl 16) or (b[o+3].toInt() shl 24)
            val gyro = ((b[12].toInt() and 255) or (b[13].toInt() shl 8)).toShort().toInt()
            val dt = (b[14].toInt() and 255) or ((b[15].toInt() and 255) shl 8)
            if (dt <= 0 || dt > 5000) return null
            return WheelOdometry(b[1].toInt() and 255, i32(4), i32(8), gyro / 1000f, dt)
        }

        private fun crc16Ccitt(data: ByteArray, offset: Int, length: Int): Int {
            var crc = 0xFFFF
            for (i in offset until offset + length) {
                crc = crc xor ((data[i].toInt() and 255) shl 8)
                repeat(8) { crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
            }
            return crc
        }
    }
}
