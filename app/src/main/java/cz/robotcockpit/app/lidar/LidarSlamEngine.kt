package cz.robotcockpit.app.lidar

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Lightweight 2D LiDAR-only SLAM: correlative scan matching against a rolling
 * occupancy grid, followed by ray integration. It estimates relative pose;
 * it is not a substitute for wheel odometry/IMU when scans have little overlap.
 */
data class SlamSnapshot(
    val cells: IntArray,
    val gridSize: Int,
    val resolution: Float,
    val x: Float,
    val y: Float,
    val headingDeg: Float,
    val scansIntegrated: Int,
    val matchQuality: Float,
    val path: List<Offset>,
    val running: Boolean
)

class LidarSlamEngine(
    private val gridSize: Int = 240,
    private val resolution: Float = 0.05f
) {
    private val cells = IntArray(gridSize * gridSize)
    private var poseX = 0f
    private var poseY = 0f
    private var heading = 0f
    private var integrated = 0
    private var quality = 0f
    private var running = false
    private var lastScanNanos = 0L
    private val trajectory = ArrayList<Offset>()

    @Synchronized fun start() { running = true }
    @Synchronized fun pause() { running = false }

    @Synchronized fun reset() {
        cells.fill(0)
        poseX = 0f
        poseY = 0f
        heading = 0f
        integrated = 0
        quality = 0f
        lastScanNanos = 0L
        trajectory.clear()
    }

    @Synchronized fun isRunning() = running

    @Synchronized fun process(ranges: FloatArray, receivedAtNanos: Long): SlamSnapshot {
        if (!running || receivedAtNanos == lastScanNanos) return snapshot()
        lastScanNanos = receivedAtNanos
        val points = ArrayList<Pair<Float, Float>>(90)
        for (i in 0 until min(360, ranges.size) step 4) {
            val d = ranges[i]
            if (d.isFinite() && d in 0.12f..8.0f) {
                val a = Math.toRadians(i.toDouble())
                points.add(sin(a).toFloat() * d to cos(a).toFloat() * d)
            }
        }
        if (points.size < 12) return snapshot()
        if (integrated > 0) match(points)
        integrate(ranges)
        integrated++
        if (trajectory.isEmpty() || hypot(trajectory.last().x - poseX, trajectory.last().y - poseY) > 0.04f) {
            trajectory.add(Offset(poseX, poseY))
            if (trajectory.size > 2000) trajectory.removeAt(0)
        }
        return snapshot()
    }

    private fun match(points: List<Pair<Float, Float>>) {
        val baseX = poseX
        val baseY = poseY
        val baseH = heading
        var best = -1f
        var bestX = baseX
        var bestY = baseY
        var bestH = baseH
        // Coarse correlative search: +/- 30 cm and +/- 8 degrees.
        for (dy in -6..6 step 2) for (dx in -6..6 step 2) for (da in -4..4 step 2) {
            val x = baseX + dx * resolution
            val y = baseY + dy * resolution
            val h = baseH + Math.toRadians((da * 2).toDouble()).toFloat()
            val score = score(points, x, y, h)
            if (score > best) { best = score; bestX = x; bestY = y; bestH = h }
        }
        // Fine search around the coarse optimum.
        val coarseX = bestX
        val coarseY = bestY
        val coarseH = bestH
        for (dy in -2..2) for (dx in -2..2) for (da in -2..2) {
            val x = coarseX + dx * resolution
            val y = coarseY + dy * resolution
            val h = coarseH + Math.toRadians(da.toDouble()).toFloat()
            val score = score(points, x, y, h)
            if (score > best) { best = score; bestX = x; bestY = y; bestH = h }
        }
        quality = if (points.isEmpty()) 0f else (best / points.size).coerceIn(0f, 1f)
        // Reject implausible jumps when overlap is poor.
        if (quality >= 0.08f) {
            poseX = bestX
            poseY = bestY
            heading = normalizeAngle(bestH)
        }
    }

    private fun score(points: List<Pair<Float, Float>>, x: Float, y: Float, h: Float): Float {
        val c = cos(h); val s = sin(h)
        var hits = 0f
        for (p in points) {
            val wx = x + p.first * c + p.second * s
            val wy = y - p.first * s + p.second * c
            val gx = (gridSize / 2f + wx / resolution).roundToInt()
            val gy = (gridSize / 2f - wy / resolution).roundToInt()
            if (gx !in 2 until gridSize - 2 || gy !in 2 until gridSize - 2) continue
            var local = 0
            for (oy in -2..2) for (ox in -2..2) {
                local = max(local, cells[(gy + oy) * gridSize + gx + ox])
            }
            hits += (local.coerceAtLeast(0) / 100f)
        }
        return hits
    }

    private fun integrate(ranges: FloatArray) {
        val c = cos(heading); val s = sin(heading)
        for (i in 0 until min(360, ranges.size)) {
            val d = ranges[i]
            if (!d.isFinite() || d !in 0.08f..8.0f) continue
            val a = Math.toRadians(i.toDouble())
            val lx = sin(a).toFloat() * d
            val ly = cos(a).toFloat() * d
            val wx = poseX + lx * c + ly * s
            val wy = poseY - lx * s + ly * c
            val x0 = (gridSize / 2f + poseX / resolution).roundToInt()
            val y0 = (gridSize / 2f - poseY / resolution).roundToInt()
            val x1 = (gridSize / 2f + wx / resolution).roundToInt()
            val y1 = (gridSize / 2f - wy / resolution).roundToInt()
            rayFree(x0, y0, x1, y1)
            if (x1 in 0 until gridSize && y1 in 0 until gridSize) {
                val idx = y1 * gridSize + x1
                cells[idx] = min(100, cells[idx] + 28)
            }
        }
    }

    private fun rayFree(x0: Int, y0: Int, x1: Int, y1: Int) {
        var x = x0; var y = y0
        val dx = abs(x1 - x0); val sx = if (x0 < x1) 1 else -1
        val dy = -abs(y1 - y0); val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        var steps = 0
        while ((x != x1 || y != y1) && steps++ < gridSize * 2) {
            if (x in 0 until gridSize && y in 0 until gridSize) {
                val idx = y * gridSize + x
                cells[idx] = max(-40, cells[idx] - 2)
            }
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    /** A* on the occupancy grid. Unknown space is traversable with a higher cost. */
    @Synchronized fun planPath(start: Offset, goal: Offset): List<Offset> {
        fun toCell(p: Offset): Pair<Int, Int> =
            (gridSize / 2f + p.x / resolution).roundToInt() to (gridSize / 2f - p.y / resolution).roundToInt()
        val (sx, sy) = toCell(start); val (gx, gy) = toCell(goal)
        if (sx !in 1 until gridSize - 1 || sy !in 1 until gridSize - 1 ||
            gx !in 1 until gridSize - 1 || gy !in 1 until gridSize - 1) return emptyList()
        val total = gridSize * gridSize
        val costs = FloatArray(total) { Float.POSITIVE_INFINITY }
        val parent = IntArray(total) { -1 }
        val closed = BooleanArray(total)
        val open = java.util.PriorityQueue<Pair<Int, Float>>(compareBy { it.second })
        val startId = sy * gridSize + sx
        val goalId = gy * gridSize + gx
        costs[startId] = 0f
        open.add(startId to 0f)
        val dirs = arrayOf(-1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1)
        var visited = 0
        while (open.isNotEmpty() && visited++ < 30000) {
            val id = open.poll().first
            if (closed[id]) continue
            if (id == goalId) break
            closed[id] = true
            val x = id % gridSize; val y = id / gridSize
            for ((dx, dy) in dirs) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 1 until gridSize - 1 || ny !in 1 until gridSize - 1) continue
                val ni = ny * gridSize + nx
                if (closed[ni] || cells[ni] >= 45) continue
                // Penalize unknown cells; discourage cutting corners across occupied cells.
                if (dx != 0 && dy != 0 &&
                    (cells[y * gridSize + nx] >= 45 || cells[ny * gridSize + x] >= 45)) continue
                val terrain = if (cells[ni] == 0) 3.5f else 1f
                val next = costs[id] + (if (dx != 0 && dy != 0) 1.4142f else 1f) * terrain
                if (next < costs[ni]) {
                    costs[ni] = next
                    parent[ni] = id
                    val heuristic = hypot((gx - nx).toFloat(), (gy - ny).toFloat())
                    open.add(ni to next + heuristic)
                }
            }
        }
        if (parent[goalId] == -1 && goalId != startId) return emptyList()
        val result = ArrayList<Offset>()
        var cursor = goalId
        while (cursor != startId && cursor >= 0) {
            val x = cursor % gridSize; val y = cursor / gridSize
            result.add(Offset((x - gridSize / 2f) * resolution, (gridSize / 2f - y) * resolution))
            cursor = parent[cursor]
            if (result.size > total) return emptyList()
        }
        result.add(start)
        result.reverse()
        return result
    }

    @Synchronized fun snapshot() = SlamSnapshot(
        cells = cells.copyOf(), gridSize = gridSize, resolution = resolution,
        x = poseX, y = poseY, headingDeg = Math.toDegrees(heading.toDouble()).toFloat(),
        scansIntegrated = integrated, matchQuality = quality,
        path = trajectory.toList(), running = running
    )

    private fun normalizeAngle(value: Float): Float {
        var a = value
        while (a > Math.PI) a -= (2f * Math.PI.toFloat())
        while (a < -Math.PI) a += (2f * Math.PI.toFloat())
        return a
    }
}
