package com.thehumanworks.wallbreach.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/** A host announcement found on the LAN. */
data class Beacon(val address: String, val tcpPort: Int, val hostName: String, val version: Int)

/** Text beacon: "WALLBREACH|<version>|<tcpPort>|<hostName>" sent by UDP broadcast once a second. */
object BeaconCodec {
  const val PREFIX = "WALLBREACH"
  const val UDP_PORT = 47811

  fun encode(tcpPort: Int, hostName: String, version: Int = Protocol.VERSION): ByteArray =
      "$PREFIX|$version|$tcpPort|${hostName.replace("|", "/").take(40)}".toByteArray(Charsets.UTF_8)

  fun parse(data: ByteArray, length: Int, fromAddress: String): Beacon? {
    val text = try {
      String(data, 0, length, Charsets.UTF_8)
    } catch (_: Exception) {
      return null
    }
    val parts = text.split("|", limit = 4)
    if (parts.size != 4 || parts[0] != PREFIX) return null
    val version = parts[1].toIntOrNull() ?: return null
    val port = parts[2].toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    return Beacon(fromAddress, port, parts[3], version)
  }
}

/** Host side: periodically broadcasts the beacon on every IPv4 broadcast address. */
class BeaconBroadcaster(
    private val tcpPort: Int,
    private val hostName: String,
    private val udpPort: Int = BeaconCodec.UDP_PORT,
    private val extraTargets: List<InetAddress> = emptyList(),
) {
  private val running = AtomicBoolean(true)

  init {
    Thread({ loop() }, "wb-beacon-tx").apply { isDaemon = true }.start()
  }

  private fun targets(): List<InetAddress> {
    val out = LinkedHashSet<InetAddress>()
    try {
      for (ni in NetworkInterface.getNetworkInterfaces()) {
        if (!ni.isUp || ni.isLoopback) continue
        for (ia in ni.interfaceAddresses) ia.broadcast?.let { out += it }
      }
    } catch (_: Exception) {}
    try {
      out += InetAddress.getByName("255.255.255.255")
    } catch (_: Exception) {}
    out += extraTargets
    return out.toList()
  }

  private fun loop() {
    try {
      DatagramSocket().use { sock ->
        sock.broadcast = true
        val payload = BeaconCodec.encode(tcpPort, hostName)
        while (running.get()) {
          for (t in targets()) {
            try {
              sock.send(DatagramPacket(payload, payload.size, t, udpPort))
            } catch (_: Exception) {}
          }
          Thread.sleep(1000)
        }
      }
    } catch (_: Exception) {}
  }

  fun close() = running.set(false)
}

/** Client side: listens for beacons and reports each one found. */
class BeaconListener(private val udpPort: Int = BeaconCodec.UDP_PORT, private val onBeacon: (Beacon) -> Unit) {
  private val running = AtomicBoolean(true)
  @Volatile private var socket: DatagramSocket? = null
  @Volatile var error: String? = null
    private set

  init {
    Thread({ loop() }, "wb-beacon-rx").apply { isDaemon = true }.start()
  }

  private fun loop() {
    try {
      val s = DatagramSocket(null)
      s.reuseAddress = true
      s.broadcast = true
      s.soTimeout = 1000
      s.bind(InetSocketAddress(udpPort))
      socket = s
      val buf = ByteArray(512)
      while (running.get()) {
        val pkt = DatagramPacket(buf, buf.size)
        try {
          s.receive(pkt)
        } catch (_: java.net.SocketTimeoutException) {
          continue
        }
        val from = pkt.address?.hostAddress ?: continue
        BeaconCodec.parse(pkt.data, pkt.length, from)?.let(onBeacon)
      }
    } catch (e: Exception) {
      if (running.get()) error = e.message
    } finally {
      socket?.close()
    }
  }

  fun close() {
    running.set(false)
    socket?.close()
  }
}
