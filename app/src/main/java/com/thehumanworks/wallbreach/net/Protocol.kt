package com.thehumanworks.wallbreach.net

import com.thehumanworks.wallbreach.sim.DroneKind
import com.thehumanworks.wallbreach.sim.DroneView
import com.thehumanworks.wallbreach.sim.EventType
import com.thehumanworks.wallbreach.sim.GameEvent
import com.thehumanworks.wallbreach.sim.Phase
import com.thehumanworks.wallbreach.sim.PlayerView
import com.thehumanworks.wallbreach.sim.PortalView
import com.thehumanworks.wallbreach.sim.ShotView
import com.thehumanworks.wallbreach.sim.Snapshot
import com.thehumanworks.wallbreach.sim.V3
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Wall Breach LAN co-op wire protocol (host-authoritative).
 *
 * Framing on the TCP stream: [int32 length][payload]. Payload = [byte type][fields...], big-endian
 * via DataOutput. All positions are in the shared co-location frame.
 *
 * client -> host: Hello, ClientState (~30 Hz), Fire, Ping, Bye
 * host -> client: Welcome / Reject, Snapshot (~20 Hz, includes one-shot events), Pong, Bye
 */
sealed class Msg {
  data class Hello(val version: Int, val name: String) : Msg()
  data class Welcome(val version: Int, val playerIdx: Int, val hostName: String) : Msg()
  data class Reject(val reason: String) : Msg()
  data class ClientState(
      val head: V3,
      val headFwd: V3,
      val leftHand: V3,
      val rightHand: V3,
      val shieldActive: Boolean,
      val shieldCenter: V3,
      val shieldNormal: V3,
  ) : Msg()
  data class Fire(val origin: V3, val dir: V3, val claimedDroneId: Int) : Msg()
  data class SnapshotMsg(val snapshot: Snapshot) : Msg()
  data class Ping(val nanos: Long) : Msg()
  data class Pong(val nanos: Long) : Msg()
  data class Bye(val reason: String) : Msg()
}

object Protocol {
  const val MAGIC = 0x57425248 // "WBRH"
  const val VERSION = 1
  const val MAX_FRAME = 1 shl 20

  private const val T_HELLO: Int = 1
  private const val T_WELCOME: Int = 2
  private const val T_REJECT: Int = 3
  private const val T_CLIENT_STATE: Int = 4
  private const val T_FIRE: Int = 5
  private const val T_SNAPSHOT: Int = 6
  private const val T_PING: Int = 7
  private const val T_PONG: Int = 8
  private const val T_BYE: Int = 9

  fun encode(msg: Msg): ByteArray {
    val bos = ByteArrayOutputStream(256)
    val o = DataOutputStream(bos)
    when (msg) {
      is Msg.Hello -> {
        o.writeByte(T_HELLO)
        o.writeInt(MAGIC)
        o.writeInt(msg.version)
        o.writeUTF(msg.name)
      }
      is Msg.Welcome -> {
        o.writeByte(T_WELCOME)
        o.writeInt(msg.version)
        o.writeByte(msg.playerIdx)
        o.writeUTF(msg.hostName)
      }
      is Msg.Reject -> {
        o.writeByte(T_REJECT)
        o.writeUTF(msg.reason)
      }
      is Msg.ClientState -> {
        o.writeByte(T_CLIENT_STATE)
        o.v3(msg.head)
        o.v3(msg.headFwd)
        o.v3(msg.leftHand)
        o.v3(msg.rightHand)
        o.writeBoolean(msg.shieldActive)
        o.v3(msg.shieldCenter)
        o.v3(msg.shieldNormal)
      }
      is Msg.Fire -> {
        o.writeByte(T_FIRE)
        o.v3(msg.origin)
        o.v3(msg.dir)
        o.writeInt(msg.claimedDroneId)
      }
      is Msg.SnapshotMsg -> {
        o.writeByte(T_SNAPSHOT)
        writeSnapshot(o, msg.snapshot)
      }
      is Msg.Ping -> {
        o.writeByte(T_PING)
        o.writeLong(msg.nanos)
      }
      is Msg.Pong -> {
        o.writeByte(T_PONG)
        o.writeLong(msg.nanos)
      }
      is Msg.Bye -> {
        o.writeByte(T_BYE)
        o.writeUTF(msg.reason)
      }
    }
    o.flush()
    return bos.toByteArray()
  }

