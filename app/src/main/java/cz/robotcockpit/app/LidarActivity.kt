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
fun LidarScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var devices by remember { mutableStateOf<List<LidarDevice>>(emptyList()) }
    var frame by remember { mutableStateOf<LidarFrame?>(null) }
    var status by remember { mutableStateOf("LDS BLE odpojeno") }
    var connected by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var selectedAddress by remember { mutableStateOf("") }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("LIVE POLAR SCAN", color = Color(0xFF45D9E8), fontSize = 11.sp)
                Text(frame?.let { "${it.rpm} RPM · ${it.rangesCm.count { d -> d > 0 }} bodů" } ?: "ČEKÁM NA DATA", color = Color(0xFF8EA3B7), fontSize = 10.sp)
            }
            LidarPolarView(frame, Modifier.fillMaxWidth().weight(1f))
            Text("Rozsah: 0–12,75 m · 360 vzorků · 5 cm / jednotka", color = Color(0xFF8EA3B7), fontSize = 9.sp)
            Text("Toto je živý LiDAR scan. Pro skutečné SLAM mapování je nutné doplnit odhad pohybu robota (enkodéry/IMU) a scan matching.", color = Color(0xFFFFC66D), fontSize = 10.sp)
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
