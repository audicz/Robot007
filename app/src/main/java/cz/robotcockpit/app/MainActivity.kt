package cz.robotcockpit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

private val Bg = Color(0xFF080D13)
private val Panel = Color(0xFF111B26)
private val Panel2 = Color(0xFF192735)
private val Border = Color(0xFF2A3C4E)
private val Cyan = Color(0xFF45D9E8)
private val Green = Color(0xFF62E5A0)
private val Amber = Color(0xFFFFC66D)
private val Red = Color(0xFFFF5F6D)
private val Muted = Color(0xFF8EA3B7)
private val Ink = Color(0xFFEAF3FB)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RobotCockpitApp() }
    }
}

@Composable
fun RobotCockpitApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("robot_cockpit", 0) }
    var page by remember { mutableIntStateOf(0) }
    var telemetry by remember { mutableStateOf(RobotTelemetry()) }
    val logs = remember { mutableStateListOf<String>() }
    var url by remember { mutableStateOf(prefs.getString("url", "ws://192.168.1.100:9090") ?: "ws://192.168.1.100:9090") }
    var mapTopic by remember { mutableStateOf(prefs.getString("map", "/map") ?: "/map") }
    var scanTopic by remember { mutableStateOf(prefs.getString("scan", "/scan") ?: "/scan") }
    var odomTopic by remember { mutableStateOf(prefs.getString("odom", "/odom") ?: "/odom") }
    var batteryTopic by remember { mutableStateOf(prefs.getString("battery", "/battery_state") ?: "/battery_state") }
    var cmdTopic by remember { mutableStateOf(prefs.getString("cmd", "/cmd_vel") ?: "/cmd_vel") }
    var goalTopic by remember { mutableStateOf(prefs.getString("goal", "/goal_pose") ?: "/goal_pose") }
    var armed by remember { mutableStateOf(false) }
    var estop by remember { mutableStateOf(true) }
    var throttle by remember { mutableFloatStateOf(0f) }
    var steering by remember { mutableFloatStateOf(0f) }
    var maxLinear by remember { mutableFloatStateOf(0.35f) }
    var maxAngular by remember { mutableFloatStateOf(0.8f) }
    var lastCommand by remember { mutableStateOf("STOP") }

    val client = remember {
        RosbridgeClient(
            onTelemetry = { telemetry = it },
            onLog = { message ->
                logs.add(0, message)
                while (logs.size > 80) logs.removeAt(logs.lastIndex)
            }
        )
    }

    fun connect() {
        prefs.edit().putString("url", url.trim())
            .putString("map", mapTopic.trim()).putString("scan", scanTopic.trim())
            .putString("odom", odomTopic.trim()).putString("battery", batteryTopic.trim())
            .putString("cmd", cmdTopic.trim()).putString("goal", goalTopic.trim()).apply()
        client.connect(url.trim(), RosTopics(mapTopic.trim(), scanTopic.trim(), odomTopic.trim(), batteryTopic.trim(), cmdTopic.trim(), goalTopic.trim()))
        armed = false
        estop = true
        throttle = 0f
        steering = 0f
    }

    fun stopRobot() {
        client.publishVelocity(0f, 0f)
        throttle = 0f
        steering = 0f
        armed = false
        estop = true
        lastCommand = "NOUZOVÝ STOP · nulový Twist odeslán"
    }

    fun drive(y: Float, x: Float) {
        if (telemetry.connected && armed && !estop) {
            throttle = y
            steering = x
            val linear = y * maxLinear
            val angular = -x * maxAngular
            client.publishVelocity(linear, angular)
            lastCommand = "v %.2f m/s · ω %.2f rad/s".format(linear, angular)
        }
    }

    DisposableEffect(Unit) {
        onDispose { client.disconnect(false) }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Cyan, secondary = Green, background = Bg, surface = Panel,
        onPrimary = Bg, onBackground = Ink, onSurface = Ink, error = Red
    )) {
        Column(Modifier.fillMaxSize().background(Bg)) {
            TopHeader(
                connected = telemetry.connected,
                status = telemetry.status,
                estop = estop,
                onConnect = { if (telemetry.connected) { stopRobot(); client.disconnect(); armed = false } else page = 4 }
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (page) {
                    0 -> OverviewScreen(
                        telemetry, armed, estop, throttle, steering, maxLinear, lastCommand,
                        onOpenMap = { page = 1 },
                        onOpenControl = { page = 2 },
                        onArm = { if (telemetry.connected) { estop = false; armed = true; lastCommand = "POHON POVOLEN" } },
                        onStop = { stopRobot() },
                        onDrive = { y, x -> drive(y, x) },
                        onRelease = { drive(0f, 0f) }
                    )
                    1 -> MapScreen(
                        telemetry,
                        onBack = { page = 0 },
                        onGoal = { x, y -> if (telemetry.connected && !estop) client.publishGoal(x, y) }
                    )
                    2 -> ControlScreen(
                        telemetry, armed, estop, throttle, steering, maxLinear, maxAngular,
                        onArm = { if (telemetry.connected) { estop = false; armed = true; lastCommand = "POHON POVOLEN" } },
                        onDisarm = { client.publishVelocity(0f, 0f); armed = false; throttle = 0f; steering = 0f },
                        onStop = { stopRobot() },
                        onDrive = { y, x -> drive(y, x) },
                        onRelease = { drive(0f, 0f) },
                        onLinear = { maxLinear = it },
                        onAngular = { maxAngular = it },
                        lastCommand = lastCommand
                    )
                    3 -> TelemetryScreen(telemetry, logs)
                    else -> SetupScreen(
                        url, mapTopic, scanTopic, odomTopic, batteryTopic, cmdTopic, goalTopic,
                        telemetry.connected,
                        onUrl = { url = it }, onMap = { mapTopic = it }, onScan = { scanTopic = it },
                        onOdom = { odomTopic = it }, onBattery = { batteryTopic = it },
                        onCmd = { cmdTopic = it }, onGoal = { goalTopic = it },
                        onConnect = { connect() },
                        onDisconnect = { stopRobot(); client.disconnect(); armed = false }
                    )
                }
            }
            NavigationBar(containerColor = Panel, contentColor = Ink) {
                val labels = listOf("PŘEHLED", "MAPA", "ŘÍZENÍ", "TELEMETRIE", "NASTAVENÍ")
                val icons = listOf("⌂", "◎", "✣", "⌁", "⚙")
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = page == index,
                        onClick = { page = index },
                        icon = { Text(icons[index], fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                        label = { Text(label, fontSize = 8.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Cyan, selectedTextColor = Cyan,
                            indicatorColor = Panel2, unselectedIconColor = Muted, unselectedTextColor = Muted
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun TopHeader(connected: Boolean, status: String, estop: Boolean, onConnect: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(Cyan.copy(alpha = .13f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Text("R7", color = Cyan, fontSize = 17.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("ROBOT COCKPIT", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                Text("ROS 2 GROUND CONTROL  ·  ANDROID", color = Muted, fontSize = 9.sp, letterSpacing = .6.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (connected) Green else Amber, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text(if (connected) "ONLINE" else "OFFLINE", color = if (connected) Green else Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text(if (estop) "POHON ZAJIŠTĚN" else "POHON POVOLEN", color = if (estop) Red else Green, fontSize = 8.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status, color = if (connected) Green else Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onConnect, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), shape = RoundedCornerShape(8.dp)) {
                Text(if (connected) "ODPOJIT" else "PŘIPOJIT", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ScreenHeading(kicker: String, title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(kicker, color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text(title, color = Ink, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(13.dp), content = content)
    }
}

@Composable
private fun Metric(label: String, value: String, detail: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
        Text(value, color = color, fontSize = 19.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(detail, color = Muted, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun OverviewScreen(
    t: RobotTelemetry, armed: Boolean, estop: Boolean, throttle: Float, steering: Float,
    maxLinear: Float, lastCommand: String, onOpenMap: () -> Unit, onOpenControl: () -> Unit,
    onArm: () -> Unit, onStop: () -> Unit, onDrive: (Float, Float) -> Unit, onRelease: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeading("MISSION CONTROL", "Živý přehled robota", "Mapa · LiDAR · lokalizace · ruční ovládání") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("BATERIE", t.batteryPercent?.let { "%.0f%%".format(it) } ?: "—", t.voltage?.let { "%.1f V".format(it) } ?: "bez dat", Cyan, Modifier.weight(1f))
                Metric("RYCHLOST", t.linearSpeed?.let { "%.2f".format(it) } ?: "—", "m/s · odometrie", Green, Modifier.weight(1f))
                Metric("LiDAR", t.scan?.ranges?.size?.toString() ?: "—", "měřicích paprsků", Amber, Modifier.weight(1f))
            }
        }
        item {
            PanelCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("LIVE MAP  ·  OCCUPANCY GRID", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("Mapa a sken v jednom pohledu", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(if (t.map != null) "${t.map!!.width}×${t.map!!.height}" else "ČEKÁM NA MAPU", color = if (t.map != null) Green else Amber, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                RobotMap(t, Modifier.fillMaxWidth().height(230.dp), interactive = false, onGoal = null)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Legend(Cyan, "LiDAR"); Legend(Green, "Robot"); Legend(Red, "Obsazeno"); Legend(Amber, "Cíl")
                }
                Text("Poloha: ${t.x?.let { "%.2f".format(it) } ?: "—"} m / ${t.y?.let { "%.2f".format(it) } ?: "—"} m   ·   zprávy ${t.messages}", color = Muted, fontSize = 10.sp)
                OutlinedButton(onClick = onOpenMap, modifier = Modifier.fillMaxWidth()) { Text("OTEVŘÍT MAPU A NAVIGACI") }
            }
        }
        item {
            PanelCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("MANUÁLNÍ OVLÁDÁNÍ", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(if (armed && !estop) "Pohon povolen" else "Pohon bezpečně zajištěn", color = if (armed && !estop) Green else Red, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Povely se posílají do ROS tématu cmd_vel.", color = Muted, fontSize = 10.sp)
                    }
                    Button(onClick = onOpenControl) { Text("ŘÍZENÍ ↗") }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Joystick(Modifier.weight(1f), throttle, steering, t.connected && armed && !estop, onDrive, onRelease)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("LIMIT ${"%.2f".format(maxLinear)} m/s", color = Muted, fontSize = 10.sp)
                        Button(onClick = onArm, enabled = t.connected && !armed && estop, modifier = Modifier.fillMaxWidth()) { Text("POVOLIT") }
                        Button(onClick = onStop, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)) { Text("■ STOP") }
                    }
                }
                Text(lastCommand, color = Muted, fontSize = 10.sp)
            }
        }
        item {
            PanelCard {
                Text("STAV KOMUNIKACE", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                StatusLine("ROS bridge", if (t.connected) "PŘIPOJENO" else "OFFLINE", t.connected)
                StatusLine("Occupancy grid", if (t.map != null) "DATA PŘIJATA" else "ČEKÁM", t.map != null)
                StatusLine("LaserScan", if (t.scan != null) "DATA PŘIJATA" else "ČEKÁM", t.scan != null)
                StatusLine("Odometry", if (t.x != null && t.y != null) "DATA PŘIJATA" else "ČEKÁM", t.x != null)
                if (t.error != null) Text(t.error, color = Red, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun MapScreen(t: RobotTelemetry, onBack: () -> Unit, onGoal: (Float, Float) -> Unit) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NAVIGACE  ·  ROS 2", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text("Mapa a cíle", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onBack) { Text("ZPĚT") }
        }
        PanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (t.map != null) "MAPA ŽIVÁ" else "ČEKÁM NA /map", color = if (t.map != null) Green else Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("frame: map", color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(8.dp))
            RobotMap(t, Modifier.fillMaxWidth().height(380.dp), interactive = true, onGoal = onGoal)
            Spacer(Modifier.height(8.dp))
            Text("Klepnutím do mapy odešleš cíl na /goal_pose (PoseStamped, frame map). Vyžaduje připojení a odjištění.", color = Muted, fontSize = 10.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("X", t.x?.let { "%.2f m".format(it) } ?: "—", "map frame", Cyan, Modifier.weight(1f))
            Metric("Y", t.y?.let { "%.2f m".format(it) } ?: "—", "map frame", Green, Modifier.weight(1f))
            Metric("YAW", t.yaw?.let { "${(it * 180f / PI.toFloat()).toInt()}°" } ?: "—", "orientace", Amber, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ControlScreen(
    t: RobotTelemetry, armed: Boolean, estop: Boolean, throttle: Float, steering: Float,
    maxLinear: Float, maxAngular: Float, onArm: () -> Unit, onDisarm: () -> Unit,
    onStop: () -> Unit, onDrive: (Float, Float) -> Unit, onRelease: () -> Unit,
    onLinear: (Float) -> Unit, onAngular: (Float) -> Unit, lastCommand: String
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenHeading("MANUAL CONTROL", "Ruční řízení", "Mrtvý muž: po puštění joysticku se odešle nulový povel")
        PanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (armed && !estop) "POHON POVOLEN" else "POHON ZAJIŠTĚN", color = if (armed && !estop) Green else Red, fontSize = 14.sp, fontWeight = FontWeight.Black)
                    Text(if (t.connected) "ROS bridge je připojen" else "Nejdřív připoj ROS bridge", color = Muted, fontSize = 10.sp)
                }
                Box(Modifier.size(10.dp).background(if (armed && !estop) Green else Red, CircleShape))
            }
            Spacer(Modifier.height(12.dp))
            Joystick(Modifier.fillMaxWidth().heightIn(max = 300.dp).aspectRatio(1.1f), throttle, steering, t.connected && armed && !estop, onDrive, onRelease)
            Spacer(Modifier.height(10.dp))
            Text("LINEÁRNÍ RYCHLOST  ${"%.2f".format(maxLinear)} m/s", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Slider(value = maxLinear, onValueChange = onLinear, valueRange = 0.05f..1.5f, enabled = !armed || estop)
            Text("ÚHLOVÁ RYCHLOST  ${"%.2f".format(maxAngular)} rad/s", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Slider(value = maxAngular, onValueChange = onAngular, valueRange = 0.1f..2.5f, enabled = !armed || estop)
            Text("Povel: $lastCommand", color = Cyan, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onArm, enabled = t.connected && !armed && estop, modifier = Modifier.weight(1f).height(52.dp)) { Text("POVOLIT POHON") }
                Button(onClick = onDisarm, enabled = armed, modifier = Modifier.weight(1f).height(52.dp)) { Text("ODJISTIT") }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onStop, modifier = Modifier.fillMaxWidth().height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White), shape = RoundedCornerShape(12.dp)) {
                Text("■  NOUZOVÝ STOP", fontSize = 15.sp, fontWeight = FontWeight.Black)
            }
        }
        PanelCard {
            Text("BEZPEČNOSTNÍ POZNÁMKA", color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("STOP odešle nulový Twist a aplikace zruší místní povolení pohybu. Není to nezávislý hardwarový nouzový vypínač; ten musí být na robotovi.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TelemetryScreen(t: RobotTelemetry, logs: List<String>) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeading("SYSTEM STATUS", "Telemetrie", "Hodnoty jsou z ROS témat, ne z demo generátoru") }
        item {
            PanelCard {
                Text("POLOHA A POHYB", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                DataLine("X", t.x?.let { "%.3f m".format(it) } ?: "—")
                DataLine("Y", t.y?.let { "%.3f m".format(it) } ?: "—")
                DataLine("Orientace", t.yaw?.let { "${(it * 180f / PI.toFloat()).toInt()}°" } ?: "—")
                DataLine("Lineární rychlost", t.linearSpeed?.let { "%.3f m/s".format(it) } ?: "—")
                DataLine("Úhlová rychlost", t.angularSpeed?.let { "%.3f rad/s".format(it) } ?: "—")
            }
        }
        item {
            PanelCard {
                Text("NAPÁJENÍ A SENZORY", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                DataLine("Baterie", t.batteryPercent?.let { "%.1f %%".format(it) } ?: "—")
                DataLine("Napětí", t.voltage?.let { "%.2f V".format(it) } ?: "—")
                DataLine("LiDAR paprsky", t.scan?.ranges?.size?.toString() ?: "—")
                DataLine("Mapa", t.map?.let { "${it.width} × ${it.height} / %.3f m".format(it.resolution) } ?: "—")
                DataLine("Přijaté zprávy", t.messages.toString())
                DataLine("Poslední téma", t.lastTopic)
                DataLine("Spojení", t.status)
                if (t.error != null) DataLine("Chyba", t.error, Red)
            }
        }
        item { Text("UDÁLOSTI", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp) }
        if (logs.isEmpty()) item { Text("Zatím žádné události.", color = Muted, fontSize = 11.sp) }
        items(logs) { entry ->
            Text(entry, color = Muted, fontSize = 10.sp, modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(7.dp)).padding(9.dp))
        }
    }
}

@Composable
private fun SetupScreen(
    url: String, map: String, scan: String, odom: String, battery: String, cmd: String, goal: String,
    connected: Boolean, onUrl: (String) -> Unit, onMap: (String) -> Unit, onScan: (String) -> Unit,
    onOdom: (String) -> Unit, onBattery: (String) -> Unit, onCmd: (String) -> Unit, onGoal: (String) -> Unit,
    onConnect: () -> Unit, onDisconnect: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenHeading("CONNECTION & TOPICS", "Nastavení", "ROS 2 přes rosbridge WebSocket")
        PanelCard {
            Text("PŘIPOJENÍ", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("Adresa rosbridge serveru", color = Muted, fontSize = 11.sp)
            OutlinedTextField(value = url, onValueChange = onUrl, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("ws://IP_ROBOTA:9090") })
            Text("Například ws://192.168.1.50:9090. Na robotovi musí běžet rosbridge_server pro ROS 2 a telefon musí mít síťovou dostupnost.", color = Muted, fontSize = 10.sp)
            Spacer(Modifier.height(8.dp))
            if (!connected) Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("PŘIPOJIT ROS 2") }
            else Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)) { Text("ODPOJIT A ZASTAVIT") }
        }
        PanelCard {
            Text("ROS TÉMATA", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            TopicField("Mapa · nav_msgs/OccupancyGrid", map, onMap)
            TopicField("LiDAR · sensor_msgs/LaserScan", scan, onScan)
            TopicField("Odometrie · nav_msgs/Odometry", odom, onOdom)
            TopicField("Baterie · sensor_msgs/BatteryState", battery, onBattery)
            TopicField("Povel pohybu · geometry_msgs/Twist", cmd, onCmd)
            TopicField("Navigační cíl · PoseStamped", goal, onGoal)
            Text("Názvy témat se musí shodovat s robotem. Pro cíl je potřeba kompatibilní navigační stack, který odebírá PoseStamped z nastaveného tématu.", color = Muted, fontSize = 10.sp)
        }
        PanelCard {
            Text("CO TATO VERZE UMÍ", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("• Připojení přes rosbridge WebSocket\n• Živá occupancy grid mapa a LaserScan\n• Odometrie, rychlost a BatteryState\n• Povel Twist z joysticku\n• Cíl PoseStamped na mapě\n• Ručně nastavitelná témata", color = Ink, fontSize = 12.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(5.dp))
            Text("Zatím neobsahuje nativní ROS 2 DDS ani MAVLink/ArduPilot. Pro ArduPilot je nutné doplnit samostatný MAVLink transport a bezpečné mapování režimů/armingu.", color = Amber, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TopicField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValue, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) })
}

@Composable
private fun StatusLine(label: String, value: String, good: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(if (good) Green else Amber, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (good) Green else Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DataLine(label: String, value: String, valueColor: Color = Ink) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun Joystick(
    modifier: Modifier, throttle: Float, steering: Float, enabled: Boolean,
    onMove: (Float, Float) -> Unit, onRelease: () -> Unit
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Canvas(modifier.onSizeChanged { size = it }.pointerInput(enabled, size) {
        fun handle(p: Offset) {
            if (!enabled || size.width <= 0 || size.height <= 0) return
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = min(size.width, size.height) * .34f
            val x = ((p.x - cx) / radius).coerceIn(-1f, 1f)
            val y = ((cy - p.y) / radius).coerceIn(-1f, 1f)
            onMove(y, x)
        }
        detectDragGestures(
            onDragStart = { handle(it) },
            onDragEnd = { onRelease() },
            onDragCancel = { onRelease() }
        ) { change, _ -> change.consume(); handle(change.position) }
    }) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val radius = min(this.size.width, this.size.height) * .36f
        drawCircle(Panel2, radius, Offset(cx, cy))
        drawCircle(Border, radius, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        drawLine(Border, Offset(cx - radius, cy), Offset(cx + radius, cy), strokeWidth = 1.dp.toPx())
        drawLine(Border, Offset(cx, cy - radius), Offset(cx, cy + radius), strokeWidth = 1.dp.toPx())
        val kx = cx + steering.coerceIn(-1f, 1f) * radius
        val ky = cy - throttle.coerceIn(-1f, 1f) * radius
        drawCircle(if (enabled) Cyan.copy(alpha = .22f) else Muted.copy(alpha = .13f), radius * .45f, Offset(kx, ky))
        drawCircle(if (enabled) Cyan else Muted, radius * .23f, Offset(kx, ky))
        drawCircle(if (enabled) Green else Muted, radius * .055f, Offset(cx, cy))
    }
}

@Composable
private fun RobotMap(
    telemetry: RobotTelemetry, modifier: Modifier, interactive: Boolean, onGoal: ((Float, Float) -> Unit)?
) {
    val map = telemetry.map
    Canvas(modifier.background(Color(0xFF070C11), RoundedCornerShape(10.dp)).pointerInput(map, interactive, onGoal) {
        if (interactive && onGoal != null) detectTapGestures { p ->
            val grid = map ?: return@detectTapGestures
            if (grid.width <= 0 || grid.height <= 0 || grid.resolution <= 0f) return@detectTapGestures
            val worldW = grid.width * grid.resolution
            val worldH = grid.height * grid.resolution
            val scale = min(size.width / worldW, size.height / worldH) * .92f
            val ox = (size.width - worldW * scale) / 2f
            val oy = (size.height - worldH * scale) / 2f
            val wx = grid.originX + (p.x - ox) / scale
            val wy = grid.originY + worldH - (p.y - oy) / scale
            if (wx >= grid.originX && wx <= grid.originX + worldW && wy >= grid.originY && wy <= grid.originY + worldH) onGoal(wx, wy)
        }
    }) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF070C11))
        val grid = map
        if (grid != null && grid.width > 0 && grid.height > 0 && grid.resolution > 0f) {
            val worldW = grid.width * grid.resolution
            val worldH = grid.height * grid.resolution
            val scale = min(w / worldW, h / worldH) * .92f
            val ox = (w - worldW * scale) / 2f
            val oy = (h - worldH * scale) / 2f
            val cellPx = grid.resolution * scale
            val step = max(1, max(grid.width / 150, grid.height / 150))
            var gy = 0
            while (gy < grid.height) {
                var gx = 0
                while (gx < grid.width) {
                    val index = gy * grid.width + gx
                    val occ = if (index in grid.cells.indices) grid.cells[index] else -1
                    val color = when {
                        occ < 0 -> Color(0xFF101B25)
                        occ >= 65 -> Color(0xFFDAE5EE)
                        occ >= 25 -> Color(0xFF435466)
                        else -> Color(0xFF182631)
                    }
                    val x = ox + gx * cellPx
                    val y = oy + (grid.height - gy - step) * cellPx
                    drawRect(color, Offset(x, y), Size(cellPx * step + .5f, cellPx * step + .5f))
                    gx += step
                }
                gy += step
            }
            drawGridLines(ox, oy, worldW * scale, worldH * scale)
            val rx = telemetry.x ?: (grid.originX + worldW / 2f)
            val ry = telemetry.y ?: (grid.originY + worldH / 2f)
            val yaw = telemetry.yaw ?: 0f
            val sx = ox + (rx - grid.originX) * scale
            val sy = oy + (worldH - (ry - grid.originY)) * scale
            drawScan(telemetry.scan, sx, sy, yaw, scale, ox, oy, grid.originX, grid.originY, worldH, true)
            drawRobot(sx, sy, yaw)
            if (interactive) {
                drawCircle(Amber, 6.dp.toPx(), Offset(w * .5f, h * .07f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
        } else {
            val cx = w / 2f
            val cy = h / 2f
            val scale = min(w, h) / 12f
            for (i in 1..5) drawCircle(Border.copy(alpha = .55f), i * scale, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            drawLine(Border, Offset(cx, 0f), Offset(cx, h), 1.dp.toPx())
            drawLine(Border, Offset(0f, cy), Offset(w, cy), 1.dp.toPx())
            drawScan(telemetry.scan, cx, cy, telemetry.yaw ?: 0f, scale, 0f, 0f, 0f, 0f, 0f, false)
            drawRobot(cx, cy, telemetry.yaw ?: 0f)

        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGridLines(ox: Float, oy: Float, width: Float, height: Float) {
    val spacing = 40.dp.toPx()
    var x = ox
    while (x <= ox + width) { drawLine(Border.copy(alpha = .25f), Offset(x, oy), Offset(x, oy + height), 1.dp.toPx()); x += spacing }
    var y = oy
    while (y <= oy + height) { drawLine(Border.copy(alpha = .25f), Offset(ox, y), Offset(ox + width, y), 1.dp.toPx()); y += spacing }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScan(
    scan: LaserScan?, sx: Float, sy: Float, yaw: Float, scale: Float,
    ox: Float, oy: Float, originX: Float, originY: Float, worldH: Float, hasMap: Boolean
) {
    if (scan == null || scan.ranges.isEmpty()) return
    val stride = max(1, scan.ranges.size / 500)
    var i = 0
    while (i < scan.ranges.size) {
        val range = scan.ranges[i]
        if (range.isFinite() && range >= scan.rangeMin && range <= scan.rangeMax) {
            val a = yaw + scan.angleMin + i * scan.angleIncrement
            val dx = cos(a) * range
            val dy = sin(a) * range
            drawCircle(Cyan, 1.8.dp.toPx(), Offset(sx + dx * scale, sy - dy * scale))
        }
        i += stride
    }
}
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRobot(x: Float, y: Float, yaw: Float) {
    drawCircle(Green.copy(alpha = .2f), 13.dp.toPx(), Offset(x, y))
    drawCircle(Green, 6.dp.toPx(), Offset(x, y))
    drawLine(Green, Offset(x, y), Offset(x + cos(yaw) * 22.dp.toPx(), y - sin(yaw) * 22.dp.toPx()), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
}