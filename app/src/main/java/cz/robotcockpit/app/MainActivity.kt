package cz.robotcockpit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RobotCockpit() }
    }
}

@Composable
fun RobotCockpit() {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("MAPA", "OVLADAČ", "SENZORY", "TRASY")
    MaterialTheme {
        Column(Modifier.fillMaxSize().background(Color(0xFF101418)).padding(12.dp)) {
            Text("ROBOT COCKPIT 0.1", color = Color.White, fontSize = 22.sp)
            Spacer(Modifier.height(10.dp))
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, title -> Tab(tab == i, { tab = i }, text = { Text(title) }) }
            }
            Spacer(Modifier.height(10.dp))
            when (tab) {
                0 -> MapPanel()
                1 -> ControllerPanel()
                2 -> SensorsPanel()
                else -> RoutesPanel()
            }
        }
    }
}

@Composable
fun MapPanel() {
    Column(Modifier.fillMaxSize()) {
        Text("2D mapa / odometrie", color = Color.White, fontSize = 18.sp)
        Text("X: 0.00 m   Y: 0.00 m   Úhel: 0°", color = Color.LightGray)
        Canvas(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF182027))) {
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(Color.White, 10f, c)
            drawLine(Color.Cyan, c, Offset(c.x, c.y - 70f), 5f)
            for (x in 0..8) drawLine(Color(0xFF26343D), Offset(x * size.width / 8, 0f), Offset(x * size.width / 8, size.height), 1f)
            for (y in 0..8) drawLine(Color(0xFF26343D), Offset(0f, y * size.height / 8), Offset(size.width, y * size.height / 8), 1f)
        }
    }
}

@Composable
fun ControllerPanel() {
    var speed by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Diferenciální řízení", color = Color.White, fontSize = 18.sp)
        Spacer(Modifier.height(15.dp))
        Box(Modifier.size(220.dp).background(Color(0xFF202830), CircleShape), contentAlignment = Alignment.Center) {
            Text("JOYSTICK\n${"%.2f".format(speed)}", color = Color.White, fontSize = 20.sp)
        }
        Spacer(Modifier.height(20.dp))
        Slider(value = speed, onValueChange = { speed = it }, valueRange = -1f..1f)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { speed = 0f }) { Text("STOP") }
            Button(onClick = { speed = 1f }) { Text("VPŘED") }
            Button(onClick = { speed = -1f }) { Text("VZAD") }
        }
    }
}

@Composable
fun SensorsPanel() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Senzory a telemetrie", color = Color.White, fontSize = 18.sp)
        listOf("IMU / Gyro" to "X 0.02  Y -0.01  Z 0.00", "Enkodér L" to "0 rpm / 0 imp",
            "Enkodér R" to "0 rpm / 0 imp", "LiDAR" to "simulace – 360°",
            "GPS" to "N 49.000000  E 18.000000", "Baterie" to "24.8 V   82 %").forEach { (a,b) ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(a); Text(b)
                }
            }
        }
    }
}

@Composable
fun RoutesPanel() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Programování tras", color = Color.White, fontSize = 18.sp)
        Text("Předdefinované kroky pro pozdější řízení robota.", color = Color.LightGray)
        Button(onClick = {}) { Text("NOVÁ TRASA") }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Testovací trasa")
                Text("1. Jeď 2.0 m → 2. Otoč 90° → 3. Jeď 1.0 m → HOME")
            }
        }
    }
}
