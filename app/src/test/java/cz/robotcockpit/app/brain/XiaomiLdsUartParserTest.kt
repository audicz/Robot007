package cz.robotcockpit.app.brain

import org.junit.Assert.*
import org.junit.Test

class XiaomiLdsUartParserTest {
    private fun frame(index: Int, distanceMm: Int = 1250): ByteArray {
        val b = ByteArray(22)
        b[0] = 0xFA.toByte()
        b[1] = (0xA0 + index).toByte()
        b[2] = 0x00
        b[3] = 0x4B // 300 rpm in Q6 format
        for (sample in 0 until 4) {
            val off = 4 + sample * 4
            b[off] = (distanceMm and 0xFF).toByte()
            b[off + 1] = (distanceMm shr 8).toByte()
            b[off + 2] = 0x01
            b[off + 3] = 0x00
        }
        var sum = 0
        for (off in 0 until 20 step 2) {
            sum += (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
        }
        val checksum = (sum and 0x7FFF) or 0x8000
        b[20] = (checksum and 0xFF).toByte()
        b[21] = (checksum shr 8).toByte()
        return b
    }

    @Test fun assemblesOne360PointRevolutionFromChunkedFrames() {
        val parser = XiaomiLdsUartParser()
        val stream = (0 until 90).flatMap { frame(it).asIterable() }.toByteArray()
        val scans = ArrayList<cz.robotcockpit.app.core.LidarScan>()
        var offset = 0
        while (offset < stream.size) {
            val end = minOf(offset + 37, stream.size)
            scans += parser.feed(stream.copyOfRange(offset, end))
            offset = end
        }
        assertEquals(1, scans.size)
        assertEquals(360, scans.single().rangesMeters.size)
        assertTrue(scans.single().rangesMeters.all { kotlin.math.abs(it - 1.25f) < 0.001f })
        assertEquals(300f, parser.lastMotorRpm!!, 0.01f)
    }

    @Test fun rejectsBadChecksumAndDoesNotEmitPartialRevolution() {
        val parser = XiaomiLdsUartParser()
        val bad = frame(0).also { it[10] = (it[10].toInt() xor 0x01).toByte() }
        parser.feed(bad)
        assertEquals(1L, parser.invalidPacketCount)
        assertTrue(parser.feed(frame(1)).isEmpty())
    }
}
