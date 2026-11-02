package cz.robotcockpit.app.core

/** App-side guard rails. ESP32 watchdog and physical power cutoff remain authoritative. */
class RobotSafetySupervisor(
    private val maxLinearMetersPerSecond: Double,
    private val maxAngularRadiansPerSecond: Double,
    private val telemetryTimeoutMillis: Long = 1_000L
) {
    init {
        require(maxLinearMetersPerSecond > 0.0)
        require(maxAngularRadiansPerSecond > 0.0)
        require(telemetryTimeoutMillis > 0L)
    }

    fun validateCommand(
        requested: Twist2D,
        health: RobotHealth,
        nowNanos: Long = System.nanoTime()
    ): Twist2D {
        val ageMs = if (health.lastTelemetryNanos == 0L) Long.MAX_VALUE
            else (nowNanos - health.lastTelemetryNanos).coerceAtLeast(0L) / 1_000_000L

        if (!health.bluetoothConnected || !health.armed || !health.commandFresh ||
            ageMs > telemetryTimeoutMillis || health.fault != null
        ) return Twist2D(0.0, 0.0)

        return Twist2D(
            requested.linear.coerceIn(-maxLinearMetersPerSecond, maxLinearMetersPerSecond),
            requested.angular.coerceIn(-maxAngularRadiansPerSecond, maxAngularRadiansPerSecond)
        )
    }
}
