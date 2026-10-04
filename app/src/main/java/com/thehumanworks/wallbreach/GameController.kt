package com.thehumanworks.wallbreach

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Hand
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.toolkit.getAbsoluteTransform
import com.thehumanworks.wallbreach.net.Beacon
import com.thehumanworks.wallbreach.net.BeaconBroadcaster
import com.thehumanworks.wallbreach.net.BeaconListener
import com.thehumanworks.wallbreach.net.ClientSession
import com.thehumanworks.wallbreach.net.Connection
import com.thehumanworks.wallbreach.net.HostSession
import com.thehumanworks.wallbreach.net.NetClient
import com.thehumanworks.wallbreach.net.NetHost
import com.thehumanworks.wallbreach.net.Protocol
import com.thehumanworks.wallbreach.render.Sfx
import com.thehumanworks.wallbreach.render.WorldRenderer
import com.thehumanworks.wallbreach.render.v3
import com.thehumanworks.wallbreach.render.vec
import com.thehumanworks.wallbreach.sim.DroneKind
import com.thehumanworks.wallbreach.sim.EventType
import com.thehumanworks.wallbreach.sim.GameEvent
import com.thehumanworks.wallbreach.sim.GameSim
import com.thehumanworks.wallbreach.sim.Phase
import com.thehumanworks.wallbreach.sim.PortalSite
import com.thehumanworks.wallbreach.sim.SharedFrame
import com.thehumanworks.wallbreach.sim.Snapshot
import com.thehumanworks.wallbreach.sim.V3
import com.thehumanworks.wallbreach.sim.raySphere
import com.thehumanworks.wallbreach.ui.Screen
import com.thehumanworks.wallbreach.ui.UiActions
import com.thehumanworks.wallbreach.ui.UiState
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * The per-frame game system: reads head/hand input, drives solo / host / client modes, renders the
 * shared snapshot, plays spatial audio + haptics and keeps the in-world panels up to date.
 */
class GameController(private val act: WallBreachActivity, val ui: UiState) : SystemBase(), UiActions {
  enum class Mode { MENU, SOLO, HOST, CLIENT }

  private class HandInput(
      val active: Boolean,
      val isHand: Boolean,
      val pos: V3,
      val fwd: V3,
      val trigger: Boolean,
      val squeeze: Boolean,
      val pressedA: Boolean,
      val pressedMenu: Boolean,
  )

  private val inactiveHand = HandInput(false, false, V3.ZERO, V3.FWD, false, false, false, false)

  private var mode = Mode.MENU
  private val pending = ConcurrentLinkedQueue<() -> Unit>()
  private val renderer = WorldRenderer()
  private var sfx: Sfx? = null
  private var sim: GameSim? = null
  private var hostSession: HostSession? = null
  private var broadcaster: BeaconBroadcaster? = null
  private var nsd: NsdHelper? = null
  private var listener: BeaconListener? = null
  private var client: ClientSession? = null
  private var multicastLock: WifiManager.MulticastLock? = null
  private val discovered = AtomicReference<Beacon?>(null)
  private val connectResult = AtomicReference<Any?>(null)
  private var connectingTo: String? = null
  private var frame = SharedFrame.IDENTITY // active frame for the current mode
  private var calibFrame = SharedFrame.IDENTITY // last co-location calibration (kept across rounds)
  private var cachedIp: String? = null
  private var ipTimer = 0f
  private var markerPos: V3? = null
  private var markerFwd = V3.FWD
  private var screenBeforeCalib = Screen.MAIN
  private var worldSites: List<PortalSite> = emptyList()
  @Volatile private var roomDirty = false
  private var roomRetries = 0
  private var lastNs = 0L
  private var started = false
  private val netEvents = ArrayList<GameEvent>()
  private var snapTimer = 0f
  private var stateTimer = 0f
  private var hudTimer = 0f
  private var lastPhase: Phase? = null
  private var current: Snapshot? = null
  private val fireCd = FloatArray(2)
  private var calibHold = 0f
  private var bannerTimer = 0f
  private var hurtFlash = 0f
  private var lastShieldHand = 0
  private var myIdx = 0
  private var menuPanel: Entity? = null
  private var hudPanel: Entity? = null
  private var menuVisible = false
  private var hudVisible = false
  private var hudPos: V3? = null
  private var lastHead = V3(0f, 1.6f, 0f)
  private var lastHeadFwd = V3.FWD
  private val prefs = act.getSharedPreferences("wallbreach", Context.MODE_PRIVATE)
  private val deviceName = "${Build.MODEL} #${(100..999).random()}"

