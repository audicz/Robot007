package cz.robotcockpit.app

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 2D occupancy mapper with local correlative scan matching.
 *
 * Odometry supplies the initial pose. Each new scan searches a bounded
 * X/Y/yaw window against previously occupied cells, then integrates at the
 * best pose. This is local scan matching, not global loop closure.
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
    private var lastMatchScore = 0f
    private var lastMatchCorrected = false

    @Synchronized
    fun clear() {
        cells.fill(0)
        originXcm = null
        originYcm = null
        scansIntegrated = 0
        lastMatchScore = 0f
        lastMatchCorrected = false
    }

    /**
     * Aligns a new scan to the existing occupancy map around the odometry pose.
     * Search window: +/-15 cm in X/Y and +/-8 degrees in heading.
     * The first scan initializes the map from odometry.
     */
    @Synchronized
    fun matchAndIntegrate(frame: LidarFrame, odometryPose: RobotPose2D): RobotPose2D? {
        if (!validGeometry()) return null
        if (scansIntegrated == 0) {
            val ok = integrateAt(frame, odometryPose)
            lastMatchScore = 0f
            lastMatchCorrected = false
            return if (ok) odometryPose else null
        }

        val baseScore = score(frame, odometryPose)
        var bestPose = odometryPose
        var bestScore = baseScore
        val xyStep = cellSizeCm.toDouble()
        val angleStep = 2.0

        for (dy in -3..3) {
            for (dx in -3..3) {
                for (da in -4..4) {
                    if (dx == 0 && dy == 0 && da == 0) continue
                    val candidate = RobotPose2D(
                        odometryPose.xCm + dx * xyStep,
                        odometryPose.yCm + dy * xyStep,
                        normalizeAngle(odometryPose.yawDeg + da * angleStep)
                    )
                    val candidateScore = score(frame, candidate)
                    if (candidateScore > bestScore) {
                        bestScore = candidateScore
                        bestPose = candidate
                    }
                }
            }
        }

        // Reject weak matches rather than pulling the map toward unrelated geometry.
        val accepted = bestScore >= 0.10f && bestScore >= baseScore + 0.015f
        val finalPose = if (accepted) bestPose else odometryPose
        lastMatchScore = bestScore
        lastMatchCorrected = accepted
        return if (integrateAt(frame, finalPose)) finalPose else null
    }

    @Synchronized
    fun integrate(frame: LidarFrame, pose: RobotPose2D): Boolean = integrateAt(frame, pose)

    private fun validGeometry() = width >= 16 && height >= 16 && cellSizeCm > 0f

    private fun score(frame: LidarFrame, pose: RobotPose2D): Float {
        val ox = originXcm ?: return 0f
        val oy = originYcm ?: return 0f
        var sum = 0f
        var samples = 0
        val yaw = Math.toRadians(pose.yawDeg)
        // 3-degree sampling balances matching quality and mobile CPU cost.
        for (angleDeg in 0 until 360 step 3) {
            val range = frame.rangesCm.getOrNull(angleDeg) ?: 0
            if (range !in 10..1275) continue
            val angle = yaw + Math.toRadians(angleDeg.toDouble())
            val wx = pose.xCm + sin(angle) * range
            val wy = pose.yCm + cos(angle) * range
            val gx = (width / 2.0 + (wx - ox) / cellSizeCm).roundToInt()
            val gy = (height / 2.0 - (wy - oy) / cellSizeCm).roundToInt()
            if (gx !in 1 until width - 1 || gy !in 1 until height - 1) continue

            var localBest = 0
            for (ny in -1..1) for (nx in -1..1) {
                localBest = maxOf(localBest, cells[(gy + ny) * width + gx + nx].toInt())
            }
            // Only occupied cells are useful scan-match targets.
            sum += localBest.coerceAtLeast(0) / 40f
            samples++
        }
        return if (samples >= 20) sum / samples else 0f
    }

    private fun integrateAt(frame: LidarFrame, pose: RobotPose2D): Boolean {
        if (!validGeometry()) return false
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
        val dx = abs(x1 - x0)
        val sx = if (x0 < x1) 1 else -1
        val dy = -abs(y1 - y0)
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

    @Synchronized
    fun matchStatus(): Pair<Boolean, Float> = lastMatchCorrected to lastMatchScore

    private fun normalizeAngle(degrees: Double): Double {
        var value = degrees % 360.0
        if (value > 180.0) value -= 360.0
        if (value < -180.0) value += 360.0
        return value
    }
}
