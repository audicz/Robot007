package cz.robotcockpit.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.sqrt

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
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

data class RobotTelemetry(
    val connected: Boolean = false,
    val status: String = "Bluetooth odpojen",
    val robotName: String = "—",
    val armed: Boolean = false,
    val throttle: Double? = null,
    val steer: Double? = null,
    val leftPwm: Int? = null,
    val rightPwm: Int? = null,
    val batteryV: Double? = null,
    val distanceCm: Double? = null,
    val uptimeMs: Long? = null,
    val firmware: String = "—",
    val lastMessage: String = "—",
    val error: String? = null,
    val messages: Long = 0
)

class BluetoothLink(private val onState: (RobotTelemetry) -> Unit) {
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var writer: OutputStream? = null
    @Volatile private var running = false
    @Volatile private var current = RobotTelemetry()
    private val writeLock = Any()

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect("Připojuji…")
        thread(name = "robot-spp-connect") {
            try {
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket = s
                s.connect()
                writer = s.outputStream
                running = true
                current = current.copy(connected = true, status = "Připojeno přes SPP", robotName = device.name ?: device.address, error = null)
                onState(current)
                sendRaw(JSONObject().put("type", "hello").put("protocol", 1).toString())
                val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
                while (running) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    try {
                        val j = JSONObject(line)
                        if (j.optString("type") == "telemetry") {
                            current = current.copy(
                                connected = true,
                                status = "Připojeno · data přijímána",
                                armed = j.optBoolean("armed", false),
                                throttle = if (j.has("throttle")) j.optDouble("throttle") else null,
                                steer = if (j.has("steer")) j.optDouble("steer") else null,
                                leftPwm = if (j.has("leftPwm")) j.optInt("leftPwm") else null,
                                rightPwm = if (j.has("rightPwm")) j.optInt("rightPwm") else null,
                                batteryV = if (j.has("batteryV") && !j.isNull("batteryV")) j.optDouble("batteryV") else null,
                                distanceCm = if (j.has("distanceCm") && !j.isNull("distanceCm")) j.optDouble("distanceCm") else null,
                                uptimeMs = if (j.has("uptimeMs")) j.optLong("uptimeMs") else null,
                                firmware = j.optString("firmware", current.firmware),
                                lastMessage = line,
                                error = null,
                                messages = current.messages + 1
                            )
                        } else {
                            current = current.copy(lastMessage = line, messages = current.messages + 1)
                        }
                        onState(current)
                    } catch (_: Exception) {
                        current = current.copy(lastMessage = line, messages = current.messages + 1)
                        onState(current)
                    }
                }
                if (running) disconnect("Spojení bylo ukončeno")
            } catch (e: Exception) {
                closeSocket()
                current = current.copy(connected = false, armed = false, status = "Připojení selhalo", error = e.message ?: e.javaClass.simpleName)
                onState(current)
            }
        }
    }

    fun sendDrive(throttle: Float, steer: Float, armed: Boolean) {
        sendRaw(JSONObject().put("type", "cmd").put("throttle", throttle.coerceIn(-1f, 1f).toDouble())
            .put("steer", steer.coerceIn(-1f, 1f).toDouble()).put("armed", armed).toString())
    }

    fun arm(enabled: Boolean) {
        sendRaw(JSONObject().put("type", if (enabled) "arm" else "stop").put("armed", enabled).toString())
    }

    private fun sendRaw(line: String) {
        try {
            synchronized(writeLock) {
                val out = writer ?: return
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (e: Exception) {
            current = current.copy(error = "Odeslání selhalo: ${e.message}")
            onState(current)
        }
    }

    fun disconnect(message: String = "Odpojeno") {
        try {
            if (running) sendRaw(JSONObject().put("type", "stop").put("armed", false).toString())
        } catch (_: Exception) {}
        running = false
        closeSocket()
        current = current.copy(connected = false, armed = false, status = message)
        onState(current)
    }

    private fun closeSocket() {
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null
        socket = null
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RobotCockpitApp() }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun RobotCockpitApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var t by remember { mutableStateOf(RobotTelemetry()) }
    var page by remember { mutableIntStateOf(0) }
    var paired by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var selectedAddress by remember { mutableStateOf("") }
    var maxPower by remember { mutableFloatStateOf(0.35f) }
    var armed by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("STOP") }
    val adapter = remember { BluetoothAdapter.getDefaultAdapter() }
    val link = remember { BluetoothLink { t = it; if (!it.connected) armed = false } }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            paired = try { adapter?.bondedDevices?.toList()?.sortedBy { it.name ?: it.address } ?: emptyList() } catch (_: SecurityException) { emptyList() }
            if (selectedAddress.isBlank()) selectedAddress = paired.firstOrNull()?.address ?: ""
        } else {
            t = t.copy(status = "Chybí oprávnění Bluetooth", error = "Povol aplikaci přístup k připojeným zařízením.")
        }
    }

    fun refreshDevices() {
        if (android.os.Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            paired = try { adapter?.bondedDevices?.toList()?.sortedBy { it.name ?: it.address } ?: emptyList() } catch (e: Exception) {
                t = t.copy(error = e.message ?: "Nelze načíst spárovaná zařízení")
                emptyList()
            }
            if (selectedAddress.isBlank()) selectedAddress = paired.firstOrNull()?.address ?: ""
        }
    }

    DisposableEffect(Unit) {
        refreshDevices()
        onDispose { link.disconnect("Aplikace ukončena") }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, secondary = Green, background = Bg, surface = Panel, onPrimary = Bg, onBackground = Ink, onSurface = Ink, error = Red)) {
        Column(Modifier.fillMaxSize().background(Bg)) {
            Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(Cyan.copy(alpha = .13f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        Text("R7", color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("ROBOT COCKPIT", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        Text("ESP32 · BLUETOOTH CLASSIC SPP", color = Muted, fontSize = 9.sp, letterSpacing = .6.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(if (t.connected) Green else Amber, CircleShape))
                            Spacer(Modifier.width(5.dp))
                            Text(if (t.connected) "ONLINE" else "OFFLINE", color = if (t.connected) Green else Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(if (armed && t.connected) "POHON POVOLEN" else "POHON ZAJIŠTĚN", color = if (armed && t.connected) Green else Red, fontSize = 8.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.status, color = if (t.connected) Green else Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { if (t.connected) { link.disconnect(); armed = false } else { refreshDevices(); page = 3 } }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                        Text(if (t.connected) "ODPOJIT" else "PŘIPOJIT", fontSize = 10.sp)
                    }
                }
                if (t.error != null) Text(t.error ?: "", color = Red, fontSize = 10.sp)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (page) {
                    0 -> OverviewPage(t, armed, maxPower, onDrivePage = { page = 1 }, onConnectPage = { refreshDevices(); page = 3 }, onArm = {
                        if (t.connected) { link.arm(true); armed = true; command = "POHON POVOLEN" }
                    }, onStop = { link.arm(false); link.sendDrive(0f, 0f, false); armed = false; command = "NOUZOVÝ STOP" })
                    1 -> DrivePage(t, armed, maxPower, onPower = { maxPower = it }, onArm = { link.arm(true); armed = true; command = "POHON POVOLEN" }, onStop = { link.arm(false); link.sendDrive(0f, 0f, false); armed = false; command = "NOUZOVÝ STOP" }, onDrive = { y, x ->
                        val throttle = y * maxPower
                        val steer = x * maxPower
                        if (t.connected && armed) { link.sendDrive(throttle, steer, true); command = "T ${"%.2f".format(throttle)} · S ${"%.2f".format(steer)}" }
                    }, onRelease = { if (t.connected) link.sendDrive(0f, 0f, armed); command = "NEUTRÁL" }, command = command)
                    2 -> TelemetryPage(t)
                    else -> ConnectionPage(t, paired, selectedAddress, onSelect = { selectedAddress = it }, onRefresh = { refreshDevices() }, onConnect = {
                        val device = paired.firstOrNull { it.address == selectedAddress }
                        if (device != null) link.connect(device) else t = t.copy(error = "Nejdřív spáruj ESP32 v nastavení Androidu.")
                    })
                }
            }
            NavigationBar(containerColor = Panel, contentColor = Ink) {
                listOf("PŘEHLED", "ŘÍZENÍ", "TELEMETRIE", "BLUETOOTH").forEachIndexed { i, label ->
                    NavigationBarItem(selected = page == i, onClick = { page = i }, icon = { Text(listOf("⌂", "✣", "⌁", "ᛒ")[i], fontSize = 20.sp, fontWeight = FontWeight.Bold) }, label = { Text(label, fontSize = 8.sp, maxLines = 1) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Cyan, selectedTextColor = Cyan, indicatorColor = Panel2, unselectedIconColor = Muted, unselectedTextColor = Muted))
                }
            }
        }
    }
}

@Composable
private fun ScreenTitle(kicker: String, title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(kicker, color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Text(title, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun CardPanel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), border = androidx.compose.foundation.BorderStroke(1.dp, Border), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(13.dp), content = content)
    }
}

@Composable
private fun Metric(label: String, value: String, detail: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(value, color = color, fontSize = 19.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(detail, color = Muted, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun OverviewPage(t: RobotTelemetry, armed: Boolean, power: Float, onDrivePage: () -> Unit, onConnectPage: () -> Unit, onArm: () -> Unit, onStop: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        item { ScreenTitle("MISSION CONTROL", "Přehled robota", "Přímé spojení ESP32 · bez Wi-Fi a ROS") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("BATERIE", t.batteryV?.let { "%.2f V".format(it) } ?: "—", "napětí", Cyan, Modifier.weight(1f))
                Metric("LEVÝ MOTOR", t.leftPwm?.toString() ?: "—", "PWM", Green, Modifier.weight(1f))
                Metric("PRAVÝ MOTOR", t.rightPwm?.toString() ?: "—", "PWM", Amber, Modifier.weight(1f))
            }
        }
        item {
            CardPanel {
                Text("STAV ŘÍZENÍ", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(if (armed && t.connected) "POHON POVOLEN" else "POHON ZAJIŠTĚN", color = if (armed && t.connected) Green else Red, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text("Výkonový limit aplikace: ${(power * 100).toInt()} %", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onDrivePage, modifier = Modifier.fillMaxWidth(), enabled = t.connected) { Text("OTEVŘÍT JOYSTICK") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onArm, enabled = t.connected && !armed, modifier = Modifier.weight(1f)) { Text("POVOLIT POHON") }
                    Button(onClick = onStop, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)) { Text("■ STOP") }
                }
            }
        }
        item {
            CardPanel {
                Text("TELEMETRIE", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                DataRow("Bluetooth zařízení", t.robotName)
                DataRow("Firmware", t.firmware)
                DataRow("Vzdálenost", t.distanceCm?.let { "%.1f cm".format(it) } ?: "—")
                DataRow("Doba běhu", t.uptimeMs?.let { "${it / 1000} s" } ?: "—")
                DataRow("Přijaté zprávy", t.messages.toString())
                Text("Připojení: ${if (t.connected) "aktivní" else "neaktivní"}", color = Muted, fontSize = 10.sp)
                OutlinedButton(onClick = onConnectPage, modifier = Modifier.fillMaxWidth()) { Text("NASTAVIT BLUETOOTH") }
            }
        }
        item {
            CardPanel {
                Text("DŮLEŽITÉ", color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Nouzové STOP v aplikaci není hardwarový nouzový vypínač. ESP32 má vlastní watchdog, ale při testování zvedni hnací kola a nejprve ověř zapojení motorového driveru.", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun DrivePage(t: RobotTelemetry, armed: Boolean, power: Float, onPower: (Float) -> Unit, onArm: () -> Unit, onStop: () -> Unit, onDrive: (Float, Float) -> Unit, onRelease: () -> Unit, command: String) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenTitle("MANUAL CONTROL", "Ruční řízení", "Drž joystick · puštění odešle nulový povel")
        CardPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (armed && t.connected) "POHON POVOLEN" else "POHON ZAJIŠTĚN", color = if (armed && t.connected) Green else Red, fontSize = 14.sp, fontWeight = FontWeight.Black)
                    Text(if (t.connected) "SPP spojení aktivní" else "Připoj robota v nabídce Bluetooth", color = Muted, fontSize = 10.sp)
                }
                Box(Modifier.size(9.dp).background(if (armed && t.connected) Green else Red, CircleShape))
            }
            Spacer(Modifier.height(6.dp))
            Joystick(Modifier.fillMaxWidth().weight(1f).heightIn(min = 180.dp), enabled = t.connected && armed, onMove = onDrive, onRelease = onRelease)
            Text("LIMIT VÝKONU · ${(power * 100).toInt()} %", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Slider(value = power, onValueChange = onPower, valueRange = 0.1f..0.6f, enabled = !armed)
            Text("Poslední povel: $command", color = Cyan, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onArm, enabled = t.connected && !armed, modifier = Modifier.weight(1f).height(50.dp)) { Text("POVOLIT") }
                Button(onClick = onStop, modifier = Modifier.weight(1f).height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)) { Text("■ NOUZOVÝ STOP") }
            }
        }
    }
}

@Composable
private fun Joystick(modifier: Modifier, enabled: Boolean, onMove: (Float, Float) -> Unit, onRelease: () -> Unit) {
    var pos by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }
    Box(modifier.background(Panel, RoundedCornerShape(16.dp)).pointerInput(enabled) {
        detectDragGestures(
            onDragStart = { p ->
                active = true
                val radius = minOf(size.width, size.height).toFloat() / 2f
                val dx = (p.x - size.width / 2f) / radius
                val dy = (size.height / 2f - p.y) / radius
                val len = sqrt(dx * dx + dy * dy).coerceAtMost(1f)
                pos = Offset(dx / maxOf(1f, sqrt(dx * dx + dy * dy)), -dy / maxOf(1f, sqrt(dx * dx + dy * dy))) * len
                if (enabled) onMove(pos.y, pos.x)
            },
            onDrag = { change, _ ->
                change.consume()
                val radius = size.minDimension / 2f
                val dx = (change.position.x - size.width / 2f) / radius
                val dy = (size.height / 2f - change.position.y) / radius
                val len = sqrt(dx * dx + dy * dy).coerceAtMost(1f)
                val divisor = maxOf(1f, sqrt(dx * dx + dy * dy))
                pos = Offset(dx / divisor * len, dy / divisor * len)
                if (enabled) onMove(pos.y, pos.x)
            },
            onDragEnd = { pos = Offset.Zero; active = false; onRelease() },
            onDragCancel = { pos = Offset.Zero; active = false; onRelease() }
        )
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension * .46f
            drawCircle(color = Border, radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
            drawCircle(color = Border.copy(alpha = .6f), radius = r * .62f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
            drawLine(color = Border, start = Offset(c.x, c.y-r), end = Offset(c.x, c.y+r), strokeWidth = 1.dp.toPx())
            drawLine(color = Border, start = Offset(c.x-r, c.y), end = Offset(c.x+r, c.y), strokeWidth = 1.dp.toPx())
            val knob = Offset(c.x + pos.x * r, c.y - pos.y * r)
            drawCircle(color = if (enabled && active) Cyan else Muted, radius = r * .23f, center = knob)
            drawCircle(color = Ink.copy(alpha = .7f), radius = r * .23f, center = knob, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        }
        if (!enabled) Text(if (tSafeConnectionHint()) "PŘIPOJ A POVOL POHON" else "POHON ZAJIŠTĚN", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

private fun tSafeConnectionHint() = true

@Composable
private fun TelemetryPage(t: RobotTelemetry) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        item { ScreenTitle("SYSTEM STATUS", "Telemetrie", "Pouze hodnoty skutečně přijaté z ESP32") }
        item {
            CardPanel {
                Text("MOTORY A POVELY", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                DataRow("Pohon", if (t.armed) "POVOLEN" else "ZAJIŠTĚN")
                DataRow("Throttle", t.throttle?.let { "%.3f".format(it) } ?: "—")
                DataRow("Steer", t.steer?.let { "%.3f".format(it) } ?: "—")
                DataRow("PWM levý", t.leftPwm?.toString() ?: "—")
                DataRow("PWM pravý", t.rightPwm?.toString() ?: "—")
            }
        }
        item {
            CardPanel {
                Text("SENZORY A SYSTÉM", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                DataRow("Napětí baterie", t.batteryV?.let { "%.2f V".format(it) } ?: "Nepřipojeno")
                DataRow("Ultrazvuk", t.distanceCm?.let { "%.1f cm".format(it) } ?: "Nepřipojeno")
                DataRow("Firmware", t.firmware)
                DataRow("Uptime", t.uptimeMs?.let { "${it / 1000} s" } ?: "—")
                DataRow("Počet zpráv", t.messages.toString())
                DataRow("Poslední paket", t.lastMessage)
                if (t.error != null) DataRow("Chyba", t.error, Red)
            }
        }
    }
}

@Composable
private fun ConnectionPage(t: RobotTelemetry, devices: List<BluetoothDevice>, selected: String, onSelect: (String) -> Unit, onRefresh: () -> Unit, onConnect: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenTitle("LINK CONFIGURATION", "Bluetooth SPP", "Nejdřív spáruj ESP32 v nastavení Bluetooth telefonu")
        CardPanel {
            Text("SPÁROVANÁ ZAŘÍZENÍ", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            if (devices.isEmpty()) {
                Text("Žádná spárovaná zařízení. Spáruj ESP32 s názvem Robot007 v nastavení Androidu a obnov seznam.", color = Muted, fontSize = 12.sp)
            } else {
                devices.forEach { device ->
                    val label = try { device.name ?: "Neznámé zařízení" } catch (_: SecurityException) { "Bluetooth zařízení" }
                    Row(Modifier.fillMaxWidth().background(if (selected == device.address) Panel2 else Color.Transparent, RoundedCornerShape(9.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == device.address, onClick = { onSelect(device.address) })
                        Column(Modifier.weight(1f)) {
                            Text(label, color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(device.address, color = Muted, fontSize = 10.sp)
                        }
                        Text(if (selected == device.address) "VYBRÁNO" else "", color = Cyan, fontSize = 8.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) { Text("OBNOVIT") }
                Button(onClick = onConnect, modifier = Modifier.weight(1f), enabled = !t.connected && selected.isNotBlank()) { Text("PŘIPOJIT") }
            }
            if (t.connected) Button(onClick = { /* disconnect is handled by top bar */ }, modifier = Modifier.fillMaxWidth(), enabled = false) { Text("SPP SPOJENÍ AKTIVNÍ") }
        }
        CardPanel {
            Text("PROTOKOL", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            DataRow("Transport", "Bluetooth Classic SPP")
            DataRow("Formát", "JSON, jeden paket na řádek")
            DataRow("Rychlost", "SPP stream")
            DataRow("Firmware", t.firmware)
            Text("Aplikace nevyhledává cizí zařízení automaticky; pracuje se spárovanými zařízeními.", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun DataRow(label: String, value: String, valueColor: Color = Ink) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1.25f))
    }
}
