package dev.atvremote.protocol.companion

import dev.atvremote.protocol.Log
import dev.atvremote.protocol.crypto.ChaChaCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Companion Link frame types. */
enum class FrameType(val value: Int) {
    UNKNOWN(0),
    NO_OP(1),
    PS_START(3),
    PS_NEXT(4),
    PV_START(5),
    PV_NEXT(6),
    U_OPACK(7),
    E_OPACK(8),
    P_OPACK(9),
    PA_REQ(10),
    PA_RSP(11),
    SESSION_START_REQUEST(16),
    SESSION_START_RESPONSE(17),
    SESSION_DATA(18),
    FAMILY_IDENTITY_REQUEST(32),
    FAMILY_IDENTITY_RESPONSE(33),
    FAMILY_IDENTITY_UPDATE(34);

    companion object {
        private val byValue = entries.associateBy { it.value }
        fun from(value: Int): FrameType = byValue[value] ?: UNKNOWN
    }
}

class Frame(val type: FrameType, val payload: ByteArray)

/**
 * Raw framed transport for the Companion Link protocol.
 *
 * Every frame is a 4-byte header (1 type byte, 3 big-endian length bytes)
 * followed by the payload. Once a session is verified, payloads are encrypted
 * with ChaCha20-Poly1305 using the header as additional authenticated data, and
 * the length field grows to include the 16-byte auth tag.
 */
class CompanionConnection(
    private val host: String,
    private val port: Int,
    private val scope: CoroutineScope,
) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var readerJob: Job? = null
    private var cipher: ChaChaCipher? = null

    /** Guards the outbound path; the cipher's counter must advance in order. */
    private val sendLock = Any()

    var onFrame: ((Frame) -> Unit)? = null
    var onClosed: ((Throwable?) -> Unit)? = null

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    suspend fun connect(timeoutMs: Int = 5000) = withContext(Dispatchers.IO) {
        val s = Socket()
        s.tcpNoDelay = true
        // Lets the OS notice a peer that vanished without a clean shutdown.
        s.keepAlive = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
        input = DataInputStream(s.getInputStream().buffered())
        readerJob = scope.launch(Dispatchers.IO) { readLoop() }
    }

    fun enableEncryption(outputKey: ByteArray, inputKey: ByteArray) {
        cipher = ChaChaCipher(outputKey, inputKey, nonceLength = 12)
    }

    fun send(type: FrameType, payload: ByteArray) {
        val out = socket?.getOutputStream() ?: throw IOException("not connected")
        try {
            sendLocked(out, type, payload)
        } catch (e: IOException) {
            // The reader blocks in readFully, so when the peer vanishes without
            // a clean FIN it never notices; the write is what discovers the
            // dead connection. Tear down here rather than leaving the UI
            // believing it is still connected.
            Log.d { "send failed, treating connection as lost: $e" }
            close()
            onClosed?.invoke(e)
            throw e
        }
    }

    private fun sendLocked(out: java.io.OutputStream, type: FrameType, payload: ByteArray) {
        synchronized(sendLock) {
            val c = cipher
            val declaredLength =
                if (c != null && payload.isNotEmpty()) payload.size + ChaChaCipher.TAG_LENGTH
                else payload.size

            val header = byteArrayOf(
                type.value.toByte(),
                ((declaredLength shr 16) and 0xFF).toByte(),
                ((declaredLength shr 8) and 0xFF).toByte(),
                (declaredLength and 0xFF).toByte(),
            )

            val body =
                if (c != null && payload.isNotEmpty()) c.encrypt(payload, aad = header)
                else payload

            Log.d { ">> $type ${Log.hex(payload)}" }
            out.write(header + body)
            out.flush()
        }
    }


    private fun readLoop() {
        val stream = input ?: return
        try {
            val header = ByteArray(4)
            while (true) {
                stream.readFully(header)
                val length = ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)

                val body = ByteArray(length)
                if (length > 0) stream.readFully(body)

                val c = cipher
                val payload =
                    if (c != null && body.isNotEmpty()) c.decrypt(body, aad = header)
                    else body

                val type = FrameType.from(header[0].toInt() and 0xFF)
                Log.d { "<< $type ${Log.hex(payload)}" }
                onFrame?.invoke(Frame(type, payload))
            }
        } catch (e: Throwable) {
            Log.d { "read loop ended: $e" }
            if (socket?.isClosed != true) onClosed?.invoke(e) else onClosed?.invoke(null)
        }
    }

    fun close() {
        readerJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        input = null
        cipher = null
    }
}
