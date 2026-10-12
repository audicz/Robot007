package cz.robotcockpit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.sqrt

private val Bg = Color(0xFF090F16)
private val Panel = Color(0xFF121D28)
private val Panel2 = Color(0xFF192735)
private val Line = Color(0xFF293B4B)
private val Cyan = Color(0xFF48D9E8)
private val Green = Color(0xFF68E3A2)
private val Amber = Color(0xFFFFC66D)
private val Red = Color(0xFFFF5E68)
private val Muted = Color(0xFF8DA2B5)
private val Ink = Color(0xFFEAF4FC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RobotCockpitApp() }
    }
}

private data class RouteStep(val title: String, val detail: String)
private val initialSteps = listOf(
    RouteStep("PŘÍJEZD", "Jeď vpřed · 2,0 m"),
    RouteStep("OTOČENÍ", "Vpravo · 90°"),
    RouteStep("PŘESUN", "Jeď vpřed · 1,0 m"),
    RouteStep("NÁVRAT", "HOME / výchozí bod")
)

@Composable
fun RobotCockpitApp() {
    var page by remember { mutableIntStateOf(0) }
    var throttle by remember { mutableFloatStateOf(0f) }
    var steering by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var estop by remember { mutableStateOf(true) }
    var speedLimit by remember { mutableFloatStateOf(35f) }
    var battery by remember { mutableFloatStateOf(82f) }
    var distance by remember { mutableFloatStateOf(0f) }
    var heading by remember { mutableFloatStateOf(32f) }
    var routeName by remember { mutableStateOf("Testovací okruh") }
    val steps = remember { mutableStateListOf<RouteStep>().apply { addAll(initialSteps) } }

    fun stopRobot() {
        throttle = 0f
        steering = 0f
        armed = false
        estop = true
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cyan, secondary = Green, background = Bg, surface = Panel,
            onPrimary = Bg, onBackground = Ink, onSurface = Ink, error = Red
        )
    ) {
        Column(
            Modifier.fillMaxSize().background(Bg)
        ) {
            Header(connected, estop, onConnect = { connected = !connected; if (!connected) stopRobot() })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (page) {
                    0 -> Dashboard(
                        connected, estop, armed, throttle, steering, battery, speedLimit, distance, heading,
                        onOpenController = { page = 2 },
                        onArm = { if (connected) { estop = false; armed = true } },
                        onStop = { stopRobot() },
                        onDrive = { y, x -> if (connected && armed && !estop) { throttle = y; steering = x } },
                        onLimit = { speedLimit = it }
                    )
                    1 -> MapScreen(distance, heading, onHome = { distance = 0f; heading = 0f })
                    2 -> ControllerScreen(
                        throttle, steering, armed, estop, connected, speedLimit,
                        onDrive = { y, x -> if (armed && !estop && connected) { throttle = y; steering = x } },
                        onStop = { stopRobot() },
                        onArm = { if (connected) { estop = false; armed = true } },
                        onDisarm = { armed = false; throttle = 0f; steering = 0f },
                        onLimit = { speedLimit = it }
                    )
                    3 -> TelemetryScreen(battery, heading, distance)
                    else -> RoutesScreen(routeName, steps, onName = { routeName = it }, onAdd = {
                        steps.add(RouteStep("KROK ${steps.size + 1}", "Jeď vpřed · 0,5 m"))
                    }, onRemove = { if (steps.isNotEmpty()) steps.removeAt(steps.lastIndex) })
                }
            }
            NavigationBar(containerColor = Panel, contentColor = Ink) {
                val nav = listOf("PŘEHLED", "MAPA", "ŘÍZENÍ", "SENZORY", "TRASY")
                val symbols = listOf("⌂", "◎", "✣", "⌁", "≡")
                nav.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = page == i,
                        onClick = { page = i },
                        icon = { Text(symbols[i], fontSize = 21.sp, fontWeight = FontWeight.Bold) },
                        label = { Text(label, fontSize = 9.sp, maxLines = 1) },
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
private fun Header(connected: Boolean, estop: Boolean, onConnect: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(Cyan.copy(alpha = .13f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text("R7", color = Cyan, fontWeight = FontWeight.Black, fontSize = 17.sp)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("ROBOT COCKPIT", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                Text("MOBILE CONTROL SYSTEM  ·  v0.2", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (connected) Green else Amber, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text(if (connected) "PŘIPOJENO" else "OFFLINE", color = if (connected) Green else Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text("SIMULAČNÍ REŽIM", color = Muted, fontSize = 8.sp)
            }
        }
        Spacer(Modifier.height(11.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).background(if (estop) Red.copy(alpha = .12f) else Green.copy(alpha = .10f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(if (estop) Red else Green, CircleShape))
                    Spacer(Modifier.width(7.dp))
                    Text(if (estop) "POHON ZAJIŠTĚN" else "POHON ODBLOKOVÁN", color = if (estop) Red else Green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onConnect, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(8.dp)) {
                Text(if (connected) "ODPOJIT" else "DEMO PŘIPOJIT", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ScreenTitle(kicker: String, title: String, detail: String? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(kicker, color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
        Text(title, color = Ink, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        if (detail != null) Text(detail, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun Dashboard(
    connected: Boolean, estop: Boolean, armed: Boolean, throttle: Float, steering: Float,
    battery: Float, speedLimit: Float, distance: Float, heading: Float,
    onOpenController: () -> Unit, onArm: () -> Unit, onStop: () -> Unit,
    onDrive: (Float, Float) -> Unit, onLimit: (Float) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle("CENTRÁLNÍ PANEL", "Robot · live přehled", "2D mapa / LiDAR / ruční řízení") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("BATERIE", "${battery.toInt()}%", "24,8 V · simulace", Cyan, Modifier.weight(1f))
                MetricCard("RYCHLOST", if (armed) "${(throttle * speedLimit).toInt()}%" else "0%", "limit ${speedLimit.toInt()} %", Green, Modifier.weight(1f))
                MetricCard("POHON", if (armed && !estop) "READY" else "STOP", if (estop) "bezpečně zajištěn" else "připraven", if (estop) Red else Amber, Modifier.weight(1f))
            }
        }
        item {
            CardBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ROS MAP  ·  2D SLAM", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                        Text("Mapa + LiDAR v jednom pohledu", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("X ${"%.2f".format(distance)} m  ·  Y 0,42 m", color = Muted, fontSize = 10.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${heading.toInt()}°", color = Cyan, fontSize = 22.sp, fontWeight = FontWeight.Black)
                        Text(if (connected) "DEMO LINK" else "NO ROS LINK", color = Amber, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(8.dp))
                MiniMap(Modifier.fillMaxWidth().height(222.dp))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LegendDot(Cyan, "Plánovaná trasa")
                    LegendDot(Green, "Robot")
                    LegendDot(Amber, "LiDAR body")
                    LegendDot(Red, "Překážka")
                }
                Spacer(Modifier.height(5.dp))
                Text("ROS / occupancy grid / scan: vizualizace návrhu, nikoli živá data.", color = Muted, fontSize = 9.sp)
            }
        }
        item {
            CardBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("GAMEPAD  ·  MANUÁLNÍ POHYB", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                        Text(if (armed && !estop) "Pohon povolen" else "Pohon zajištěn", color = if (armed && !estop) Green else Red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("Levý/virtuální joystick · přetáhni pro pohyb", color = Muted, fontSize = 9.sp)
                    }
                    Button(onClick = onOpenController, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp), shape = RoundedCornerShape(9.dp)) { Text("CELÉ ŘÍZENÍ ↗", fontSize = 9.sp) }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Joystick(
                            modifier = Modifier.size(148.dp),
                            throttle = throttle, steering = steering,
                            enabled = connected && armed && !estop,
                            onMove = { y, x -> onDrive(y, x) },
                            onRelease = { onDrive(0f, 0f) }
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("RYCHLOSTNÍ LIMIT  ${speedLimit.toInt()}%", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Slider(value = speedLimit, onValueChange = onLimit, valueRange = 5f..100f, enabled = !armed || estop)
                        Button(onClick = onArm, enabled = connected && (!armed || estop), modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(9.dp)) {
                            Text(if (estop) "POVOLIT POHON" else if (armed) "POHON POVOLEN" else "AKTIVOVAT", fontSize = 10.sp)
                        }
                        Button(onClick = onStop, modifier = Modifier.fillMaxWidth().height(42.dp), colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White), shape = RoundedCornerShape(9.dp)) {
                            Text("■  STOP", fontSize = 11.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
        item {
            CardBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("OVLÁDÁNÍ", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text(if (armed && !estop) "Řízení odblokováno" else "Pohon je zastaven", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(if (connected) "Demo spojení aktivní" else "Nejprve aktivuj demo spojení", color = Muted, fontSize = 11.sp)
                    }
                    Button(onClick = onOpenController, shape = RoundedCornerShape(10.dp)) { Text("ŘÍDIT  →") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onArm, enabled = connected && !estop, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(12.dp)) {
                    Text("POVOLIT POHON", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Button(onClick = onStop, modifier = Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White), shape = RoundedCornerShape(12.dp)) {
                    Text("■  NOUZOVÝ STOP", fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(Amber, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("Údaje jsou demonstrační. Robot není připojen přes Bluetooth ani Wi-Fi.", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, sub: String, accent: Color, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(13.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(11.dp)) {
            Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(7.dp))
            Text(value, color = accent, fontSize = 18.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Text(sub, color = Muted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CardBlock(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), content = content)
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Muted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MiniMap(modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFF0A141D), RoundedCornerShape(12.dp))) {
        val step = 22f
        // Occupancy-grid-like background with darker occupied cells.
        var x = 0f
        while (x < size.width) {
            drawLine(Line.copy(alpha = .50f), Offset(x, 0f), Offset(x, size.height), 1f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(Line.copy(alpha = .50f), Offset(0f, y), Offset(size.width, y), 1f)
            y += step
        }
        val cell = 11f
        val occupied = listOf(
            .12f to .18f, .15f to .18f, .18f to .18f, .12f to .22f,
            .78f to .18f, .82f to .18f, .78f to .22f, .82f to .22f,
            .20f to .58f, .24f to .58f, .20f to .62f,
            .68f to .68f, .72f to .68f, .76f to .68f, .72f to .72f
        )
        occupied.forEach { (ox, oy) ->
            drawRect(Color(0xFF354756), topLeft = Offset(size.width * ox, size.height * oy), size = Size(cell, cell))
        }

        // Planned route, drawn as a smooth-looking sequence of connected waypoints.
        val p1 = Offset(size.width * .14f, size.height * .80f)
        val p2 = Offset(size.width * .42f, size.height * .80f)
        val p3 = Offset(size.width * .42f, size.height * .48f)
        val p4 = Offset(size.width * .68f, size.height * .48f)
        val p5 = Offset(size.width * .83f, size.height * .34f)
        val route = listOf(p1, p2, p3, p4, p5)
        route.zipWithNext().forEach { (a, b) ->
            drawLine(Cyan.copy(alpha = .22f), a, b, 10f, cap = StrokeCap.Round)
            drawLine(Cyan, a, b, 2.5f, cap = StrokeCap.Round)
        }
        route.forEach { drawCircle(Cyan, 3f, it) }

        // 2D LiDAR sweep and sampled return points, shown in the same map coordinate frame.
        val robot = Offset(size.width * .42f, size.height * .80f)
        val lidarRadius = min(size.width, size.height) * .34f
        for (i in 0 until 52) {
            val a = (i * 6.92 - 150.0) * Math.PI / 180.0
            val variable = .52 + ((i * 37) % 43) / 100.0
            val rr = lidarRadius * variable.toFloat()
            val hit = Offset(robot.x + kotlin.math.cos(a).toFloat() * rr, robot.y + kotlin.math.sin(a).toFloat() * rr)
            if (hit.x in 2f..(size.width - 2f) && hit.y in 2f..(size.height - 2f)) {
                drawLine(Amber.copy(alpha = .10f), robot, hit, 1f)
                drawCircle(Amber, if (i % 5 == 0) 3f else 1.8f, hit)
            }
        }

        // Robot body and heading indicator.
        drawCircle(Green.copy(alpha = .17f), 18f, robot)
        drawCircle(Green, 7f, robot)
        drawLine(Green, robot, Offset(robot.x, robot.y - 19f), 3f, cap = StrokeCap.Round)
        drawLine(Green, Offset(robot.x, robot.y - 19f), Offset(robot.x - 5f, robot.y - 12f), 2f, cap = StrokeCap.Round)
        drawLine(Green, Offset(robot.x, robot.y - 19f), Offset(robot.x + 5f, robot.y - 12f), 2f, cap = StrokeCap.Round)
        drawCircle(Red, 4f, Offset(size.width * .19f, size.height * .28f))
        drawCircle(Red, 4f, Offset(size.width * .76f, size.height * .20f))
    }
}

@Composable
private fun MapScreen(distance: Float, heading: Float, onHome: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenTitle("NAVIGACE", "Mapa a odometrie", "Náhled trasy · lokální simulace")
        CardBlock {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("POLOHA X / Y", color = Muted, fontSize = 10.sp); Text("0,00 / 0,42 m", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Column(horizontalAlignment = Alignment.End) { Text("SMĚR", color = Muted, fontSize = 10.sp); Text("${heading.toInt()}°", color = Cyan, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(12.dp))
        CardBlock {
            MiniMap(Modifier.fillMaxWidth().weight(1f).heightIn(min = 260.dp))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("PROJETÁ VZDÁLENOST", color = Muted, fontSize = 10.sp)
                Text("${"%.2f".format(distance)} m", color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) { Text("RESET ODOMETRIE / HOME") }
        Spacer(Modifier.height(8.dp))
        Text("Mapové body jsou ilustrační, GPS a enkodéry nejsou připojeny.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun ControllerScreen(
    throttle: Float, steering: Float, armed: Boolean, estop: Boolean, connected: Boolean, speedLimit: Float,
    onDrive: (Float, Float) -> Unit, onStop: () -> Unit, onArm: () -> Unit, onDisarm: () -> Unit, onLimit: (Float) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
        ScreenTitle("MANUÁLNÍ REŽIM", "Řízení robota", "Virtuální joystick · diferenciální pohon")
        CardBlock {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("POHON", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(if (armed && !estop) "PŘIPRAVEN" else "ZASTAVEN", color = if (armed && !estop) Green else Red, fontSize = 17.sp, fontWeight = FontWeight.Black)
                }
                Text("Y ${"%.2f".format(throttle)}   X ${"%.2f".format(steering)}", color = Cyan, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Joystick(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(4.dp),
                throttle = throttle, steering = steering,
                enabled = connected && armed && !estop,
                onMove = { y, x -> onDrive(y, x) },
                onRelease = { onDrive(0f, 0f) }
            )
        }
        Text("LIMIT VÝKONU  ·  ${speedLimit.toInt()} %", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Slider(value = speedLimit, onValueChange = onLimit, valueRange = 5f..100f, enabled = !armed || estop)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onArm, enabled = connected && !estop && !armed, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(10.dp)) { Text("POVOLIT") }
            OutlinedButton(onClick = onDisarm, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(10.dp)) { Text("ZNEAKTIVNIT") }
            Button(onClick = onStop, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Red), shape = RoundedCornerShape(10.dp)) { Text("STOP", fontWeight = FontWeight.Black) }
        }
        Spacer(Modifier.height(8.dp))
        Text(if (!connected) "Aktivuj DEMO PŘIPOJIT v horní liště. Toto není skutečné spojení s robotem." else if (estop) "Pohon je zajištěn. STOP lze uvolnit pouze po potvrzení připravenosti." else "Demo joystick funguje pouze po povolení pohonu.", color = Amber, fontSize = 10.sp)
    }
}

@Composable
private fun Joystick(
    modifier: Modifier, throttle: Float, steering: Float, enabled: Boolean,
    onMove: (Float, Float) -> Unit, onRelease: () -> Unit
) {
    var local by remember { mutableStateOf(Offset.Zero) }
    Canvas(
        modifier = modifier
            .pointerInput(enabled) {
                detectDragGestures(
                    onDragStart = { p ->
                        val r = min(size.width, size.height) / 2f
                        val dx = (p.x - size.width / 2f) / r
                        val dy = (size.height / 2f - p.y) / r
                        val d = sqrt(dx * dx + dy * dy).coerceAtMost(1f)
                        local = Offset(dx / maxOf(1f, sqrt(dx * dx + dy * dy)), dy / maxOf(1f, sqrt(dx * dx + dy * dy)))
                        if (enabled) onMove(local.y, local.x)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val r = min(size.width, size.height) / 2f
                        val dx = (change.position.x - size.width / 2f) / r
                        val dy = (size.height / 2f - change.position.y) / r
                        val len = sqrt(dx * dx + dy * dy)
                        local = if (len > 1f) Offset(dx / len, dy / len) else Offset(dx, dy)
                        if (enabled) onMove(local.y, local.x)
                    },
                    onDragEnd = { local = Offset.Zero; onRelease() },
                    onDragCancel = { local = Offset.Zero; onRelease() }
                )
            }
    ) {
        val r = min(size.width, size.height) * .43f
        val c = Offset(size.width / 2f, size.height / 2f)
        for (i in 1..3) drawCircle(Line, r * i / 3f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        drawLine(Line, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 2f)
        drawLine(Line, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 2f)
        drawCircle(Cyan.copy(alpha = .08f), r * .92f, c)
        val px = if (enabled) steering else 0f
        val py = if (enabled) throttle else 0f
        val knob = Offset(c.x + px * r, c.y - py * r)
        drawLine(Cyan.copy(alpha = .55f), c, knob, 3f, cap = StrokeCap.Round)
        drawCircle(if (enabled) Cyan.copy(alpha = .22f) else Muted.copy(alpha = .12f), r * .24f, knob)
        drawCircle(if (enabled) Cyan else Muted, r * .13f, knob)
    }
    Text(
        if (!enabled) "Pohon zajištěn" else "PŘETÁHNI JOYSTICK",
        color = if (enabled) Cyan else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp).wrapContentSize()
    )
}

@Composable
private fun TelemetryScreen(battery: Float, heading: Float, distance: Float) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("DIAGNOSTIKA", "Senzory a telemetrie", "Stavové panely · demonstrační hodnoty") }
        item {
            CardBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("BATERIE", color = Muted, fontSize = 10.sp); Text("24,8 V", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Black) }
                    Text("${battery.toInt()} %", color = Green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { battery / 100f }, modifier = Modifier.fillMaxWidth().height(7.dp), color = Green, trackColor = Line)
                Spacer(Modifier.height(7.dp))
                Text("Proud  ·  1,4 A       Výkon  ·  34 W       Teplota  ·  31 °C", color = Muted, fontSize = 11.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("IMU / GYRO", "0,02", "rad/s · osa Z", Cyan, Modifier.weight(1f))
                MetricCard("GPS", "FIX", "49.0000 / 18.0000", Green, Modifier.weight(1f))
            }
        }
        item {
            CardBlock {
                Text("MOTOR A  /  LEVÝ", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("0 rpm", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Enkodér  ·  0 imp     Proud  ·  0,0 A", color = Muted, fontSize = 11.sp)
                Divider(Modifier.padding(vertical = 12.dp), color = Line)
                Text("MOTOR B  /  PRAVÝ", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("0 rpm", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Enkodér  ·  0 imp     Proud  ·  0,0 A", color = Muted, fontSize = 11.sp)
            }
        }
        item {
            CardBlock {
                Text("LIDAR / OKOLÍ", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Canvas(Modifier.fillMaxWidth().height(170.dp)) {
                    val c = Offset(size.width / 2, size.height / 2)
                    val r = min(size.width, size.height) * .43f
                    for (i in 1..4) drawCircle(Line, r * i / 4f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                    drawLine(Line, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
                    drawLine(Line, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
                    for (i in 0..23) {
                        val a = i * Math.PI / 12
                        val rr = r * (.45f + ((i * 17) % 45) / 100f)
                        drawCircle(if (i % 4 == 0) Amber else Cyan, if (i % 4 == 0) 4f else 2.5f, Offset(c.x + (kotlin.math.cos(a) * rr).toFloat(), c.y + (kotlin.math.sin(a) * rr).toFloat()))
                    }
                    drawCircle(Green, 5f, c)
                }
                Text("24 body · simulovaný scan 360°", color = Muted, fontSize = 10.sp)
            }
        }
        item { Text("Odometrie ${"%.2f".format(distance)} m  ·  Směr ${heading.toInt()}°", color = Muted, fontSize = 11.sp) }
        item { Text("Poznámka: žádná hodnota zatím nepřichází ze skutečného hardwaru.", color = Amber, fontSize = 11.sp) }
    }
}

@Composable
private fun RoutesScreen(
    routeName: String, steps: SnapshotStateList<RouteStep>, onName: (String) -> Unit, onAdd: () -> Unit, onRemove: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
        ScreenTitle("AUTONOMNÍ REŽIM", "Editor tras", "Sestav kroky a zkontroluj pořadí")
        CardBlock {
            Text("NÁZEV TRASY", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = routeName, onValueChange = onName, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = Line))
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${steps.size} KROKŮ", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("NEULOŽENO", color = Amber, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(steps) { index, step ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).background(Panel2, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Text("${index + 1}", color = Cyan, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(step.title, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(step.detail, color = Muted, fontSize = 11.sp)
                        }
                        Text("⋮⋮", color = Muted, fontSize = 18.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAdd, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(10.dp)) { Text("+ PŘIDAT KROK") }
            Button(onClick = onRemove, enabled = steps.isNotEmpty(), modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Panel2)) { Text("− ODEBRAT") }
        }
        Spacer(Modifier.height(8.dp))
        Text("Editor je lokální prototyp. Trasa se zatím neukládá do souboru ani neposílá robotovi.", color = Amber, fontSize = 10.sp)
    }
}
