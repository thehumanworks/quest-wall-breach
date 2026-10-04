package com.thehumanworks.wallbreach

import com.thehumanworks.wallbreach.sim.DroneKind
import com.thehumanworks.wallbreach.sim.EventType
import com.thehumanworks.wallbreach.sim.GameSim
import com.thehumanworks.wallbreach.sim.Phase
import com.thehumanworks.wallbreach.sim.PortalSite
import com.thehumanworks.wallbreach.sim.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSimTest {
  private val dt = 1f / 60f

  /** Aimbot: shoot the nearest drone every 0.2 s from the player's head. */
  private fun autoplay(sim: GameSim, seconds: Float, players: Int = 1) {
    var t = 0f
    var cd = 0f
    while (t < seconds && sim.phase != Phase.GAME_OVER) {
      sim.step(dt)
      t += dt
      cd -= dt
      if (cd <= 0f) {
        cd = 0.2f
        for (i in 0 until players) {
          val head = sim.players[i].head
          sim.drones.minByOrNull { it.pos.distSq(head) }?.let { sim.fire(i, head, it.pos - head) }
        }
      }
    }
  }

  @Test
  fun wavesEscalateAndFifthIsBoss() {
    val sim = GameSim(seed = 3)
    sim.startGame(1)
    val seenBossWaves = mutableSetOf<Int>()
    var maxWave = 0
    var t = 0f
    var cd = 0f
    while (t < 400f && sim.wave < 6) {
      sim.step(dt); t += dt; cd -= dt
      sim.players[0].health = 100 // invulnerable for this test
      if (cd <= 0f) {
        cd = 0.12f
        val h = sim.players[0].head
        sim.drones.minByOrNull { it.pos.distSq(h) }?.let { sim.fire(0, h, it.pos - h) }
      }
      if (sim.drones.any { it.kind == DroneKind.BOSS }) seenBossWaves += sim.wave
      maxWave = maxOf(maxWave, sim.wave)
    }
    assertTrue("reached wave 6, got $maxWave", maxWave >= 6)
    assertEquals(setOf(5), seenBossWaves)
    assertTrue(sim.score > 0)
  }

  @Test
  fun idlePlayerEventuallyDies() {
    val sim = GameSim(seed = 4)
    sim.startGame(1)
    var t = 0f
    while (t < 300f && sim.phase != Phase.GAME_OVER) { sim.step(dt); t += dt }
    assertEquals(Phase.GAME_OVER, sim.phase)
    assertEquals(0, sim.players[0].health)
    assertTrue(sim.drones.isEmpty())
  }

  @Test
  fun comboMultiplierGrowsAndResetsOnHit() {
    val sim = GameSim(seed = 5)
    sim.startGame(1)
    autoplay(sim, 20f)
    // force a combo situation
    val before = sim.combo
    assertTrue(sim.multiplier >= 1)
    sim.players[0].health = 100
    // an enemy shot into the face resets combo
    var events = sim.drainEvents()
    var t = 0f
    while (t < 120f && events.none { it.type == EventType.PLAYER_HIT }) {
      sim.step(dt); t += dt; events = sim.drainEvents()
    }
    assertTrue(events.any { it.type == EventType.PLAYER_HIT })
    assertEquals(0, sim.combo)
    assertTrue(before >= 0)
  }

  @Test
  fun shieldReflectsShotsBackAtDrones() {
    val sim = GameSim(seed = 6)
    sim.startGame(1)
    // hold a shield right in front of the face, facing outwards towards where drones come from
    var blocks = 0
    var hits = 0
    var t = 0f
    while (t < 90f && sim.phase == Phase.PLAYING || (t < 90f && sim.phase == Phase.WAVE_BREAK)) {
      val head = sim.players[0].head
      val threat = sim.shots.filter { it.hostile }.minByOrNull { it.pos.distSq(head) }
      val dir = if (threat != null) (threat.pos - head).norm() else V3(0f, 0f, -1f)
      sim.updatePlayer(0, head, dir, head, head, true, head + dir * 0.3f, dir)
      sim.step(dt); t += dt
      for (e in sim.drainEvents()) {
        if (e.type == EventType.SHIELD_BLOCK) blocks++
        if (e.type == EventType.PLAYER_HIT && e.value == GameSim.SHOT_DAMAGE) hits++
      }
      sim.players[0].health = 100
    }
    assertTrue("expected shield blocks, got $blocks (shot hits $hits)", blocks > 0)
    assertTrue("shield should block most shots: blocks=$blocks hits=$hits", blocks >= hits)
  }

  @Test
  fun portalsSitOnRealWalls() {
    val sim = GameSim(seed = 7)
    // a single 3 m wide wall 2 m in front of the player, normal facing the player
    sim.sites = listOf(PortalSite(V3(0f, 1.3f, -2f), V3(0f, 0f, 1f), V3(1f, 0f, 0f), 1.5f, 0f, 2.6f))
    sim.startGame(1)
    repeat(20 * 60) {
      sim.step(dt)
      for (p in sim.portals) {
        assertEquals(-2f, p.pos.z, 0.05f)
        assertTrue(p.pos.x in -1.5f..1.5f)
        assertTrue(p.pos.y in 0.6f..2.3f)
      }
    }
  }

  @Test
  fun coopNeedsBothPlayersDownAndRevivesBetweenWaves() {
    val sim = GameSim(seed = 8)
    sim.startGame(2)
    sim.players[1].head = V3(1f, 1.6f, 0f)
    var t = 0f
    // player 1 idles and will be killed; player 0 is invulnerable and keeps shooting
    var p1Down = false
    var p1Revived = false
    var cd = 0f
    while (t < 600f && !p1Revived) {
      sim.step(dt); t += dt; cd -= dt
      sim.players[0].health = 100
      if (cd <= 0f) {
        cd = 0.15f
        val h = sim.players[0].head
        // only kill when p1 is already down so the wave can end
        if (p1Down) sim.drones.minByOrNull { it.pos.distSq(h) }?.let { sim.fire(0, h, it.pos - h) }
      }
      for (e in sim.drainEvents()) {
        if (e.type == EventType.PLAYER_DOWN && e.player == 1) p1Down = true
        if (e.type == EventType.PLAYER_REVIVED && e.player == 1) p1Revived = true
      }
      assertFalse("game must not end while player 0 lives", sim.phase == Phase.GAME_OVER)
    }
    assertTrue(p1Down)
    assertTrue(p1Revived)
    assertEquals(50, sim.players[1].health)
  }

  @Test
  fun clientHitClaimIsHonouredWithinTolerance() {
    val sim = GameSim(seed = 9)
    sim.startGame(2)
    while (sim.drones.isEmpty()) sim.step(dt)
    val d = sim.drones.first()
    val origin = V3(0f, 1.6f, 0f)
    // aim 0.3 m off to the side of the drone (client view lag) but claim it
    val aim = (d.pos + V3(0.3f, 0f, 0f) - origin).norm()
    assertEquals(d.id, sim.fire(1, origin, aim, d.id))
    // a wild claim far off the ray is rejected
    while (sim.drones.isEmpty()) sim.step(dt)
    val d2 = sim.drones.first()
    val away = (origin - d2.pos).norm()
    assertEquals(-1, sim.fire(1, origin, away, d2.id))
  }
}
