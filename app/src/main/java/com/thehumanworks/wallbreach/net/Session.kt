package com.thehumanworks.wallbreach.net

import com.thehumanworks.wallbreach.sim.GameEvent
import com.thehumanworks.wallbreach.sim.GameSim
import com.thehumanworks.wallbreach.sim.Snapshot
import com.thehumanworks.wallbreach.sim.V3

/**
 * Host-side co-op glue: feeds the remote player's inputs into the authoritative [GameSim] and
 * streams snapshots back. Engine-independent so it can be exercised in JVM tests over loopback.
 */
class HostSession(val sim: GameSim, val host: NetHost, private val hostName: String = "Host") {
  var partnerConnected = false
    private set
  var partnerName: String = ""
    private set
  private var handshook: Connection? = null
  private var lastPingMs = 0L

  /** Drain the network inbox. Call once per frame on the game thread. */
  fun poll(nowMs: Long = System.currentTimeMillis()) {
    val c = host.client
    if (c == null || !c.isOpen) {
      if (partnerConnected) onPartnerLost()
      return
    }
    if (handshook != null && handshook !== c) onPartnerLost()
    while (true) {
      val m = c.inbox.poll() ?: break
      when (m) {
        is Msg.Hello -> {
          if (m.version != Protocol.VERSION) {
            c.send(Msg.Reject("Version mismatch: host v${Protocol.VERSION}, you v${m.version}"))
            continue
          }
          handshook = c
          partnerName = m.name
          partnerConnected = true
          c.send(Msg.Welcome(Protocol.VERSION, 1, hostName))
          sim.setPlayerConnected(1, true)
        }
        is Msg.ClientState ->
            if (handshook === c) sim.updatePlayer(1, m.head, m.headFwd, m.leftHand, m.rightHand, m.shieldActive, m.shieldCenter, m.shieldNormal)
        is Msg.Fire -> if (handshook === c) sim.fire(1, m.origin, m.dir, m.claimedDroneId)
        is Msg.Ping -> c.send(Msg.Pong(m.nanos))
        is Msg.Bye -> {
          c.close("partner left")
        }
        else -> {}
      }
    }
    if (partnerConnected && nowMs - c.lastReceivedMs > TIMEOUT_MS) {
      c.close("timeout")
      onPartnerLost()
    }
    if (partnerConnected && nowMs - lastPingMs > 1000) {
      lastPingMs = nowMs
      c.send(Msg.Ping(System.nanoTime()))
    }
  }

  private fun onPartnerLost() {
    partnerConnected = false
    handshook = null
    sim.setPlayerConnected(1, false)
  }

  fun sendSnapshot(s: Snapshot) {
    val c = handshook ?: return
    if (c.isOpen) c.send(Msg.SnapshotMsg(s))
  }

  fun close() {
    host.client?.send(Msg.Bye("host quit"))
    host.close()
  }

  companion object {
    const val TIMEOUT_MS = 5000L
  }
}

/** Client-side co-op glue: mirrors host snapshots and sends local inputs. */
class ClientSession(val conn: Connection, name: String = "Guest") {
  var playerIdx = -1
    private set
  var hostName = ""
    private set
  var rejected: String? = null
    private set
  var latest: Snapshot? = null
    private set
  var rttMs: Float = 0f
    private set
  private val pendingEvents = ArrayList<GameEvent>()
  private var lastPingMs = 0L

  init {
    conn.send(Msg.Hello(Protocol.VERSION, name))
  }

  val connected: Boolean
    get() = conn.isOpen && rejected == null

  val welcomed: Boolean
    get() = playerIdx >= 0

  /** Returns true if a new snapshot arrived. Events from every snapshot are accumulated. */
  fun poll(nowMs: Long = System.currentTimeMillis()): Boolean {
    var fresh = false
    while (true) {
      val m = conn.inbox.poll() ?: break
      when (m) {
        is Msg.Welcome -> {
          playerIdx = m.playerIdx
          hostName = m.hostName
        }
        is Msg.Reject -> {
          rejected = m.reason
          conn.close(m.reason)
        }
        is Msg.SnapshotMsg -> {
          latest = m.snapshot
          pendingEvents += m.snapshot.events
          fresh = true
        }
        is Msg.Ping -> conn.send(Msg.Pong(m.nanos))
        is Msg.Pong -> rttMs = (System.nanoTime() - m.nanos) / 1e6f
        is Msg.Bye -> conn.close(m.reason)
        else -> {}
      }
    }
    if (conn.isOpen && nowMs - conn.lastReceivedMs > HostSession.TIMEOUT_MS) conn.close("host timed out")
    if (conn.isOpen && nowMs - lastPingMs > 1000) {
      lastPingMs = nowMs
      conn.send(Msg.Ping(System.nanoTime()))
    }
    return fresh
  }

  fun drainEvents(): List<GameEvent> {
    val out = ArrayList(pendingEvents)
    pendingEvents.clear()
    return out
  }

  fun sendState(head: V3, headFwd: V3, lh: V3, rh: V3, shieldActive: Boolean, shieldCenter: V3, shieldNormal: V3) {
    if (welcomed) conn.send(Msg.ClientState(head, headFwd, lh, rh, shieldActive, shieldCenter, shieldNormal))
  }

  fun sendFire(origin: V3, dir: V3, claimedDroneId: Int) {
    if (welcomed) conn.send(Msg.Fire(origin, dir, claimedDroneId))
  }

  fun close() {
    conn.send(Msg.Bye("guest left"))
    Thread.sleep(50)
    conn.close("left")
  }
}
