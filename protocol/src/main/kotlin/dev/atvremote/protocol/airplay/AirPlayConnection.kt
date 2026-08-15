package dev.atvremote.protocol.airplay

import dev.atvremote.protocol.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

data class HttpResponse(
    val code: Int,
    val message: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val isSuccess: Boolean get() = code in 200..299

    override fun equals(other: Any?): Boolean =
        other is HttpResponse && code == other.code && body.contentEquals(other.body)

    override fun hashCode(): Int = code * 31 + body.contentHashCode()
}

/**
 * Control connection to an AirPlay receiver (port 7000).
 *
 * The same socket carries three things in sequence: HTTP POSTs for the HAP
 * pairing handshakes, then RTSP-style SETUP/RECORD requests, and finally
 * periodic feedback. Once pair-verify completes, everything after it is wrapped
 * in HAP block encryption, which [HapSession] handles transparently.
 */
class AirPlayConnection(private val host: String, private val port: Int) {

    private var socket: Socket? = null
    private val session = HapSession()
    private var buffer = ByteArray(0)

    private var cseq = 0
    val sessionId: Long = (Math.random() * 0xFFFFFFFFL).toLong()
    private val dacpId: String = java.lang.Long.toHexString(
        (Math.random() * Long.MAX_VALUE).toLong()
    ).uppercase()
    private val activeRemote: Long = (Math.random() * 0xFFFFFFFFL).toLong()

    var localIp: String = ""
        private set

    val remoteIp: String get() = host

    /** URI used for RTSP-style session requests. */
    val sessionUri: String get() = "rtsp://$localIp/$sessionId"

    suspend fun connect(timeoutMs: Int = 5000) = withContext(Dispatchers.IO) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
        localIp = (s.localAddress?.hostAddress) ?: ""
    }

    fun enableEncryption(outputKey: ByteArray, inputKey: ByteArray) =
        session.enable(outputKey, inputKey)

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    suspend fun exchange(
        method: String,
        uri: String? = null,
        protocol: String = "RTSP/1.0",
        headers: Map<String, Any> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val s = socket ?: throw IOException("not connected")
        val target = uri ?: sessionUri

        val allHeaders = LinkedHashMap<String, Any>()
        allHeaders["CSeq"] = cseq++
        allHeaders["DACP-ID"] = dacpId
        allHeaders["Active-Remote"] = activeRemote
        allHeaders["Client-Instance"] = dacpId
        allHeaders["User-Agent"] = USER_AGENT
        allHeaders.putAll(headers)
        contentType?.let { allHeaders["Content-Type"] = it }
        allHeaders["Content-Length"] = body?.size ?: 0

        val head = buildString {
            append("$method $target $protocol\r\n")
            allHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }

        val request = ByteArrayOutputStream().apply {
            write(head.toByteArray(Charsets.UTF_8))
            body?.let { write(it) }
        }.toByteArray()

        Log.d { ">> $method $target (${request.size} bytes)" }
        s.getOutputStream().write(session.encrypt(request))
        s.getOutputStream().flush()

        readResponse()
    }

    suspend fun post(
        path: String,
        body: ByteArray? = null,
        headers: Map<String, Any> = emptyMap(),
        contentType: String = "application/octet-stream",
    ): HttpResponse = exchange(
        "POST", path, protocol = "HTTP/1.1",
        headers = headers, body = body, contentType = contentType,
    )

    // ------------------------------------------------------------- reading

    private fun readResponse(): HttpResponse {
        val s = socket ?: throw IOException("not connected")
        val input = s.getInputStream()
        val chunk = ByteArray(8192)

        while (true) {
            // Headers end at the first blank line; only then is Content-Length known.
            val headerEnd = indexOfHeaderEnd(buffer)
            if (headerEnd >= 0) {
                val headerText = String(buffer, 0, headerEnd, Charsets.UTF_8)
                val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
                val statusParts = lines.first().split(" ", limit = 3)
                val code = statusParts.getOrNull(1)?.toIntOrNull() ?: 0
                val message = statusParts.getOrNull(2) ?: ""

                val headers = lines.drop(1).mapNotNull { line ->
                    val idx = line.indexOf(':')
                    if (idx <= 0) null
                    else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                }.toMap()

                val contentLength = headers.entries
                    .firstOrNull { it.key.equals("Content-Length", true) }
                    ?.value?.toIntOrNull() ?: 0

                val bodyStart = headerEnd + 4
                if (buffer.size >= bodyStart + contentLength) {
                    val body = buffer.copyOfRange(bodyStart, bodyStart + contentLength)
                    buffer = buffer.copyOfRange(bodyStart + contentLength, buffer.size)
                    Log.d { "<< $code $message (${body.size} byte body)" }
                    return HttpResponse(code, message, headers, body)
                }
            }

            val read = input.read(chunk)
            if (read < 0) throw IOException("connection closed while reading response")
            buffer += session.decrypt(chunk.copyOfRange(0, read))
        }
    }

    private fun indexOfHeaderEnd(data: ByteArray): Int {
        for (i in 0..data.size - 4) {
            if (data[i] == CR && data[i + 1] == LF && data[i + 2] == CR && data[i + 3] == LF) {
                return i
            }
        }
        return -1
    }

    companion object {
        const val USER_AGENT = "AirPlay/550.10"
        const val BPLIST_CONTENT_TYPE = "application/x-apple-binary-plist"
        private const val CR: Byte = 0x0D
        private const val LF: Byte = 0x0A
    }
}
