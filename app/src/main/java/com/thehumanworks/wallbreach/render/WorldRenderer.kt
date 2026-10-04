package com.thehumanworks.wallbreach.render

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Quaternion
import com.meta.spatial.toolkit.Material
import com.thehumanworks.wallbreach.sim.DroneKind
import com.thehumanworks.wallbreach.sim.SharedFrame
import com.thehumanworks.wallbreach.sim.Snapshot
import com.thehumanworks.wallbreach.sim.V3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Turns engine-independent [Snapshot]s (shared frame) into Spatial SDK entities (device world
 * frame). The same renderer is used by solo, host and client, so all three look identical.
 */
class WorldRenderer {
  var frame: SharedFrame = SharedFrame.IDENTITY
  private var time = 0f
  private val rng = Random(1)

  // ---- palette (unlit = self-illuminated "neon")
  private val portalRim = Prims.glow(0.85f, 0.2f, 1f)
  private val portalRimBoss = Prims.glow(1f, 0.25f, 0.1f)
  private val portalCore = Prims.glow(0.03f, 0f, 0.07f)
  private val portalShard = Prims.glow(0.4f, 0.9f, 1f)
  private val portalShardBoss = Prims.glow(1f, 0.8f, 0.2f)
  private val white = Prims.glow(1f, 1f, 1f)
  private val eyeMat = Prims.glow(1f, 0.1f, 0.15f)
  private val hostileShot = Prims.glow(1f, 0.15f, 0.35f)
  private val hostileHalo = Prims.glow(1f, 0.2f, 0.4f, 0.35f)
  private val friendlyShot = Prims.glow(0.3f, 1f, 1f)
  private val friendlyHalo = Prims.glow(0.3f, 1f, 1f, 0.35f)
  private val shieldMat = Prims.glow(0.3f, 0.85f, 1f, 0.35f)
  private val shieldRim = Prims.glow(0.5f, 0.95f, 1f, 0.8f)
  private val markerMat = Prims.glow(1f, 0.8f, 0.2f)
  private val explodeCore = Prims.glow(1f, 0.95f, 0.8f)
  private val explodeHalo = Prims.glow(1f, 0.5f, 0.1f, 0.45f)
  private val debrisMat = Prims.glow(1f, 0.7f, 0.2f)
  private val playerColors = listOf(Triple(0.25f, 0.9f, 1f), Triple(1f, 0.3f, 0.85f))

  private fun droneCore(k: DroneKind): Material =
      when (k) {
        DroneKind.SCOUT -> Prims.glow(1f, 0.75f, 0.1f)
        DroneKind.GUNNER -> Prims.glow(0.1f, 1f, 0.55f)
        DroneKind.TANK -> Prims.glow(0.7f, 0.3f, 1f)
        DroneKind.BOSS -> Prims.glow(1f, 0.15f, 0.1f)
      }

  private fun droneHalo(k: DroneKind): Material =
      when (k) {
        DroneKind.SCOUT -> Prims.glow(1f, 0.75f, 0.1f, 0.25f)
        DroneKind.GUNNER -> Prims.glow(0.1f, 1f, 0.55f, 0.25f)
        DroneKind.TANK -> Prims.glow(0.7f, 0.3f, 1f, 0.25f)
        DroneKind.BOSS -> Prims.glow(1f, 0.2f, 0.1f, 0.3f)
      }

  private class PortalVis(val rim: Entity, val core: Entity, val shards: List<Entity>, val boss: Boolean)

  private inner class DroneVis(val kind: DroneKind, var pos: V3) {
    val core = Prims.sphere(droneCore(kind))
    val halo = Prims.sphere(droneHalo(kind))
    val eye = Prims.sphere(eyeMat)
    val ring: Entity? = if (kind == DroneKind.SCOUT) null else Prims.sphere(droneCore(kind))
    val sats: List<Entity> = if (kind == DroneKind.BOSS) List(4) { Prims.sphere(droneCore(DroneKind.SCOUT)) } else emptyList()
    var lastHp = 1f
    var flash = 0f
    var flashing = false
    val spin = rng.nextFloat() * 6f
    fun all() = listOfNotNull(core, halo, eye, ring) + sats
  }

  private class ShotVis(val core: Entity, val halo: Entity, var pos: V3, var hostile: Boolean)

  private class AvatarVis(val head: Entity, val visor: Entity, val lh: Entity, val rh: Entity, val shield: Entity) {
    var head0 = V3.ZERO
    var lh0 = V3.ZERO
    var rh0 = V3.ZERO
    var sc0 = V3.ZERO
    fun all() = listOf(head, visor, lh, rh, shield)
  }

  private class Fx(val parts: List<Entity>, val life: Float, val tick: (Float, Float) -> Unit) {
    var age = 0f
  }

