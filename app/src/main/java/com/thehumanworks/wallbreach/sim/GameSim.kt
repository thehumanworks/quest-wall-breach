package com.thehumanworks.wallbreach.sim

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Authoritative Wall Breach simulation. Pure Kotlin (no Android / Spatial SDK types) so it runs in
 * JVM unit tests. All coordinates are in the shared co-location frame (metres, Y up, floor = 0).
 *
 * Solo: the device owns a GameSim. Co-op: only the host owns one; the client mirrors snapshots.
 */
class GameSim(seed: Long = System.nanoTime()) {

  class PlayerState(val idx: Int) {
    var connected = false
    var health = MAX_HEALTH
    var alive = true
    var head = V3(0f, 1.6f, 0f)
    var headFwd = V3.FWD
    var leftHand = V3(-0.2f, 1.2f, 0.3f)
    var rightHand = V3(0.2f, 1.2f, 0.3f)
    var shieldActive = false
    var shieldCenter = V3.ZERO
    var shieldNormal = V3.FWD
    var kills = 0
    var hurtCooldown = 0f
  }

  class Portal(
      val id: Int,
      val pos: V3,
      val normal: V3,
      val radius: Float,
      val boss: Boolean,
      val queue: ArrayDeque<DroneKind>,
      val spawnInterval: Float,
      var spawnTimer: Float,
  ) {
    var age = 0f
    var closing = false
    var closeTimer = CLOSE_TIME
    fun visibleRadius(): Float {
      val open = min(1f, age / OPEN_TIME)
      val close = if (closing) (closeTimer / CLOSE_TIME).coerceIn(0f, 1f) else 1f
      return radius * open * close
    }
  }

  class Drone(
      val id: Int,
      val kind: DroneKind,
      var pos: V3,
      var vel: V3,
      var hp: Int,
      val maxHp: Int,
      val portalNormal: V3,
      val home: V3,
      var target: Int,
      var fireTimer: Float,
      var orbitAngle: Float,
      val orbitDist: Float,
      val orbitDir: Float,
      val bobPhase: Float,
  ) {
    var age = 0f
    var summonTimer = 6f
  }

  class Shot(
      val id: Int,
      var pos: V3,
      var vel: V3,
      var hostile: Boolean,
      var ttl: Float,
      var damage: Int,
  )

  private val rng = Random(seed)
  var phase = Phase.LOBBY
    private set
  var wave = 0
    private set
  var score = 0
    private set
  var combo = 0
    private set
  private var comboTimer = 0f
  var phaseTimer = 0f
    private set
  var tick = 0
    private set

  val players = arrayOf(PlayerState(0), PlayerState(1))
  var sites: List<PortalSite> = PortalSite.virtualRoom(V3.ZERO)
  val portals = ArrayList<Portal>()
  val drones = ArrayList<Drone>()
  val shots = ArrayList<Shot>()
  private val events = ArrayList<GameEvent>()
  private var nextId = 1
  private var clearPending = false

  val multiplier: Int
    get() = min(MAX_MULT, 1 + combo / 4)

  private val coop: Boolean
    get() = players.count { it.connected } > 1

  init {
    players[0].connected = true
  }

  // ---------------------------------------------------------------- lifecycle

  fun startGame(numPlayers: Int) {
    portals.clear()
    drones.clear()
    shots.clear()
    events.clear()
    score = 0
    combo = 0
    comboTimer = 0f
    wave = 0
    tick = 0
    for (p in players) {
      p.health = MAX_HEALTH
      p.alive = true
      p.kills = 0
      p.hurtCooldown = 0f
    }
    players[0].connected = true
    players[1].connected = numPlayers > 1
    beginWave(1)
  }

  fun backToLobby() {
    phase = Phase.LOBBY
    portals.clear()
    drones.clear()
    shots.clear()
  }

  fun setPlayerConnected(idx: Int, connected: Boolean) {
    val p = players[idx]
    p.connected = connected
    if (!connected) {
      p.shieldActive = false
      if (phase == Phase.PLAYING || phase == Phase.WAVE_BREAK) checkGameOver()
      flushClear()
    } else if (phase == Phase.PLAYING || phase == Phase.WAVE_BREAK) {
      // late join: drop in with half health
      p.alive = true
      p.health = MAX_HEALTH / 2
    }
  }

