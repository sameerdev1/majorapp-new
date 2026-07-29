package com.majorgym.app.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

private const val SERVICE_TYPE = "_majorgym._tcp."

sealed class SyncOutcome {
    data class Success(val peerName: String, val recordCount: Int) : SyncOutcome()
    data object NoCodeSet : SyncOutcome()
    data object NotFound : SyncOutcome()
    data class Error(val message: String) : SyncOutcome()
}

/**
 * Local Wi-Fi/hotspot device sync - no internet, no server, no accounts.
 *
 * Devices on the same network discover each other over mDNS (Android's NSD
 * APIs), prove they belong to the same gym's sync circle by matching a
 * SHA-256 hash of a shared "sync code" (the code itself is never sent over
 * the network), then exchange their full member list once and each keep
 * whichever copy of every record was edited most recently.
 *
 * All networking is scoped to a single bounded sync attempt - it starts when
 * "Sync Now" is tapped and fully tears down (service unregistered, discovery
 * stopped, socket closed) when that attempt ends, whether it succeeds, times
 * out, or fails. Nothing runs in the background between syncs.
 */
class SyncManager(
    private val context: Context,
    private val repository: Repository,
    private val prefs: SyncPrefs
) {
    suspend fun runSync(
        timeoutMs: Long = 20_000,
        onStatus: (String) -> Unit
    ): SyncOutcome = withContext(Dispatchers.IO) {
        val code = prefs.syncCode
        if (code.isNullOrBlank()) return@withContext SyncOutcome.NoCodeSet
        val codeHash = sha256(code)

        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = wifi.createMulticastLock("majorgym_sync").apply { setReferenceCounted(true) }
        multicastLock.acquire()

        val connectedSocket = CompletableDeferred<Socket>()
        val claimed = AtomicBoolean(false)
        var serverSocket: ServerSocket? = null
        var registrationListener: NsdManager.RegistrationListener? = null
        var discoveryListener: NsdManager.DiscoveryListener? = null

        try {
            serverSocket = ServerSocket(0)
            val socketRef = serverSocket
            onStatus("Waiting for other phones on this Wi-Fi\u2026")

            // Accept an inbound connection from a peer in the background.
            Thread {
                try {
                    socketRef.soTimeout = timeoutMs.toInt()
                    val s = socketRef.accept()
                    if (claimed.compareAndSet(false, true)) {
                        connectedSocket.complete(s)
                    } else {
                        s.close()
                    }
                } catch (_: Exception) {
                    // Timeout or socket closed - expected if no one connected in time.
                }
            }.start()

            val serviceName = "majorgym-${prefs.deviceId.take(8)}"
            val serviceInfo = NsdServiceInfo().apply {
                this.serviceName = serviceName
                this.serviceType = SERVICE_TYPE
                port = serverSocket.localPort
                setAttribute("id", prefs.deviceId)
                setAttribute("code", codeHash)
                setAttribute("name", prefs.deviceName)
            }

            val registered = CompletableDeferred<Boolean>()
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) { registered.complete(true) }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { registered.complete(false) }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            registered.await()

            onStatus("Looking for authorized devices\u2026")

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}
                override fun onServiceFound(found: NsdServiceInfo) {
                    if (found.serviceName == serviceName) return
                    nsdManager.resolveService(found, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            if (claimed.get()) return
                            val attrs = info.attributes
                            val peerCodeHash = attrs["code"]?.let { String(it, Charsets.UTF_8) }
                            val peerId = attrs["id"]?.let { String(it, Charsets.UTF_8) }
                            if (peerCodeHash != codeHash) return
                            if (peerId == null || peerId == prefs.deviceId) return
                            if (!prefs.canAdd(peerId)) return
                            if (!claimed.compareAndSet(false, true)) return
                            try {
                                connectedSocket.complete(Socket(info.host, info.port))
                            } catch (e: Exception) {
                                claimed.set(false)
                            }
                        }
                    })
                }
                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

            val socket = withTimeoutOrNull(timeoutMs) { connectedSocket.await() }
                ?: return@withContext SyncOutcome.NotFound

            onStatus("Connected \u2014 exchanging records\u2026")
            performExchange(socket)
        } catch (e: Exception) {
            SyncOutcome.Error(e.message ?: "Sync failed")
        } finally {
            try { discoveryListener?.let { nsdManager.stopServiceDiscovery(it) } } catch (_: Exception) {}
            try { registrationListener?.let { nsdManager.unregisterService(it) } } catch (_: Exception) {}
            try { serverSocket?.close() } catch (_: Exception) {}
            try { multicastLock.release() } catch (_: Exception) {}
        }
    }

    private suspend fun performExchange(socket: Socket): SyncOutcome = try {
        socket.use { s ->
            val writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))

            val myPayload = JSONObject().apply {
                put("deviceId", prefs.deviceId)
                put("deviceName", prefs.deviceName)
                put("data", JSONObject(BackupManager.exportJson(context, repository.allOnce())))
            }
            writer.write(myPayload.toString())
            writer.write("\n")
            writer.flush()

            val line = reader.readLine() ?: return SyncOutcome.Error("Connection closed early")
            val peerPayload = JSONObject(line)
            val peerId = peerPayload.getString("deviceId")
            val peerName = peerPayload.optString("deviceName", "Unknown device")
            val peerData = peerPayload.getJSONObject("data")

            val incoming = BackupManager.importJson(context, peerData.toString())
            repository.mergeAll(incoming)
            prefs.recordSync(peerId, peerName)

            SyncOutcome.Success(peerName, incoming.size)
        }
    } catch (e: Exception) {
        SyncOutcome.Error(e.message ?: "Sync failed")
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
