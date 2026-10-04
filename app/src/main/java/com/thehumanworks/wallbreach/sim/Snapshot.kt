package com.thehumanworks.wallbreach.sim

/** Engine-independent view of the game, produced by the host every tick and mirrored by clients. */
enum class Phase { LOBBY, PLAYING, WAVE_BREAK, GAME_OVER }

enum class DroneKind(val maxHp: Int, val speed: Float, val radius: Float, val points: Int) {
  SCOUT(1, 1.25f, 0.10f, 100),
  GUNNER(2, 0.75f, 0.13f, 250),
  TANK(5, 0.45f, 0.18f, 500),
  BOSS(40, 0.35f, 0.42f, 5000),
}

enum class EventType {
  LASER, // a = muzzle, b = end point, player = shooter, value = hit drone id or -1
  EXPLODE, // a = position, value = DroneKind ordinal
  PLAYER_HIT, // a = position, player = victim, value = damage
  SHIELD_BLOCK, // a = position, player = blocker
  PORTAL_OPEN, // a = position, value = 1 if boss portal
  ENEMY_FIRE, // a = position
  WAVE_START, // value = wave number
  BOSS_INCOMING, // value = wave number
  WAVE_CLEAR, // value = wave number
  GAME_OVER, // value = final score
  PLAYER_DOWN, // player = victim
  PLAYER_REVIVED, // player = revived
}

data class GameEvent(
    val type: EventType,
    val player: Int = -1,
    val a: V3 = V3.ZERO,
    val b: V3 = V3.ZERO,
    val value: Int = 0,
)

data class PlayerView(
    val idx: Int,
    val connected: Boolean,
    val health: Float,
    val alive: Boolean,
    val head: V3,
    val headFwd: V3,
    val leftHand: V3,
    val rightHand: V3,
    val shieldActive: Boolean,
    val shieldCenter: V3,
    val shieldNormal: V3,
)

data class PortalView(val id: Int, val pos: V3, val normal: V3, val radius: Float, val boss: Boolean)

data class DroneView(val id: Int, val kind: DroneKind, val pos: V3, val hpFrac: Float)

data class ShotView(val id: Int, val pos: V3, val hostile: Boolean)

data class Snapshot(
    val tick: Int,
    val phase: Phase,
    val wave: Int,
    val score: Int,
    val combo: Int,
    val multiplier: Int,
    val phaseTimer: Float,
    val players: List<PlayerView>,
    val portals: List<PortalView>,
    val drones: List<DroneView>,
    val shots: List<ShotView>,
    val events: List<GameEvent>,
)

/**
 * Where portals may open: a vertical wall rectangle. [normal] is horizontal and points into the
 * room, [right] is horizontal along the wall. Heights are absolute (floor = 0).
 */
data class PortalSite(
    val center: V3,
    val normal: V3,
    val right: V3,
    val halfWidth: Float,
    val yMin: Float,
    val yMax: Float,
) {
  companion object {
    /** Four virtual walls in a square around [around] — used when there is no room scan. */
    fun virtualRoom(around: V3, halfSize: Float = 2.4f): List<PortalSite> {
      val c = V3(around.x, 1.4f, around.z)
      val dirs = listOf(V3(0f, 0f, -1f), V3(1f, 0f, 0f), V3(0f, 0f, 1f), V3(-1f, 0f, 0f))
      return dirs.map { d ->
        PortalSite(
            center = c + d * halfSize,
            normal = -d,
            right = V3.UP.cross(-d).norm(),
            halfWidth = halfSize,
            yMin = 0.8f,
            yMax = 2.2f,
        )
      }
    }
  }
}
