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
import kotlinx.coroutines.launch
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
    var mapFileStatus by remember { mutableStateOf("Mapa se průběžně ukládá do telefonu") }
    val mapFileScope = rememberCoroutineScope()

    // Export the complete SLAM session through Android's system file picker.
    val saveMapLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            mapFileScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    if (occupancyGrid.integratedScans() == 0) false
                    else {
                        occupancyGrid.saveTo(mapFile) &&
                            (context.contentResolver.openOutputStream(uri)?.use { output ->
                                occupancyGrid.writeTo(output)
                            } ?: false)
                    }
                }
                mapFileStatus = if (saved) "Mapa a SLAM graf byly exportovány" else "Export se nezdařil – není dostupná mapa nebo soubor"
            }
        }
    }
    val exportImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { uri ->
        if (uri != null) mapFileScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val svg = renderMapSvg(occupancyGrid, mappedPose)
                    context.contentResolver.openOutputStream(uri)?.use { stream -> stream.write(svg.toByteArray(Charsets.UTF_8)); true } ?: false
                } catch (_: Exception) { false }
            }
            mapFileStatus = if (ok) "Obrázek mapy byl uložen jako SVG" else "Export obrázku se nezdařil"
        }
    }

    val openMapLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            mapFileScope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    val imported = File(context.cacheDir, "robot007-import.r7slam")
                    val copied = try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream == null) false
                        else {
                            inputStream.use { input ->
                                imported.outputStream().use { output -> input.copyTo(output) }
                            }
                            true
                        }
                    } catch (_: Exception) { false }
                    if (!copied) false
                    else {
                        val validator = LidarSlamGrid()
                        if (!validator.loadFrom(imported)) false
                        else {
                            val restored = occupancyGrid.loadFrom(imported)
                            if (restored) {
                                imported.copyTo(mapFile, overwrite = true)
                            }
                            imported.delete()
                            restored
                        }
                    }
                }
                if (loaded) {
                    mappedPose = occupancyGrid.currentPose()
                    val (tracking, score) = occupancyGrid.matchStatus()
                    slamUpdate = SlamUpdate(
                        occupancyGrid.currentPose(), tracking, false, score,
                        occupancyGrid.integratedScans(), occupancyGrid.keyframeCount(),
                        occupancyGrid.loopClosures()
                    )
                    mapRevision++
                    mapFileStatus = "Mapa byla načtena – pokračuji v mapování"
                } else {
                    mapFileStatus = "Soubor není platná mapa Robot Cockpit (.r7slam)"
                }
            }
        }
    }

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
            val update = withContext(Dispatchers.Default) { occupancyGrid.process(currentFrame) }
            if (update != null) {
                slamUpdate = update
                mappedPose = update.pose
                mapRevision++
            }
        }
    }

    // Periodic autosave is independent of scan frequency, so continuous frames
    // cannot keep cancelling the save operation.
    LaunchedEffect(slamReady) {
        if (slamReady) {
            while (true) {
                delay(5000)
                if (occupancyGrid.integratedScans() > 0) {
                    withContext(Dispatchers.IO) { occupancyGrid.saveTo(mapFile) }
                }
            }
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
                    mapFile.delete()
                }) { Text("VYMAZAT") }
            }
            if (mapMode) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { saveMapLauncher.launch("robot007-map.r7slam") }, modifier = Modifier.weight(1f), enabled = slamReady && occupancyGrid.integratedScans() > 0, contentPadding = PaddingValues(4.dp)) { Text("ULOŽIT", fontSize = 10.sp) }
                    OutlinedButton(onClick = { exportImageLauncher.launch("robot007-map.svg") }, modifier = Modifier.weight(1f), enabled = slamReady && occupancyGrid.integratedScans() > 0, contentPadding = PaddingValues(4.dp)) { Text("EXPORT SVG", fontSize = 10.sp) }
                    OutlinedButton(onClick = { openMapLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("NAČÍST", fontSize = 10.sp) }
                }
                Text(mapFileStatus, color = Color(0xFF8EA3B7), fontSize = 9.sp)
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

private fun renderMapSvg(grid: LidarSlamGrid, pose: RobotPose2D?): String {
    val w = grid.width
    val h = grid.height
    val cells = grid.snapshot()
    val svg = StringBuilder()
    val lt = 60.toChar()
    fun tag(value: String) { svg.append(lt).append(value) }
    tag("""svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h">""")
    tag("""rect width="100%" height="100%" fill="#0d151e"/>""")
    tag("""g fill="#62e5a0">""")
    for (y in 0 until h) for (x in 0 until w) {
        val v = cells[y * w + x].toInt()
        if (v > 1) {
            val opacity = (v / 40f).coerceIn(.25f, 1f)
            tag("""rect x="$x" y="$y" width="1" height="1" opacity="$opacity"/>""")
        }
    }
    tag("/g>")
    val trail = grid.trajectorySnapshot().mapNotNull { grid.robotCell(it) }
    if (trail.isNotEmpty()) {
        tag("polyline fill=" + 34.toChar() + "none" + 34.toChar() + " stroke=" + 34.toChar() + "#ffc66d" + 34.toChar() + " stroke-width=" + 34.toChar() + "2" + 34.toChar() + " stroke-linecap=" + 34.toChar() + "round" + 34.toChar() + " points=")
        trail.forEach { svg.append("${it.first},${it.second} ") }
        svg.append(34.toChar()).append("/>")
    }
    pose?.let { p -> grid.robotCell(p)?.let { cell ->
        val x = cell.first
        val y = cell.second
        val angle = Math.toRadians(p.yawDeg)
        val ex = x + kotlin.math.sin(angle) * 18.0
        val ey = y - kotlin.math.cos(angle) * 18.0
        tag("""circle cx="$x" cy="$y" r="5" fill="none" stroke="#45d9e8" stroke-width="2"/>""")
        tag("""line x1="$x" y1="$y" x2="$ex" y2="$ey" stroke="#ffffff" stroke-width="2"/>""")
    } }
    tag("/svg>")
    return svg.toString()
}
