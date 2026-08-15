package dev.atvremote.protocol.companion

import dev.atvremote.protocol.Log
import dev.atvremote.protocol.hap.Credentials
import dev.atvremote.protocol.hap.PairSetupSession
import dev.atvremote.protocol.hap.PairVerifySession
import dev.atvremote.protocol.hap.HapError
import dev.atvremote.protocol.hap.Tlv8
import dev.atvremote.protocol.hap.TlvValue
import dev.atvremote.protocol.opack.Opack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PairingException(message: String) : Exception(message)
class ProtocolException(message: String) : Exception(message)

/** Message kinds carried inside an OPACK frame. */
private const val MSG_EVENT = 1L
private const val MSG_REQUEST = 2L
private const val MSG_RESPONSE = 3L

/**
 * Companion Link client: pairing, session establishment and request/response.
 *
 * Two distinct handshakes exist. Pair-setup runs once, is driven by the PIN
 * shown on screen, and yields long-term [Credentials]. Pair-verify runs on
 * every connection, proves possession of those credentials, and produces the
 * per-session ChaCha20 keys.
 */
class CompanionClient(
    val host: String,
    val port: Int,
    private val scope: CoroutineScope,
) {
    private val connection = CompanionConnection(host, port, scope)
    private val xid = AtomicInteger(1)

    /** Pending replies keyed by XID (OPACK) or frame type (auth handshakes). */
    private val pending = ConcurrentHashMap<Any, CompletableDeferred<Map<Any?, Any?>>>()

    var onEvent: ((String, Map<Any?, Any?>) -> Unit)? = null
    var onDisconnect: ((Throwable?) -> Unit)? = null

    val isConnected: Boolean get() = connection.isConnected

    suspend fun connect() {
        connection.onFrame = ::handleFrame
        connection.onClosed = { cause ->
            pending.values.forEach { it.completeExceptionally(cause ?: ProtocolException("closed")) }
            pending.clear()
            onDisconnect?.invoke(cause)
        }
        connection.connect()
    }

    fun close() = connection.close()

    /**
     * Send an empty NoOp frame.
     *
     * The Apple TV drops a Companion connection that has been idle for a while
     * even while it is awake and playing, so something has to keep it warm.
     * NoOp exists for exactly this and has no side effects on the device.
     */
    fun sendNoOp() = connection.send(FrameType.NO_OP, ByteArray(0))

    // ------------------------------------------------------------ framing

    private fun handleFrame(frame: Frame) {
        if (frame.payload.isEmpty()) {
            Log.d { "ignoring empty ${frame.type} frame" }
            return
        }
        val decoded = runCatching { Opack.unpack(frame.payload) }
            .onFailure { e -> Log.d { "OPACK decode failed for ${frame.type}: $e :: ${Log.hex(frame.payload, 512)}" } }
            .getOrNull() as? Map<*, *>
        if (decoded == null) {
            Log.d { "undecodable ${frame.type} payload: ${Log.hex(frame.payload, 512)}" }
            return
        }
        Log.d { "<< decoded ${frame.type}: $decoded" }

        @Suppress("UNCHECKED_CAST")
        val map = decoded as Map<Any?, Any?>

        when (frame.type) {
            FrameType.PS_NEXT, FrameType.PV_NEXT -> {
                val waiter = pending.remove(frame.type)
                if (waiter == null) Log.d { "no waiter for ${frame.type}" } else waiter.complete(map)
            }
            FrameType.E_OPACK, FrameType.U_OPACK, FrameType.P_OPACK -> {
                when (map["_t"] as? Long) {
                    MSG_EVENT -> {
                        val name = map["_i"] as? String ?: return
                        @Suppress("UNCHECKED_CAST")
                        onEvent?.invoke(name, (map["_c"] as? Map<Any?, Any?>) ?: emptyMap())
                    }
                    MSG_RESPONSE -> {
                        val id = (map["_x"] as? Long)?.toInt() ?: return
                        pending.remove(id)?.complete(map)
                    }
                }
            }
            else -> Log.d { "unhandled frame type ${frame.type}" }
        }
    }

    private suspend fun exchange(
        type: FrameType,
        payload: Map<String, Any?>,
        key: Any,
        timeoutMs: Long = 10_000,
    ): Map<Any?, Any?> {
        val deferred = CompletableDeferred<Map<Any?, Any?>>()
        pending[key] = deferred
        try {
            connection.send(type, Opack.pack(payload))
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(key)
            throw ProtocolException("timed out waiting for response to $type")
        }
    }

    /** Send a request and await its response, correlated by XID. */
    suspend fun request(
        identifier: String,
        content: Map<String, Any?> = emptyMap(),
        timeoutMs: Long = 10_000,
    ): Map<Any?, Any?> {
        val id = xid.getAndIncrement()
        val response = exchange(
            FrameType.E_OPACK,
            mapOf("_i" to identifier, "_t" to MSG_REQUEST, "_c" to content, "_x" to id),
            id,
            timeoutMs,
        )
        (response["_em"] as? String)?.let { throw ProtocolException("$identifier failed: $it") }
        return response
    }

    /** Fire-and-forget event; the device sends no reply. */
    fun sendEvent(identifier: String, content: Map<String, Any?> = emptyMap()) {
        connection.send(
            FrameType.E_OPACK,
            Opack.pack(
                mapOf(
                    "_i" to identifier,
                    "_t" to MSG_EVENT,
                    "_c" to content,
                    "_x" to xid.getAndIncrement(),
                )
            ),
        )
    }

    // ----------------------------------------------------------- pairing

    /**
     * Extract the TLV8 pairing blob from a Companion frame. Error handling
     * lives in the shared pairing sessions, which understand HAP status codes.
     */
    private fun pairingData(response: Map<Any?, Any?>): Map<Int, ByteArray> {
        val raw = response["_pd"] as? ByteArray
            ?: throw PairingException("device response contained no pairing data")
        return Tlv8.read(raw)
    }

    private suspend fun authExchange(
        type: FrameType,
        tlv: Map<Int, ByteArray>,
        extra: Map<String, Any?> = emptyMap(),
        timeoutMs: Long = 20_000,
    ): Map<Int, ByteArray> {
        val waitFor = if (type == FrameType.PS_START || type == FrameType.PS_NEXT) {
            FrameType.PS_NEXT
        } else {
            FrameType.PV_NEXT
        }
        val payload = LinkedHashMap<String, Any?>()
        payload["_pd"] = Tlv8.write(tlv)
        payload.putAll(extra)
        payload["_x"] = xid.getAndIncrement()
        return pairingData(exchange(type, payload, waitFor, timeoutMs))
    }

    /**
     * Begin pair-setup. The Apple TV displays a 4-digit PIN in response to
     * this; feed it to [PairingSession.complete].
     */
    suspend fun startPairing(deviceName: String = "Android Remote"): PairingSession {
        val setup = PairSetupSession(deviceName)
        val m2 = authExchange(
            FrameType.PS_START, setup.startRequest(), mapOf("_pwTy" to 1)
        )
        return PairingSession(setup, m2)
    }

    inner class PairingSession internal constructor(
        private val setup: PairSetupSession,
        private val m2: Map<Int, ByteArray>,
    ) {
        /** Complete pair-setup with the PIN shown on the TV. */
        suspend fun complete(pin: String): Credentials {
            require(pin.isNotBlank()) { "PIN must not be blank" }
            val m4 = authExchange(
                FrameType.PS_NEXT, setup.proofRequest(m2, pin), mapOf("_pwTy" to 1)
            )
            val m6 = authExchange(
                FrameType.PS_NEXT, setup.exchangeRequest(m4), mapOf("_pwTy" to 1)
            )
            return setup.finish(m6)
        }
    }

    /**
     * Run pair-verify with stored credentials and switch the connection to
     * encrypted operation. Must be called before any [request].
     */
    suspend fun authenticate(credentials: Credentials) {
        val verify = PairVerifySession(credentials)
        val m2 = authExchange(
            FrameType.PV_START, verify.startRequest(), mapOf("_auTy" to 4), timeoutMs = 10_000
        )
        authExchange(FrameType.PV_NEXT, verify.finishRequest(m2), timeoutMs = 10_000)

        val (outputKey, inputKey) = verify.encryptionKeys(
            SRP_SALT, SRP_OUTPUT_INFO, SRP_INPUT_INFO
        )
        connection.enableEncryption(outputKey, inputKey)
    }

    private companion object {
        const val SRP_SALT = ""
        const val SRP_OUTPUT_INFO = "ClientEncrypt-main"
        const val SRP_INPUT_INFO = "ServerEncrypt-main"
    }
}
