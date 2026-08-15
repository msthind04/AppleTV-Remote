package dev.atvremote.protocol.hap

/** TLV8 tag values from the HomeKit Accessory Protocol pairing spec. */
object TlvValue {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val SEQ_NO = 0x06
    const val ERROR = 0x07
    const val BACK_OFF = 0x08
    const val CERTIFICATE = 0x09
    const val SIGNATURE = 0x0A
    const val PERMISSIONS = 0x0B
    const val FRAGMENT_DATA = 0x0C
    const val FRAGMENT_LAST = 0x0D

    // Apple extensions beyond the published HAP spec.
    const val NAME = 0x11
    const val FLAGS = 0x13
}

enum class HapError(val code: Int) {
    UNKNOWN(0x01),
    AUTHENTICATION(0x02),
    BACK_OFF(0x03),
    MAX_PEERS(0x04),
    MAX_TRIES(0x05),
    UNAVAILABLE(0x06),
    BUSY(0x07);

    companion object {
        fun from(code: Int): HapError? = entries.firstOrNull { it.code == code }
    }
}

/**
 * TLV8 codec.
 *
 * A value longer than 255 bytes is emitted as several consecutive records
 * sharing one tag; the reader concatenates them back into a single value.
 */
object Tlv8 {

    fun write(entries: Map<Int, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((tag, value) in entries) {
            // A zero-length value emits no record at all, matching the
            // encoder Apple devices interoperate with.
            var pos = 0
            while (pos < value.size) {
                val chunk = minOf(value.size - pos, 255)
                out.write(tag)
                out.write(chunk)
                out.write(value, pos, chunk)
                pos += chunk
            }
        }
        return out.toByteArray()
    }

    fun read(data: ByteArray): Map<Int, ByteArray> {
        val result = LinkedHashMap<Int, ByteArray>()
        var pos = 0
        while (pos + 1 < data.size) {
            val tag = data[pos].toInt() and 0xFF
            val length = data[pos + 1].toInt() and 0xFF
            require(pos + 2 + length <= data.size) { "TLV8 truncated at offset $pos" }
            val value = data.copyOfRange(pos + 2, pos + 2 + length)
            result[tag] = result[tag]?.plus(value) ?: value
            pos += 2 + length
        }
        return result
    }

    /** Human-readable summary, used in error paths and protocol logging. */
    fun describe(tlv: Map<Int, ByteArray>): String = tlv.entries.joinToString(", ") { (tag, v) ->
        when (tag) {
            TlvValue.SEQ_NO -> "SeqNo=M${v.firstOrNull()?.toInt() ?: 0}"
            TlvValue.ERROR -> {
                val code = v.firstOrNull()?.toInt() ?: 0
                "Error=${HapError.from(code)?.name ?: "0x${code.toString(16)}"}"
            }
            TlvValue.BACK_OFF -> "BackOff=${v.fold(0L) { a, b -> a * 256 + (b.toInt() and 0xFF) }}s"
            TlvValue.METHOD -> "Method=${v.firstOrNull()?.toInt() ?: 0}"
            else -> "0x${tag.toString(16)}=${v.size}bytes"
        }
    }
}
