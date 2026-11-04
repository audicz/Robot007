package cz.robotcockpit.app

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

data class RobotPose2D(val xCm: Double, val yCm: Double, val yawDeg: Double)
data class SlamUpdate(
    val pose: RobotPose2D,
    val tracking: Boolean,
    val loopClosure: Boolean,
    val score: Float,
    val scans: Int,
    val keyframes: Int = 0,
    val loopClosures: Int = 0
)

/**
 * Lightweight LiDAR-only 2D SLAM for the Android client.
 *
 * Pipeline:
 *  - correlative scan-to-occupancy-map tracking (no wheel odometry required)
 *  - keyframe submap scans and sequential relative-pose constraints
 *  - rotation-invariant polar descriptors + point-to-point ICP loop candidates
 *  - pose-graph relaxation and occupancy-map rebuild after accepted loop closure
 *  - binary persistence of map, keyframes and graph
 *
 * This is a practical embedded SLAM implementation, not a drop-in replacement
 * for Cartographer / ORB-SLAM. It has no probabilistic submap matcher or
 * full nonlinear sparse least-squares solver. Loop closures are gated by
 * descriptor similarity, ICP overlap and ICP residual to limit false matches.
 */
class LidarSlamGrid(val width: Int = 600, val height: Int = 600, val cellSizeCm: Float = 5f) {
    private val cells = ByteArray(width * height)
    private var initialized = false
    private var scansIntegrated = 0
    private var lastPose = RobotPose2D(0.0, 0.0, 0.0)
    private var lastScore = 0f
    private var lastTracking = false
    private var lastLoopClosure = false
    private var loopClosureCount = 0
    private var framesSinceKeyframe = 0
    private val keyframes = ArrayList<Keyframe>()
    private val edges = ArrayList<GraphEdge>()

    private data class Point(val x: Double, val y: Double) {
        val range: Double get() = hypot(x, y)
    }
    private data class Match(val pose: RobotPose2D, val score: Float)
    private data class Keyframe(val pose: RobotPose2D, val points: List<Point>)
    private data class GraphEdge(
        val from: Int,
        val to: Int,
        val dx: Double,
        val dy: Double,
        val dyaw: Double,
        val loop: Boolean
    )
    private data class DescriptorMatch(val index: Int, val shift: Int, val error: Double, val overlap: Int)
    private data class IcpResult(val dx: Double, val dy: Double, val yaw: Double, val rmse: Double, val overlap: Double)

    @Synchronized fun clear() {
        cells.fill(0)
        initialized = false
        scansIntegrated = 0
        lastPose = RobotPose2D(0.0, 0.0, 0.0)
        lastScore = 0f
        lastTracking = false
        lastLoopClosure = false
        loopClosureCount = 0
        framesSinceKeyframe = 0
        keyframes.clear()
        edges.clear()
    }

    @Synchronized fun process(frame: LidarFrame): SlamUpdate? {
        if (width < 32 || height < 32 || cellSizeCm <= 0f) return null
        val points = scanPoints(frame)
        if (points.size < 35) {
            lastTracking = false
            lastScore = 0f
            return null
        }

        if (!initialized) {
            initialized = true
            lastPose = RobotPose2D(0.0, 0.0, 0.0)
            integrate(points, lastPose)
            scansIntegrated++
            addKeyframe(points, lastPose, null)
            lastTracking = true
            lastScore = 1f
            return update(false)
        }

        // Local scan-to-map search, with a finer second pass around the best pose.
        val coarse = search(points, lastPose, 45.0, 30.0, 10.0, 5.0)
        val fine = if (coarse.score >= 0.10f) {
            search(points, coarse.pose, 10.0, 6.0, 2.0, 1.0)
        } else coarse

        val tracked = fine.score >= 0.10f
        lastScore = fine.score
        lastTracking = tracked
        lastLoopClosure = false
        if (!tracked) return update(false)

        lastPose = fine.pose
        integrate(points, lastPose)
        scansIntegrated++
        framesSinceKeyframe++

        val previous = keyframes.lastOrNull()
        val shouldKeyframe = previous == null ||
            distance(previous.pose, lastPose) >= 25.0 ||
            abs(normalize(lastPose.yawDeg - previous.pose.yawDeg)) >= 12.0 ||
            framesSinceKeyframe >= 18

        if (shouldKeyframe) {
            val previousIndex = keyframes.lastIndex
            addKeyframe(points, lastPose, previousIndex.takeIf { it >= 0 })
            framesSinceKeyframe = 0

            if (keyframes.size >= 10 && keyframes.size % 3 == 0) {
                val loop = detectLoop(keyframes.lastIndex)
                if (loop != null) {
                    val (candidateIndex, relative) = loop
                    val currentIndex = keyframes.lastIndex
                    if (edges.none { it.loop && it.from == candidateIndex && it.to == currentIndex }) {
                        edges.add(GraphEdge(candidateIndex, currentIndex, relative.dx, relative.dy, relative.yaw, true))
                        loopClosureCount++
                        optimizePoseGraph()
                        rebuildMapFromKeyframes()
                        lastPose = keyframes.last().pose
                        lastLoopClosure = true
                    }
                }
            }
        }
        return update(lastLoopClosure)
    }

