package dev.atvremote.protocol.airplay

import dev.atvremote.protocol.Log
import kotlinx.coroutines.CoroutineScope

/**
 * The AirPlay event channel.
 *
 * Nothing here is used for now-playing, but the receiver refuses to bring up
 * the data channel unless this exists and answers. Every request it sends gets
 * a bare 200 back.
 */
class EventChannel(
    host: String,
    port: Int,
    outputKey: ByteArray,
    inputKey: ByteArray,
    scope: CoroutineScope,
) : HapChannel(host, port, outputKey, inputKey, scope) {

    override fun handleReceived() {
        while (true) {
            val end = indexOfHeaderEnd(buffer) ?: return
            val head = String(buffer, 0, end, Charsets.UTF_8)
            buffer = buffer.copyOfRange(end + 4, buffer.size)

            val lines = head.split("\r\n").filter { it.isNotBlank() }
            val cseq = lines.firstOrNull { it.startsWith("CSeq:", true) }
                ?.substringAfter(":")?.trim()
            Log.d { "event channel <= ${lines.firstOrNull()}" }

            val response = buildString {
                append("RTSP/1.0 200 OK\r\n")
                if (cseq != null) append("CSeq: $cseq\r\n")
                append("Content-Length: 0\r\n")
                append("Audio-Latency: 0\r\n")
                append("\r\n")
            }
            send(response.toByteArray(Charsets.UTF_8))
        }
    }

    private fun indexOfHeaderEnd(data: ByteArray): Int? {
        for (i in 0..data.size - 4) {
            if (data[i] == 0x0D.toByte() && data[i + 1] == 0x0A.toByte() &&
                data[i + 2] == 0x0D.toByte() && data[i + 3] == 0x0A.toByte()
            ) return i
        }
        return null
    }
}
