package dev.atvremote.protocol.airplay

import dev.atvremote.protocol.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A secondary AirPlay channel: its own TCP connection, encrypted with a key
 * pair derived from the pair-verify shared secret.
 *
 * Subclasses see a plaintext byte stream via [handleReceived] and append to
 * [buffer]; framing is their concern.
 */
abstract class HapChannel(
    private val host: String,
    private val port: Int,
    outputKey: ByteArray,
    inputKey: ByteArray,
    private val scope: CoroutineScope,
) {
    private val session = HapSession().apply { enable(outputKey, inputKey) }
    private var socket: Socket? = null
    private var readerJob: Job? = null

    /** Plaintext received so far; subclasses consume from the front. */
    protected var buffer: ByteArray = ByteArray(0)

    var onClosed: ((Throwable?) -> Unit)? = null

    val isConnected: Boolean get() = socket?.isClosed == false

    suspend fun connect(timeoutMs: Int = 5000) = withContext(Dispatchers.IO) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
        readerJob = scope.launch(Dispatchers.IO) { readLoop() }
    }

    private fun readLoop() {
        val s = socket ?: return
        val chunk = ByteArray(16384)
        try {
            val input = s.getInputStream()
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                buffer += session.decrypt(chunk.copyOfRange(0, read))
                runCatching { handleReceived() }
                    .onFailure { Log.d { "${this::class.simpleName} handler failed: $it" } }
            }
            onClosed?.invoke(null)
        } catch (e: Throwable) {
            if (socket?.isClosed != true) onClosed?.invoke(e)
        }
    }

    protected abstract fun handleReceived()

    fun send(data: ByteArray) {
        val out = socket?.getOutputStream() ?: return
        synchronized(this) {
            out.write(session.encrypt(data))
            out.flush()
        }
    }

    fun close() {
        readerJob?.cancel()
        runCatching { socket?.close() }
        socket = null
    }
}