  fun updatePlayer(
      idx: Int,
      head: V3,
      headFwd: V3,
      leftHand: V3,
      rightHand: V3,
      shieldActive: Boolean,
      shieldCenter: V3,
      shieldNormal: V3,
  ) {
    val p = players[idx]
    p.head = head
    p.headFwd = headFwd
    p.leftHand = leftHand
    p.rightHand = rightHand
    p.shieldActive = shieldActive && p.alive
    p.shieldCenter = shieldCenter
    p.shieldNormal = shieldNormal.norm()
  }

  fun drainEvents(): List<GameEvent> {
    val out = ArrayList(events)
    events.clear()
    return out
  }

  // ---------------------------------------------------------------- input

  /**
   * Hitscan blaster shot from player [idx]. [claimedDroneId] lets a remote client report what it
   * hit on its (slightly delayed) view; the host accepts it if the ray passes near that drone.
   * Returns the id of the drone hit, or -1.
   */
  fun fire(idx: Int, origin: V3, dir: V3, claimedDroneId: Int = -1): Int {
    if (phase != Phase.PLAYING && phase != Phase.WAVE_BREAK) return -1
    val p = players[idx]
    if (!p.alive || !p.connected) return -1
    val d = dir.norm()
    if (d == V3.ZERO) return -1

    var bestDrone: Drone? = null
    var bestShot: Shot? = null
    var bestT = MAX_RANGE

    if (claimedDroneId >= 0) {
      val c = drones.firstOrNull { it.id == claimedDroneId }
      if (c != null && pointRayDistance(c.pos, origin, d) < c.kind.radius + CLAIM_TOLERANCE) {
        bestDrone = c
        bestT = (c.pos - origin).dot(d).coerceAtLeast(0f)
      }
    }
    if (bestDrone == null) {
      for (dr in drones) {
        val t = raySphere(origin, d, dr.pos, dr.kind.radius * HIT_FORGIVENESS)
        if (t in 0f..bestT) {
          bestT = t
          bestDrone = dr
        }
      }
      for (s in shots) {
        if (!s.hostile) continue
        val t = raySphere(origin, d, s.pos, SHOT_HIT_RADIUS)
        if (t in 0f..bestT) {
          bestT = t
          bestShot = s
          bestDrone = null
        }
      }
    }
    val end = origin + d * bestT
    events += GameEvent(EventType.LASER, idx, origin, end, bestDrone?.id ?: -1)
    if (bestDrone != null) {
      damageDrone(bestDrone, 1, idx)
      return bestDrone.id
    }
    if (bestShot != null) {
      shots.remove(bestShot)
      score += 10 * multiplier
      events += GameEvent(EventType.EXPLODE, idx, bestShot.pos, value = -1)
    }
    return -1
  }

  // ---------------------------------------------------------------- simulation

  fun step(dt: Float) {
    stepInner(dt)
    flushClear()
  }

  private fun flushClear() {
    if (!clearPending) return
    clearPending = false
    drones.clear()
    shots.clear()
  }

  private fun stepInner(dt: Float) {
    tick++
    for (p in players) if (p.hurtCooldown > 0f) p.hurtCooldown -= dt
    updatePortals(dt)
    when (phase) {
      Phase.LOBBY,
      Phase.GAME_OVER -> return
      Phase.WAVE_BREAK -> {
        updateDrones(dt)
        updateShots(dt)
        phaseTimer -= dt
        if (phaseTimer <= 0f) beginWave(wave + 1)
      }
      Phase.PLAYING -> {
        spawnFromPortals(dt)
        updateDrones(dt)
        updateShots(dt)
        if (combo > 0) {
          comboTimer -= dt
          if (comboTimer <= 0f) combo = 0
        }
        if (phase == Phase.PLAYING && portals.all { it.queue.isEmpty() } && drones.isEmpty()) {
          waveCleared()
        }
      }
    }
  }

