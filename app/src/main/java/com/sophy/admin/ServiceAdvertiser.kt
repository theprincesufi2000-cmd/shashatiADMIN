package com.sophy.receiver

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class ServiceAdvertiser {
    companion object {
        const val SERVICE_TYPE = "_sophy._udp"
        const val SERVICE_NAME = "Sophy Receiver"
    }

    private var manager: NsdManager? = null
    private var registered = false
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun start(context: android.content.Context) {
        if (registered) return
        manager = context.getSystemService(NsdManager::class.java)
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = NetworkReceiver.PORT
            setAttribute("app", "sophy")
            setAttribute("version", "1")
            setAttribute("codec", "pcm16")
            setAttribute("rate", Protocol.SAMPLE_RATE.toString())
            setAttribute("channels", Protocol.CHANNELS.toString())
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registered = false
                Log.e("SophyNSD", "Registration failed: $errorCode")
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registered = false
                Log.e("SophyNSD", "Unregistration failed: $errorCode")
            }
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registered = true
                Log.i("SophyNSD", "Advertised as ${serviceInfo.serviceName}:${serviceInfo.port}")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                registered = false
            }
        }
        try {
            manager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (t: Throwable) {
            Log.e("SophyNSD", "Unable to advertise", t)
        }
    }

    fun stop() {
        try {
            registrationListener?.let { manager?.unregisterService(it) }
        } catch (_: Throwable) {}
        registrationListener = null
        registered = false
    }
}
