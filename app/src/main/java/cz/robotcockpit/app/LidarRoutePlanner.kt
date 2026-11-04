package cz.robotcockpit.app

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

data class GridCell(val x: Int, val y: Int)
data class PlannedRoute(val cells: List<GridCell>, val lengthCm: Double, val expandedNodes: Int)

/**
 * Conservative A* planner over the LiDAR occupancy grid.
 * Only cells observed as free are traversable; unknown cells remain blocked.
 */
class LidarRoutePlanner {
    private data class OpenNode(val index: Int, val f: Double)

    fun plan(
        grid: LidarSlamGrid,
        startPose: RobotPose2D,
        requestedGoal: GridCell,
        obstacleInflationCells: Int = 3,
        maxExpandedNodes: Int = 80000
    ): PlannedRoute? {
        val width = grid.width
        val height = grid.height
        val total = width * height
        val occupancy = grid.snapshot()
        if (occupancy.size != total || width < 3 || height < 3) return null

        fun inside(x: Int, y: Int) = x in 1 until width - 1 && y in 1 until height - 1
        fun index(x: Int, y: Int) = y * width + x

        // Inflate occupied cells by the configured robot clearance radius.
        val blocked = BooleanArray(total)
        val radius = obstacleInflationCells.coerceIn(0, 12)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                if (occupancy[index(x, y)].toInt() > 1) {
                    for (dy in -radius..radius) for (dx in -radius..radius) {
                        if (dx * dx + dy * dy <= radius * radius && inside(x + dx, y + dy)) {
                            blocked[index(x + dx, y + dy)] = true
                        }
                    }
                }
            }
        }

        // LiDAR rays are angularly sampled, so free evidence is sparse at long
        // range. Expand only the observed-free corridor by 2 cells (10 cm);
        // unknown space farther away remains blocked.
        val observedFree = BooleanArray(total)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                if (occupancy[index(x, y)].toInt() < 0) {
                    for (dy in -2..2) for (dx in -2..2) {
                        if (dx * dx + dy * dy > 4) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (inside(nx, ny) && !blocked[index(nx, ny)]) observedFree[index(nx, ny)] = true
                    }
                }
            }
        }

        fun walkable(x: Int, y: Int): Boolean =
            inside(x, y) && !blocked[index(x, y)] &&
                (occupancy[index(x, y)].toInt() <= -1 || observedFree[index(x, y)])

        fun nearestWalkable(origin: GridCell, maxRadius: Int): GridCell? {
            if (walkable(origin.x, origin.y)) return origin
            for (r in 1..maxRadius) {
                var best: GridCell? = null
                var bestD = Int.MAX_VALUE
                for (dy in -r..r) for (dx in -r..r) {
                    if (maxOf(abs(dx), abs(dy)) != r) continue
                    val x = origin.x + dx
                    val y = origin.y + dy
                    if (!walkable(x, y)) continue
                    val d = dx * dx + dy * dy
                    if (d < bestD) { bestD = d; best = GridCell(x, y) }
                }
                if (best != null) return best
            }
            return null
        }

        val robot = grid.robotCell(startPose) ?: return null
        val start = nearestWalkable(GridCell(robot.first.toInt(), robot.second.toInt()), 12) ?: return null
        val goal = nearestWalkable(requestedGoal, 6) ?: return null
        val startIndex = index(start.x, start.y)
        val goalIndex = index(goal.x, goal.y)
        if (startIndex == goalIndex) return PlannedRoute(listOf(start), 0.0, 0)

        val gScore = DoubleArray(total) { Double.POSITIVE_INFINITY }
        val parent = IntArray(total) { -1 }
        val closed = BooleanArray(total)
        val open = PriorityQueue<OpenNode>(compareBy { it.f })
        fun heuristic(x: Int, y: Int): Double {
            val dx = abs(goal.x - x).toDouble()
            val dy = abs(goal.y - y).toDouble()
            return maxOf(dx, dy) + (sqrt(2.0) - 1.0) * minOf(dx, dy)
        }
        gScore[startIndex] = 0.0
        open.add(OpenNode(startIndex, heuristic(start.x, start.y)))
        val dirs = arrayOf(
            intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
            intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1)
        )
        var expanded = 0

        while (open.isNotEmpty() && expanded < maxExpandedNodes) {
            val current = open.poll().index
            if (closed[current]) continue
            if (current == goalIndex) {
                val raw = ArrayList<GridCell>()
                var cursor = goalIndex
                while (cursor >= 0) {
                    raw.add(GridCell(cursor % width, cursor / width))
                    if (cursor == startIndex) break
                    cursor = parent[cursor]
                }
                if (raw.lastOrNull() != start) return null
                raw.reverse()
                val simplified = simplify(raw, ::walkable)
                var length = 0.0
                for (i in 1 until simplified.size) {
                    val dx = (simplified[i].x - simplified[i - 1].x).toDouble()
                    val dy = (simplified[i].y - simplified[i - 1].y).toDouble()
                    length += hypot(dx, dy) * grid.cellSizeCm
                }
                return PlannedRoute(simplified, length, expanded)
            }
            closed[current] = true
            expanded++
            val cx = current % width
            val cy = current / width
            for (dir in dirs) {
                val nx = cx + dir[0]
                val ny = cy + dir[1]
                if (!walkable(nx, ny)) continue
                val diagonal = dir[0] != 0 && dir[1] != 0
                if (diagonal && (!walkable(cx + dir[0], cy) || !walkable(cx, cy + dir[1]))) continue
                val next = index(nx, ny)
                if (closed[next]) continue
                val tentative = gScore[current] + if (diagonal) sqrt(2.0) else 1.0
                if (tentative < gScore[next]) {
                    parent[next] = current
                    gScore[next] = tentative
                    open.add(OpenNode(next, tentative + heuristic(nx, ny)))
                }
            }
        }
        return null
    }

    private fun simplify(path: List<GridCell>, walkable: (Int, Int) -> Boolean): List<GridCell> {
        if (path.size < 3) return path
        val result = ArrayList<GridCell>()
        var anchor = 0
        result.add(path.first())
        while (anchor < path.lastIndex) {
            var candidate = path.lastIndex
            while (candidate > anchor + 1 && !lineIsClear(path[anchor], path[candidate], walkable)) candidate--
            result.add(path[candidate])
            anchor = candidate
        }
        return result
    }

    private fun lineIsClear(a: GridCell, b: GridCell, walkable: (Int, Int) -> Boolean): Boolean {
        var x = a.x
        var y = a.y
        val dx = abs(b.x - a.x)
        val sx = if (a.x < b.x) 1 else -1
        val dy = -abs(b.y - a.y)
        val sy = if (a.y < b.y) 1 else -1
        var error = dx + dy
        while (true) {
            if (!walkable(x, y)) return false
            if (x == b.x && y == b.y) return true
            val twice = 2 * error
            if (twice >= dy) { error += dy; x += sx }
            if (twice <= dx) { error += dx; y += sy }
        }
    }
}
