package cz.robotcockpit.app.brain

import cz.robotcockpit.app.core.LidarScan as CoreLidarScan
import cz.robotcockpit.app.core.Pose2D
import cz.robotcockpit.app.core.WheelTicks
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * First integration layer between the existing RobotCockpit core data model
 * and the phone-side mapping/planning modules.
 *
 * This is odometry + occupancy mapping, not scan-matching SLAM yet.
 */
data class DriveCalibration(
    val wheelDiameterMeters: Double,
    val ticksPerWheelRevolution: Double,
    val wheelTrackMeters: Double
) {
    init {
        require(wheelDiameterMeters > 0.0)
        require(ticksPerWheelRevolution > 0.0)
        require(wheelTrackMeters > 0.0)
    }
}

class DifferentialOdometry(private val calibration: DriveCalibration) {
    var pose = Pose2D(0.0, 0.0, 0.0)
        private set
    private var previous: WheelTicks? = null
    private val metresPerTick = PI * calibration.wheelDiameterMeters /
        calibration.ticksPerWheelRevolution

    @Synchronized fun reset(newPose: Pose2D = Pose2D(0.0, 0.0, 0.0)) {
        pose = newPose
        previous = null
    }

    @Synchronized fun correctPose(corrected: Pose2D) {
        // Preserve previous encoder ticks; only correct the estimated world pose.
        pose = corrected
    }

    @Synchronized fun update(ticks: WheelTicks, gyroYawDelta: Double? = null): Pose2D {
        val old = previous
        previous = ticks
        if (old == null) return pose
        val dl = (ticks.left - old.left) * metresPerTick
        val dr = (ticks.right - old.right) * metresPerTick
        val distance = (dl + dr) * 0.5
        val wheelYaw = (dr - dl) / calibration.wheelTrackMeters
        // If a calibrated gyro increment is supplied, blend it with wheel yaw.
        val dyaw = if (gyroYawDelta != null && gyroYawDelta.isFinite())
            wheelYaw * 0.15 + gyroYawDelta * 0.85 else wheelYaw
        val mid = pose.yaw + dyaw * 0.5
        pose = Pose2D(
            pose.x + cos(mid) * distance,
            pose.y + sin(mid) * distance,
            wrapAngle(pose.yaw + dyaw)
        )
        return pose
    }

    private fun wrapAngle(a: Double): Double {
        var v = a
        while (v > PI) v -= 2.0 * PI
        while (v < -PI) v += 2.0 * PI
        return v
    }
}

data class BrainSnapshot(
    val pose: Pose2D,
    val map: RobotMap,
    val scanTimestampNanos: Long,
    val scanPoints: Int,
    val scanMatchAccepted: Boolean = false,
    val scanMatchScore: Double = 0.0
)

class RobotBrainEngine(
    val map: RobotMap = RobotMap(),
    private val odometry: DifferentialOdometry,
    private val scanMatcher: ScanMatcher = ScanMatcher(map)
) {
    @Synchronized fun onWheelTicks(ticks: WheelTicks, gyroYawDelta: Double? = null): Pose2D =
        odometry.update(ticks, gyroYawDelta)

    @Synchronized fun onLidarScan(scan: CoreLidarScan): BrainSnapshot {
        val predicted = odometry.pose
        // The map renderer uses 360 one-degree bins. Resample the source scan
        // using its declared angular metadata rather than assuming its point count.
        val bins = FloatArray(360) { Float.NaN }
        var valid = 0
        for (i in scan.rangesMeters.indices) {
            val range = scan.rangesMeters[i]
            if (!range.isFinite() || range < scan.rangeMin || range > scan.rangeMax) continue
            val angle = scan.angleMin + i * scan.angleIncrement
            val bin = (((Math.toDegrees(angle) % 360.0) + 360.0) % 360.0).roundToInt() % 360
            bins[bin] = range
            valid++
        }
        val converted = LidarScan(
            sequence = (scan.timestampNanos and 0x7fffffffL).toInt(),
            rpm = 0,
            rangesMeters = bins,
            receivedAtMs = scan.timestampNanos / 1_000_000L
        )
        val match = scanMatcher.match(
            bins,
            RobotPose(predicted.x.toFloat(), predicted.y.toFloat(), predicted.yaw.toFloat()),
            scan.rangeMin.toFloat(),
            scan.rangeMax.toFloat()
        )
        val corrected = if (match.accepted) {
            val p = Pose2D(match.pose.x.toDouble(), match.pose.y.toDouble(), match.pose.yaw.toDouble())
            odometry.correctPose(p)
            p
        } else predicted
        map.integrate(converted, RobotPose(corrected.x.toFloat(), corrected.y.toFloat(), corrected.yaw.toFloat()), scan.rangeMax.toFloat())
        return BrainSnapshot(corrected, map, scan.timestampNanos, valid, match.accepted, match.score)
    }

    fun worldToCell(pose: Pose2D): GridCell = GridCell(
        (map.width / 2 + pose.x / map.resolution).roundToInt(),
        (map.height / 2 - pose.y / map.resolution).roundToInt()
    )

    fun planTo(goalWorld: Pose2D): List<GridCell> =
        AStarPlanner(map).plan(worldToCell(odometry.pose), worldToCell(goalWorld))
}
