package cz.robotcockpit.app.lidar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MarosD3ScanAssemblerTest {
    private fun fragment(sequence: Int, index: Int, dutyNibble: Int = 0, range: Int = 20): ByteArray {
        return ByteArray(20).also { packet ->
            packet[0] = 0xD3.toByte()
            packet[1] = (((dutyNibble and 0x0F) shl 4) or (sequence and 0x0F)).toByte()
            packet[2] = index.toByte()
            packet[3] = 150.toByte() // 300 RPM, encoded as RPM / 2
            val sampleCount = if (index == 22) 8 else 16
            for (i in 0 until sampleCount) {
                packet[4 + i] = range.toByte()
            }
        }
    }

    @Test
    fun assembles23FragmentsWhenMotorDutyNibbleChanges() {
        val assembler = MarosD3ScanAssembler()
        var completed: MarosLdsScan? = null

        for (index in 0 until MarosD3ScanAssembler.FRAGMENT_COUNT) {
            // The bridge may adjust motor duty while a revolution is being transmitted.
            completed = assembler.accept(fragment(sequence = 7, index = index, dutyNibble = index % 16), 100L + index * 15L)
                ?: completed
        }

        val scan = completed
        assertNotNull(scan)
        assertEquals(7, scan!!.sequence)
        assertEquals(300, scan.rpm)
        assertEquals(360, scan.rangesMeters.size)
        assertEquals(360, scan.validPoints)
        assertEquals(1.0f, scan.nearestMeters!!, 0.0001f)
    }

    @Test
    fun doesNotPublishIncompleteScan() {
        val assembler = MarosD3ScanAssembler()
        for (index in 0 until 22) {
            assertNull(assembler.accept(fragment(sequence = 3, index = index), 100L + index * 15L))
        }
        assertEquals(0L, assembler.completedScans)
    }

    @Test
    fun rejectsWrongMagicAndPacketLength() {
        val assembler = MarosD3ScanAssembler()
        val wrongMagic = fragment(1, 0).also { it[0] = 0xD4.toByte() }
        assertNull(assembler.accept(wrongMagic, 10L))
        assertNull(assembler.accept(ByteArray(19), 20L))
        assertEquals(2L, assembler.rejectedPackets)
    }
}
