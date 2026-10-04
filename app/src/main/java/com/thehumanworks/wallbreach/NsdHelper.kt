package com.thehumanworks.wallbreach

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** mDNS / DNS-SD discovery ("_wallbreach._tcp"), used alongside the UDP broadcast beacon. */
class NsdHelper(context: Context) {
  private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
  private var reg: NsdManager.RegistrationListener? = null
  private var disc: NsdManager.DiscoveryListener? = null

  fun register(name: String, port: Int) {
    val info = NsdServiceInfo().apply {
      serviceName = name
      serviceType = SERVICE_TYPE
      setPort(port)
    }
    val l = object : NsdManager.RegistrationListener {
      override fun onServiceRegistered(si: NsdServiceInfo) { Log.i(TAG, "NSD registered ${si.serviceName}") }
      override fun onRegistrationFailed(si: NsdServiceInfo, err: Int) { Log.w(TAG, "NSD register failed $err") }
      override fun onServiceUnregistered(si: NsdServiceInfo) {}
      override fun onUnregistrationFailed(si: NsdServiceInfo, err: Int) {}
    }
    reg = l
    try {
      nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    } catch (e: Exception) {
      Log.w(TAG, "NSD register error", e)
    }
  }

  fun discover(onFound: (host: String, port: Int, name: String) -> Unit) {
    val l = object : NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(type: String) {}
      override fun onDiscoveryStopped(type: String) {}
      override fun onStartDiscoveryFailed(type: String, err: Int) { Log.w(TAG, "NSD discovery failed $err") }
      override fun onStopDiscoveryFailed(type: String, err: Int) {}
      override fun onServiceLost(si: NsdServiceInfo) {}
      override fun onServiceFound(si: NsdServiceInfo) {
        if (!si.serviceType.startsWith("_wallbreach")) return
        @Suppress("DEPRECATION")
        nsd.resolveService(
            si,
            object : NsdManager.ResolveListener {
              override fun onResolveFailed(si: NsdServiceInfo, err: Int) { Log.w(TAG, "NSD resolve failed $err") }
              override fun onServiceResolved(si: NsdServiceInfo) {
                @Suppress("DEPRECATION") val host = si.host?.hostAddress ?: return
                onFound(host, si.port, si.serviceName)
              }
            },
        )
      }
    }
    disc = l
    try {
      nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    } catch (e: Exception) {
      Log.w(TAG, "NSD discover error", e)
    }
  }

  fun stop() {
    reg?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
    disc?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
    reg = null
    disc = null
  }

  companion object {
    const val SERVICE_TYPE = "_wallbreach._tcp."
    private const val TAG = "WallBreachNsd"
  }
}