  // ------------------------------------------------------------------ lifecycle

  fun onSceneReady() {
    sfx = Sfx(act.scene)
    nsd = NsdHelper(act)
    ui.bestSolo = prefs.getInt(KEY_BEST_SOLO, 0)
    ui.bestCoop = prefs.getInt(KEY_BEST_COOP, 0)
    val head = act.scene.getViewerPose()
    menuPanel = Entity.createPanelEntity(R.id.menu_panel, Transform(Pose(Vector3(0f, 1.3f, -1.3f))), Visible(true))
    hudPanel = Entity.createPanelEntity(R.id.hud_panel, Transform(Pose(Vector3(0f, 2f, -1.5f))), Visible(false))
    menuVisible = true
    placeMenu(head.t.v3(), (head.q * Vector3(0f, 0f, 1f)).v3())
    started = true
  }

  fun onRoomLoaded(success: Boolean) {
    Log.i(TAG, "room load success=$success")
    roomRetries = 0
    roomDirty = true
  }

  fun shutdown() {
    teardownNet()
  }

  private fun post(a: () -> Unit) {
    pending.add(a)
  }

  // ------------------------------------------------------------------ UI actions (posted to game thread)

  override fun onSolo() = post { click(); startSolo() }

  override fun onHost() = post { click(); startHosting() }

  override fun onJoin() = post { click(); startJoining() }

  override fun onStartCoop() = post { click(); startCoopRound() }

  override fun onCalibrate() = post {
    click()
    screenBeforeCalib = ui.screen
    calibHold = 0f
    ui.screen = Screen.CALIBRATE
  }

  override fun onCancelCalibrate() = post { click(); ui.screen = screenAfterCalibration() }

  /** If the host started the round while we were calibrating, go straight into the game. */
  private fun screenAfterCalibration(): Screen {
    val ph = current?.phase
    return if (ph == Phase.PLAYING || ph == Phase.WAVE_BREAK) Screen.PLAYING else screenBeforeCalib
  }

  override fun onBack() = post { click(); backToMenu(null) }

  override fun onScanRoom() = post {
    click()
    ui.roomStatus = "Opening room setup…"
    act.rescanRoom()
  }

  override fun onPlayAgain() = post {
    click()
    when (mode) {
      Mode.SOLO -> startSolo()
      Mode.HOST -> startCoopRound()
      else -> {}
    }
  }

  private fun click() = sfx?.ui(sfx?.click, 0.6f)

  // ------------------------------------------------------------------ modes

  private fun startSolo() {
    teardownNet()
    mode = Mode.SOLO
    myIdx = 0
    frame = SharedFrame.IDENTITY // solo needs no co-location
    renderer.frame = frame
    renderer.clear()
    val s = GameSim()
    s.sites = sitesForGame(lastHead)
    s.updatePlayer(0, lastHead, lastHeadFwd, lastHead, lastHead, false, lastHead, lastHeadFwd)
    s.startGame(1)
    sim = s
    lastPhase = null
    ui.netStatus = ""
  }

  private fun startHosting() {
    teardownNet()
    mode = Mode.HOST
    myIdx = 0
    ui.isHost = true
    frame = calibFrame
    renderer.frame = frame
    renderer.clear()
    val s = GameSim()
    sim = s
    val host =
        try {
          NetHost(NetHost.DEFAULT_TCP_PORT)
        } catch (e: Exception) {
          NetHost(0)
        }
    hostSession = HostSession(s, host, deviceName)
    acquireMulticast()
    broadcaster = BeaconBroadcaster(host.localPort, deviceName)
    nsd?.register(deviceName, host.localPort)
    lastPhase = Phase.LOBBY
    ui.netStatus = "Waiting for partner…"
    ui.netDetail = ""
    ui.screen = Screen.HOST_LOBBY
  }

