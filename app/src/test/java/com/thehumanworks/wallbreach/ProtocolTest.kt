package com.thehumanworks.wallbreach

import com.thehumanworks.wallbreach.net.BeaconCodec
import com.thehumanworks.wallbreach.net.Msg
import com.thehumanworks.wallbreach.net.Protocol
import com.thehumanworks.wallbreach.sim.GameSim
import com.thehumanworks.wallbreach.sim.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
  private fun rt(m: Msg) = assertEquals(m, Protocol.decode(Protocol.encode(m)))

  @Test
  fun simpleMessagesRoundTrip() {
    rt(Msg.Hello(Protocol.VERSION, "Quest-B ✓"))
    rt(Msg.Welcome(1, 1, "Tomas"))
    rt(Msg.Reject("Game is full"))
    rt(Msg.ClientState(V3(1f, 2f, 3f), V3(0f, 0f, 1f), V3(-1f, 1f, 0f), V3(1f, 1f, 0f), true, V3(0.1f, 1.2f, 0.3f), V3(0f, 0f, -1f)))
    rt(Msg.Fire(V3(0f, 1.4f, 0f), V3(0f, 0f, -1f), 42))
    rt(Msg.Ping(123456789L))
    rt(Msg.Pong(-5L))
    rt(Msg.Bye("bye"))
  }

  @Test
  fun liveSnapshotRoundTripsExactly() {
    val sim = GameSim(seed = 1)
    sim.startGame(2)
    repeat(600) { sim.step(1f / 60f) } // 10 s: portals open, drones out, shots flying
    sim.fire(0, V3(0f, 1.5f, 0f), V3(0f, 0f, -1f))
    val snap = sim.snapshot(sim.drainEvents())
    assertTrue("expected drones in flight", snap.drones.isNotEmpty())
    val decoded = (Protocol.decode(Protocol.encode(Msg.SnapshotMsg(snap))) as Msg.SnapshotMsg).snapshot
    assertEquals(snap, decoded)
    assertTrue("snapshot should stay small: ${Protocol.encode(Msg.SnapshotMsg(snap)).size}", Protocol.encode(Msg.SnapshotMsg(snap)).size < 4000)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsUnknownType() {
    Protocol.decode(byteArrayOf(99))
  }

  @Test
  fun beaconParse() {
    val bytes = BeaconCodec.encode(47812, "Tomas's Quest")
    val b = BeaconCodec.parse(bytes, bytes.size, "192.168.1.20")
    assertNotNull(b)
    assertEquals(47812, b!!.tcpPort)
    assertEquals("Tomas's Quest", b.hostName)
    assertEquals("192.168.1.20", b.address)
    assertNull(BeaconCodec.parse("HELLO|1|2|x".toByteArray(), 11, "1.1.1.1"))
    assertNull(BeaconCodec.parse("WALLBREACH|1|99999|x".toByteArray(), 20, "1.1.1.1"))
  }
}
