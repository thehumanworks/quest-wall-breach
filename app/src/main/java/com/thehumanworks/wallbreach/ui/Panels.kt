package com.thehumanworks.wallbreach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Cyan = Color(0xFF39E6FF)
private val Pink = Color(0xFFFF4FD8)
private val Amber = Color(0xFFFFC23A)
private val Bg = Color(0xE6070B1A)

@Composable
private fun NeonButton(text: String, color: Color = Cyan, enabled: Boolean = true, onClick: () -> Unit) {
  Button(
      onClick = onClick,
      enabled = enabled,
      shape = RoundedCornerShape(10.dp),
      colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.22f), contentColor = color, disabledContainerColor = Color(0x22FFFFFF), disabledContentColor = Color(0x66FFFFFF)),
      modifier = Modifier.fillMaxWidth().height(40.dp).border(1.dp, if (enabled) color else Color(0x33FFFFFF), RoundedCornerShape(10.dp)),
  ) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
  }
}

@Composable
private fun Title(sub: String) {
  Text("WALL BREACH", color = Cyan, fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
  Text(sub, color = Color(0xCCFFFFFF), fontSize = 12.sp, textAlign = TextAlign.Center)
  Spacer(Modifier.height(10.dp))
}

@Composable
private fun Small(text: String, color: Color = Color(0xB3FFFFFF)) {
  Text(text, color = color, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
fun MenuPanel(ui: UiState, actions: UiActions) {
  Box(
      Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(Bg).border(2.dp, Brush.linearGradient(listOf(Cyan, Pink)), RoundedCornerShape(18.dp)).padding(18.dp),
      contentAlignment = Alignment.TopCenter,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
      when (ui.screen) {
        Screen.MAIN -> {
          Title("Portals are cracking open in your walls. Hold the line.")
          NeonButton("SOLO") { actions.onSolo() }
          NeonButton("HOST CO-OP", Pink) { actions.onHost() }
          NeonButton("JOIN CO-OP", Pink) { actions.onJoin() }
          NeonButton("RESCAN ROOM", Amber) { actions.onScanRoom() }
          Spacer(Modifier.height(4.dp))
          Small("Best solo ${ui.bestSolo}   ·   Best co-op ${ui.bestCoop}", Amber)
          if (ui.netStatus.isNotEmpty()) Small(ui.netStatus, Pink)
          Small(ui.roomStatus)
          Small("Trigger / pinch: fire (hold for auto)  ·  Grip: shield (deflects shots)  ·  Hands: left palm = shield, right pinch = fire  ·  ☰ quits a run")
        }
        Screen.HOST_LOBBY,
        Screen.JOIN_LOBBY -> {
          Title(if (ui.screen == Screen.HOST_LOBBY) "Hosting a co-op game" else "Joining a co-op game")
          Small(ui.netStatus, if (ui.partnerConnected) Cyan else Amber)
          if (ui.netDetail.isNotEmpty()) Small(ui.netDetail)
          Small(if (ui.calibrated) "Shared spot: calibrated ✓" else "Shared spot: NOT calibrated — do this on both headsets", if (ui.calibrated) Cyan else Pink)
          NeonButton(if (ui.calibrated) "RECALIBRATE SHARED SPOT" else "CALIBRATE SHARED SPOT", Amber) { actions.onCalibrate() }
          if (ui.screen == Screen.HOST_LOBBY) NeonButton(if (ui.partnerConnected) "START CO-OP" else "START (partner can drop in)") { actions.onStartCoop() }
          NeonButton("BACK", Color(0xFFAAAAAA)) { actions.onBack() }
          Small(ui.roomStatus)
        }
        Screen.CALIBRATE -> {
          Title("Co-location calibration")
          Text(
              "1. Agree on one physical spot, e.g. the front-left corner of a table.\n" +
                  "2. Rest your RIGHT controller on that spot, pointing at the same wall your partner will point at.\n" +
                  "3. Press A (or, with hands, hold a right pinch for 1.5 s).\n" +
                  "4. Your partner does exactly the same on their headset.",
              color = Color.White,
              fontSize = 13.sp,
          )
          Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(ui.calibrateHold.coerceIn(0f, 1f)).background(Amber))
          }
          NeonButton("CANCEL", Color(0xFFAAAAAA)) { actions.onCancelCalibrate() }
        }
        Screen.GAME_OVER -> {
          Title(if (ui.lastWasCoop) "Co-op run over" else "The walls have fallen")
          Text("${ui.lastScore}", color = Amber, fontSize = 40.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
          Small("Reached wave ${ui.lastWave}")
          Small(if (ui.newBest) "★ NEW HIGH SCORE ★" else "Best ${if (ui.lastWasCoop) ui.bestCoop else ui.bestSolo}", if (ui.newBest) Pink else Color(0xB3FFFFFF))
          if (!ui.lastWasCoop || ui.isHost) NeonButton("PLAY AGAIN") { actions.onPlayAgain() } else Small("Waiting for the host to restart…", Amber)
          NeonButton("MENU", Color(0xFFAAAAAA)) { actions.onBack() }
        }
        Screen.PLAYING -> {}
      }
    }
  }
}

@Composable
private fun HealthBar(label: String, hp: Float, alive: Boolean, color: Color) {
  Column(Modifier.width(150.dp)) {
    Text(if (alive) label else "$label — DOWN", color = if (alive) color else Pink, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    Box(Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x44FFFFFF))) {
      val frac = (hp / 100f).coerceIn(0f, 1f)
      val c = if (frac > 0.5f) color else if (frac > 0.25f) Amber else Pink
      Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(c))
    }
  }
}

@Composable
fun HudPanel(ui: UiState) {
  val flash = ui.hurtFlash.coerceIn(0f, 1f)
  Box(
      Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color(0xB3070B1A)).border(2.dp, Pink.copy(alpha = 0.25f + 0.75f * flash), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
  ) {
    Column {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text("WAVE ${ui.wave}", color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        Text("${ui.score}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        Text(if (ui.multiplier > 1) "x${ui.multiplier}" else "combo ${ui.combo}", color = Amber, fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
      }
      Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        HealthBar("YOU", ui.health, ui.alive, Cyan)
        if (ui.showPartner) HealthBar("PARTNER", ui.partnerHealth, ui.partnerAlive, Pink)
      }
      if (ui.banner.isNotEmpty()) {
        Text(ui.banner, color = Pink, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
      }
    }
  }
}
