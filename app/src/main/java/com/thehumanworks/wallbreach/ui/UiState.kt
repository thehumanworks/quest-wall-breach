package com.thehumanworks.wallbreach.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Screen { MAIN, HOST_LOBBY, JOIN_LOBBY, CALIBRATE, PLAYING, GAME_OVER }

interface UiActions {
  fun onSolo()
  fun onHost()
  fun onJoin()
  fun onStartCoop()
  fun onCalibrate()
  fun onCancelCalibrate()
  fun onBack()
  fun onScanRoom()
  fun onPlayAgain()
}

/** Compose-observable state for the in-world panels. Written only from the main thread. */
class UiState {
  var screen by mutableStateOf(Screen.MAIN)
  var roomStatus by mutableStateOf("Looking for your room scan…")
  var netStatus by mutableStateOf("")
  var netDetail by mutableStateOf("")
  var calibrated by mutableStateOf(false)
  var partnerConnected by mutableStateOf(false)
  var isHost by mutableStateOf(false)
  var bestSolo by mutableIntStateOf(0)
  var bestCoop by mutableIntStateOf(0)
  var lastScore by mutableIntStateOf(0)
  var lastWave by mutableIntStateOf(0)
  var newBest by mutableStateOf(false)
  var lastWasCoop by mutableStateOf(false)
  var calibrateHold by mutableFloatStateOf(0f)

  // HUD
  var wave by mutableIntStateOf(0)
  var score by mutableIntStateOf(0)
  var multiplier by mutableIntStateOf(1)
  var combo by mutableIntStateOf(0)
  var health by mutableFloatStateOf(100f)
  var partnerHealth by mutableFloatStateOf(100f)
  var showPartner by mutableStateOf(false)
  var alive by mutableStateOf(true)
  var partnerAlive by mutableStateOf(true)
  var banner by mutableStateOf("")
  var hurtFlash by mutableFloatStateOf(0f)
}
