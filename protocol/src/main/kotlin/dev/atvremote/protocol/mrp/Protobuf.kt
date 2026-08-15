package dev.atvremote.protocol.mrp

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf wire-format codec.
 *
 * MRP defines dozens of message types, but now-playing needs only a handful of
 * fields out of a few of them. Rather than pull protoc and generated sources
 * into an Android build, this reads the wire format generically and lets
 * callers pluck fields by number. Unknown fields are preserved rather than
 * rejected, which also makes it tolerant of tvOS revisions.
 */
sealed interface ProtoField {
    data class Varint(val value: Long) : ProtoField
    data class Fixed64(val value: Long) : ProtoField
    data class Bytes(val value: ByteArray) : ProtoField {
        override fun equals(other: Any?) = other is Bytes && value.contentEquals(other.value)
        override fun hashCode() = value.contentHashCode()
    }
    data class Fixed32(val value: Int) : ProtoField
}

class ProtoMessage(val fields: Map<Int, List<ProtoField>>) {

    fun has(number: Int): Boolean = fields.containsKey(number)

    fun varint(number: Int): Long? =
        (fields[number]?.firstOrNull() as? ProtoField.Varint)?.value

    fun bool(number: Int): Boolean? = varint(number)?.let { it != 0L }

    fun bytes(number: Int): ByteArray? =
        (fields[number]?.firstOrNull() as? ProtoField.Bytes)?.value

    fun string(number: Int): String? = bytes(number)?.toString(Charsets.UTF_8)

    fun double(number: Int): Double? {
        val f = fields[number]?.firstOrNull()
        return when (f) {
            is ProtoField.Fixed64 -> Double.fromBits(f.value)
            is ProtoField.Fixed32 -> Float.fromBits(f.value).toDouble()
            is ProtoField.Varint -> f.value.toDouble()
            else -> null
        }
    }

    fun message(number: Int): ProtoMessage? = bytes(number)?.let { Protobuf.decode(it) }

    fun messages(number: Int): List<ProtoMessage> =
        fields[number].orEmpty().filterIsInstance<ProtoField.Bytes>()
            .mapNotNull { runCatching { Protobuf.decode(it.value) }.getOrNull() }

    /** Field numbers present, useful when exploring an unfamiliar message. */
    override fun toString(): String =
        "ProtoMessage(fields=${fields.keys.sorted()})"
}

object Protobuf {

    fun decode(data: ByteArray): ProtoMessage {
        val fields = LinkedHashMap<Int, MutableList<ProtoField>>()
        var pos = 0

        while (pos < data.size) {
            val (tag, tagLen) = readVarint(data, pos)
            pos += tagLen
            val number = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            if (number == 0) break

            val field: ProtoField = when (wireType) {
                0 -> {
                    val (v, len) = readVarint(data, pos)
                    pos += len
                    ProtoField.Varint(v)
                }
                1 -> {
                    val v = readFixed(data, pos, 8)
                    pos += 8
                    ProtoField.Fixed64(v)
                }
                2 -> {
                    val (len, lenLen) = readVarint(data, pos)
                    pos += lenLen
                    val end = (pos + len.toInt()).coerceAtMost(data.size)
                    val value = data.copyOfRange(pos, end)
                    pos = end
                    ProtoField.Bytes(value)
                }
                5 -> {
                    val v = readFixed(data, pos, 4).toInt()
                    pos += 4
                    ProtoField.Fixed32(v)
                }
                // Groups (3/4) are obsolete and unused by MRP.
                else -> break
            }
            fields.getOrPut(number) { mutableListOf() }.add(field)
        }
        return ProtoMessage(fields)
    }

    fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = offset
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return result to (i - offset)
            shift += 7
            if (shift > 63) break
        }
        throw IllegalArgumentException("invalid varint at $offset")
    }

    fun writeVarint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return out.toByteArray()
            }
            out.write(b or 0x80)
        }
    }

    private fun readFixed(data: ByteArray, offset: Int, bytes: Int): Long {
        var v = 0L
        for (i in 0 until bytes) v = v or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    /** Builder for the few messages we need to send. */
    class Builder {
        private val out = ByteArrayOutputStream()

        fun varint(number: Int, value: Long) = apply {
            out.write(writeVarint((number.toLong() shl 3) or 0))
            out.write(writeVarint(value))
        }

        fun bool(number: Int, value: Boolean) = varint(number, if (value) 1L else 0L)

        fun bytes(number: Int, value: ByteArray) = apply {
            out.write(writeVarint((number.toLong() shl 3) or 2))
            out.write(writeVarint(value.size.toLong()))
            out.write(value)
        }

        fun string(number: Int, value: String) = bytes(number, value.toByteArray(Charsets.UTF_8))

        fun message(number: Int, builder: Builder) = bytes(number, builder.build())

        fun build(): ByteArray = out.toByteArray()
    }
}
