package cz.robotcockpit.app.brain

import cz.robotcockpit.app.core.Pose2D
import cz.robotcockpit.app.core.WheelTicks
import java.io.IOException

/**
 * Phone-side brain facade. A transport adapter feeds raw Xiaomi LDS UART bytes
 * and wheel encoder ticks; this class assembles scans, updates odometry/map,
 * and persists the occupancy grid.
 *
 * The controller deliberately does not open Bluetooth itself: the supplied
 * lidarbridge.yaml currently exposes ESPHome sector sensors, not a raw BLE/SPP
 * point-cloud stream. Transport must provide the actual UART frames first.
 */
class RobotBrainController(
    calibration: DriveCalibration,
    private val mapStore: RobotMapStore
) {
    private val odometry = DifferentialOdometry(calibration)
    private val lidarParser = XiaomiLdsUartParser()
    private var activeMap = RobotMap()
    private var engine = RobotBrainEngine(activeMap, odometry)

    @Volatile private var latest: BrainSnapshot? = null

    val latestSnapshot: BrainSnapshot? get() = latest
    val lidarMotorRpm: Float? get() = lidarParser.lastMotorRpm
    val validLidarPackets: Long get() = lidarParser.validPacketCount
    val invalidLidarPackets: Long get() = lidarParser.invalidPacketCount
    val pose: Pose2D get() = odometry.pose

    /** Feed arbitrary chunks from the transport; one result per full 360° scan. */
    @Synchronized
    fun feedLidarBytes(bytes: ByteArray): List<BrainSnapshot> =
        lidarParser.feed(bytes).map { scan ->
            engine.onLidarScan(scan).also { latest = it }
        }

    @Synchronized
    fun updateWheelTicks(ticks: WheelTicks, gyroYawDelta: Double? = null): Pose2D =
        odometry.update(ticks, gyroYawDelta)

    @Synchronized
    @Throws(IOException::class)
    fun saveMap() = mapStore.save(activeMap)

    @Synchronized
    @Throws(IOException::class)
    fun loadMap() {
        activeMap = mapStore.load()
        engine = RobotBrainEngine(activeMap, odometry)
        latest = null
    }

    @Synchronized
    fun resetLocalization(initialPose: Pose2D = Pose2D(0.0, 0.0, 0.0)) {
        odometry.reset(initialPose)
        latest = null
        lidarParser.reset()
    }

    @Synchronized
    fun clearMap() {
        activeMap.clear()
        latest = null
    }
}