  private fun beginWave(n: Int) {
    wave = n
    phase = Phase.PLAYING
    val boss = n % BOSS_EVERY == 0
    val coopScale = if (coop) 1.5f else 1f
    val interval = (2.4f - 0.13f * n).coerceAtLeast(0.55f)
    if (boss) {
      events += GameEvent(EventType.BOSS_INCOMING, value = n)
      openPortal(ArrayDeque(listOf(DroneKind.BOSS)), radius = 0.85f, boss = true, interval = 3f, firstDelay = 2.5f)
      val scouts = ((n / 2) * coopScale).toInt()
      if (scouts > 0) {
        openPortal(ArrayDeque(List(scouts) { DroneKind.SCOUT }), 0.4f, false, interval, 4f)
      }
    } else {
      events += GameEvent(EventType.WAVE_START, value = n)
      val total = (min(30, 3 + 2 * n) * coopScale).toInt()
      val kinds =
          List(total) {
            val r = rng.nextFloat()
            when {
              n >= 4 && r < 0.15f -> DroneKind.TANK
              n >= 2 && r < 0.45f -> DroneKind.GUNNER
              else -> DroneKind.SCOUT
            }
          }
      val portalCount = min(MAX_PORTALS, 1 + (n - 1) / 2 + if (coop) 1 else 0)
      val queues = List(portalCount) { ArrayDeque<DroneKind>() }
      kinds.forEachIndexed { i, k -> queues[i % portalCount].addLast(k) }
      queues.forEachIndexed { i, q ->
        if (q.isNotEmpty()) openPortal(q, 0.4f, false, interval, 1.4f + i * 0.9f)
      }
    }
  }

  private fun waveCleared() {
    events += GameEvent(EventType.WAVE_CLEAR, value = wave)
    for (p in players) {
      if (!p.connected) continue
      if (p.alive) {
        p.health = min(MAX_HEALTH, p.health + WAVE_HEAL)
      } else {
        p.alive = true
        p.health = MAX_HEALTH / 2
        events += GameEvent(EventType.PLAYER_REVIVED, p.idx, p.head)
      }
    }
    for (pt in portals) pt.closing = true
    phase = Phase.WAVE_BREAK
    phaseTimer = WAVE_BREAK_TIME
  }

  private fun openPortal(
      queue: ArrayDeque<DroneKind>,
      radius: Float,
      boss: Boolean,
      interval: Float,
      firstDelay: Float,
  ) {
    val (pos, normal) = pickPortalSpot(radius, boss)
    val portal = Portal(nextId++, pos, normal, radius, boss, queue, interval, firstDelay)
    portals += portal
    events += GameEvent(EventType.PORTAL_OPEN, a = pos, b = normal, value = if (boss) 1 else 0)
  }

  private fun pickPortalSpot(radius: Float, boss: Boolean): Pair<V3, V3> {
    val candidates = sites.ifEmpty { PortalSite.virtualRoom(players[0].head) }
    var best: Pair<V3, V3>? = null
    repeat(16) { attempt ->
      val site =
          when {
            boss -> candidates.maxByOrNull { it.halfWidth }!!
            // early waves: favour the wall the host player is looking at so the first breach is seen
            wave <= 2 && portals.isEmpty() && attempt < 4 ->
                candidates.maxByOrNull { -it.normal.dot(players[0].headFwd.flat().norm()) }!!
            else -> candidates[rng.nextInt(candidates.size)]
          }
      val maxU = (site.halfWidth - radius - 0.1f).coerceAtLeast(0f)
      val u = if (maxU > 0f) (rng.nextFloat() * 2f - 1f) * maxU else 0f
      val lo = maxOf(site.yMin, 0.6f) + radius * 0.7f
      val hi = minOf(site.yMax, 2.3f) - radius * 0.7f
      val y = if (hi > lo) lo + rng.nextFloat() * (hi - lo) else (site.yMin + site.yMax) / 2f
      val base = V3(site.center.x, 0f, site.center.z) + site.right * u
      val pos = V3(base.x, y, base.z) + site.normal * 0.03f
      val candidate = pos to site.normal
      if (best == null) best = candidate
      val clear = portals.all { it.pos.dist(pos) > it.radius + radius + 0.35f }
      if (clear) return candidate
    }
    return best!!
  }

  private fun updatePortals(dt: Float) {
    val it = portals.iterator()
    while (it.hasNext()) {
      val p = it.next()
      p.age += dt
      if (!p.closing && p.queue.isEmpty() && p.age > OPEN_TIME) p.closing = true
      if (p.closing) {
        p.closeTimer -= dt
        if (p.closeTimer <= 0f) it.remove()
      }
    }
  }

  private fun maxAlive(): Int = min(14, 5 + wave) + if (coop) 3 else 0