  fun decode(bytes: ByteArray): Msg {
    val i = DataInputStream(ByteArrayInputStream(bytes))
    return when (val t = i.readUnsignedByte()) {
      T_HELLO -> {
        val magic = i.readInt()
        require(magic == MAGIC) { "bad magic" }
        Msg.Hello(i.readInt(), i.readUTF())
      }
      T_WELCOME -> Msg.Welcome(i.readInt(), i.readUnsignedByte(), i.readUTF())
      T_REJECT -> Msg.Reject(i.readUTF())
      T_CLIENT_STATE ->
          Msg.ClientState(i.v3(), i.v3(), i.v3(), i.v3(), i.readBoolean(), i.v3(), i.v3())
      T_FIRE -> Msg.Fire(i.v3(), i.v3(), i.readInt())
      T_SNAPSHOT -> Msg.SnapshotMsg(readSnapshot(i))
      T_PING -> Msg.Ping(i.readLong())
      T_PONG -> Msg.Pong(i.readLong())
      T_BYE -> Msg.Bye(i.readUTF())
      else -> throw IllegalArgumentException("unknown message type $t")
    }
  }

  private fun writeSnapshot(o: DataOutputStream, s: Snapshot) {
    o.writeInt(s.tick)
    o.writeByte(s.phase.ordinal)
    o.writeShort(s.wave)
    o.writeInt(s.score)
    o.writeShort(s.combo)
    o.writeByte(s.multiplier)
    o.writeFloat(s.phaseTimer)
    o.writeByte(s.players.size)
    for (p in s.players) {
      o.writeByte(p.idx)
      o.writeBoolean(p.connected)
      o.writeFloat(p.health)
      o.writeBoolean(p.alive)
      o.v3(p.head)
      o.v3(p.headFwd)
      o.v3(p.leftHand)
      o.v3(p.rightHand)
      o.writeBoolean(p.shieldActive)
      o.v3(p.shieldCenter)
      o.v3(p.shieldNormal)
    }
    o.writeShort(s.portals.size)
    for (p in s.portals) {
      o.writeInt(p.id)
      o.v3(p.pos)
      o.v3(p.normal)
      o.writeFloat(p.radius)
      o.writeBoolean(p.boss)
    }
    o.writeShort(s.drones.size)
    for (d in s.drones) {
      o.writeInt(d.id)
      o.writeByte(d.kind.ordinal)
      o.v3(d.pos)
      o.writeFloat(d.hpFrac)
    }
    o.writeShort(s.shots.size)
    for (sh in s.shots) {
      o.writeInt(sh.id)
      o.v3(sh.pos)
      o.writeBoolean(sh.hostile)
    }
    o.writeShort(s.events.size)
    for (e in s.events) {
      o.writeByte(e.type.ordinal)
      o.writeByte(e.player + 1) // -1 encodes as 0
      o.v3(e.a)
      o.v3(e.b)
      o.writeInt(e.value)
    }
  }

  private fun readSnapshot(i: DataInputStream): Snapshot {
    val tick = i.readInt()
    val phase = Phase.entries[i.readUnsignedByte()]
    val wave = i.readUnsignedShort()
    val score = i.readInt()
    val combo = i.readUnsignedShort()
    val mult = i.readUnsignedByte()
    val timer = i.readFloat()
    val players =
        List(i.readUnsignedByte()) {
          PlayerView(i.readUnsignedByte(), i.readBoolean(), i.readFloat(), i.readBoolean(), i.v3(), i.v3(), i.v3(), i.v3(), i.readBoolean(), i.v3(), i.v3())
        }
    val portals = List(i.readUnsignedShort()) { PortalView(i.readInt(), i.v3(), i.v3(), i.readFloat(), i.readBoolean()) }
    val drones = List(i.readUnsignedShort()) { DroneView(i.readInt(), DroneKind.entries[i.readUnsignedByte()], i.v3(), i.readFloat()) }
    val shots = List(i.readUnsignedShort()) { ShotView(i.readInt(), i.v3(), i.readBoolean()) }
    val events =
        List(i.readUnsignedShort()) {
          GameEvent(EventType.entries[i.readUnsignedByte()], i.readUnsignedByte() - 1, i.v3(), i.v3(), i.readInt())
        }
    return Snapshot(tick, phase, wave, score, combo, mult, timer, players, portals, drones, shots, events)
  }

  private fun DataOutputStream.v3(v: V3) {
    writeFloat(v.x)
    writeFloat(v.y)
    writeFloat(v.z)
  }

  private fun DataInputStream.v3() = V3(readFloat(), readFloat(), readFloat())
}
