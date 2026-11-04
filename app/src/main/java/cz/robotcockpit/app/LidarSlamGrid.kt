package cz.robotcockpit.app

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

data class RobotPose2D(val xCm: Double, val yCm: Double, val yawDeg: Double)
data class SlamUpdate(val pose: RobotPose2D, val tracking: Boolean, val loopClosure: Boolean, val score: Float, val scans: Int)

/**
 * LiDAR-only 2D scan-to-map localization and occupancy mapping.
 * It initializes at (0,0,0), estimates each next pose from the scan itself,
 * and periodically performs a coarse global relocalization against the map.
 * No ESP32 odometry, wheel encoders or IMU are required.
 *
 * Global relocalization can correct a drifted pose when a previously mapped
 * place is recognized. This lightweight backend does not redistribute all
 * historical poses with a pose-graph optimizer.
 */
class LidarSlamGrid(val width: Int = 600, val height: Int = 600, val cellSizeCm: Float = 5f) {
    private val cells = ByteArray(width * height)
    private var initialized = false
    private var scansIntegrated = 0
    private var lastPose = RobotPose2D(0.0, 0.0, 0.0)
    private var lastScore = 0f
    private var lastTracking = false
    private var lastLoopClosure = false
    private var framesSinceGlobalSearch = 0
    private val trajectory = ArrayList<RobotPose2D>()

    private data class Point(val x: Double, val y: Double)
    private data class Match(val pose: RobotPose2D, val score: Float)

    @Synchronized fun clear() {
        cells.fill(0); initialized = false; scansIntegrated = 0
        lastPose = RobotPose2D(0.0, 0.0, 0.0)
        lastScore = 0f; lastTracking = false; lastLoopClosure = false
        framesSinceGlobalSearch = 0; trajectory.clear()
    }

    @Synchronized fun process(frame: LidarFrame): SlamUpdate? {
        if (width < 32 || height < 32 || cellSizeCm <= 0f) return null
        val points = scanPoints(frame)
        if (points.size < 35) { lastTracking = false; lastScore = 0f; return null }

        if (!initialized) {
            initialized = true
            lastPose = RobotPose2D(0.0, 0.0, 0.0)
            integrate(points, lastPose)
            scansIntegrated++
            trajectory.add(lastPose)
            lastTracking = true; lastScore = 1f
            return SlamUpdate(lastPose, true, false, 1f, scansIntegrated)
        }

        // Coarse-to-fine correlative scan matching against accumulated occupancy.
        val coarse = search(points, lastPose, 45.0, 30.0, 10.0, 5.0)
        val fine = if (coarse.score >= 0.10f) search(points, coarse.pose, 10.0, 6.0, 2.0, 1.0) else coarse
        var chosen = fine.pose
        var chosenScore = fine.score
        var loop = false

        framesSinceGlobalSearch++
        // Global place recognition/relocalization, deliberately rate-limited.
        if (framesSinceGlobalSearch >= 24 && scansIntegrated >= 30) {
            framesSinceGlobalSearch = 0
            val global = globalSearch(points)
            if (global.score >= 0.24f && hypot(global.pose.xCm - lastPose.xCm, global.pose.yCm - lastPose.yCm) >= 100.0 &&
                global.score >= chosenScore + 0.04f) {
                chosen = global.pose
                chosenScore = global.score
                loop = true
            }
        }

        val tracked = chosenScore >= 0.10f
        lastScore = chosenScore; lastTracking = tracked; lastLoopClosure = loop
        if (!tracked) return SlamUpdate(lastPose, false, false, chosenScore, scansIntegrated)

        lastPose = chosen
        integrate(points, chosen)
        scansIntegrated++
        if (trajectory.isEmpty() || distance(trajectory.last(), chosen) >= 10.0 ||
            abs(normalize(chosen.yawDeg - trajectory.last().yawDeg)) >= 4.0) {
            trajectory.add(chosen)
            if (trajectory.size > 4000) trajectory.removeAt(0)
        }
        return SlamUpdate(chosen, true, loop, chosenScore, scansIntegrated)
    }

    private fun scanPoints(frame: LidarFrame): List<Point> {
        val out = ArrayList<Point>(180)
        for (deg in 0 until 360 step 2) {
            val range = frame.rangesCm.getOrNull(deg) ?: 0
            if (range !in 15..1275) continue
            val a = Math.toRadians(deg.toDouble())
            out.add(Point(sin(a) * range, cos(a) * range))
        }
        return out
    }

