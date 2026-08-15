package dev.atvremote.protocol.opack

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * OPACK is Apple's internal binary serialization format, used as the payload
 * encoding for every Companion Link frame.
 *
 * The format is self-describing: a leading tag byte encodes both the type and,
 * for small values, the value or length inline. Larger values spill into an
 * explicit little-endian length prefix.
 *
 * Encoders additionally keep an "object list": any multi-byte encoded value may
 * be replaced by a back-reference to an earlier identical value. Apple devices
 * emit these references, so the decoder must support them. We emit them too,
 * matching the reference implementation's behaviour so byte output is
 * comparable.
 */
object Opack {

    fun pack(value: Any?): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        pack(value, out, mutableListOf())
        return out.toByteArray()
    }

    fun unpack(data: ByteArray): Any? = Decoder(data).let {
        val v = it.decode(mutableListOf())
        v
    }

    /** Decode and also report how many bytes were consumed. */
    fun unpackWithRemaining(data: ByteArray): Pair<Any?, ByteArray> {
        val d = Decoder(data)
        val v = d.decode(mutableListOf())
        return v to data.copyOfRange(d.pos, data.size)
    }

    // ---------------------------------------------------------------- encode

    private fun pack(value: Any?, sink: java.io.ByteArrayOutputStream, objects: MutableList<ByteArray>) {
        val encoded = encode(value, objects)

        // Back-reference if we've emitted this exact encoding before.
        val idx = objects.indexOfFirst { it.contentEquals(encoded) }
        if (idx >= 0) {
            sink.write(referenceTo(idx))
            return
        }
        if (encoded.size > 1) objects.add(encoded)
        sink.write(encoded)
    }

    private fun referenceTo(index: Int): ByteArray = when {
        index < 0x21 -> byteArrayOf((0xA0 + index).toByte())
        index <= 0xFF -> byteArrayOf(0xC1.toByte(), index.toByte())
        index <= 0xFFFF -> byteArrayOf(0xC2.toByte()) + le(index.toLong(), 2)
        else -> byteArrayOf(0xC3.toByte()) + le(index.toLong(), 4)
    }

    private fun encode(value: Any?, objects: MutableList<ByteArray>): ByteArray = when (value) {
        null -> byteArrayOf(0x04)
        is Boolean -> byteArrayOf(if (value) 0x01 else 0x02)
        is UUID -> byteArrayOf(0x05) + uuidBytes(value)
        is Float -> byteArrayOf(0x35) + ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
        is Double -> byteArrayOf(0x36) + ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()
        is Byte, is Short, is Int, is Long -> encodeInt((value as Number).toLong())
        is String -> encodeString(value)
        is ByteArray -> encodeBytes(value)
        is List<*> -> encodeList(value, objects)
        is Map<*, *> -> encodeMap(value, objects)
        else -> throw IllegalArgumentException("OPACK cannot encode ${value::class}")
    }

    private fun encodeInt(v: Long): ByteArray = when {
        // OPACK integers are unsigned. Encoding a negative value would
        // silently wrap (-10 would read back as 246), so refuse it: callers
        // wanting a signed quantity must use a Double, which is what the
        // device expects for things like relative seek offsets.
        v < 0 -> throw IllegalArgumentException(
            "OPACK cannot encode negative integer $v; use a Double instead"
        )
        v < 0x28 -> byteArrayOf((v + 8).toByte())
        v <= 0xFF -> byteArrayOf(0x30) + le(v, 1)
        v <= 0xFFFF -> byteArrayOf(0x31) + le(v, 2)
        v <= 0xFFFFFFFFL -> byteArrayOf(0x32) + le(v, 4)
        else -> byteArrayOf(0x33) + le(v, 8)
    }

    private fun encodeString(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return when {
            b.size <= 0x20 -> byteArrayOf((0x40 + b.size).toByte()) + b
            b.size <= 0xFF -> byteArrayOf(0x61) + le(b.size.toLong(), 1) + b
            b.size <= 0xFFFF -> byteArrayOf(0x62) + le(b.size.toLong(), 2) + b
            b.size <= 0xFFFFFF -> byteArrayOf(0x63) + le(b.size.toLong(), 3) + b
            else -> byteArrayOf(0x64) + le(b.size.toLong(), 4) + b
        }
    }

    private fun encodeBytes(b: ByteArray): ByteArray = when {
        b.size <= 0x20 -> byteArrayOf((0x70 + b.size).toByte()) + b
        b.size <= 0xFF -> byteArrayOf(0x91.toByte()) + le(b.size.toLong(), 1) + b
        b.size <= 0xFFFF -> byteArrayOf(0x92.toByte()) + le(b.size.toLong(), 2) + b
        b.size <= 0xFFFFFFFFL -> byteArrayOf(0x93.toByte()) + le(b.size.toLong(), 4) + b
        else -> byteArrayOf(0x94.toByte()) + le(b.size.toLong(), 8) + b
    }

    private fun encodeList(list: List<*>, objects: MutableList<ByteArray>): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        body.write(byteArrayOf((0xD0 + minOf(list.size, 0xF)).toByte()))
        list.forEach { pack(it, body, objects) }
        if (list.size >= 0xF) body.write(0x03)
        return body.toByteArray()
    }

    private fun encodeMap(map: Map<*, *>, objects: MutableList<ByteArray>): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        body.write(byteArrayOf((0xE0 + minOf(map.size, 0xF)).toByte()))
        map.forEach { (k, v) ->
            pack(k, body, objects)
            pack(v, body, objects)
        }
        if (map.size >= 0xF) body.write(0x03)
        return body.toByteArray()
    }

    private fun le(value: Long, bytes: Int): ByteArray =
        ByteArray(bytes) { i -> ((value shr (8 * i)) and 0xFF).toByte() }

    private fun uuidBytes(u: UUID): ByteArray = ByteBuffer.allocate(16)
        .putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()

    // ---------------------------------------------------------------- decode

    private class Decoder(val data: ByteArray) {
        var pos = 0

        fun u8(): Int = data[pos++].toInt() and 0xFF

        fun take(n: Int): ByteArray {
            require(pos + n <= data.size) { "OPACK truncated: need $n at $pos of ${data.size}" }
            val b = data.copyOfRange(pos, pos + n)
            pos += n
            return b
        }

        fun leInt(n: Int): Long {
            var v = 0L
            val b = take(n)
            for (i in b.indices) v = v or ((b[i].toLong() and 0xFF) shl (8 * i))
            return v
        }

        fun decode(objects: MutableList<Any?>): Any? {
            val tag = u8()
            var addToObjects = true
            val value: Any? = when {
                tag == 0x01 -> { addToObjects = false; true }
                tag == 0x02 -> { addToObjects = false; false }
                tag == 0x04 -> { addToObjects = false; null }
                tag == 0x05 -> {
                    val b = ByteBuffer.wrap(take(16))
                    UUID(b.long, b.long)
                }
                // Absolute time: surfaced as a raw little-endian integer.
                tag == 0x06 -> leInt(8)
                tag in 0x08..0x2F -> { addToObjects = false; (tag - 8).toLong() }
                tag == 0x35 -> ByteBuffer.wrap(take(4)).order(ByteOrder.LITTLE_ENDIAN).float
                tag == 0x36 -> ByteBuffer.wrap(take(8)).order(ByteOrder.LITTLE_ENDIAN).double
                (tag and 0xF0) == 0x30 -> leInt(1 shl (tag and 0xF))
                tag in 0x40..0x60 -> String(take(tag - 0x40), Charsets.UTF_8)
                tag in 0x61..0x64 -> {
                    val len = leInt(tag and 0xF).toInt()
                    String(take(len), Charsets.UTF_8)
                }
                tag in 0x70..0x90 -> take(tag - 0x70)
                tag in 0x91..0x94 -> {
                    val len = leInt(1 shl ((tag and 0xF) - 1)).toInt()
                    take(len)
                }
                (tag and 0xF0) == 0xD0 -> {
                    addToObjects = false
                    val count = tag and 0xF
                    val out = mutableListOf<Any?>()
                    if (count == 0xF) {
                        while ((data[pos].toInt() and 0xFF) != 0x03) out.add(decode(objects))
                        pos++
                    } else repeat(count) { out.add(decode(objects)) }
                    out
                }
                (tag and 0xE0) == 0xE0 -> {
                    addToObjects = false
                    val count = tag and 0xF
                    val out = LinkedHashMap<Any?, Any?>()
                    if (count == 0xF) {
                        while ((data[pos].toInt() and 0xFF) != 0x03) {
                            val k = decode(objects); out[k] = decode(objects)
                        }
                        pos++
                    } else repeat(count) {
                        val k = decode(objects); out[k] = decode(objects)
                    }
                    out
                }
                tag in 0xA0..0xC0 -> { addToObjects = false; objects[tag - 0xA0] }
                tag in 0xC1..0xC4 -> {
                    addToObjects = false
                    objects[leInt(tag - 0xC0).toInt()]
                }
                else -> throw IllegalArgumentException("Unknown OPACK tag 0x${tag.toString(16)}")
            }

            if (addToObjects && objects.none { sameValue(it, value) }) objects.add(value)
            return value
        }

        private fun sameValue(a: Any?, b: Any?): Boolean =
            if (a is ByteArray && b is ByteArray) a.contentEquals(b) else a == b
    }
}
