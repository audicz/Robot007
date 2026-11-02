package cz.robotcockpit.app.core

import org.json.JSONObject

/** Line-delimited JSON protocol v1 shared with the current ESP32 SPP firmware. */
object RobotCommandProtocol {
    fun hello(): String = JSONObject().put("type", "hello").put("protocol", 1).toString()

    fun arm(enabled: Boolean): String =
        if (enabled) JSONObject().put("type", "arm").put("armed", true).toString() else stop()

    fun drive(throttle: Double, steer: Double): String {
        require(throttle.isFinite() && steer.isFinite())
        return JSONObject()
            .put("type", "cmd")
            .put("throttle", throttle.coerceIn(-1.0, 1.0))
            .put("steer", steer.coerceIn(-1.0, 1.0))
            .put("armed", true)
            .toString()
    }

    fun stop(): String = JSONObject().put("type", "stop").put("armed", false).toString()
}
