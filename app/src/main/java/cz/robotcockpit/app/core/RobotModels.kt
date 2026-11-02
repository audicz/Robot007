package cz.robotcockpit.app.core

data class Pose2D(val x: Double, val y: Double, val yaw: Double)
data class Twist2D(val linear: Double, val angular: Double)
data class LidarScan(
    val angleMin: Double,
    val angleIncrement: Double,
    val rangeMin: Double,
    val rangeMax: Double,
    val rangesMeters: FloatArray,
    val timestampNanos: Long = System.nanoTime()
)
data class WheelTicks(val left: Long, val right: Long, val timestampNanos: Long = System.nanoTime())
data class RobotHealth(
    val bluetoothConnected: Boolean = false,
    val armed: Boolean = false,
    val commandFresh: Boolean = false,
    val batteryVolts: Double? = null,
    val lastTelemetryNanos: Long = 0L,
    val fault: String? = null
)
