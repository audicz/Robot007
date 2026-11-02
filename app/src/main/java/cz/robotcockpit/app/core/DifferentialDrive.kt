package cz.robotcockpit.app.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Wheel-tick odometry. Configure using measured wheel radius, track and effective encoder ticks/revolution. */
class DifferentialDrive(
    private val wheelRadiusMeters: Double,
    private val wheelTrackMeters: Double,
    private val ticksPerWheelRevolution: Double
) {
    init {
        require(wheelRadiusMeters > 0.0 && wheelTrackMeters > 0.0 && ticksPerWheelRevolution > 0.0)
    }

    private var previous: WheelTicks? = null
    var pose = Pose2D(0.0, 0.0, 0.0)
        private set

    fun reset(newPose: Pose2D = Pose2D(0.0, 0.0, 0.0)) {
        pose = newPose
        previous = null
    }

    fun update(ticks: WheelTicks): Pose2D {
        val old = previous
        previous = ticks
        if (old == null) return pose

        val metersPerTick = 2.0 * PI * wheelRadiusMeters / ticksPerWheelRevolution
        val dl = (ticks.left - old.left) * metersPerTick
        val dr = (ticks.right - old.right) * metersPerTick
        val distance = (dl + dr) * 0.5
        val dYaw = (dr - dl) / wheelTrackMeters
        val midYaw = pose.yaw + dYaw * 0.5
        pose = Pose2D(
            pose.x + distance * cos(midYaw),
            pose.y + distance * sin(midYaw),
            normalizeAngle(pose.yaw + dYaw)
        )
        return pose
    }

    companion object {
        fun normalizeAngle(angle: Double): Double {
            var a = angle
            while (a > PI) a -= 2.0 * PI
            while (a < -PI) a += 2.0 * PI
            return a
        }
    }
}
