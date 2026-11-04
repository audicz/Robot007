package cz.robotcockpit.app

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Odometry-based 2D occupancy mapper.
 *
 * This is deliberately not called a complete SLAM implementation: it places scans
 * using the robot pose supplied by the SPP telemetry. Scan matching / loop closure
 * can be added later without changing the SPP v1 wire protocol.
 */
data class RobotPose2D(val xCm: Double, val yCm: Double, val yawDeg: Double)

class LidarSlamGrid(
    val width: Int = 400,
    val height: Int = 400,
    val cellSizeCm: Float = 5f
) {
    private val cells = ByteArray(width * height)
    private var originXcm: Double? = null
    private var originYcm: Double? = null
    private var scansIntegrated = 0

    @Synchronized
    fun clear() {
        cells.fill(0)
        originXcm = null
        originYcm = null
        scansIntegrated = 0
    }

    @Synchronized
    fun integrate(frame: LidarFrame, pose: RobotPose2D): Boolean {
        if (width < 16 || height < 16 || cellSizeCm <= 0f) return false
        if (originXcm == null) {
            originXcm = pose.xCm
            originYcm = pose.yCm
        }
        val ox = originXcm ?: return false
        val oy = originYcm ?: return false
        val robotX = (width / 2.0 + (pose.xCm - ox) / cellSizeCm).roundToInt()
        val robotY = (height / 2.0 - (pose.yCm - oy) / cellSizeCm).roundToInt()
        if (robotX !in 1 until width - 1 || robotY !in 1 until height - 1) return false

        val yaw = Math.toRadians(pose.yawDeg)
        for (angleDeg in 0 until 360 step 2) {
            val rangeCm = frame.rangesCm.getOrNull(angleDeg) ?: 0
            if (rangeCm <= 0 || rangeCm > 1275) continue

            // LDS angle 0 points forward (+Y); positive angle turns clockwise.
            val a = yaw + Math.toRadians(angleDeg.toDouble())
            val worldX = pose.xCm + sin(a) * rangeCm
            val worldY = pose.yCm + cos(a) * rangeCm
            val endX = (width / 2.0 + (worldX - ox) / cellSizeCm).roundToInt()
            val endY = (height / 2.0 - (worldY - oy) / cellSizeCm).roundToInt()
            if (endX !in 1 until width - 1 || endY !in 1 until height - 1) continue

            traceRay(robotX, robotY, endX, endY)
            val hitIndex = endY * width + endX
            cells[hitIndex] = (cells[hitIndex].toInt() + 5).coerceAtMost(40).toByte()
        }
        scansIntegrated++
        return true
    }

    private fun traceRay(x0: Int, y0: Int, x1: Int, y1: Int) {
        var x = x0
        var y = y0
        val dx = kotlin.math.abs(x1 - x0)
        val sx = if (x0 < x1) 1 else -1
        val dy = -kotlin.math.abs(y1 - y0)
        val sy = if (y0 < y1) 1 else -1
        var error = dx + dy
        while (true) {
            if (x == x1 && y == y1) break
            val index = y * width + x
            cells[index] = (cells[index].toInt() - 1).coerceAtLeast(-20).toByte()
            val twice = 2 * error
            if (twice >= dy) { error += dy; x += sx }
            if (twice <= dx) { error += dx; y += sy }
            if (x !in 1 until width - 1 || y !in 1 until height - 1) break
        }
    }

    @Synchronized
    fun snapshot(): ByteArray = cells.copyOf()

    @Synchronized
    fun robotCell(pose: RobotPose2D): Pair<Float, Float>? {
        val ox = originXcm ?: return null
        val oy = originYcm ?: return null
        val x = width / 2.0 + (pose.xCm - ox) / cellSizeCm
        val y = height / 2.0 - (pose.yCm - oy) / cellSizeCm
        if (x < 0.0 || x >= width.toDouble() || y < 0.0 || y >= height.toDouble()) return null
        return x.toFloat() to y.toFloat()
    }

    @Synchronized
    fun integratedScans(): Int = scansIntegrated
}
