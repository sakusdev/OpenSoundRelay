// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.ArrayDeque
import java.util.LinkedHashMap

data class DiscoveredDevice(
    val serviceName: String,
    val address: InetSocketAddress,
) {
    val key: String = "$serviceName@${address.address?.hostAddress ?: address.hostString}:${address.port}"
    val addressLabel: String = "${address.address?.hostAddress ?: address.hostString}:${address.port}"
}

class LanDiscoveryManager(
    context: Context,
    private val onDevicesChanged: (List<DiscoveredDevice>) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val devices = LinkedHashMap<String, DiscoveredDevice>()
    private val pendingResolutions = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var registeredServiceName: String? = null

    fun startDiscovery() {
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                onStatus("Scanning the local network")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.startsWith(SERVICE_TYPE.removeSuffix("."))) return
                if (serviceInfo.serviceName == registeredServiceName) return
                enqueueResolution(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val changed = devices.entries.removeAll { it.value.serviceName == serviceInfo.serviceName }
                if (changed) publishDevices()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                onStatus("Scan stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener = null
                onStatus("LAN scan failed ($errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                onStatus("Could not stop LAN scan ($errorCode)")
            }
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Throwable) {
            discoveryListener = null
            onStatus("LAN scan unavailable: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        runCatching { nsdManager.stopServiceDiscovery(listener) }
        synchronized(pendingResolutions) {
            pendingResolutions.clear()
            resolving = false
        }
    }

    fun registerReceiver(port: Int, deviceName: String = Build.MODEL) {
        unregisterReceiver()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "OSR · $deviceName"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("version", "1")
            setAttribute("role", "receiver")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                onStatus("Receiver visible as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                registrationListener = null
                onStatus("Could not advertise receiver ($errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registeredServiceName = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                onStatus("Could not stop receiver advertisement ($errorCode)")
            }
        }
        registrationListener = listener
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Throwable) {
            registrationListener = null
            onStatus("LAN advertisement unavailable: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun unregisterReceiver() {
        val listener = registrationListener ?: return
        registrationListener = null
        registeredServiceName = null
        runCatching { nsdManager.unregisterService(listener) }
    }

    fun close() {
        stopDiscovery()
        unregisterReceiver()
        devices.clear()
        publishDevices()
    }

    private fun enqueueResolution(serviceInfo: NsdServiceInfo) {
        synchronized(pendingResolutions) {
            if (pendingResolutions.any { it.serviceName == serviceInfo.serviceName }) return
            pendingResolutions.addLast(serviceInfo)
        }
        resolveNext()
    }

    private fun resolveNext() {
        val next = synchronized(pendingResolutions) {
            if (resolving || pendingResolutions.isEmpty()) return
            resolving = true
            pendingResolutions.removeFirst()
        }
        mainHandler.post {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = firstHost(serviceInfo)
                    if (host != null && serviceInfo.port in 1..65_535) {
                        val device = DiscoveredDevice(
                            serviceName = serviceInfo.serviceName,
                            address = InetSocketAddress(host, serviceInfo.port),
                        )
                        devices[device.key] = device
                        publishDevices()
                    }
                    finishResolution()
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    finishResolution()
                }
            })
        }
    }

    private fun finishResolution() {
        synchronized(pendingResolutions) { resolving = false }
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun firstHost(serviceInfo: NsdServiceInfo): InetAddress? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfo.hostAddresses.firstOrNull()
        } else {
            serviceInfo.host
        }
    }

    private fun publishDevices() {
        val snapshot = devices.values.sortedBy { it.serviceName.lowercase() }
        mainHandler.post { onDevicesChanged(snapshot) }
    }

    companion object {
        const val SERVICE_TYPE = "_opensoundrelay._udp."
    }
}
