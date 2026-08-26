package com.localconnect.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.localconnect.app.model.Peer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "NsdDiscoveryManager"
private const val SERVICE_TYPE = "_localconnect._tcp."

/**
 * Quảng bá thiết bị của mình lên mạng LAN (Wi-Fi hotspot 2.4GHz) và tìm các thiết bị
 * khác đang chạy LocalConnect trong cùng mạng, dùng Network Service Discovery (mDNS).
 * Không cần Internet, chỉ cần cùng một mạng Wi-Fi.
 */
class NsdDiscoveryManager(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registeredServiceName: String = ""

    private val _discoveredPeers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val discoveredPeers: StateFlow<Map<String, Peer>> = _discoveredPeers

    fun startAdvertising(myId: String, myName: String, port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "LocalConnect-$myId"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", myId)
            setAttribute("name", myName)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                Log.i(TAG, "Đã quảng bá dịch vụ: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Đăng ký NSD thất bại: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun startDiscovery(myId: String) {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Bắt đầu tìm thiết bị trong mạng...")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return
                if (service.serviceName == "LocalConnect-$myId") return // bỏ qua chính mình
                resolve(service, myId)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val lostId = service.serviceName.removePrefix("LocalConnect-")
                _discoveredPeers.update { it - lostId }
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Tìm kiếm thất bại: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun resolve(service: NsdServiceInfo, myId: String) {
        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Không phân giải được ${info.serviceName}: $errorCode")
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val id = info.attributes["id"]?.let { String(it) }
                    ?: info.serviceName.removePrefix("LocalConnect-")
                val name = info.attributes["name"]?.let { String(it) } ?: info.serviceName
                if (id == myId) return
                val host = info.host?.hostAddress ?: return
                val peer = Peer(id = id, name = name, host = host, port = info.port)
                _discoveredPeers.update { it + (id to peer) }
                PeerHostRegistry.update(id, host)
                Log.i(TAG, "Tìm thấy thiết bị: $name @ $host:${info.port}")
            }
        })
    }

    fun stop() {
        try { discoveryListener?.let { nsdManager.stopServiceDiscovery(it) } } catch (_: Exception) {}
        try { registrationListener?.let { nsdManager.unregisterService(it) } } catch (_: Exception) {}
        discoveryListener = null
        registrationListener = null
    }
}
