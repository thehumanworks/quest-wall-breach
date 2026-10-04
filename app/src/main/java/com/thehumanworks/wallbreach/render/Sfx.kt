package com.thehumanworks.wallbreach.render

import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SceneAudioAsset
import com.thehumanworks.wallbreach.sim.V3

/** Procedurally generated sound effects (see tools/gen_sounds.py) played as spatial audio. */
class Sfx(private val scene: Scene) {
  private fun load(name: String): SceneAudioAsset? =
      try {
        SceneAudioAsset.loadLocalFile("audio/$name.ogg")
      } catch (e: Throwable) {
        android.util.Log.w("WallBreach", "audio $name failed: ${e.message}")
        null
      }

  val laser = load("laser")
  val explode = load("explode")
  val bossExplode = load("boss_explode")
  val hurt = load("hurt")
  val shield = load("shield")
  val portal = load("portal")
  val enemyShot = load("enemy_shot")
  val wave = load("wave")
  val bossAlarm = load("boss_alarm")
  val gameOver = load("gameover")
  val click = load("click")
  val reflect = load("reflect")

  /** Positional (spatialised) sound at a world-space position. */
  fun at(asset: SceneAudioAsset?, worldPos: V3, volume: Float = 1f) {
    asset ?: return
    try {
      scene.playSound(asset, Vector3(worldPos.x, worldPos.y, worldPos.z), volume)
    } catch (_: Throwable) {}
  }

  /** Non-positional UI / self sound. */
  fun ui(asset: SceneAudioAsset?, volume: Float = 1f) {
    asset ?: return
    try {
      scene.playSound(asset, volume)
    } catch (_: Throwable) {}
  }
}