  private fun startCoopRound() {
    val s = sim ?: return
    if (mode != Mode.HOST) return
    s.sites = sitesForGame(lastHead)
    s.startGame(if (hostSession?.partnerConnected == true) 2 else 1)
  }

  private fun startJoining() {
    teardownNet()
    mode = Mode.CLIENT
    myIdx = -1
    ui.isHost = false
    frame = calibFrame
    renderer.frame = frame
    renderer.clear()
    acquireMulticast()
    listener = BeaconListener { b -> if (b.version == Protocol.VERSION) discovered.compareAndSet(null, b) }
    nsd?.discover { host, port, name -> discovered.compareAndSet(null, Beacon(host, port, name, Protocol.VERSION)) }
    lastPhase = Phase.LOBBY
    ui.netStatus = "Searching for a host on your Wi-Fi…"
    ui.netDetail = ""
    ui.screen = Screen.JOIN_LOBBY
  }

  private fun backToMenu(notice: String?) {
    teardownNet()
    mode = Mode.MENU
    sim = null
    current = null
    lastPhase = null
    renderer.clear()
    ui.screen = Screen.MAIN
    ui.netStatus = notice ?: ""
    ui.partnerConnected = false
  }

  private fun teardownNet() {
    try {
      hostSession?.close()
    } catch (_: Exception) {}
    try {
      client?.close()
    } catch (_: Exception) {}
    broadcaster?.close()
    listener?.close()
    nsd?.stop()
    hostSession = null
    client = null
    broadcaster = null
    listener = null
    connectingTo = null
    discovered.set(null)
    (connectResult.getAndSet(null) as? Connection)?.close()
    multicastLock?.let { if (it.isHeld) it.release() }
    multicastLock = null
    netEvents.clear()
  }

  private fun acquireMulticast() {
    try {
      val wifi = act.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      multicastLock = wifi.createMulticastLock("wallbreach").apply {
        setReferenceCounted(false)
        acquire()
      }
    } catch (e: Exception) {
      Log.w(TAG, "multicast lock failed", e)
    }
  }

  private fun sitesForGame(head: V3): List<PortalSite> {
    val world = worldSites.ifEmpty { PortalSite.virtualRoom(head) }
    return world.map {
      PortalSite(frame.worldToShared(it.center), frame.dirWorldToShared(it.normal), frame.dirWorldToShared(it.right), it.halfWidth, it.yMin, it.yMax)
    }
  }

  // ------------------------------------------------------------------ frame

  override fun execute() {
    val now = System.nanoTime()
    val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.05f)
    lastNs = now
    if (!started) return
    while (true) {
      val a = pending.poll() ?: break
      try {
        a()
      } catch (e: Exception) {
        Log.e(TAG, "action failed", e)
      }
    }

    val headPose = act.scene.getViewerPose()
    val head = headPose.t.v3()
    val headFwd = (headPose.q * Vector3(0f, 0f, 1f)).v3().norm()
    lastHead = head
    lastHeadFwd = headFwd
    if (roomDirty) rebuildRoom(head)

    val body = systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()?.tryGetLocalPlayerAvatarBody()
    val left = readHand(body?.leftHand)
    val right = readHand(body?.rightHand)
    val hands = arrayOf(left, right)

    // shield: grip on a controller, or the left hand when hand-tracking
    val leftShield = left.active && (left.isHand || left.squeeze)
    val rightShield = right.active && !right.isHand && right.squeeze && !leftShield
    val shieldHand = if (leftShield) 0 else if (rightShield) 1 else -1
    if (shieldHand >= 0) lastShieldHand = shieldHand
    val sh = if (shieldHand >= 0) hands[shieldHand] else null
    val shieldCenter = sh?.let { it.pos + it.fwd * 0.12f } ?: head
    val shieldNormal = sh?.fwd ?: headFwd

    if (left.pressedMenu || right.pressedMenu) onMenuButton()
    if (ui.screen == Screen.CALIBRATE) handleCalibration(left, right, dt)
    pollNetworkSetup()