  private fun spawnFromPortals(dt: Float) {
    for (p in portals) {
      if (p.closing || p.queue.isEmpty()) continue
      p.spawnTimer -= dt
      if (p.spawnTimer > 0f) continue
      if (drones.size >= maxAlive() && p.queue.first() != DroneKind.BOSS) {
        p.spawnTimer = 0.3f
        continue
      }
      spawnDrone(p.queue.removeFirst(), p.pos + p.normal * 0.05f, p.normal)
      p.spawnTimer = p.spawnInterval
    }
  }

  private fun speedScale() = (1f + 0.035f * (wave - 1)).coerceAtMost(1.7f)

  private fun pickTarget(): Int {
    val alive = players.filter { it.connected && it.alive }
    return if (alive.isEmpty()) 0 else alive[rng.nextInt(alive.size)].idx
  }

  private fun spawnDrone(kind: DroneKind, pos: V3, normal: V3) {
    val hp =
        when (kind) {
          DroneKind.BOSS -> ((40 + 15 * (wave / BOSS_EVERY - 1)) * (if (coop) 1.6f else 1f)).toInt()
          DroneKind.TANK -> kind.maxHp + if (wave >= 10) 1 else 0
          else -> kind.maxHp
        }
    val target = pickTarget()
    val rel = pos - players[target].head
    drones +=
        Drone(
            id = nextId++,
            kind = kind,
            pos = pos,
            vel = normal * EMERGE_SPEED,
            hp = hp,
            maxHp = hp,
            portalNormal = normal,
            home = pos + normal * 1.3f + V3(0f, 0.2f, 0f),
            target = target,
            fireTimer = 1.5f + rng.nextFloat() * 1.5f,
            orbitAngle = atan2(rel.x, rel.z),
            orbitDist = 1.4f + rng.nextFloat() * 1.0f,
            orbitDir = if (rng.nextBoolean()) 1f else -1f,
            bobPhase = rng.nextFloat() * 6.28f,
        )
  }

  private fun updateDrones(dt: Float) {
    val s = speedScale()
    val fireScale = 1f + 0.05f * wave
    val it = drones.listIterator()
    val spawned = ArrayList<Drone>()
    while (it.hasNext()) {
      val d = it.next()
      d.age += dt
      if (!players[d.target].alive || !players[d.target].connected) d.target = pickTarget()
      val tp = players[d.target]
      val head = tp.head
      val speed = d.kind.speed * s
      if (d.age < EMERGE_TIME) {
        d.vel = d.portalNormal * EMERGE_SPEED
      } else {
        val desiredVel: V3 =
            when (d.kind) {
              DroneKind.SCOUT -> {
                val wob = V3(sin(d.age * 3f + d.bobPhase), cos(d.age * 2.3f + d.bobPhase), 0f) * 0.25f
                ((head - d.pos).norm() + wob).norm() * speed
              }
              DroneKind.GUNNER,
              DroneKind.TANK -> {
                d.orbitAngle += dt * (if (d.kind == DroneKind.GUNNER) 0.35f else 0.18f) * d.orbitDir
                val bob = 0.25f * sin(d.age * 1.7f + d.bobPhase)
                val off = V3(sin(d.orbitAngle), 0f, cos(d.orbitAngle)) * d.orbitDist
                val desired = V3(head.x + off.x, head.y + 0.1f + bob, head.z + off.z)
                val delta = desired - d.pos
                delta.norm() * speed * min(1f, delta.len() * 2f)
              }
              DroneKind.BOSS -> {
                val right = V3.UP.cross(d.portalNormal).norm()
                val desired = d.home + right * (sin(d.age * 0.45f) * 1.1f) + V3(0f, 0.25f * sin(d.age * 0.8f), 0f)
                val delta = desired - d.pos
                delta.norm() * speed * min(1f, delta.len() * 2f)
              }
            }
        d.vel = d.vel.lerp(desiredVel, min(1f, dt * 2.5f))
      }
      d.pos = d.pos + d.vel * dt

      // contact damage (kamikaze scouts)
      if (d.kind == DroneKind.SCOUT && d.age > EMERGE_TIME && tp.alive && d.pos.dist(head) < SCOUT_CONTACT) {
        hurt(tp, SCOUT_DAMAGE, d.pos)
        events += GameEvent(EventType.EXPLODE, d.target, d.pos, value = d.kind.ordinal)
        it.remove()
        continue
      }

      if (d.kind != DroneKind.SCOUT && d.age > EMERGE_TIME && phase == Phase.PLAYING && tp.alive) {
        d.fireTimer -= dt
        if (d.fireTimer <= 0f && d.pos.dist(head) < 7f) {
          when (d.kind) {
            DroneKind.GUNNER -> {
              fireAt(d.pos, head, 0.06f, 1, SHOT_SPEED)
              d.fireTimer = (2.6f + rng.nextFloat()) / fireScale
            }
            DroneKind.TANK -> {
              fireAt(d.pos, head, 0.18f, 3, SHOT_SPEED * 0.85f)
              d.fireTimer = (3.2f + rng.nextFloat()) / fireScale
            }
            else -> { // BOSS
              fireAt(d.pos, head, 0.35f, 5, SHOT_SPEED * 1.1f)
              d.fireTimer = (1.8f + rng.nextFloat() * 0.6f) / fireScale
            }
          }
          events += GameEvent(EventType.ENEMY_FIRE, a = d.pos, value = d.kind.ordinal)
        }
        if (d.kind == DroneKind.BOSS) {
          d.summonTimer -= dt
          if (d.summonTimer <= 0f) {
            d.summonTimer = 7f
            repeat(2) { k ->
              if (drones.size + spawned.size < maxAlive()) {
                val offset = V3(if (k == 0) -0.5f else 0.5f, 0f, 0f).rotY(rng.nextFloat())
                spawned += makeMinion(d.pos + offset, d.portalNormal)
              }
            }
          }
        }
      }
    }
    drones += spawned
  }

