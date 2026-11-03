package cz.robotcockpit.app.brain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

/**
 * Versioned binary map format. Coordinates remain tied to the RobotMap origin
 * and resolution; CRC32 rejects truncated or corrupted files.
 */
object RobotMapCodec {
    private const val MAGIC = 0x4D41524F // "MARO"
    private const val VERSION = 1
    private const val MAX_CELLS = 4_000_000

    fun encode(map: RobotMap): ByteArray {
        val cells = map.toByteArray()
        require(cells.size == map.width * map.height)
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out -> out.write(cells) }
        val raw = payload.toByteArray()
        val crc = CRC32().apply { update(raw) }.value
        val result = ByteArrayOutputStream()
        DataOutputStream(result).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(map.width)
            out.writeInt(map.height)
            out.writeFloat(map.resolution)
            out.writeInt(raw.size)
            out.writeLong(crc)
            out.write(raw)
        }
        return result.toByteArray()
    }

    @Throws(IOException::class)
    fun decode(bytes: ByteArray): RobotMap {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != MAGIC) throw IOException("Neplatný soubor mapy")
            if (input.readInt() != VERSION) throw IOException("Nepodporovaná verze mapy")
            val width = input.readInt()
            val height = input.readInt()
            val resolution = input.readFloat()
            val size = input.readInt()
            val expectedCrc = input.readLong()
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_CELLS ||
                !resolution.isFinite() || resolution <= 0f || size != width * height ||
                size > MAX_CELLS) throw IOException("Neplatné rozměry mapy")
            val cells = ByteArray(size)
            input.readFully(cells)
            if (input.available() != 0) throw IOException("Soubor mapy obsahuje přebytečná data")
            val actualCrc = CRC32().apply { update(cells) }.value
            if (actualCrc != expectedCrc) throw IOException("Kontrola CRC mapy selhala")
            return RobotMap(width, height, resolution).also { it.loadFromByteArray(cells) }
        }
    }
}

/** Simple atomic file store. Call from a worker thread, not the Android UI thread. */
class RobotMapStore(private val file: File) {
    @Synchronized @Throws(IOException::class)
    fun save(map: RobotMap) {
        val parent = file.absoluteFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Nelze vytvořit adresář mapy")
        }
        val temp = File(file.parentFile ?: File("."), file.name + ".tmp")
        try {
            temp.writeBytes(RobotMapCodec.encode(map))
            try {
                Files.move(temp.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    @Synchronized @Throws(IOException::class)
    fun load(): RobotMap {
        if (!file.isFile) throw IOException("Uložená mapa neexistuje")
        return RobotMapCodec.decode(file.readBytes())
    }

    @Synchronized fun exists(): Boolean = file.isFile
}