  private val portals = HashMap<Int, PortalVis>()
  private val drones = HashMap<Int, DroneVis>()
  private val shots = HashMap<Int, ShotVis>()
  private val avatars = HashMap<Int, AvatarVis>()
  private val fx = ArrayList<Fx>()
  private var localShield: Pair<Entity, Entity>? = null
  private var marker: List<Entity>? = null

  /** Smoothed drone positions in the SHARED frame, as currently displayed (for client hit claims). */
  fun displayedDrones(): List<Pair<Int, Pair<V3, Float>>> = drones.map { (id, d) -> id to (d.pos to d.kind.radius) }

  fun render(snap: Snapshot?, dt: Float, myIdx: Int, headWorld: V3, smooth: Boolean) {
    time += dt
    val k = if (smooth) min(1f, dt * 14f) else 1f
    syncPortals(snap)
    syncDrones(snap, dt, k, headWorld)
    syncShots(snap, if (smooth) min(1f, dt * 20f) else 1f)
    syncAvatars(snap, myIdx, if (smooth) min(1f, dt * 18f) else 1f)
    tickFx(dt)
  }

  private fun syncPortals(snap: Snapshot?) {
    val live = snap?.portals?.associateBy { it.id } ?: emptyMap()
    portals.keys.filter { it !in live }.forEach { id -> portals.remove(id)?.let { v -> (listOf(v.rim, v.core) + v.shards).forEach { it.destroy() } } }
    for ((id, p) in live) {
      val vis =
          portals.getOrPut(id) {
            PortalVis(Prims.sphere(if (p.boss) portalRimBoss else portalRim), Prims.sphere(portalCore), List(if (p.boss) 10 else 6) { Prims.box(if (p.boss) portalShardBoss else portalShard) }, p.boss)
          }
      val pos = frame.sharedToWorld(p.pos)
      val n = frame.dirSharedToWorld(p.normal).norm()
      val rot = lookAlong(n)
      val r = p.radius.coerceAtLeast(0.001f)
      val pulse = 1f + 0.05f * sin(time * 6f + id)
      Prims.place(vis.rim, pos, rot, V3(r * pulse, r * 1.2f * pulse, 0.01f))
      Prims.place(vis.core, pos + n * 0.006f, rot, V3(r * 0.82f, r * 1.0f, 0.012f))
      val right = V3.UP.cross(n).norm()
      val up = n.cross(right).norm()
      val dir = if (vis.boss) -1f else 1f
      vis.shards.forEachIndexed { i, s ->
        val a = dir * time * 1.6f + i * (2f * PI.toFloat() / vis.shards.size)
        val off = right * (cos(a) * r * 1.12f) + up * (sin(a) * r * 1.32f)
        val sz = 0.05f * (r / 0.4f)
        Prims.place(s, pos + off + n * 0.02f, rot, V3(sz, sz * 0.5f, 0.015f))
      }
    }
  }

  private fun syncDrones(snap: Snapshot?, dt: Float, k: Float, headWorld: V3) {
    val live = snap?.drones?.associateBy { it.id } ?: emptyMap()
    drones.keys.filter { it !in live }.forEach { id -> drones.remove(id)?.all()?.forEach { it.destroy() } }
    for ((id, d) in live) {
      val vis = drones.getOrPut(id) { DroneVis(d.kind, d.pos) }
      vis.pos = vis.pos.lerp(d.pos, k)
      if (d.hpFrac < vis.lastHp - 1e-3f) {
        vis.flash = 0.1f
        if (!vis.flashing) {
          vis.core.setComponent(white)
          vis.flashing = true
        }
      }
      vis.lastHp = d.hpFrac
      if (vis.flash > 0f) {
        vis.flash -= dt
        if (vis.flash <= 0f && vis.flashing) {
          vis.core.setComponent(droneCore(d.kind))
          vis.flashing = false
        }
      }
      val r = d.kind.radius
      val w = frame.sharedToWorld(vis.pos)
      val toHead = (headWorld - w).norm()
      val face = lookAlong(toHead)
      val pulse = 1f + 0.12f * sin(time * 8f + vis.spin)
      Prims.place(vis.core, w, face, Prims.uniform(r * 0.75f))
      Prims.place(vis.halo, w, face, Prims.uniform(r * 1.35f * pulse))
      Prims.place(vis.eye, w + toHead * (r * 0.62f), face, Prims.uniform(r * 0.28f))
      vis.ring?.let { ring ->
        val spinQ = Quaternion.fromAxisAngleRadians(com.meta.spatial.core.Vector3(0f, 1f, 0f), time * 3f + vis.spin)
        val tilt = Quaternion.fromAxisAngleRadians(com.meta.spatial.core.Vector3(1f, 0f, 0f), 0.35f)
        Prims.place(ring, w, tilt * spinQ, V3(r * 1.7f, r * 0.08f, r * 1.7f))
      }
      vis.sats.forEachIndexed { i, s ->
        val a = time * 2.2f + i * (PI.toFloat() / 2f)
        Prims.place(s, w + V3(cos(a), sin(a * 0.7f) * 0.3f, sin(a)) * (r * 1.7f), Quaternion(), Prims.uniform(0.06f))
      }
    }
  }

