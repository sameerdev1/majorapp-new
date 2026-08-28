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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

private const val SERVICE_TYPE = "_majorgym._tcp."
private const val NONCE_BYTES = 32
/** Sanity cap on an incoming encrypted frame - real payloads (member JSON +
 *  photos as Base64) can legitimately run into a few MB for a big roster, but
 *  this stops a hostile/garbled peer from making us try to allocate gigabytes
 *  from a forged length prefix. */
private const val MAX_FRAME_BYTES = 64 * 1024 * 1024

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
 * APIs), filter candidates by a SHA-256 hash of the shared "sync code" sent
 * in the (unauthenticated, cleartext-by-design) NSD advertisement, then - the
 * important part - actually prove mutual possession of the real code over the
 * TCP connection itself before any member data crosses the wire at all:
 *
 *  1. Each side sends the other a random nonce.
 *  2. Each side must answer with HMAC-SHA256(key, peer's nonce), where `key`
 *     is derived from the code via PBKDF2 (never the code itself, and never
 *     reused/replayable - a fresh nonce every session). A wrong/missing
 *     answer closes the connection immediately - fix #2's "reject
 *     unauthorized clients" / "fail safely when authentication fails".
 *  3. Only once both proofs check out is a single AES-256-GCM key (derived
 *     from the same code, independent salt from the backup-file key) used to
 *     encrypt every subsequent byte in both directions - authenticated
 *     encryption, so a tampered frame is detected and rejected outright
 *     rather than silently accepted.
 *
 * A device on the same Wi-Fi that doesn't know the code can see that a sync
 * service exists (mDNS is inherently public on the LAN) but can neither pass
 * the proof step nor decrypt anything that follows it - matching fix #2's
 * requirements in full, not just "hash the sync code differently".
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
                            // Only the device that sorts first by ID dials out; the other
                            // side only accepts. Without this, both phones can each open a
                            // separate connection to the other at the same time, and each
                            // ends up talking on a different socket than its peer - one side
                            // then blocks waiting for data that's arriving on the other,
                            // unused connection ("Broken pipe" once it's torn down).
                            if (prefs.deviceId >= peerId) return
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

            socket.soTimeout = 10_000
            onStatus("Connected \u2014 authenticating\u2026")
            performExchange(socket, code)
        } catch (e: Exception) {
            SyncOutcome.Error(e.message ?: "Sync failed")
        } finally {
            try { discoveryListener?.let { nsdManager.stopServiceDiscovery(it) } } catch (_: Exception) {}
            try { registrationListener?.let { nsdManager.unregisterService(it) } } catch (_: Exception) {}
            try { serverSocket?.close() } catch (_: Exception) {}
            try { multicastLock.release() } catch (_: Exception) {}
        }
    }

    private suspend fun performExchange(socket: Socket, syncCode: String): SyncOutcome = try {
        socket.use { s ->
            val out = DataOutputStream(s.getOutputStream())
            val input = DataInputStream(s.getInputStream())
            val channelKey = CryptoUtils.deriveSyncChannelKey(syncCode)

            // --- Mutual challenge/response: prove possession of the real
            // sync code (never sent itself) before anything else happens. ---
            val myNonce = CryptoUtils.randomBytes(NONCE_BYTES)
            writeFrame(out, myNonce)
            val peerNonce = readFrame(input) ?: return SyncOutcome.Error("Connection closed during authentication")

            val myProof = CryptoUtils.hmacSha256(channelKey, peerNonce)
            writeFrame(out, myProof)
            val peerProof = readFrame(input) ?: return SyncOutcome.Error("Connection closed during authentication")
            val expectedPeerProof = CryptoUtils.hmacSha256(channelKey, myNonce)
            if (!CryptoUtils.constantTimeEquals(peerProof, expectedPeerProof)) {
                // Wrong/missing sync code on the other end - fail safely,
                // exchange nothing further.
                return SyncOutcome.Error("The other device's sync code doesn't match")
            }

            // --- From here on, every frame is AES-256-GCM encrypted under
            // the same code-derived key - confidentiality AND tamper
            // detection for the actual member data, per fix #2/#8. Templates
            // travel as plaintext-within-this-encrypted-frame (not a second,
            // weaker encryption layer inside the JSON) - the receiving device
            // re-encrypts them at rest with its own Keystore key the moment
            // Repository.mergeAll saves them. ---
            val members = repository.allOnce()
            val myPayload = JSONObject().apply {
                put("deviceId", prefs.deviceId)
                put("deviceName", prefs.deviceName)
                put("data", JSONObject(exportJsonWithPlainTemplates(members)))
            }

            val encryptedOut = CryptoUtils.aesGcmEncrypt(myPayload.toString().toByteArray(Charsets.UTF_8), channelKey)
            writeFrame(out, encryptedOut)

            val encryptedIn = readFrame(input) ?: return SyncOutcome.Error("Connection closed while exchanging records")
            val decrypted = try {
                CryptoUtils.aesGcmDecrypt(encryptedIn, channelKey)
            } catch (e: Exception) {
                // GCM auth failure = tampered or corrupted in transit.
                return SyncOutcome.Error("The received data failed integrity verification")
            }
            val peerPayload = JSONObject(String(decrypted, Charsets.UTF_8))
            val peerId = peerPayload.getString("deviceId")
            val peerName = peerPayload.optString("deviceName", "Unknown device")
            val peerData = peerPayload.getJSONObject("data")

            val incoming = BackupManager.importJson(context, peerData.toString())
            repository.mergeAll(incoming)
            prefs.recordSync(peerId, peerName)

            SyncOutcome.Success(peerName, incoming.size)
        }
    } catch (e: java.net.SocketTimeoutException) {
        SyncOutcome.Error("Timed out waiting for the other phone - try again")
    } catch (e: Exception) {
        SyncOutcome.Error(e.message ?: "Sync failed")
    }

    /** [BackupManager.exportJson] already embeds fingerprint templates as
     *  plain Base64 under "fingerprintTemplateBase64" (fix #1) - safe here
     *  specifically because the whole frame this gets embedded in is already
     *  AES-GCM encrypted before it touches the socket (see [performExchange]).
     *  [BackupManager.importJson] reads that same key on the way in, so no
     *  separate parser is needed for the sync path either. */
    private fun exportJsonWithPlainTemplates(members: List<Member>): String =
        BackupManager.exportJson(context, members)

    /** [4-byte big-endian length][payload]. Rejects an implausible length up
     *  front instead of trying to allocate/read it. */
    private fun writeFrame(out: DataOutputStream, payload: ByteArray) {
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    private fun readFrame(input: DataInputStream): ByteArray? = try {
        val len = input.readInt()
        if (len < 0 || len > MAX_FRAME_BYTES) null
        else ByteArray(len).also { input.readFully(it) }
    } catch (e: Exception) {
        null
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
