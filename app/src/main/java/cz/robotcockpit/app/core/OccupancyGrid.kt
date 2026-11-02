package cz.robotcockpit.app.core

import kotlin.math.floor
import kotlin.math.abs

/**
 * Phone-side 2D occupancy grid. Values: -1 unknown, 0 free, 100 occupied.
 * Integrates scans using an externally supplied pose; this is NOT a SLAM engine.
 */
class OccupancyGrid(
    val width: Int,
    val height: Int,
    val resolutionMeters: Double,
    val originX: Double,
    val originY: Double
) {
    private val cells = ByteArray(width * height) { UNKNOWN.toByte() }

    init {
        require(width > 0 && height > 0 && resolutionMeters > 0.0)
    }

    fun valueAt(x: Int, y: Int): Int? =
        if (x in 0 until width && y in 0 until height) cells[y * width + x].toInt() else null

    fun snapshot(): ByteArray = cells.copyOf()

    fun worldToCell(xMeters: Double, yMeters: Double): Pair<Int, Int>? {
        val x = floor((xMeters - originX) / resolutionMeters).toInt()
        val y = floor((yMeters - originY) / resolutionMeters).toInt()
        return if (x in 0 until width && y in 0 until height) x to y else null
    }

    fun integrate(scan: LidarScan, pose: Pose2D) {
        scan.rangesMeters.forEachIndexed { index, raw ->
            val range = raw.toDouble()
            if (!range.isFinite() || range < scan.rangeMin || range > scan.rangeMax) return@forEachIndexed
            val angle = pose.yaw + scan.angleMin + index * scan.angleIncrement
            val endX = pose.x + range * kotlin.math.cos(angle)
            val endY = pose.y + range * kotlin.math.sin(angle)
            val start = worldToCell(pose.x, pose.y) ?: return@forEachIndexed
            val end = worldToCell(endX, endY) ?: return@forEachIndexed
            traceRay(start.first, start.second, end.first, end.second)
        }
    }

    private fun traceRay(x0: Int, y0: Int, x1: Int, y1: Int) {
        var x = x0
        var y = y0
        val dx = abs(x1 - x0)
        val sx = if (x0 < x1) 1 else -1
        val dy = -abs(y1 - y0)
        val sy = if (y0 < y1) 1 else -1
        var error = dx + dy

        while (true) {
            val atEnd = x == x1 && y == y1
            if (x in 0 until width && y in 0 until height) {
                cells[y * width + x] = (if (atEnd) OCCUPIED else FREE).toByte()
            }
            if (atEnd) break
            val twice = 2 * error
            if (twice >= dy) { error += dy; x += sx }
            if (twice <= dx) { error += dx; y += sy }
        }
    }

    companion object {
        const val UNKNOWN = -1
        const val FREE = 0
        const val OCCUPIED = 100
    }
}
