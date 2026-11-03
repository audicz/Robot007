package cz.robotcockpit.app.brain

import java.util.PriorityQueue
import kotlin.math.abs

data class GridCell(val x: Int, val y: Int)
data class QueueNode(val cell: GridCell, val g: Int, val f: Int)

/** A* on confirmed-free cells only; unknown space is deliberately blocked. */
class AStarPlanner(private val map: RobotMap) {
    fun plan(start: GridCell, goal: GridCell, clearanceCells: Int = 2): List<GridCell> {
        if (!map.isFree(start.x,start.y) || !map.isFree(goal.x,goal.y)) return emptyList()
        val open = PriorityQueue<QueueNode>(compareBy { it.f })
        val gScore = HashMap<GridCell,Int>()
        val parent = HashMap<GridCell,GridCell>()
        gScore[start]=0; open.add(QueueNode(start,0,heuristic(start,goal)))
        val dirs = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)
        val closed = HashSet<GridCell>()
        while (open.isNotEmpty() && closed.size < map.width*map.height) {
            val cur=open.poll()
            if (!closed.add(cur.cell)) continue
            if (cur.cell==goal) {
                val path=mutableListOf(goal); var p=goal
                while (p!=start) { p=parent[p] ?: return emptyList(); path.add(p) }
                return path.asReversed()
            }
            for ((dx,dy) in dirs) {
                val n=GridCell(cur.cell.x+dx,cur.cell.y+dy)
                if (n.x !in 0 until map.width || n.y !in 0 until map.height || closed.contains(n)) continue
                if (!map.isFree(n.x,n.y) || !clearanceOk(n,clearanceCells)) continue
                val tentative=cur.g+1
                if (tentative < (gScore[n] ?: Int.MAX_VALUE)) {
                    parent[n]=cur.cell; gScore[n]=tentative
                    open.add(QueueNode(n,tentative,tentative+heuristic(n,goal)))
                }
            }
        }
        return emptyList()
    }
    private fun clearanceOk(c:GridCell,r:Int):Boolean {
        for (y in c.y-r..c.y+r) for (x in c.x-r..c.x+r)
            if (map.isOccupied(x,y)) return false
        return true
    }
    private fun heuristic(a:GridCell,b:GridCell)=abs(a.x-b.x)+abs(a.y-b.y)
}
