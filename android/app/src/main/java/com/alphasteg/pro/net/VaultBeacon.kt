package com.alphasteg.pro.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress

/**
 * Announces an unlocked vault on the local network, and finds ones announced by
 * others, over mDNS.
 *
 * Without this, reaching the network drive means reading an IP off one screen and
 * typing it into another - painful on a DAP, where the keyboard is a touchscreen
 * the size of a credit card. A beacon lets the player list what is on the network
 * instead.
 *
 * What is advertised is deliberately thin: a name, a host and a port. The session
 * token never goes near it, so seeing the beacon tells you a vault is being
 * served and nothing else; without the token every request still gets a 401. The
 * beacon lives exactly as long as the server does.
 */
class VaultBeacon(context: Context) {

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    /** A vault someone on this network is serving. */
    data class Found(
        val name: String,
        val host: String,
        val port: Int
    ) {
        val url: String get() = "http://$host:$port/"
    }

    /**
     * Start advertising a vault served on [port]. [label] is what other devices
     * show in their list, so it should say which phone this is, not what is in it.
     */
    fun advertise(label: String, port: Int) {
        val manager = nsd ?: return
        if (registration != null) return
        val info = NsdServiceInfo().apply {
            serviceName = label
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo?) {}
            override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo?) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { registration = null }
    }

    /** Stop advertising. Safe to call when nothing is being advertised. */
    fun stopAdvertising() {
        val manager = nsd ?: return
        val listener = registration ?: return
        registration = null
        runCatching { manager.unregisterService(listener) }
    }

    /**
     * Look for vaults on the network, calling [onFound] on the main thread as each
     * one resolves. Call [stopDiscovery] when the picker closes; discovery holds a
     * multicast socket open and is not free to leave running.
     */
    fun discover(onFound: (Found) -> Unit) {
        val manager = nsd ?: return
        if (discovery != null) return

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo?) {}

            override fun onServiceFound(info: NsdServiceInfo?) {
                val service = info ?: return
                if (service.serviceType?.contains(SERVICE_NAME) != true) return
                // A found service carries only a name; the address needs resolving.
                runCatching { manager.resolveService(service, resolveListener(onFound)) }
            }
        }
        discovery = listener
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { discovery = null }
    }

    fun stopDiscovery() {
        val manager = nsd ?: return
        val listener = discovery ?: return
        discovery = null
        runCatching { manager.stopServiceDiscovery(listener) }
    }

    private fun resolveListener(onFound: (Found) -> Unit) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {}
        override fun onServiceResolved(info: NsdServiceInfo?) {
            val service = info ?: return
            val address: InetAddress = service.host ?: return
            val host = address.hostAddress ?: return
            onFound(Found(service.serviceName ?: "AlphaVault", host, service.port))
        }
    }

    companion object {
        private const val SERVICE_NAME = "_alphavault"
        const val SERVICE_TYPE = "_alphavault._tcp."
    }
}
