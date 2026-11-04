package cz.robotcockpit.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object CockpitPalette {
    val Background = Color(0xFF080D13)
    val Panel = Color(0xFF111B26)
    val PanelRaised = Color(0xFF192735)
    val Border = Color(0xFF2A3C4E)
    val Cyan = Color(0xFF45D9E8)
    val Green = Color(0xFF62E5A0)
    val Amber = Color(0xFFFFC66D)
    val Red = Color(0xFFFF5F6D)
    val Muted = Color(0xFF8EA3B7)
    val Text = Color(0xFFEAF3FB)
    val MapBackground = Color(0xFF0D151E)
}

@Composable
internal fun CockpitScreenHeader(kicker: String, title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(kicker, color = CockpitPalette.Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Text(title, color = CockpitPalette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = CockpitPalette.Muted, fontSize = 11.sp)
    }
}

@Composable
internal fun CockpitPanel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CockpitPalette.Panel),
        border = BorderStroke(1.dp, CockpitPalette.Border),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(13.dp), content = content)
    }
}