  private fun syncShots(snap: Snapshot?, k: Float) {
    val live = snap?.shots?.associateBy { it.id } ?: emptyMap()
    shots.keys.filter { it !in live }.forEach { id -> shots.remove(id)?.let { it.core.destroy(); it.halo.destroy() } }
    for ((id, s) in live) {
      val vis = shots.getOrPut(id) { ShotVis(Prims.sphere(if (s.hostile) hostileShot else friendlyShot), Prims.sphere(if (s.hostile) hostileHalo else friendlyHalo), s.pos, s.hostile) }
      if (vis.hostile != s.hostile) {
        vis.hostile = s.hostile
        vis.core.setComponent(if (s.hostile) hostileShot else friendlyShot)
        vis.halo.setComponent(if (s.hostile) hostileHalo else friendlyHalo)
        vis.pos = s.pos
      }
      vis.pos = vis.pos.lerp(s.pos, k)
      val w = frame.sharedToWorld(vis.pos)
      Prims.place(vis.core, w, Quaternion(), Prims.uniform(0.03f))
      Prims.place(vis.halo, w, Quaternion(), Prims.uniform(0.065f * (1f + 0.2f * sin(time * 20f + id))))
    }
  }

  private fun syncAvatars(snap: Snapshot?, myIdx: Int, k: Float) {
    val others = snap?.players?.filter { it.idx != myIdx && it.connected && myIdx >= 0 }?.associateBy { it.idx } ?: emptyMap()
    avatars.keys.filter { it !in others }.forEach { id -> avatars.remove(id)?.all()?.forEach { it.destroy() } }
    for ((idx, p) in others) {
      val (r, g, b) = playerColors[idx % playerColors.size]
      val vis =
          avatars.getOrPut(idx) {
            AvatarVis(
                    Prims.sphere(Prims.glow(r, g, b, 0.45f)),
                    Prims.box(Prims.glow(r, g, b)),
                    Prims.sphere(Prims.glow(r, g, b)),
                    Prims.sphere(Prims.glow(r, g, b)),
                    Prims.sphere(shieldMat),
                )
                .also {
                  it.head0 = p.head
                  it.lh0 = p.leftHand
                  it.rh0 = p.rightHand
                  it.sc0 = p.shieldCenter
                }
          }
      vis.head0 = vis.head0.lerp(p.head, k)
      vis.lh0 = vis.lh0.lerp(p.leftHand, k)
      vis.rh0 = vis.rh0.lerp(p.rightHand, k)
      vis.sc0 = vis.sc0.lerp(p.shieldCenter, k)
      val head = frame.sharedToWorld(vis.head0)
      val fwd = frame.dirSharedToWorld(p.headFwd).norm()
      val dim = if (p.alive) 1f else 0.5f
      val face = lookAlong(fwd)
      Prims.place(vis.head, head, face, V3(0.1f, 0.12f, 0.11f) * dim)
      Prims.place(vis.visor, head + fwd * 0.09f, face, V3(0.15f, 0.045f, 0.03f) * dim)
      Prims.place(vis.lh, frame.sharedToWorld(vis.lh0), Quaternion(), Prims.uniform(0.04f * dim))
      Prims.place(vis.rh, frame.sharedToWorld(vis.rh0), Quaternion(), Prims.uniform(0.04f * dim))
      if (p.shieldActive) {
        Prims.place(vis.shield, frame.sharedToWorld(vis.sc0), lookAlong(frame.dirSharedToWorld(p.shieldNormal)), V3(0.24f, 0.24f, 0.012f))
      } else {
        Prims.place(vis.shield, head, Quaternion(), V3.ZERO)
      }
    }
  }

  /** The local player's own shield, drawn directly from tracked input (no network lag). */
  fun setLocalShield(active: Boolean, centerWorld: V3, normalWorld: V3) {
    val (disc, rim) = localShield ?: (Prims.sphere(shieldMat) to Prims.sphere(shieldRim)).also { localShield = it }
    if (active) {
      val rot = lookAlong(normalWorld)
      val pulse = 1f + 0.03f * sin(time * 10f)
      Prims.place(disc, centerWorld, rot, V3(0.24f * pulse, 0.24f * pulse, 0.012f))
      Prims.place(rim, centerWorld - normalWorld * 0.004f, rot, V3(0.255f, 0.255f, 0.006f))
    } else {
      Prims.place(disc, centerWorld, Quaternion(), V3.ZERO)
      Prims.place(rim, centerWorld, Quaternion(), V3.ZERO)
    }
  }

