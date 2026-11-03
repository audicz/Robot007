package cz.robotcockpit.app.brain

import cz.robotcockpit.app.core.LidarScan
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

    @Test fun occupancyMapCodecRoundTripsAndDetectsCorruption() {
        val map = RobotMap(width = 20, height = 16, resolution = 0.1f)
        map.integrate(
            LidarScan(0.0, Math.PI / 180.0, 0.08, 12.0,
                FloatArray(360) { if (it == 0) 1.0f else Float.NaN }),
            RobotPose()
        )
        val encoded = RobotMapCodec.encode(map)
        val restored = RobotMapCodec.decode(encoded)
        assertEquals(map.width, restored.width)
        assertEquals(map.height, restored.height)
        assertEquals(map.resolution, restored.resolution, 0.0001f)
        assertTrue(map.toByteArray().contentEquals(restored.toByteArray()))

        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        try {
            RobotMapCodec.decode(encoded)
            fail("Corrupt map should be rejected")
        } catch (_: java.io.IOException) {
            // Expected: checksum mismatch.
        }
    }
}
