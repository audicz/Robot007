package cz.robotcockpit.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import android.bluetooth.BluetoothDevice
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

class LidarActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { LidarScreen(onBack = { finish() }) }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun LidarScreen(telemetry: RobotTelemetry = RobotTelemetry(), onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var devices by remember { mutableStateOf<List<LidarDevice>>(emptyList()) }
    var frame by remember { mutableStateOf<LidarFrame?>(null) }
    var status by remember { mutableStateOf("LDS BLE odpojeno") }
    var connected by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var selectedAddress by remember { mutableStateOf("") }
    var mapMode by remember { mutableStateOf(false) }
    var mapRevision by remember { mutableIntStateOf(0) }
    var mappedPose by remember { mutableStateOf<RobotPose2D?>(null) }
    var slamUpdate by remember { mutableStateOf<SlamUpdate?>(null) }
    var slamReady by remember { mutableStateOf(false) }
    val occupancyGrid = remember { LidarSlamGrid() }
    val mapFile = remember(context) { File(context.filesDir, "robot007_slam_map.bin") }

    // Restore map and graph before accepting the first new scan.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { occupancyGrid.loadFrom(mapFile) }
        mappedPose = occupancyGrid.currentPose()
        slamReady = true
        mapRevision++
    }

    LaunchedEffect(frame?.receivedAtMs, slamReady) {
        val currentFrame = frame
        if (slamReady && currentFrame != null) {
            val update = occupancyGrid.process(currentFrame)
            if (update != null) {
                slamUpdate = update
                mappedPose = update.pose
                mapRevision++
            }
        }
    }

    // Debounced autosave; the binary file contains the occupancy map and SLAM graph.
    LaunchedEffect(mapRevision, slamReady) {
        if (slamReady && occupancyGrid.integratedScans() > 0) {
            delay(1400)
            withContext(Dispatchers.IO) { occupancyGrid.saveTo(mapFile) }
        }
    }
    val client = remember {
        LidarBleClient(context, { devices = it }, { frame = it }, {
            status = it
            connected = it.contains("stream aktivní") || it.contains("BLE připojeno")
        })
    }
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            client.startScan()
            scanning = true
        } else status = "Chybí oprávnění pro BLE skenování / připojení"
    }
    fun ensurePermissionsAndScan() {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            client.startScan()
            scanning = true
        } else permissionLauncher.launch(missing.toTypedArray())
    }
    DisposableEffect(Unit) {
        onDispose { client.close() }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF45D9E8), background = Color(0xFF080D13), surface = Color(0xFF111B26))) {
        Column(Modifier.fillMaxSize().background(Color(0xFF080D13)).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color(0xFF45D9E8), fontSize = 32.sp, modifier = Modifier.padding(end = 10.dp))
                Column(Modifier.weight(1f)) {
                    Text("ROBOT COCKPIT · LDS", color = Color.White, fontSize = 17.sp)
                    Text("Xiaomi LDS BLE · 360° scan", color = Color(0xFF8EA3B7), fontSize = 10.sp)
                }
                TextButton(onClick = onBack) { Text("ZPĚT") }
            }
            Spacer(Modifier.height(8.dp))
            Text(status, color = if (connected) Color(0xFF62E5A0) else Color(0xFFFFC66D), fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ensurePermissionsAndScan() }, modifier = Modifier.weight(1f)) { Text(if (scanning) "OBNOVIT SCAN" else "VYHLEDAT LDS") }
                OutlinedButton(onClick = { client.disconnect(); connected = false; status = "LDS odpojeno"; frame = null }) { Text("ODPOJIT") }
            }
            if (!connected) {
                Text("BLE zařízení", color = Color(0xFF45D9E8), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                LazyColumn(Modifier.heightIn(max = 150.dp)) {
                    items(devices, key = { it.address }) { item ->
                        Row(Modifier.fillMaxWidth().background(Color(0xFF111B26), RoundedCornerShape(9.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = Color.White, fontSize = 12.sp)
                                Text(item.address, color = Color(0xFF8EA3B7), fontSize = 9.sp)
                            }
                            TextButton(onClick = {
                                selectedAddress = item.address
                                client.connect(item.device)
                                scanning = false
                            }) { Text("PŘIPOJIT") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (mapMode) "2D OCCUPANCY MAP" else "LIVE POLAR SCAN", color = Color(0xFF45D9E8), fontSize = 11.sp)
                Text(frame?.let { "${it.rpm} RPM · ${it.rangesCm.count { d -> d > 0 }} bodů" } ?: "ČEKÁM NA DATA", color = Color(0xFF8EA3B7), fontSize = 10.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { mapMode = false }, modifier = Modifier.weight(1f)) { Text("ŽIVÝ SKEN") }
                Button(onClick = { mapMode = true }, modifier = Modifier.weight(1f)) { Text("2D MAPA") }
                OutlinedButton(onClick = {
                    occupancyGrid.clear()
                    mappedPose = null
                    slamUpdate = null
                    mapRevision++
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { mapFile.delete() }
                }) { Text("VYMAZAT") }
            }
            if (mapMode) {
                LidarOccupancyView(occupancyGrid, mapRevision, mappedPose, Modifier.fillMaxWidth().weight(1f))
                val update = slamUpdate
                Text(
                    when {
                        update == null -> "ČEKÁM NA KOMPLETNÍ 360° SKEN"
                        !update.tracking -> "TRACKING ZTRACEN · mapa se nezapisuje (score ${"%.2f".format(update.score)})"
                        update.loopClosure -> "LOOP CLOSURE · ${update.loopClosures} oprav · ${update.keyframes} keyframů · score ${"%.2f".format(update.score)}"
                        else -> "LiDAR SLAM · ${update.scans} skenů · ${update.keyframes} keyframů · loop closures ${update.loopClosures} · score ${"%.2f".format(update.score)}"
                    },
                    color = if (update?.tracking == true) Color(0xFF62E5A0) else Color(0xFFFFC66D),
                    fontSize = 10.sp
                )
                Text("LiDAR-only scan matching + ICP loop closure + pose graph · automatické ukládání mapy · 30 × 30 m / 5 cm",
                    color = Color(0xFF8EA3B7), fontSize = 9.sp)
            } else {
                LidarPolarView(frame, Modifier.fillMaxWidth().weight(1f))
                Text("Rozsah: 0–12,75 m · 360 vzorků · 5 cm / jednotka", color = Color(0xFF8EA3B7), fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun LidarPolarView(frame: LidarFrame?, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFF0D151E), RoundedCornerShape(14.dp)).padding(8.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * .47f
        for (ring in 1..4) {
            drawCircle(Color(0xFF2A3C4E), radius * ring / 4f, center, style = Stroke(1.dp.toPx()))
        }
        drawLine(Color(0xFF2A3C4E), Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.dp.toPx())
        drawLine(Color(0xFF2A3C4E), Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1.dp.toPx())
        drawCircle(Color(0xFF45D9E8), 5.dp.toPx(), center)
        val ranges = frame?.rangesCm ?: return@Canvas
        val maxRangeCm = 1275f
        ranges.forEachIndexed { index, distance ->
            if (distance > 0) {
                val r = (distance / maxRangeCm).coerceIn(0f, 1f) * radius
                val angle = Math.toRadians(index.toDouble() - 90.0)
                val p = Offset(center.x + cos(angle).toFloat() * r, center.y + sin(angle).toFloat() * r)
                drawCircle(Color(0xFF62E5A0), 2.dp.toPx(), p)
            }
        }
    }
}

@Composable
private fun LidarOccupancyView(
    grid: LidarSlamGrid,
    revision: Int,
    mappedPose: RobotPose2D?,
    modifier: Modifier = Modifier
) {
    val cells = remember(revision) { grid.snapshot() }
    val displayPose = mappedPose
    val robotCell = remember(revision, displayPose) {
        displayPose?.let { grid.robotCell(it) }
    }
    val trajectory = remember(revision) {
        grid.trajectorySnapshot().mapNotNull { grid.robotCell(it) }
    }
    // Keep map cells square on both portrait and landscape screens.
    BoxWithConstraints(
        modifier.background(Color(0xFF0D151E), RoundedCornerShape(14.dp)).padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        val mapSize = minOf(maxWidth, maxHeight)
        Canvas(Modifier.size(mapSize)) {
        val cellW = size.width / grid.width
        val cellH = size.height / grid.height
        for (y in 0 until grid.height) {
            for (x in 0 until grid.width) {
                val value = cells[y * grid.width + x].toInt()
                if (value > 1) {
                    drawRect(
                        color = Color(0xFF62E5A0).copy(alpha = (value / 40f).coerceIn(.25f, 1f)),
                        topLeft = Offset(x * cellW, y * cellH),
                        size = androidx.compose.ui.geometry.Size(cellW + .25f, cellH + .25f)
                    )
                }
            }
        }
        for (i in 1 until trajectory.size) {
            val a = trajectory[i - 1]
            val b = trajectory[i]
            drawLine(
                Color(0xFFFFC66D).copy(alpha = 0.9f),
                Offset(a.first * cellW, a.second * cellH),
                Offset(b.first * cellW, b.second * cellH),
                strokeWidth = 2.dp.toPx()
            )
        }
        val robot = robotCell
        if (robot != null) {
            val p = Offset(robot.first * cellW, robot.second * cellH)
            drawCircle(Color(0xFF45D9E8), 5.dp.toPx(), p)
            val yaw = Math.toRadians(displayPose?.yawDeg ?: 0.0)
            val end = Offset(p.x + sin(yaw).toFloat() * 18.dp.toPx(), p.y - cos(yaw).toFloat() * 18.dp.toPx())
            drawLine(Color.White, p, end, 2.dp.toPx())
        }
        }
    }
}