  fun showMarker(worldPos: V3?, fwd: V3 = V3.FWD) {
    if (worldPos == null) {
      marker?.forEach { Prims.place(it, V3.ZERO, Quaternion(), V3.ZERO) }
      return
    }
    val m = marker ?: List(3) { if (it == 2) Prims.sphere(markerMat) else Prims.box(markerMat) }.also { marker = it }
    val rot = lookAlong(fwd.flat().let { if (it.lenSq() < 1e-4f) V3.FWD else it })
    Prims.place(m[0], worldPos, rot, V3(0.25f, 0.006f, 0.006f))
    Prims.place(m[1], worldPos + fwd.flat().norm() * 0.1f, rot, V3(0.006f, 0.006f, 0.25f))
    Prims.place(m[2], worldPos, rot, Prims.uniform(0.015f))
  }

  // ------------------------------------------------------------------ effects

  fun beam(fromWorld: V3, toWorld: V3, playerIdx: Int) {
    val (r, g, b) = playerColors[playerIdx.coerceAtLeast(0) % playerColors.size]
    val len = fromWorld.dist(toWorld).coerceAtLeast(0.01f)
    val dir = (toWorld - fromWorld).norm()
    val rot = lookAlong(dir)
    val mid = (fromWorld + toWorld) * 0.5f
    val core = Prims.box(Prims.glow(1f, 1f, 1f), mid, V3(0.006f, 0.006f, len), rot)
    val glow = Prims.box(Prims.glow(r, g, b, 0.6f), mid, V3(0.018f, 0.018f, len), rot)
    val tip = Prims.sphere(Prims.glow(r, g, b), toWorld, Prims.uniform(0.03f))
    fx += Fx(listOf(core, glow, tip), 0.09f) { t, _ ->
      val s = 1f - t
      core.setComponent(com.meta.spatial.toolkit.Scale(com.meta.spatial.core.Vector3(0.006f * s, 0.006f * s, len)))
      glow.setComponent(com.meta.spatial.toolkit.Scale(com.meta.spatial.core.Vector3(0.018f * s, 0.018f * s, len)))
      tip.setComponent(com.meta.spatial.toolkit.Scale(com.meta.spatial.core.Vector3(0.03f + 0.05f * t)))
    }
  }

  fun explosion(worldPos: V3, size: Float) {
    val core = Prims.sphere(explodeCore, worldPos, Prims.uniform(0.1f * size))
    val halo = Prims.sphere(explodeHalo, worldPos, Prims.uniform(0.1f * size))
    val n = if (size > 2f) 14 else 7
    val debris = List(n) { Prims.box(debrisMat, worldPos, Prims.uniform(0.025f * min(size, 2f))) }
    val vels = List(n) { V3(rng.nextFloat() - 0.5f, rng.nextFloat() * 0.8f, rng.nextFloat() - 0.5f).norm() * (1.2f + rng.nextFloat() * 1.5f) * min(size, 2.5f) }
    val pos = MutableList(n) { worldPos }
    val life = if (size > 2f) 1.1f else 0.55f
    fx += Fx(listOf(core, halo) + debris, life) { t, dt ->
      Prims.place(core, worldPos, Quaternion(), Prims.uniform(0.12f * size * (1f - t).coerceAtLeast(0f)))
      Prims.place(halo, worldPos, Quaternion(), Prims.uniform(0.12f * size + 0.35f * size * t))
      for (i in 0 until n) {
        val v = vels[i] + V3(0f, -4f * t * life, 0f)
        pos[i] = pos[i] + v * dt
        Prims.place(debris[i], pos[i], Quaternion.fromAxisAngleRadians(com.meta.spatial.core.Vector3(0.7071f, 0.7071f, 0f), t * 12f + i), Prims.uniform(0.025f * min(size, 2f) * (1f - t)))
      }
    }
  }

  private fun tickFx(dt: Float) {
    val it = fx.iterator()
    while (it.hasNext()) {
      val f = it.next()
      f.age += dt
      if (f.age >= f.life) {
        f.parts.forEach { e -> e.destroy() }
        it.remove()
      } else {
        f.tick(f.age / f.life, dt)
      }
    }
  }

  fun clear() {
    portals.values.forEach { v -> (listOf(v.rim, v.core) + v.shards).forEach { it.destroy() } }
    portals.clear()
    drones.values.forEach { d -> d.all().forEach { it.destroy() } }
    drones.clear()
    shots.values.forEach { it.core.destroy(); it.halo.destroy() }
    shots.clear()
    avatars.values.forEach { a -> a.all().forEach { it.destroy() } }
    avatars.clear()
    fx.forEach { f -> f.parts.forEach { it.destroy() } }
    fx.clear()
  }
}
