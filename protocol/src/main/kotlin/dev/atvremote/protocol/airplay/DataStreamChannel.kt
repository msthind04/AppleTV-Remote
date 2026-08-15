package dev.atvremote.protocol.airplay

import dev.atvremote.protocol.Log
import dev.atvremote.protocol.mrp.ProtoMessage
import dev.atvremote.protocol.mrp.Protobuf
import dev.atvremote.protocol.plist.BinaryPlist
import kotlinx.coroutines.CoroutineScope
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * The AirPlay data stream channel, which is what actually carries MRP.
 *
 * Framing is three layers deep: a fixed 32-byte big-endian header, then a
 * binary plist whose `params.data` holds a run of varint-length-prefixed
 * protobuf messages.
 */
class DataStreamChannel(
    host: String,
    port: Int,
    outputKey: ByteArray,
    inputKey: ByteArray,
    scope: CoroutineScope,
) : HapChannel(host, port, outputKey, inputKey, scope) {

    var onProtobuf: ((ProtoMessage) -> Unit)? = null

    private var sendSeqno: Long = Random.nextLong(0x100000000L, 0x1FFFFFFFFL)

    override fun handleReceived() {
        while (buffer.size >= HEADER_LENGTH) {
            val size = ByteBuffer.wrap(buffer, 0, 4).int
            if (size < HEADER_LENGTH || buffer.size < size) return

            val messageType = buffer.copyOfRange(4, 16)
            val seqno = ByteBuffer.wrap(buffer, 20, 8).long
            val payload = buffer.copyOfRange(HEADER_LENGTH, size)
            buffer = buffer.copyOfRange(size, buffer.size)

            runCatching { processPayload(payload) }
                .onFailure { Log.d { "data channel payload failed: $it" } }

            // A "sync" frame is a request and expects an acknowledgement.
            if (messageType.copyOfRange(0, 4).contentEquals(SYNC)) {
                send(encodeMessage(RPLY + ByteArray(8), ByteArray(4), seqno, ByteArray(0)))
            }
        }
    }

    private fun processPayload(payload: ByteArray) {
        if (payload.isEmpty()) return
        val plist = BinaryPlist.read(payload) as? Map<*, *> ?: return
        val params = plist["params"] as? Map<*, *> ?: return
        val data = params["data"] as? ByteArray ?: return

        for (message in decodeProtobufs(data)) onProtobuf?.invoke(message)
    }

    /**
     * Split a run of protobuf messages.
     *
     * Normally each is varint-length-prefixed, but ConfigureConnectionMessage
     * is known to arrive bare. A leading 0x08 is the tag for field 1 (the
     * message type, always present) and is far too small to be a valid length,
     * so it reliably distinguishes the two cases.
     */
    private fun decodeProtobufs(data: ByteArray): List<ProtoMessage> {
        val result = mutableListOf<ProtoMessage>()
        var rest = data
        while (rest.isNotEmpty()) {
            val body: ByteArray
            if ((rest[0].toInt() and 0xFF) == 0x08) {
                body = rest
                rest = ByteArray(0)
            } else {
                val (length, consumed) = Protobuf.readVarint(rest, 0)
                val start = consumed
                val end = start + length.toInt()
                if (end > rest.size) break
                body = rest.copyOfRange(start, end)
                rest = rest.copyOfRange(end, rest.size)
            }
            runCatching { Protobuf.decode(body) }.getOrNull()?.let { result.add(it) }
        }
        return result
    }

    fun sendProtobuf(message: ByteArray) {
        val framed = Protobuf.writeVarint(message.size.toLong()) + message
        val payload = BinaryPlist.write(
            linkedMapOf<Any?, Any?>(
                "params" to linkedMapOf<Any?, Any?>("data" to framed)
            )
        )
        send(encodeMessage(SYNC + ByteArray(8), COMM, sendSeqno++, payload))
    }

    private fun encodeMessage(
        messageType: ByteArray,
        command: ByteArray,
        seqno: Long,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(4).putInt(HEADER_LENGTH + payload.size).array())
        out.write(messageType.copyOf(12))
        out.write(command.copyOf(4))
        out.write(ByteBuffer.allocate(8).putLong(seqno).array())
        out.write(ByteArray(4)) // padding
        out.write(payload)
        return out.toByteArray()
    }

    private companion object {
        const val HEADER_LENGTH = 32
        val SYNC = "sync".toByteArray(Charsets.US_ASCII)
        val RPLY = "rply".toByteArray(Charsets.US_ASCII)
        val COMM = "comm".toByteArray(Charsets.US_ASCII)
    }
}