    val phase = current?.phase
    val inRound = ui.screen == Screen.PLAYING && (phase == Phase.PLAYING || phase == Phase.WAVE_BREAK)
    val meAlive = current?.players?.getOrNull(myIdx)?.alive ?: true

    // blaster: trigger / pinch; hold for auto-fire
    val fires = ArrayList<Pair<V3, V3>>()
    for (i in 0..1) {
      val h = hands[i]
      fireCd[i] -= dt
      val canFire = h.active && h.trigger && shieldHand != i && !(h.isHand && i == 0)
      if (inRound && meAlive && canFire && fireCd[i] <= 0f) {
        fireCd[i] = FIRE_INTERVAL
        fires += (h.pos + h.fwd * 0.06f) to h.fwd
        act.haptic(if (i == 0) Hand.LEFT else Hand.RIGHT, 0.25f, 25)
      }
      if (!h.trigger) fireCd[i] = min(fireCd[i], 0f)
    }

    val shieldOn = inRound && meAlive && shieldHand >= 0
    val sHead = frame.worldToShared(head)
    val sFwd = frame.dirWorldToShared(headFwd)
    val sL = frame.worldToShared(if (left.active) left.pos else head)
    val sR = frame.worldToShared(if (right.active) right.pos else head)
    val sShieldC = frame.worldToShared(shieldCenter)
    val sShieldN = frame.dirWorldToShared(shieldNormal)

    when (mode) {
      Mode.SOLO,
      Mode.HOST -> {
        val s = sim!!
        hostSession?.poll()
        s.updatePlayer(0, sHead, sFwd, sL, sR, shieldOn, sShieldC, sShieldN)
        for ((o, d) in fires) s.fire(0, frame.worldToShared(o), frame.dirWorldToShared(d))
        s.step(dt)
        val ev = s.drainEvents()
        val snap = s.snapshot(ev)
        current = snap
        hostSession?.let { hs ->
          netEvents += ev
          snapTimer += dt
          if (snapTimer >= SNAPSHOT_INTERVAL) {
            snapTimer = 0f
            hs.sendSnapshot(snap.copy(events = ArrayList(netEvents)))
            netEvents.clear()
          }
          ui.partnerConnected = hs.partnerConnected
          ui.netStatus = if (hs.partnerConnected) "Partner connected: ${hs.partnerName}" else "Waiting for partner… (both headsets on the same Wi-Fi)"
          ipTimer -= dt
          if (ipTimer <= 0f) {
            ipTimer = 2f
            cachedIp = localIp()
          }
          ui.netDetail = "This headset: ${cachedIp ?: "no Wi-Fi?"} · port ${hs.host.localPort}"
        }
        handleEvents(ev, skipOwnLasers = false)
      }
      Mode.CLIENT -> {
        val c = client
        if (c != null) {
          c.poll()
          if (!c.connected) {
            val why = c.rejected ?: c.conn.closeReason ?: "connection lost"
            backToMenu("Co-op ended: $why")
            return
          }
          if (c.welcomed) myIdx = c.playerIdx
          current = c.latest
          stateTimer += dt
          if (stateTimer >= STATE_INTERVAL) {
            stateTimer = 0f
            c.sendState(sHead, sFwd, sL, sR, shieldOn, sShieldC, sShieldN)
          }
          for ((o, d) in fires) clientFire(c, o, d)
          handleEvents(c.drainEvents(), skipOwnLasers = true)
          ui.partnerConnected = c.welcomed
          ui.netStatus =
              if (c.welcomed) "Connected to ${c.hostName} (${c.rttMs.toInt()} ms) — waiting for the host to start"
              else "Connecting to ${connectingTo ?: "host"}…"
        } else {
          current = null
          ui.netStatus = if (connectingTo != null) "Connecting to $connectingTo…" else "Searching for a host on your Wi-Fi…"
          ui.netDetail = listener?.error?.let { "Discovery error: $it" } ?: "Host must press HOST CO-OP on the other headset"
        }
      }
      Mode.MENU -> current = null
    }

