package cz.robotcockpit.app.brain

/** Autonomous drive is gated until live sensor, odometry and stop-chain checks pass. */
data class BrainReadiness(
    val lidarConnected: Boolean = false,
    val scanFresh: Boolean = false,
    val odometryFresh: Boolean = false,
    val encodersCalibrated: Boolean = false,
    val emergencyStopAvailable: Boolean = false,
    val controllerWatchdogConfirmed: Boolean = false
) {
    val autonomousAllowed: Boolean get() = lidarConnected && scanFresh &&
        odometryFresh && encodersCalibrated && emergencyStopAvailable && controllerWatchdogConfirmed
    val missingRequirements: List<String> get() = buildList {
        if (!lidarConnected) add("LiDAR není připojen")
        if (!scanFresh) add("Chybí čerstvý kompletní sken")
        if (!odometryFresh) add("Chybí aktuální odometrie")
        if (!encodersCalibrated) add("Enkodéry nejsou kalibrované")
        if (!emergencyStopAvailable) add("Není ověřené nouzové zastavení")
        if (!controllerWatchdogConfirmed) add("Není potvrzen watchdog řídicí jednotky")
    }
}