    private fun update(loop: Boolean) = SlamUpdate(
        lastPose, lastTracking, loop, lastScore, scansIntegrated, keyframes.size, loopClosureCount
    )

    private fun addKeyframe(points: List<Point>, pose: RobotPose2D, previousIndex: Int?) {
        val index = keyframes.size
        if (previousIndex != null && previousIndex in keyframes.indices) {
            val relative = between(keyframes[previousIndex].pose, pose)
            edges.add(GraphEdge(previousIndex, index, relative.xCm, relative.yCm, relative.yawDeg, false))
        }
        keyframes.add(Keyframe(pose, points.toList()))
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

    private fun search(
        points: List<Point>, center: RobotPose2D,
        xyRadius: Double, yawRadius: Double, xyStep: Double, yawStep: Double
    ): Match {
        var bestPose = center
        var best = score(points, center)
        val n = (xyRadius / xyStep).roundToInt()
        val a = (yawRadius / yawStep).roundToInt()
        for (iy in -n..n) for (ix in -n..n) for (ia in -a..a) {
            if (ix == 0 && iy == 0 && ia == 0) continue
            val candidate = RobotPose2D(
                center.xCm + ix * xyStep,
                center.yCm + iy * xyStep,
                normalize(center.yawDeg + ia * yawStep)
            )
            val value = score(points, candidate)
            if (value > best) {
                best = value
                bestPose = candidate
            }
        }
        return Match(bestPose, best)
    }

    private fun score(points: List<Point>, pose: RobotPose2D): Float {
        var sum = 0f
        var used = 0
        val a = Math.toRadians(pose.yawDeg)
        val c = cos(a)
        val s = sin(a)
        for (p in points) {
            val wx = pose.xCm + p.x * c + p.y * s
            val wy = pose.yCm - p.x * s + p.y * c
            val gx = (width / 2.0 + wx / cellSizeCm).roundToInt()
            val gy = (height / 2.0 - wy / cellSizeCm).roundToInt()
            if (gx !in 1 until width - 1 || gy !in 1 until height - 1) continue
            var local = 0
            for (dy in -1..1) for (dx in -1..1) {
                local = maxOf(local, cells[(gy + dy) * width + gx + dx].toInt())
            }
            if (local > 0) sum += (local / 40f).coerceAtMost(1f)
            used++
        }
        return if (used < 25) 0f else sum / used
    }

    /** Compare polar range signatures over all rotations; retain a few plausible revisits. */
    private fun detectLoop(currentIndex: Int): Pair<Int, IcpResult>? {
        val current = keyframes[currentIndex]
        val currentDescriptor = descriptor(current.points)
        val candidates = ArrayList<DescriptorMatch>()
        for (i in 0 until currentIndex - 8) {
            if (edges.any { it.loop && it.from == i && it.to == currentIndex }) continue
            val d = compareDescriptors(currentDescriptor, descriptor(keyframes[i].points), i)
            if (d != null && d.overlap >= 20 && d.error < 0.16) candidates.add(d)
        }
        val bestCandidates = candidates.sortedBy { it.error }.take(4)
        for (candidate in bestCandidates) {
            val target = keyframes[candidate.index].points
            val initialYaw = candidate.shift * 10.0
            val icp = icpAlign(current.points, target, initialYaw) ?: continue
            if (icp.overlap >= 0.42 && icp.rmse <= 14.0) {
                return candidate.index to icp
            }
        }
        return null
    }

    private fun descriptor(points: List<Point>): DoubleArray {
        val bins = DoubleArray(36) { Double.NaN }
        for (p in points) {
            val angle = ((Math.toDegrees(atan2(p.x, p.y)) + 360.0) % 360.0)
            val bin = (angle / 10.0).toInt().coerceIn(0, 35)
            if (bins[bin].isNaN() || p.range < bins[bin]) bins[bin] = p.range
        }
        return bins
    }

    private fun compareDescriptors(a: DoubleArray, b: DoubleArray, index: Int): DescriptorMatch? {
        var bestError = Double.POSITIVE_INFINITY
        var bestShift = 0
        var bestOverlap = 0
        for (shift in 0 until 36) {
            var error = 0.0
            var overlap = 0
            for (i in 0 until 36) {
                val av = a[i]
                val bv = b[(i + shift) % 36]
                if (av.isNaN() || bv.isNaN()) continue
                error += abs(av - bv) / 1275.0
                overlap++
            }
            if (overlap >= 20) {
                val mean = error / overlap
                if (mean < bestError) {
                    bestError = mean
                    bestShift = shift
                    bestOverlap = overlap
                }
            }
        }
        return if (bestError.isFinite()) DescriptorMatch(index, bestShift, bestError, bestOverlap) else null
    }

    /**
     * ICP maps points from the current scan frame into the candidate keyframe frame.
     * Returns that relative transform, suitable as a pose-graph edge measurement.
     */
    private fun icpAlign(source: List<Point>, target: List<Point>, initialYaw: Double): IcpResult? {
        if (source.size < 25 || target.size < 25) return null
        var yaw = normalize(initialYaw)
        val rotated = source.map { rotate(it, yaw) }
        var tx = target.map { it.x }.average() - rotated.map { it.x }.average()
        var ty = target.map { it.y }.average() - rotated.map { it.y }.average()
        var rmse = Double.POSITIVE_INFINITY
        var overlap = 0.0

        repeat(10) {
            val sx = ArrayList<Double>()
            val sy = ArrayList<Double>()
            val txs = ArrayList<Double>()
            val tys = ArrayList<Double>()
            val maxDistance2 = 35.0 * 35.0
            for (p in source) {
                val q = rotate(p, yaw)
                val x = q.x + tx
                val y = q.y + ty
                var bestD = maxDistance2
                var best: Point? = null
                for (t in target) {
                    val dx = x - t.x
                    val dy = y - t.y
                    val d = dx * dx + dy * dy
                    if (d < bestD) { bestD = d; best = t }
                }
                if (best != null) {
                    sx.add(p.x); sy.add(p.y)
                    txs.add(best.x); tys.add(best.y)
                }
            }
            if (sx.size < 25) return null
            val fit = fitRigid(sx, sy, txs, tys) ?: return null
            yaw = fit.third
            tx = fit.first
            ty = fit.second
            var error = 0.0
            for (i in sx.indices) {
                val q = rotate(Point(sx[i], sy[i]), yaw)
                val dx = q.x + tx - txs[i]
                val dy = q.y + ty - tys[i]
                error += dx * dx + dy * dy
            }
            rmse = kotlin.math.sqrt(error / sx.size)
            overlap = sx.size.toDouble() / source.size
        }
        return if (rmse.isFinite()) IcpResult(tx, ty, yaw, rmse, overlap) else null
    }

    /** Least-squares rigid transform source -> target in x-right/y-forward coordinates. */
    private fun fitRigid(
        sx: List<Double>, sy: List<Double>, tx: List<Double>, ty: List<Double>
    ): Triple<Double, Double, Double>? {
        if (sx.size < 3) return null
        val mx = sx.average()
        val my = sy.average()
        val nx = tx.average()
        val ny = ty.average()
        var dot = 0.0
        var cross = 0.0
        for (i in sx.indices) {
            val ax = sx[i] - mx
            val ay = sy[i] - my
            val bx = tx[i] - nx
            val by = ty[i] - ny
            dot += ax * bx + ay * by
            cross += ay * bx - ax * by
        }
        val yaw = Math.toDegrees(atan2(cross, dot))
        val rotatedMean = rotate(Point(mx, my), yaw)
        return Triple(nx - rotatedMean.x, ny - rotatedMean.y, yaw)
    }

    private fun optimizePoseGraph() {
        if (keyframes.size < 2 || edges.isEmpty()) return
        val poses = keyframes.map { it.pose }.toMutableList()
        repeat(35) {
            for (edge in edges) {
                val from = poses[edge.from]
                val to = poses[edge.to]
                val predicted = between(from, to)
                val ex = predicted.xCm - edge.dx
                val ey = predicted.yCm - edge.dy
                val ea = normalize(predicted.yawDeg - edge.dyaw)
                val gain = if (edge.loop) 0.28 else 0.18
                val worldError = rotate(Point(ex, ey), from.yawDeg)
                if (edge.to != 0) {
                    val p = poses[edge.to]
                    poses[edge.to] = RobotPose2D(
                        p.xCm - worldError.x * gain,
                        p.yCm - worldError.y * gain,
                        normalize(p.yawDeg - ea * gain)
                    )
                }
                if (edge.from != 0) {
                    val p = poses[edge.from]
                    poses[edge.from] = RobotPose2D(
                        p.xCm + worldError.x * gain * 0.5,
                        p.yCm + worldError.y * gain * 0.5,
                        normalize(p.yawDeg + ea * gain * 0.5)
                    )
                }
            }
        }
        for (i in keyframes.indices) {
            val old = keyframes[i]
            keyframes[i] = old.copy(pose = poses[i])
        }
    }

    private fun rebuildMapFromKeyframes() {
        cells.fill(0)
        for (keyframe in keyframes) integrate(keyframe.points, keyframe.pose)
    }

    private fun integrate(points: List<Point>, pose: RobotPose2D) {
        val rx = (width / 2.0 + pose.xCm / cellSizeCm).roundToInt()
        val ry = (height / 2.0 - pose.yCm / cellSizeCm).roundToInt()
        if (rx !in 1 until width - 1 || ry !in 1 until height - 1) return
        for (p in points) {
            val world = transform(p, pose)
            val ex = (width / 2.0 + world.x / cellSizeCm).roundToInt()
            val ey = (height / 2.0 - world.y / cellSizeCm).roundToInt()
            if (ex !in 1 until width - 1 || ey !in 1 until height - 1) continue
            ray(rx, ry, ex, ey)
            val idx = ey * width + ex
            cells[idx] = (cells[idx].toInt() + 5).coerceAtMost(40).toByte()
        }
    }

    private fun ray(x0: Int, y0: Int, x1: Int, y1: Int) {
        var x = x0
        var y = y0
        val dx = abs(x1 - x0)
        val sx = if (x0 < x1) 1 else -1
        val dy = -abs(y1 - y0)
        val sy = if (y0 < y1) 1 else -1
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

    private fun rotate(p: Point, yawDeg: Double): Point {
        val a = Math.toRadians(yawDeg)
        return Point(p.x * cos(a) + p.y * sin(a), -p.x * sin(a) + p.y * cos(a))
    }

    private fun transform(p: Point, pose: RobotPose2D): Point {
        val r = rotate(p, pose.yawDeg)
        return Point(r.x + pose.xCm, r.y + pose.yCm)
    }

    /** Return T_from^-1 * T_to expressed in the from-frame. */
    private fun between(from: RobotPose2D, to: RobotPose2D): RobotPose2D {
        val dx = to.xCm - from.xCm
        val dy = to.yCm - from.yCm
        val a = Math.toRadians(from.yawDeg)
        return RobotPose2D(
            dx * cos(a) - dy * sin(a),
            dx * sin(a) + dy * cos(a),
            normalize(to.yawDeg - from.yawDeg)
        )
    }

    private fun distance(a: RobotPose2D, b: RobotPose2D) = hypot(a.xCm - b.xCm, a.yCm - b.yCm)

    private fun normalize(value: Double): Double {
        var a = value % 360.0
        if (a > 180.0) a -= 360.0
        if (a < -180.0) a += 360.0
        return a
    }

    @Synchronized fun snapshot(): ByteArray = cells.copyOf()
    @Synchronized fun integratedScans(): Int = scansIntegrated
    @Synchronized fun matchStatus(): Pair<Boolean, Float> = lastTracking to lastScore
    @Synchronized fun loopClosureDetected(): Boolean = lastLoopClosure
    @Synchronized fun currentPose(): RobotPose2D = lastPose
    @Synchronized fun loopClosures(): Int = loopClosureCount
    @Synchronized fun keyframeCount(): Int = keyframes.size
    @Synchronized fun trajectorySnapshot(): List<RobotPose2D> =
        keyframes.map { it.pose } + if (keyframes.isEmpty() || distance(keyframes.last().pose, lastPose) > 1.0) listOf(lastPose) else emptyList()

    @Synchronized fun robotCell(pose: RobotPose2D): Pair<Float, Float>? {
        if (!initialized) return null
        val x = width / 2.0 + pose.xCm / cellSizeCm
        val y = height / 2.0 - pose.yCm / cellSizeCm
        if (x < 0.0 || x >= width || y < 0.0 || y >= height) return null
        return x.toFloat() to y.toFloat()
    }

    /** Atomic binary save. The file includes keyframe scans so SLAM can continue after restart. */
    @Synchronized fun saveTo(file: File): Boolean {
        return try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            DataOutputStream(BufferedOutputStream(temp.outputStream())).use { out ->
                out.writeInt(0x5237534C) // R7SL
                out.writeInt(2)
                out.writeInt(width); out.writeInt(height); out.writeFloat(cellSizeCm)
                out.writeBoolean(initialized)
                out.writeInt(scansIntegrated)
                writePose(out, lastPose)
                out.writeFloat(lastScore)
                out.writeBoolean(lastTracking)
                out.writeInt(loopClosureCount)
                out.writeInt(cells.size)
                out.write(cells)
                out.writeInt(keyframes.size)
                for (k in keyframes) {
                    writePose(out, k.pose)
                    out.writeInt(k.points.size)
                    for (p in k.points) { out.writeFloat(p.x.toFloat()); out.writeFloat(p.y.toFloat()) }
                }
                out.writeInt(edges.size)
                for (e in edges) {
                    out.writeInt(e.from); out.writeInt(e.to)
                    out.writeDouble(e.dx); out.writeDouble(e.dy); out.writeDouble(e.dyaw)
                    out.writeBoolean(e.loop)
                }
            }
            if (file.exists()) file.delete()
            temp.renameTo(file)
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized fun loadFrom(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        return try {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                require(input.readInt() == 0x5237534C)
                require(input.readInt() == 2)
                require(input.readInt() == width && input.readInt() == height)
                require(kotlin.math.abs(input.readFloat() - cellSizeCm) < 0.001f)
                val loadedInitialized = input.readBoolean()
                val loadedScans = input.readInt().coerceAtLeast(0)
                val loadedPose = readPose(input)
                val loadedScore = input.readFloat()
                val loadedTracking = input.readBoolean()
                val loadedLoops = input.readInt().coerceAtLeast(0)
                val cellCount = input.readInt()
                require(cellCount == cells.size)
                val loadedCells = ByteArray(cellCount)
                input.readFully(loadedCells)
                val keyframeCount = input.readInt()
                require(keyframeCount in 0..2000)
                val loadedKeyframes = ArrayList<Keyframe>(keyframeCount)
                repeat(keyframeCount) {
                    val pose = readPose(input)
                    val count = input.readInt()
                    require(count in 0..360)
                    val points = ArrayList<Point>(count)
                    repeat(count) { points.add(Point(input.readFloat().toDouble(), input.readFloat().toDouble())) }
                    loadedKeyframes.add(Keyframe(pose, points))
                }
                val edgeCount = input.readInt()
                require(edgeCount in 0..50000)
                val loadedEdges = ArrayList<GraphEdge>(edgeCount)
                repeat(edgeCount) {
                    val from = input.readInt(); val to = input.readInt()
                    val dx = input.readDouble(); val dy = input.readDouble(); val yaw = input.readDouble()
                    val loop = input.readBoolean()
                    require(from in loadedKeyframes.indices && to in loadedKeyframes.indices)
                    loadedEdges.add(GraphEdge(from, to, dx, dy, yaw, loop))
                }
                loadedCells.copyInto(cells)
                initialized = loadedInitialized
                scansIntegrated = loadedScans
                lastPose = loadedPose
                lastScore = loadedScore
                lastTracking = loadedTracking
                lastLoopClosure = false
                loopClosureCount = loadedLoops
                keyframes.clear(); keyframes.addAll(loadedKeyframes)
                edges.clear(); edges.addAll(loadedEdges)
                framesSinceKeyframe = 0
            }
            true
        } catch (_: Exception) {
            clear()
            false
        }
    }

    private fun writePose(out: DataOutputStream, pose: RobotPose2D) {
        out.writeDouble(pose.xCm); out.writeDouble(pose.yCm); out.writeDouble(pose.yawDeg)
    }

    private fun readPose(input: DataInputStream) =
        RobotPose2D(input.readDouble(), input.readDouble(), input.readDouble())
}