  private fun makeMinion(pos: V3, normal: V3): Drone {
    val target = pickTarget()
    return Drone(nextId++, DroneKind.SCOUT, pos, normal * EMERGE_SPEED, 1, 1, normal, pos, target, 99f, 0f, 1.5f, 1f, rng.nextFloat() * 6f)
  }

  private fun fireAt(from: V3, target: V3, spread: Float, count: Int, speed: Float) {
    val base = (target - from).norm()
    for (i in 0 until count) {
      val offset = if (count == 1) (rng.nextFloat() - 0.5f) * spread else (i - (count - 1) / 2f) * spread
      val dir = base.rotY(offset).let { V3(it.x, it.y + (rng.nextFloat() - 0.5f) * spread * 0.3f, it.z) }.norm()
      shots += Shot(nextId++, from + dir * 0.2f, dir * speed, hostile = true, ttl = SHOT_TTL, damage = SHOT_DAMAGE)
    }
  }

  private fun updateShots(dt: Float) {
    val it = shots.iterator()
    while (it.hasNext()) {
      val s = it.next()
      val prev = s.pos
      if (!s.hostile) {
        // reflected shots home gently onto the nearest drone
        val tgt = drones.minByOrNull { it.pos.distSq(s.pos) }
        if (tgt != null) {
          val want = (tgt.pos - s.pos).norm() * s.vel.len()
          s.vel = s.vel.lerp(want, min(1f, dt * 4f))
        }
      }
      s.pos = s.pos + s.vel * dt
      s.ttl -= dt
      if (s.ttl <= 0f || s.pos.y < -0.5f) {
        it.remove()
        continue
      }
      if (s.hostile) {
        var consumed = false
        for (p in players) {
          if (!p.connected || !p.alive) continue
          if (p.shieldActive && shieldIntercepts(p, prev, s.pos)) {
            reflect(s, p)
            consumed = false
            break
          }
          if (pointSegmentDistance(p.head, prev, s.pos) < HEAD_RADIUS) {
            hurt(p, s.damage, s.pos)
            consumed = true
            break
          }
        }
        if (consumed) it.remove()
      } else {
        val hit = drones.firstOrNull { pointSegmentDistance(it.pos, prev, s.pos) < it.kind.radius + 0.06f }
        if (hit != null) {
          damageDrone(hit, s.damage, -1)
          it.remove()
        }
      }
    }
  }

  private fun shieldIntercepts(p: PlayerState, a: V3, b: V3): Boolean {
    val n = p.shieldNormal
    val c = p.shieldCenter
    if (b.dist(c) < SHIELD_RADIUS * 0.9f) return true
    val d0 = (a - c).dot(n)
    val d1 = (b - c).dot(n)
    if (d0 * d1 > 0f) return false
    val t = if (d0 == d1) 0f else d0 / (d0 - d1)
    return a.lerp(b, t).dist(c) < SHIELD_RADIUS
  }

