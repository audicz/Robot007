package cz.robotcockpit.app

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.atan2

data class RosTopics(
    val map: String = "/map",
    val scan: String = "/scan",
    val odom: String = "/odom",
    val battery: String = "/battery_state",
    val cmdVel: String = "/cmd_vel",
    val goal: String = "/goal_pose"
)

data class OccupancyGrid(
    val width: Int, val height: Int, val resolution: Float,
    val originX: Float, val originY: Float, val cells: IntArray
)

data class LaserScan(
    val angleMin: Float, val angleIncrement: Float, val rangeMin: Float,
    val rangeMax: Float, val ranges: FloatArray
)

data class RobotTelemetry(
    val connected: Boolean = false,
    val status: String = "OFFLINE",
    val map: OccupancyGrid? = null,
    val scan: LaserScan? = null,
    val x: Float? = null,
    val y: Float? = null,
    val yaw: Float? = null,
    val linearSpeed: Float? = null,
    val angularSpeed: Float? = null,
    val batteryPercent: Float? = null,
    val voltage: Float? = null,
    val messages: Long = 0,
    val lastTopic: String = "—",
    val error: String? = null
)

class RosbridgeClient(private val onTelemetry: (RobotTelemetry) -> Unit, private val onLog: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    @Volatile private var state = RobotTelemetry()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var topics = RosTopics()
    private var requestId = 0

    private fun update(transform: (RobotTelemetry) -> RobotTelemetry) {
        state = transform(state)
        val snapshot = state
        main.post { onTelemetry(snapshot) }
    }

    fun connect(url: String, configuredTopics: RosTopics) {
        disconnect(false)
        topics = configuredTopics
        update { it.copy(connected = false, status = "PŘIPOJOVÁNÍ", error = null) }
        log("Připojuji rosbridge: $url")
        try {
            val request = Request.Builder().url(url.trim()).build()
            socket = http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    socket = webSocket
                    update { it.copy(connected = true, status = "ROS BRIDGE ONLINE", error = null) }
                    log("WebSocket připojen. Odebírám ROS 2 témata.")
                    listOf(topics.map, topics.scan, topics.odom, topics.battery)
                        .filter { it.isNotBlank() }.distinct().forEach { subscribe(it) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try { parse(JSONObject(text)) }
                    catch (e: Exception) { log("Neplatná ROS zpráva: \${e.message ?: "JSON"}") }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                    update { it.copy(connected = false, status = "ODPOJENO") }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    update { it.copy(connected = false, status = "ODPOJENO") }
                    log("Spojení ukončeno: $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socket = null
                    update { it.copy(connected = false, status = "CHYBA SPOJENÍ", error = t.message ?: "WebSocket chyba") }
                    log("Chyba rosbridge: \${t.message ?: t.javaClass.simpleName}")
                }
            })
        } catch (e: Exception) {
            update { it.copy(connected = false, status = "CHYBNÁ ADRESA", error = e.message) }
            log("Neplatná URL: \${e.message}")
        }
    }

    private fun subscribe(topic: String) {
        val id = "sub_\${++requestId}"
        send(JSONObject().put("op", "subscribe").put("id", id).put("topic", topic).put("queue_length", 1).put("throttle_rate", 100))
        log("SUB $topic")
    }

    private fun parse(root: JSONObject) {
        if (root.optString("op") != "publish") return
        val topic = root.optString("topic")
        val msg = root.optJSONObject("msg") ?: return
        update { old ->
            val count = old.messages + 1
            when (topic) {
                topics.map -> old.copy(map = parseMap(msg), messages = count, lastTopic = topic)
                topics.scan -> old.copy(scan = parseScan(msg), messages = count, lastTopic = topic)
                topics.odom -> {
                    val pose = msg.optJSONObject("pose")?.optJSONObject("pose")
                    val pos = pose?.optJSONObject("position")
                    val q = pose?.optJSONObject("orientation")
                    val twist = msg.optJSONObject("twist")?.optJSONObject("twist")
                    val linear = twist?.optJSONObject("linear")?.optDouble("x")
                    val angular = twist?.optJSONObject("angular")?.optDouble("z")
                    val yaw = if (q != null) {
                        val x = q.optDouble("x"); val y = q.optDouble("y")
                        val z = q.optDouble("z"); val w = q.optDouble("w")
                        atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z)).toFloat()
                    } else old.yaw
                    old.copy(
                        x = pos?.optDouble("x")?.toFloat() ?: old.x,
                        y = pos?.optDouble("y")?.toFloat() ?: old.y,
                        yaw = yaw,
                        linearSpeed = linear?.toFloat() ?: old.linearSpeed,
                        angularSpeed = angular?.toFloat() ?: old.angularSpeed,
                        messages = count, lastTopic = topic
                    )
                }
                topics.battery -> {
                    val pct = msg.optDouble("percentage", Double.NaN)
                    val volts = msg.optDouble("voltage", Double.NaN)
                    old.copy(
                        batteryPercent = if (pct.isNaN()) old.batteryPercent else (pct * 100.0).toFloat().coerceIn(0f, 100f),
                        voltage = if (volts.isNaN()) old.voltage else volts.toFloat(),
                        messages = count, lastTopic = topic
                    )
                }
                else -> old.copy(messages = count, lastTopic = topic)
            }
        }
    }

    private fun parseMap(msg: JSONObject): OccupancyGrid? = try {
        val info = msg.getJSONObject("info")
        val origin = info.getJSONObject("origin").getJSONObject("position")
        val width = info.getInt("width")
        val height = info.getInt("height")
        val resolution = info.getDouble("resolution").toFloat()
        val data = msg.getJSONArray("data")
        if (width <= 0 || height <= 0 || width.toLong() * height > 1_000_000L || data.length() < width * height) null
        else {
            val cells = IntArray(width * height)
            for (i in cells.indices) cells[i] = data.optInt(i, -1)
            OccupancyGrid(width, height, resolution, origin.optDouble("x").toFloat(), origin.optDouble("y").toFloat(), cells)
        }
    } catch (_: Exception) { null }

    private fun parseScan(msg: JSONObject): LaserScan? = try {
        val jsonRanges = msg.getJSONArray("ranges")
        val ranges = FloatArray(jsonRanges.length()) { i -> jsonRanges.optDouble(i, Double.NaN).toFloat() }
        LaserScan(
            msg.optDouble("angle_min").toFloat(),
            msg.optDouble("angle_increment").toFloat(),
            msg.optDouble("range_min", 0.05).toFloat(),
            msg.optDouble("range_max", 12.0).toFloat(),
            ranges
        )
    } catch (_: Exception) { null }

    fun publishVelocity(linear: Float, angular: Float) {
        val twist = JSONObject()
            .put("linear", JSONObject().put("x", linear.toDouble()).put("y", 0).put("z", 0))
            .put("angular", JSONObject().put("x", 0).put("y", 0).put("z", angular.toDouble()))
        send(JSONObject().put("op", "publish").put("topic", topics.cmdVel)
            .put("msg", twist).put("type", "geometry_msgs/msg/Twist"))
    }

    fun publishGoal(x: Float, y: Float, yaw: Float = 0f) {
        val half = yaw / 2f
        val pose = JSONObject()
            .put("position", JSONObject().put("x", x.toDouble()).put("y", y.toDouble()).put("z", 0))
            .put("orientation", JSONObject().put("x", 0).put("y", 0)
                .put("z", kotlin.math.sin(half).toDouble()).put("w", kotlin.math.cos(half).toDouble()))
        val msg = JSONObject()
            .put("header", JSONObject().put("frame_id", "map").put("stamp", JSONObject().put("sec", 0).put("nanosec", 0)))
            .put("pose", pose)
        send(JSONObject().put("op", "publish").put("topic", topics.goal)
            .put("msg", msg).put("type", "geometry_msgs/msg/PoseStamped"))
        log("Cíl odeslán: x=%.2f, y=%.2f m".format(x, y))
    }

    private fun send(json: JSONObject) {
        val ws = socket
        if (ws == null || !state.connected) {
            log("Povel neodeslán: rosbridge není připojen")
            return
        }
        if (!ws.send(json.toString())) log("WebSocket odmítl zprávu")
    }

    fun disconnect(sendStop: Boolean = true) {
        if (sendStop && state.connected) publishVelocity(0f, 0f)
        socket?.close(1000, "Uživatel odpojil")
        socket = null
        update { it.copy(connected = false, status = "OFFLINE") }
    }

    private fun log(message: String) {
        main.post { onLog(message) }
    }
}