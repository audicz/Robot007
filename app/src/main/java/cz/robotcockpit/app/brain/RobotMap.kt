package cz.robotcockpit.app.brain

import kotlin.math.*

data class RobotPose(val x: Float = 0f, val y: Float = 0f, val yaw: Float = 0f)

/** Log-odds occupancy map. Coordinates are metres; positive Y is forward. */
class RobotMap(val width: Int = 200, val height: Int = 200, val resolution: Float = 0.05f) {
    private val cells = ByteArray(width * height)
    private val originX = width / 2
    private val originY = height / 2

    @Synchronized fun clear() = cells.fill(0)
    @Synchronized fun value(x: Int, y: Int): Int =
        if (x in 0 until width && y in 0 until height) cells[y * width + x].toInt() else 100
    fun isFree(x: Int, y: Int) = value(x, y) < -2
    fun isOccupied(x: Int, y: Int) = value(x, y) > 3

    @Synchronized fun integrate(scan: LidarScan, pose: RobotPose, maxRangeM: Float = 8f) {
        for (i in 0 until 360) {
            val r = scan.rangesMeters[i]
            if (!r.isFinite() || r < 0.15f || r > maxRangeM) continue
            val a = pose.yaw + Math.toRadians(i.toDouble()).toFloat()
            val sx = pose.x + cos(a) * r
            val sy = pose.y + sin(a) * r
            val x0 = (originX + pose.x / resolution).roundToInt()
            val y0 = (originY - pose.y / resolution).roundToInt()
            val x1 = (originX + sx / resolution).roundToInt()
            val y1 = (originY - sy / resolution).roundToInt()
            ray(x0, y0, x1, y1)
        }
    }

    private fun ray(x0: Int, y0: Int, x1: Int, y1: Int) {
        var x=x0; var y=y0
        val dx=abs(x1-x); val sx=if(x<x1) 1 else -1
        val dy=-abs(y1-y); val sy=if(y<y1) 1 else -1
        var err=dx+dy
        while (true) {
            if (x == x1 && y == y1) { update(x,y,3); break }
            update(x,y,-1)
            val e2=2*err
            if (e2>=dy) { err+=dy; x+=sx }
            if (e2<=dx) { err+=dx; y+=sy }
            if (x !in 0 until width || y !in 0 until height) break
        }
    }
    private fun update(x:Int,y:Int,delta:Int) {
        if (x !in 0 until width || y !in 0 until height) return
        val i=y*width+x
        cells[i]=(cells[i].toInt()+delta).coerceIn(-20,20).toByte()
    }

    fun toByteArray() = cells.copyOf()

    @Synchronized fun loadFromByteArray(data: ByteArray) {
        require(data.size == cells.size) { "Map data size does not match grid dimensions" }
        data.copyInto(cells)
    }
}
