package com.thehumanworks.wallbreach

import com.thehumanworks.wallbreach.net.BeaconBroadcaster
import com.thehumanworks.wallbreach.net.BeaconListener
import com.thehumanworks.wallbreach.net.ClientSession
import com.thehumanworks.wallbreach.net.HostSession
import com.thehumanworks.wallbreach.net.NetClient
import com.thehumanworks.wallbreach.net.NetHost
import com.thehumanworks.wallbreach.sim.EventType
import com.thehumanworks.wallbreach.sim.GameSim
import com.thehumanworks.wallbreach.sim.Phase
import com.thehumanworks.wallbreach.sim.V3
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Full host <-> client session over real TCP sockets on 127.0.0.1. */
class LoopbackNetworkTest {

  @Test
  fun hostAndClientPlayAWaveTogether() {
    val sim = GameSim(seed = 11)
    val host = HostSession(sim, NetHost(0), "HostQuest")
    val conn = NetClient.connect("127.0.0.1", host.host.localPort)
    val client = ClientSession(conn, "GuestQuest")
    try {
      // handshake
      val deadline = System.currentTimeMillis() + 3000
      while (!(host.partnerConnected && client.welcomed) && System.currentTimeMillis() < deadline) {
        host.poll(); client.poll(); Thread.sleep(5)
      }
      assertTrue(host.partnerConnected)
      assertEquals(1, client.playerIdx)
      assertEquals("HostQuest", client.hostName)
      assertEquals("GuestQuest", host.partnerName)

      sim.startGame(2)
      val clientHead = V3(0.8f, 1.5f, 0.2f)
      var clientKills = 0
      var lastSnapTick = -1
      val clientEvents = mutableListOf<EventType>()
      val dt = 1f / 60f
      var frame = 0
      while (frame < 60 * 40 && clientKills < 5) {
        frame++
        host.poll()
        sim.step(dt)
        if (frame % 3 == 0) host.sendSnapshot(sim.snapshot(sim.drainEvents()))
        if (client.poll()) lastSnapTick = client.latest!!.tick
        clientEvents += client.drainEvents().map { it.type }
        client.sendState(clientHead, V3(0f, 0f, -1f), clientHead, clientHead, false, clientHead, V3(0f, 0f, -1f))
        // the client shoots at what *it* sees and claims the hit
        val snap = client.latest
        if (snap != null && frame % 12 == 0) {
          snap.drones.minByOrNull { it.pos.distSq(clientHead) }?.let { d ->
            client.sendFire(clientHead, d.pos - clientHead, d.id)
          }
        }
        clientKills = sim.players[1].kills
        sim.players[0].health = 100
        sim.players[1].health = 100
        Thread.sleep(2)
      }
      assertTrue("client should have scored kills via the network, got $clientKills", clientKills >= 5)
      assertTrue(lastSnapTick > 0)
      assertTrue("client receives host events", clientEvents.contains(EventType.EXPLODE))
      assertTrue(clientEvents.contains(EventType.LASER))
      // the host saw the client's avatar pose
      assertEquals(clientHead, sim.players[1].head)
      val snap = client.latest!!
      assertEquals(sim.phase == Phase.PLAYING || sim.phase == Phase.WAVE_BREAK, true)
      assertTrue(snap.players[1].connected)
      assertTrue(snap.score > 0)

      // client leaves -> host notices and continues solo
      client.close()
      val d2 = System.currentTimeMillis() + 3000
      while (host.partnerConnected && System.currentTimeMillis() < d2) { host.poll(); Thread.sleep(10) }
      assertTrue(!host.partnerConnected)
      assertTrue(!sim.players[1].connected)
    } finally {
      host.close()
      conn.close()
    }
  }

  @Test
  fun secondGuestIsRejected() {
    val sim = GameSim(seed = 12)
    val host = HostSession(sim, NetHost(0))
    val a = ClientSession(NetClient.connect("127.0.0.1", host.host.localPort), "A")
    try {
      val deadline = System.currentTimeMillis() + 3000
      while (!a.welcomed && System.currentTimeMillis() < deadline) { host.poll(); a.poll(); Thread.sleep(5) }
      assertTrue(a.welcomed)
      val b = ClientSession(NetClient.connect("127.0.0.1", host.host.localPort), "B")
      val d2 = System.currentTimeMillis() + 3000
      while (b.rejected == null && System.currentTimeMillis() < d2) { host.poll(); b.poll(); Thread.sleep(5) }
      assertEquals("Game is full", b.rejected)
      assertNull(a.rejected)
    } finally {
      host.close()
    }
  }

  @Test
  fun udpBeaconIsDiscovered() {
    val port = 47000 + (System.nanoTime() % 900).toInt()
    val latch = CountDownLatch(1)
    var found: com.thehumanworks.wallbreach.net.Beacon? = null
    val listener = BeaconListener(port) { b -> found = b; latch.countDown() }
    Thread.sleep(200)
    val tx = BeaconBroadcaster(tcpPort = 47812, hostName = "TestHost", udpPort = port, extraTargets = listOf(InetAddress.getLoopbackAddress()))
    try {
      assertTrue("beacon not received", latch.await(5, TimeUnit.SECONDS))
      assertNotNull(found)
      assertEquals(47812, found!!.tcpPort)
      assertEquals("TestHost", found!!.hostName)
    } finally {
      tx.close()
      listener.close()
    }
  }
}