    private fun search(points: List<Point>, center: RobotPose2D, xyRadius: Double, yawRadius: Double,
                       xyStep: Double, yawStep: Double): Match {
        var bestPose = center
        var best = score(points, center)
        val n = (xyRadius / xyStep).roundToInt()
        val a = (yawRadius / yawStep).roundToInt()
        for (iy in -n..n) for (ix in -n..n) for (ia in -a..a) {
            if (ix == 0 && iy == 0 && ia == 0) continue
            val candidate = RobotPose2D(center.xCm + ix * xyStep, center.yCm + iy * xyStep,
                normalize(center.yawDeg + ia * yawStep))
            val value = score(points, candidate)
            if (value > best) { best = value; bestPose = candidate }
        }
        return Match(bestPose, best)
    }

    private fun globalSearch(points: List<Point>): Match {
        var bestPose = lastPose
        var best = 0f
        for (gy in 0 until height step 10) for (gx in 0 until width step 10) {
            val x = (gx - width / 2.0) * cellSizeCm
            val y = (height / 2.0 - gy) * cellSizeCm
            for (yaw in 0 until 360 step 15) {
                val pose = RobotPose2D(x, y, yaw.toDouble())
                val value = score(points, pose)
                if (value > best) { best = value; bestPose = pose }
            }
        }
        return Match(bestPose, best)
    }

    private fun score(points: List<Point>, pose: RobotPose2D): Float {
        var sum = 0f; var used = 0
        val a = Math.toRadians(pose.yawDeg); val c = cos(a); val s = sin(a)
        for (p in points) {
            val wx = pose.xCm + p.x * c + p.y * s
            val wy = pose.yCm - p.x * s + p.y * c
            val gx = (width / 2.0 + wx / cellSizeCm).roundToInt()
            val gy = (height / 2.0 - wy / cellSizeCm).roundToInt()
            if (gx !in 1 until width - 1 || gy !in 1 until height - 1) continue
            var local = 0
            for (dy in -1..1) for (dx in -1..1)
                local = maxOf(local, cells[(gy + dy) * width + gx + dx].toInt())
            if (local > 0) sum += (local / 40f).coerceAtMost(1f)
            used++
        }
        return if (used < 25) 0f else sum / used
    }

    private fun integrate(points: List<Point>, pose: RobotPose2D) {
        val rx = (width / 2.0 + pose.xCm / cellSizeCm).roundToInt()
        val ry = (height / 2.0 - pose.yCm / cellSizeCm).roundToInt()
        if (rx !in 1 until width - 1 || ry !in 1 until height - 1) return
        val a = Math.toRadians(pose.yawDeg); val c = cos(a); val s = sin(a)
        for (p in points) {
            val wx = pose.xCm + p.x * c + p.y * s
            val wy = pose.yCm - p.x * s + p.y * c
            val ex = (width / 2.0 + wx / cellSizeCm).roundToInt()
            val ey = (height / 2.0 - wy / cellSizeCm).roundToInt()
            if (ex !in 1 until width - 1 || ey !in 1 until height - 1) continue
            ray(rx, ry, ex, ey)
            val idx = ey * width + ex
            cells[idx] = (cells[idx].toInt() + 5).coerceAtMost(40).toByte()
        }
    }

    private fun ray(x0: Int, y0: Int, x1: Int, y1: Int) {
        var x = x0; var y = y0
        val dx = abs(x1 - x0); val sx = if (x0 < x1) 1 else -1
        val dy = -abs(y1 - y0); val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (x != x1 || y != y1) {
            val idx = y * width + x
            cells[idx] = (cells[idx].toInt() - 1).coerceAtLeast(-20).toByte()
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
            if (x !in 1 until width - 1 || y !in 1 until height - 1) break
        }
    }

    @Synchronized fun snapshot(): ByteArray = cells.copyOf()
    @Synchronized fun integratedScans(): Int = scansIntegrated
    @Synchronized fun matchStatus(): Pair<Boolean, Float> = lastTracking to lastScore
    @Synchronized fun loopClosureDetected(): Boolean = lastLoopClosure
    @Synchronized fun currentPose(): RobotPose2D = lastPose
    @Synchronized fun trajectorySnapshot(): List<RobotPose2D> = trajectory.toList()

    @Synchronized fun robotCell(pose: RobotPose2D): Pair<Float, Float>? {
        if (!initialized) return null
        val x = width / 2.0 + pose.xCm / cellSizeCm
        val y = height / 2.0 - pose.yCm / cellSizeCm
        if (x < 0.0 || x >= width || y < 0.0 || y >= height) return null
        return x.toFloat() to y.toFloat()
    }

    private fun distance(a: RobotPose2D, b: RobotPose2D) = hypot(a.xCm - b.xCm, a.yCm - b.yCm)
    private fun normalize(v: Double): Double {
        var a = v % 360.0
        if (a > 180.0) a -= 360.0
        if (a < -180.0) a += 360.0
        return a
    }
}