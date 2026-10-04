package com.thehumanworks.wallbreach.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One framed TCP link. A reader thread decodes incoming frames into [inbox]; sends go through a
 * single writer thread so the render thread never blocks on the network.
 */
class Connection(private val socket: Socket, val label: String = "peer") {
  val inbox = ConcurrentLinkedQueue<Msg>()
  private val open = AtomicBoolean(true)
  private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "wb-net-writer-$label").apply { isDaemon = true } }
  private val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
  @Volatile var lastReceivedMs: Long = System.currentTimeMillis()
    private set
  @Volatile var closeReason: String? = null
    private set
  val remoteAddress: String
    get() = socket.inetAddress?.hostAddress ?: "?"

  init {
    socket.tcpNoDelay = true
    Thread({ readLoop() }, "wb-net-reader-$label").apply { isDaemon = true }.start()
  }

  val isOpen: Boolean
    get() = open.get()

  fun send(msg: Msg) {
    if (!open.get()) return
    val bytes = Protocol.encode(msg)
    try {
      writer.execute {
        try {
          out.writeInt(bytes.size)
          out.write(bytes)
          out.flush()
        } catch (e: IOException) {
          close("write failed: ${e.message}")
        }
      }
    } catch (_: Exception) {
      // executor already shut down
    }
  }

  private fun readLoop() {
    try {
      val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
      while (open.get()) {
        val len = input.readInt()
        if (len <= 0 || len > Protocol.MAX_FRAME) throw IOException("bad frame length $len")
        val buf = ByteArray(len)
        input.readFully(buf)
        lastReceivedMs = System.currentTimeMillis()
        inbox.add(Protocol.decode(buf))
      }
    } catch (e: Exception) {
      close(if (open.get()) "connection lost: ${e.message ?: e.javaClass.simpleName}" else null)
    }
  }

  fun close(reason: String? = "closed") {
    if (!open.getAndSet(false)) return
    closeReason = reason
    writer.shutdown()
    try {
      writer.awaitTermination(200, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {}
    try {
      socket.close()
    } catch (_: IOException) {}
  }
}

/** Accepts a single co-op partner (2-player game). Extra connections get a Reject. */
class NetHost(port: Int = DEFAULT_TCP_PORT) {
  private val server = ServerSocket().apply {
    reuseAddress = true
    bind(InetSocketAddress(port))
  }
  val localPort: Int = server.localPort
  @Volatile var client: Connection? = null
    private set
  private val running = AtomicBoolean(true)

  init {
    Thread({ acceptLoop() }, "wb-net-accept").apply { isDaemon = true }.start()
  }

  private fun acceptLoop() {
    while (running.get()) {
      try {
        val s = server.accept()
        val existing = client
        if (existing != null && existing.isOpen) {
          Connection(s, "rejected").apply {
            send(Msg.Reject("Game is full"))
            Thread.sleep(100)
            close()
          }
        } else {
          client = Connection(s, "host-side")
        }
      } catch (e: Exception) {
        if (!running.get()) return
      }
    }
  }

  fun dropClient() {
    client?.close("dropped by host")
    client = null
  }

  fun close() {
    running.set(false)
    client?.close("host closed")
    try {
      server.close()
    } catch (_: IOException) {}
  }

  companion object {
    const val DEFAULT_TCP_PORT = 47812
  }
}

object NetClient {
  /** Blocking connect; call from a background thread. */
  fun connect(host: String, port: Int, timeoutMs: Int = 4000): Connection {
    val s = Socket()
    s.connect(InetSocketAddress(host, port), timeoutMs)
    return Connection(s, "client-side")
  }
}