    handlePhase(current)
    renderer.render(if (mode == Mode.MENU) null else current, dt, myIdx, head, smooth = mode == Mode.CLIENT)
    renderer.setLocalShield(shieldOn, shieldCenter, shieldNormal)
    val showMarker = ui.screen == Screen.CALIBRATE || ui.screen == Screen.HOST_LOBBY || ui.screen == Screen.JOIN_LOBBY
    renderer.showMarker(if (showMarker) markerPos else null, markerFwd)
    updatePanels(head, headFwd, dt)
  }

  private fun readHand(e: Entity?): HandInput {
    if (e == null) return inactiveHand
    val c = e.tryGetComponent<Controller>() ?: return inactiveHand
    if (!c.isActive) return inactiveHand
    val pose = getAbsoluteTransform(e)
    val fwd = (pose.q * Vector3(0f, 0f, 1f)).v3().norm()
    val st = c.buttonState
    val ch = c.changedButtons
    return HandInput(
        active = true,
        isHand = c.type == ControllerType.HAND,
        pos = pose.t.v3(),
        fwd = fwd,
        trigger = (st and (ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR)) != 0,
        squeeze = (st and (ButtonBits.ButtonSqueezeL or ButtonBits.ButtonSqueezeR)) != 0,
        pressedA = (st and ch and (ButtonBits.ButtonA or ButtonBits.ButtonX)) != 0,
        pressedMenu = (st and ch and ButtonBits.ButtonMenu) != 0,
    )
  }

  /** Client: predict the hit locally (on the drones as displayed), show it instantly, tell the host. */
  private fun clientFire(c: ClientSession, originWorld: V3, dirWorld: V3) {
    val o = frame.worldToShared(originWorld)
    val d = frame.dirWorldToShared(dirWorld).norm()
    var bestT = GameSim.MAX_RANGE
    var bestId = -1
    for ((id, pr) in renderer.displayedDrones()) {
      val t = raySphere(o, d, pr.first, pr.second * GameSim.HIT_FORGIVENESS)
      if (t in 0f..bestT) {
        bestT = t
        bestId = id
      }
    }
    renderer.beam(originWorld, originWorld + dirWorld.norm() * bestT, myIdx)
    sfx?.at(sfx?.laser, originWorld, 0.6f)
    c.sendFire(o, d, bestId)
  }

  private fun onMenuButton() {
    if (ui.screen != Screen.PLAYING) return
    when (mode) {
      Mode.SOLO -> backToMenu("Run abandoned")
      Mode.HOST -> sim?.backToLobby()
      Mode.CLIENT -> backToMenu("You left the co-op game")
      Mode.MENU -> {}
    }
  }

  private fun handleCalibration(left: HandInput, right: HandInput, dt: Float) {
    val h = if (right.active) right else left
    if (!h.active) return
    calibHold = if (h.trigger) calibHold + dt else 0f
    ui.calibrateHold = calibHold / CALIB_HOLD
    if (h.pressedA || calibHold >= CALIB_HOLD) {
      calibFrame = SharedFrame.fromPose(h.pos, h.fwd)
      frame = calibFrame
      renderer.frame = frame
      markerPos = h.pos
      markerFwd = h.fwd
      ui.calibrated = true
      calibHold = 0f
      ui.calibrateHold = 0f
      sfx?.ui(sfx?.shield, 0.8f)
      act.haptic(if (h === right) Hand.RIGHT else Hand.LEFT, 0.8f, 120)
      ui.screen = screenAfterCalibration()
    }
  }

  private fun pollNetworkSetup() {
    if (mode != Mode.CLIENT) return
    if (client == null && connectingTo == null) {
      val b = discovered.get() ?: return
      connectingTo = "${b.hostName} (${b.address})"
      thread(name = "wb-connect", isDaemon = true) {
        try {
          connectResult.set(NetClient.connect(b.address, b.tcpPort))
        } catch (e: Exception) {
          connectResult.set(e)
        }
      }
    }
    when (val r = connectResult.getAndSet(null)) {
      is Connection -> {
        client = ClientSession(r, deviceName)
        listener?.close()
        listener = null
      }
      is Exception -> {
        Log.w(TAG, "connect failed", r)
        connectingTo = null
        discovered.set(null) // keep listening; the next beacon retries
        ui.netDetail = "Couldn't connect (${r.message}); retrying…"
      }
    }
  }

  private fun handlePhase(snap: Snapshot?) {
    val ph = snap?.phase
    if (ph == lastPhase) return
    val prev = lastPhase
    lastPhase = ph
    when (ph) {
      Phase.PLAYING,
      Phase.WAVE_BREAK -> {
        if (ui.screen != Screen.PLAYING && ui.screen != Screen.CALIBRATE && (prev == null || prev == Phase.LOBBY || prev == Phase.GAME_OVER)) {
          ui.screen = Screen.PLAYING
          hudPos = null
        }
      }
      Phase.GAME_OVER -> {
        val coop = mode != Mode.SOLO
        val score = snap.score
        val key = if (coop) KEY_BEST_COOP else KEY_BEST_SOLO
        val best = prefs.getInt(key, 0)
        ui.newBest = score > best
        if (score > best) prefs.edit().putInt(key, score).apply()
        ui.bestSolo = prefs.getInt(KEY_BEST_SOLO, 0)
        ui.bestCoop = prefs.getInt(KEY_BEST_COOP, 0)
        ui.lastScore = score
        ui.lastWave = snap.wave
        ui.lastWasCoop = coop
        ui.screen = Screen.GAME_OVER
      }
      Phase.LOBBY -> {
        if (mode == Mode.HOST) ui.screen = Screen.HOST_LOBBY
        if (mode == Mode.CLIENT) ui.screen = Screen.JOIN_LOBBY
      }
      null -> {}
    }
  }

  private fun handleEvents(events: List<GameEvent>, skipOwnLasers: Boolean) {
    val fx = sfx
    for (e in events) {
      val a = frame.sharedToWorld(e.a)
      val mine = e.player == myIdx
      when (e.type) {
        EventType.LASER -> {
          if (skipOwnLasers && mine) continue
          renderer.beam(a, frame.sharedToWorld(e.b), e.player)
          fx?.at(fx.laser, a, if (mine) 0.6f else 0.45f)
        }
        EventType.EXPLODE -> {
          val size =
              when (e.value) {
                DroneKind.BOSS.ordinal -> 4.5f
                DroneKind.TANK.ordinal -> 1.6f
                DroneKind.GUNNER.ordinal -> 1.2f
                DroneKind.SCOUT.ordinal -> 1f
                else -> 0.45f
              }
          renderer.explosion(a, size)
          fx?.at(if (e.value == DroneKind.BOSS.ordinal) fx.bossExplode else fx.explode, a, if (e.value < 0) 0.4f else 1f)
          if (mine && e.value >= 0) act.haptic(Hand.RIGHT, if (e.value == DroneKind.BOSS.ordinal) 1f else 0.5f, if (e.value == DroneKind.BOSS.ordinal) 400 else 45)
        }
        EventType.PLAYER_HIT -> {
          if (mine) {
            fx?.ui(fx.hurt, 1f)
            act.haptic(Hand.LEFT, 1f, 140)
            act.haptic(Hand.RIGHT, 1f, 140)
            hurtFlash = 1f
          } else {
            fx?.at(fx.hurt, a, 0.5f)
          }
        }
        EventType.SHIELD_BLOCK -> {
          fx?.at(fx.shield, a, 1f)
          fx?.at(fx.reflect, a, 0.6f)
          if (mine) act.haptic(if (lastShieldHand == 0) Hand.LEFT else Hand.RIGHT, 0.8f, 70)
        }
        EventType.PORTAL_OPEN -> fx?.at(fx.portal, a, if (e.value == 1) 1f else 0.8f)
        EventType.ENEMY_FIRE -> fx?.at(fx.enemyShot, a, 0.5f)
        EventType.WAVE_START -> {
          fx?.ui(fx.wave, 0.7f)
          banner("WAVE ${e.value}")
        }
        EventType.BOSS_INCOMING -> {
          fx?.ui(fx.bossAlarm, 0.9f)
          banner("⚠ BOSS PORTAL — WAVE ${e.value} ⚠")
        }
        EventType.WAVE_CLEAR -> banner("WAVE ${e.value} CLEARED · +HP")
        EventType.GAME_OVER -> fx?.ui(fx.gameOver, 1f)
        EventType.PLAYER_DOWN -> banner(if (mine) "YOU'RE DOWN — partner must clear the wave" else "PARTNER DOWN — clear the wave to revive them")
        EventType.PLAYER_REVIVED -> banner(if (mine) "BACK IN THE FIGHT" else "PARTNER REVIVED")
      }
    }
  }

  private fun banner(text: String) {
    ui.banner = text
    bannerTimer = 2.5f
  }

  private fun rebuildRoom(head: V3) {
    val sites = try {
      RoomWalls.sitesFrom(act.mruk, head)
    } catch (e: Exception) {
      Log.w(TAG, "room parse failed", e)
      emptyList()
    }
    if (sites.isEmpty() && act.mruk.rooms.isNotEmpty() && roomRetries < 120) {
      roomRetries++ // anchors may still be resolving; try again next frame
      return
    }
    roomDirty = false
    worldSites = sites
    ui.roomStatus =
        if (sites.isNotEmpty()) "Room scan: ${sites.size} walls found — portals open on your real walls"
        else "No room scan found — using virtual walls around you (RESCAN ROOM to set one up)"
  }

  // ------------------------------------------------------------------ panels

  private fun placeMenu(head: V3, fwd: V3) {
    val f = fwd.flat().norm().let { if (it == V3.ZERO) V3(0f, 0f, -1f) else it }
    val pos = V3(head.x, (head.y - 0.15f).coerceAtLeast(0.9f), head.z) + f * 1.25f
    menuPanel?.setComponent(Transform(Pose(pos.vec(), Quaternion.lookRotationAroundY(f.vec()))))
  }

  private fun updatePanels(head: V3, fwd: V3, dt: Float) {
    val wantMenu = ui.screen != Screen.PLAYING
    if (wantMenu != menuVisible) {
      menuVisible = wantMenu
      if (wantMenu) placeMenu(head, fwd)
      menuPanel?.setComponent(Visible(wantMenu))
    }
    val wantHud = ui.screen == Screen.PLAYING
    if (wantHud != hudVisible) {
      hudVisible = wantHud
      hudPanel?.setComponent(Visible(wantHud))
    }
    if (wantHud) {
      // lazy-follow: floats above your line of sight and drifts after you
      val f = fwd.flat().norm().let { if (it == V3.ZERO) V3(0f, 0f, -1f) else it }
      val target = V3(head.x, head.y + 0.42f, head.z) + f * 1.5f
      val p = hudPos?.lerp(target, min(1f, dt * 2.5f)) ?: target
      hudPos = p
      val look = (p - head).flat().norm()
      hudPanel?.setComponent(Transform(Pose(p.vec(), Quaternion.lookRotationAroundY(look.vec()))))
    }
    if (bannerTimer > 0f) {
      bannerTimer -= dt
      if (bannerTimer <= 0f) ui.banner = ""
    }
    hurtFlash = (hurtFlash - dt * 2f).coerceAtLeast(0f)
    hudTimer += dt
    if (hudTimer >= 0.1f) {
      hudTimer = 0f
      val s = current
      if (s != null) {
        ui.wave = s.wave
        ui.score = s.score
        ui.multiplier = s.multiplier
        ui.combo = s.combo
        val me = s.players.getOrNull(myIdx)
        val other = s.players.firstOrNull { it.idx != myIdx && it.connected }
        ui.health = me?.health ?: 100f
        ui.alive = me?.alive ?: true
        ui.showPartner = other != null
        ui.partnerHealth = other?.health ?: 0f
        ui.partnerAlive = other?.alive ?: false
      }
      ui.hurtFlash = hurtFlash
    }
  }

  private fun localIp(): String? =
      try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
      } catch (_: Exception) {
        null
      }

  companion object {
    private const val TAG = "WallBreach"
    const val FIRE_INTERVAL = 0.17f
    const val SNAPSHOT_INTERVAL = 0.05f // 20 Hz
    const val STATE_INTERVAL = 1f / 30f
    const val CALIB_HOLD = 1.5f
    const val KEY_BEST_SOLO = "best_solo"
    const val KEY_BEST_COOP = "best_coop"
  }
}
