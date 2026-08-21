package com.sophy.admin

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class ReceiverDiscovery(
    private val context: Context,
    private val onStateChanged: () -> Unit,
    private val onReceiverFound: (host: String, port: Int) -> Unit
) {
    companion object {
        const val SERVICE_TYPE = "_sophy._udp"
        const val SERVICE_NAME = "Sophy Receiver"
    }

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val running = AtomicBoolean(false)
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        AdminState.discovering.set(true)
        AdminState.status = "البحث عن الشاشة…"
        onStateChanged()

        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i("SophyNSD", "Discovery started: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("_sophy._udp")) return
                if (!serviceInfo.serviceName.contains("Sophy", ignoreCase = true) &&
                    serviceInfo.serviceName != SERVICE_NAME) return

                // resolveService is deprecated on API 34, but remains the broadest
                // compatibility path for this Android 8+ sender. The Receiver is local.
                try {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.w("SophyNSD", "Resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            val port = info.port
                            AdminState.receiverFound.set(true)
                            AdminState.receiverName = info.serviceName
                            AdminState.receiverHost = host
                            AdminState.receiverPort = port
                            AdminState.status = "متصل بالشاشة"
                            AdminState.error = ""
                            onReceiverFound(host, port)
                            onStateChanged()
                        }
                    })
                } catch (t: Throwable) {
                    Log.e("SophyNSD", "Resolve exception", t)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceName.contains("Sophy", ignoreCase = true)) return
                AdminState.receiverFound.set(false)
                AdminState.receiverHost = ""
                AdminState.status = "الشاشة غير متاحة"
                onStateChanged()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                AdminState.discovering.set(false)
                onStateChanged()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                running.set(false)
                AdminState.discovering.set(false)
                AdminState.status = "تعذر اكتشاف الشاشة"
                AdminState.error = "NSD error $errorCode"
                onStateChanged()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w("SophyNSD", "Stop discovery failed: $errorCode")
            }
        }
        listener = l
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (t: Throwable) {
            running.set(false)
            AdminState.discovering.set(false)
            AdminState.status = "تعذر تشغيل اكتشاف الشبكة"
            AdminState.error = t.message ?: "NSD error"
            onStateChanged()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        listener?.let {
            try { nsd.stopServiceDiscovery(it) } catch (_: Throwable) {}
        }
        listener = null
        AdminState.discovering.set(false)
    }
}
