package com.thehumanworks.wallbreach

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Hand
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.mruk.MRUKLoadDeviceResult
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import com.thehumanworks.wallbreach.ui.HudPanel
import com.thehumanworks.wallbreach.ui.MenuPanel
import com.thehumanworks.wallbreach.ui.UiActions
import com.thehumanworks.wallbreach.ui.UiState

/** Wall Breach — a mixed-reality wall-defence game built on the Meta Spatial SDK. */
class WallBreachActivity : AppSystemActivity() {
  lateinit var mruk: MRUKFeature
    private set
  private val ui = UiState()
  private var game: GameController? = null

  // Panels are registered during super.onCreate(), before the game system exists, so route clicks lazily.
  private val actions =
      object : UiActions {
        override fun onSolo() { game?.onSolo() }
        override fun onHost() { game?.onHost() }
        override fun onJoin() { game?.onJoin() }
        override fun onStartCoop() { game?.onStartCoop() }
        override fun onCalibrate() { game?.onCalibrate() }
        override fun onCancelCalibrate() { game?.onCancelCalibrate() }
        override fun onBack() { game?.onBack() }
        override fun onScanRoom() { game?.onScanRoom() }
        override fun onPlayAgain() { game?.onPlayAgain() }
      }

  override fun registerFeatures(): List<SpatialFeature> {
    mruk = MRUKFeature(this, systemManager)
    return listOf(VRFeature(this), ComposeFeature(), mruk)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val g = GameController(this, ui)
    game = g
    systemManager.registerSystem(g)
    systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)
    scene.enablePassthrough(true)

    if (checkSelfPermission(PERMISSION_USE_SCENE) == PackageManager.PERMISSION_GRANTED) {
      loadRoom()
    } else {
      requestPermissions(arrayOf(PERMISSION_USE_SCENE), REQUEST_USE_SCENE)
    }
  }

  override fun onSceneReady() {
    super.onSceneReady()
    scene.setLightingEnvironment(
        ambientColor = Vector3(0.6f),
        sunColor = Vector3(1f, 1f, 1f),
        sunDirection = -Vector3(1f, 3f, -2f),
        environmentIntensity = 0.3f,
    )
    game?.onSceneReady()
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == REQUEST_USE_SCENE) {
      if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadRoom() else game?.onRoomLoaded(false)
    }
  }

  private fun loadRoom() {
    try {
      mruk.loadSceneFromDevice().whenComplete { result: MRUKLoadDeviceResult?, err: Throwable? ->
        if (err != null) Log.w(TAG, "loadSceneFromDevice failed", err)
        game?.onRoomLoaded(result == MRUKLoadDeviceResult.SUCCESS)
      }
    } catch (e: Exception) {
      Log.w(TAG, "loadSceneFromDevice threw", e)
      game?.onRoomLoaded(false)
    }
  }

  /** Launches the system Space Setup flow, then reloads the room. */
  fun rescanRoom() {
    try {
      mruk.requestSceneCapture().whenComplete { _, _ -> runOnUiThread { loadRoom() } }
    } catch (e: Exception) {
      Log.w(TAG, "requestSceneCapture failed", e)
    }
  }

  fun haptic(hand: Hand, amplitude: Float, millis: Int) {
    try {
      spatial.applyHapticFeedback(hand, amplitude, millis * 1_000_000L, 200f)
    } catch (_: Throwable) {}
  }

  override fun onSpatialShutdown() {
    game?.shutdown()
    super.onSpatialShutdown()
  }

  override fun registerPanels(): List<PanelRegistration> =
      listOf(
          ComposeViewPanelRegistration(
              R.id.menu_panel,
              composeViewCreator = { _, ctx -> ComposeView(ctx).apply { setContent { MenuPanel(ui, actions) } } },
              settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = 1.0f, height = 0.78f),
                    style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                    display = DpPerMeterDisplayOptions(),
                )
              },
          ),
          ComposeViewPanelRegistration(
              R.id.hud_panel,
              composeViewCreator = { _, ctx -> ComposeView(ctx).apply { setContent { HudPanel(ui) } } },
              settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = 0.8f, height = 0.2f),
                    style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                    display = DpPerMeterDisplayOptions(),
                )
              },
          ),
      )

  companion object {
    private const val TAG = "WallBreach"
    const val PERMISSION_USE_SCENE = "com.oculus.permission.USE_SCENE"
    const val REQUEST_USE_SCENE = 1
  }
}