  private fun reflect(s: Shot, p: PlayerState) {
    s.hostile = false
    s.ttl = 3f
    s.damage = 2
    val tgt = drones.minByOrNull { it.pos.distSq(s.pos) }
    val dir = if (tgt != null) (tgt.pos - s.pos).norm() else (p.shieldNormal * 1f).norm()
    s.vel = dir * REFLECT_SPEED
    s.pos = p.shieldCenter + dir * 0.08f
    score += 25 * multiplier
    events += GameEvent(EventType.SHIELD_BLOCK, p.idx, p.shieldCenter)
  }

  private fun damageDrone(d: Drone, dmg: Int, by: Int) {
    if (d.hp <= 0) return
    d.hp -= dmg
    if (d.hp > 0) return
    drones.remove(d)
    combo++
    comboTimer = COMBO_WINDOW
    score += d.kind.points * multiplier
    if (by >= 0) players[by].kills++
    events += GameEvent(EventType.EXPLODE, by, d.pos, value = d.kind.ordinal)
  }

  private fun hurt(p: PlayerState, dmg: Int, at: V3) {
    if (!p.alive) return
    p.health -= dmg
    p.hurtCooldown = 0.3f
    combo = 0
    events += GameEvent(EventType.PLAYER_HIT, p.idx, at, value = dmg)
    if (p.health <= 0) {
      p.health = 0
      p.alive = false
      p.shieldActive = false
      events += GameEvent(EventType.PLAYER_DOWN, p.idx, p.head)
      checkGameOver()
    }
  }

  private fun checkGameOver() {
    if (players.none { it.connected && it.alive }) {
      phase = Phase.GAME_OVER
      for (pt in portals) pt.closing = true
      clearPending = true // cleared at the end of step(): we may be mid-iteration here
      events += GameEvent(EventType.GAME_OVER, value = score)
    }
  }

  // ---------------------------------------------------------------- views

  fun snapshot(events: List<GameEvent>): Snapshot =
      Snapshot(
          tick = tick,
          phase = phase,
          wave = wave,
          score = score,
          combo = combo,
          multiplier = multiplier,
          phaseTimer = phaseTimer,
          players =
              players.map {
                PlayerView(it.idx, it.connected, it.health.toFloat(), it.alive, it.head, it.headFwd, it.leftHand, it.rightHand, it.shieldActive, it.shieldCenter, it.shieldNormal)
              },
          portals = portals.map { PortalView(it.id, it.pos, it.normal, it.visibleRadius(), it.boss) },
          drones = drones.map { DroneView(it.id, it.kind, it.pos, it.hp.toFloat() / it.maxHp) },
          shots = shots.map { ShotView(it.id, it.pos, it.hostile) },
          events = events,
      )

  companion object {
    const val MAX_HEALTH = 100
    const val WAVE_HEAL = 20
    const val BOSS_EVERY = 5
    const val MAX_PORTALS = 4
    const val MAX_MULT = 8
    const val MAX_RANGE = 30f
    const val HIT_FORGIVENESS = 1.35f
    const val CLAIM_TOLERANCE = 0.6f
    const val SHOT_HIT_RADIUS = 0.09f
    const val SHOT_SPEED = 2.2f
    const val SHOT_TTL = 6f
    const val SHOT_DAMAGE = 10
    const val SCOUT_DAMAGE = 15
    const val SCOUT_CONTACT = 0.3f
    const val HEAD_RADIUS = 0.2f
    const val SHIELD_RADIUS = 0.24f
    const val REFLECT_SPEED = 5f
    const val COMBO_WINDOW = 3f
    const val WAVE_BREAK_TIME = 4f
    const val OPEN_TIME = 0.8f
    const val CLOSE_TIME = 1.2f
    const val EMERGE_TIME = 0.7f
    const val EMERGE_SPEED = 0.9f
    @Suppress("unused") const val TAU = (2 * PI).toFloat()
  }
}

fun pointSegmentDistance(p: V3, a: V3, b: V3): Float {
  val ab = b - a
  val l2 = ab.lenSq()
  if (l2 < 1e-9f) return p.dist(a)
  val t = ((p - a).dot(ab) / l2).coerceIn(0f, 1f)
  return (a + ab * t).dist(p)
}
