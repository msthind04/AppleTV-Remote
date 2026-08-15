package dev.atvremote.protocol.plist

import java.io.ByteArrayOutputStream

/**
 * A CoreFoundation keyed-archiver UID.
 *
 * Distinct from a plain integer: inside an NSKeyedArchiver archive a UID is a
 * reference into the `$objects` array, and encoding one as an integer produces
 * an archive tvOS silently rejects.
 */
data class Uid(val value: Long) {
    override fun toString(): String = "UID($value)"
}

/**
 * Reader/writer for Apple's binary property list format (`bplist00`).
 *
 * Only what the RTI text-input channel needs is implemented, which is still
 * most of the format: booleans, integers, reals, strings (ASCII and UTF-16BE),
 * data, arrays, dictionaries and UIDs.
 *
 * Dictionaries round-trip in insertion order — NSKeyedArchiver archives are
 * order-sensitive in practice, so [LinkedHashMap] is used throughout.
 */
object BinaryPlist {

    private val MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)

    // ------------------------------------------------------------- writing

    fun write(root: Any?): ByteArray {
        val objects = ArrayList<Any?>()
        val dictRefs = HashMap<Int, Pair<List<Int>, List<Int>>>()
        val arrayRefs = HashMap<Int, List<Int>>()

        // Equal scalars share one entry; containers and UIDs are deduplicated
        // by identity only. This mirrors CoreFoundation's writer, and matters
        // because a repeated string like "NSObject" must collapse to a single
        // object for the output to be byte-identical.
        val scalarTable = HashMap<Any, Int>()
        val identityTable = java.util.IdentityHashMap<Any, Int>()
        var nullRef = -1

        fun scalarKey(value: Any): Any? = when (value) {
            is String -> "s" to value
            is Byte, is Short, is Int, is Long -> "i" to (value as Number).toLong()
            is Float, is Double -> "d" to (value as Number).toDouble()
            is Boolean -> "b" to value
            is ByteArray -> "y" to value.toList()
            else -> null // Uid and containers are not value-deduplicated
        }

        fun collect(value: Any?): Int {
            if (value == null) {
                if (nullRef >= 0) return nullRef
                nullRef = objects.size
                objects.add(null)
                return nullRef
            }

            val key = scalarKey(value)
            if (key != null) {
                scalarTable[key]?.let { return it }
            } else {
                identityTable[value]?.let { return it }
            }

            val id = objects.size
            objects.add(value)
            if (key != null) scalarTable[key] = id else identityTable[value] = id

            when (value) {
                is Map<*, *> -> {
                    // Keys first, then values: the format stores them as two
                    // consecutive runs of references.
                    val keys = value.keys.map { collect(it) }
                    val vals = value.values.map { collect(it) }
                    dictRefs[id] = keys to vals
                }
                is List<*> -> arrayRefs[id] = value.map { collect(it) }
                is Array<*> -> arrayRefs[id] = value.map { collect(it) }
            }
            return id
        }
        collect(root)

        val refSize = byteWidth(objects.size.toLong())
        val body = ByteArrayOutputStream()
        body.write(MAGIC)

        val offsets = LongArray(objects.size)
        for ((index, value) in objects.withIndex()) {
            offsets[index] = body.size().toLong()
            encode(value, index, body, refSize, dictRefs, arrayRefs)
        }

        val offsetTableStart = body.size().toLong()
        val offsetSize = byteWidth(offsetTableStart)
        for (offset in offsets) writeBigEndian(body, offset, offsetSize)

        // Trailer: 5 unused + sort version + offset size + ref size + counts.
        body.write(ByteArray(6))
        body.write(offsetSize)
        body.write(refSize)
        writeBigEndian(body, objects.size.toLong(), 8)
        writeBigEndian(body, 0L, 8) // root object index
        writeBigEndian(body, offsetTableStart, 8)

        return body.toByteArray()
    }

    private fun encode(
        value: Any?,
        index: Int,
        out: ByteArrayOutputStream,
        refSize: Int,
        dictRefs: Map<Int, Pair<List<Int>, List<Int>>>,
        arrayRefs: Map<Int, List<Int>>,
    ) {
        when (value) {
            null -> out.write(0x00)
            is Boolean -> out.write(if (value) 0x09 else 0x08)

            is Uid -> {
                val width = byteWidth(value.value).coerceAtLeast(1)
                out.write(0x80 or (width - 1))
                writeBigEndian(out, value.value, width)
            }

            is Byte, is Short, is Int, is Long -> {
                val v = (value as Number).toLong()
                val width = when {
                    v < 0 -> 8 // negatives are always stored as 8-byte signed
                    v <= 0xFF -> 1
                    v <= 0xFFFF -> 2
                    v <= 0xFFFFFFFFL -> 4
                    else -> 8
                }
                out.write(0x10 or log2(width))
                writeBigEndian(out, v, width)
            }

            is Float, is Double -> {
                out.write(0x23)
                writeBigEndian(out, java.lang.Double.doubleToRawLongBits((value as Number).toDouble()), 8)
            }

            is ByteArray -> {
                writeMarker(out, 0x40, value.size)
                out.write(value)
            }

            is String -> {
                if (value.all { it.code < 0x80 }) {
                    writeMarker(out, 0x50, value.length)
                    out.write(value.toByteArray(Charsets.US_ASCII))
                } else {
                    writeMarker(out, 0x60, value.length)
                    out.write(value.toByteArray(Charsets.UTF_16BE))
                }
            }

            is Map<*, *> -> {
                val (keys, vals) = dictRefs.getValue(index)
                writeMarker(out, 0xD0, keys.size)
                keys.forEach { writeBigEndian(out, it.toLong(), refSize) }
                vals.forEach { writeBigEndian(out, it.toLong(), refSize) }
            }

            is List<*>, is Array<*> -> {
                val refs = arrayRefs.getValue(index)
                writeMarker(out, 0xA0, refs.size)
                refs.forEach { writeBigEndian(out, it.toLong(), refSize) }
            }

            else -> throw IllegalArgumentException("Cannot encode ${value::class} as a plist")
        }
    }

    /** Marker byte, spilling the count into a follow-on integer when >= 15. */
    private fun writeMarker(out: ByteArrayOutputStream, type: Int, count: Int) {
        if (count < 0xF) {
            out.write(type or count)
        } else {
            out.write(type or 0xF)
            val width = byteWidth(count.toLong())
            out.write(0x10 or log2(width))
            writeBigEndian(out, count.toLong(), width)
        }
    }

    private fun writeBigEndian(out: ByteArrayOutputStream, value: Long, bytes: Int) {
        for (i in bytes - 1 downTo 0) out.write(((value shr (8 * i)) and 0xFF).toInt())
    }

    private fun byteWidth(value: Long): Int = when {
        value <= 0xFF -> 1
        value <= 0xFFFF -> 2
        value <= 0xFFFFFFFFL -> 4
        else -> 8
    }

    private fun log2(bytes: Int): Int = when (bytes) {
        1 -> 0
        2 -> 1
        4 -> 2
        else -> 3
    }

    // ------------------------------------------------------------- reading

    fun read(data: ByteArray): Any? {
        require(data.size > 40) { "plist too short (${data.size} bytes)" }
        require(data.copyOfRange(0, 8).contentEquals(MAGIC)) { "not a binary plist" }

        val trailer = data.size - 32
        val offsetSize = data[trailer + 6].toInt() and 0xFF
        val refSize = data[trailer + 7].toInt() and 0xFF
        val count = readBigEndian(data, trailer + 8, 8).toInt()
        val rootIndex = readBigEndian(data, trailer + 16, 8).toInt()
        val tableStart = readBigEndian(data, trailer + 24, 8).toInt()

        val offsets = IntArray(count) { i ->
            readBigEndian(data, tableStart + i * offsetSize, offsetSize).toInt()
        }

        val reader = Reader(data, offsets, refSize)
        return reader.obj(rootIndex)
    }

    private class Reader(val data: ByteArray, val offsets: IntArray, val refSize: Int) {

        /** Guards against a malformed archive containing a reference cycle. */
        private val visiting = HashSet<Int>()

        fun obj(index: Int): Any? {
            if (index !in offsets.indices) return null
            if (!visiting.add(index)) return null
            try {
                return decode(offsets[index])
            } finally {
                visiting.remove(index)
            }
        }

        private fun decode(pos: Int): Any? {
            val marker = data[pos].toInt() and 0xFF
            val type = marker and 0xF0
            val info = marker and 0x0F

            return when {
                marker == 0x00 -> null
                marker == 0x08 -> false
                marker == 0x09 -> true

                type == 0x10 -> readBigEndian(data, pos + 1, 1 shl info)
                type == 0x20 -> when (info) {
                    2 -> Float.fromBits(readBigEndian(data, pos + 1, 4).toInt()).toDouble()
                    else -> Double.fromBits(readBigEndian(data, pos + 1, 8))
                }
                type == 0x30 -> Double.fromBits(readBigEndian(data, pos + 1, 8)) // date

                type == 0x80 -> Uid(readBigEndian(data, pos + 1, info + 1))

                type == 0x40 -> {
                    val (len, start) = sizeAndStart(pos, info)
                    data.copyOfRange(start, start + len)
                }
                type == 0x50 -> {
                    val (len, start) = sizeAndStart(pos, info)
                    String(data, start, len, Charsets.US_ASCII)
                }
                type == 0x60 -> {
                    val (len, start) = sizeAndStart(pos, info)
                    String(data, start, len * 2, Charsets.UTF_16BE)
                }

                type == 0xA0 -> {
                    val (len, start) = sizeAndStart(pos, info)
                    (0 until len).map { obj(readBigEndian(data, start + it * refSize, refSize).toInt()) }
                }

                type == 0xD0 -> {
                    val (len, start) = sizeAndStart(pos, info)
                    val result = LinkedHashMap<Any?, Any?>()
                    for (i in 0 until len) {
                        val key = obj(readBigEndian(data, start + i * refSize, refSize).toInt())
                        val value = obj(
                            readBigEndian(data, start + (len + i) * refSize, refSize).toInt()
                        )
                        result[key] = value
                    }
                    result
                }

                else -> throw IllegalArgumentException("Unsupported plist marker 0x${marker.toString(16)}")
            }
        }

        /** Resolve a container/string length, following the spill integer if present. */
        private fun sizeAndStart(pos: Int, info: Int): Pair<Int, Int> {
            if (info != 0xF) return info to (pos + 1)
            val intMarker = data[pos + 1].toInt() and 0xFF
            val width = 1 shl (intMarker and 0x0F)
            val len = readBigEndian(data, pos + 2, width).toInt()
            return len to (pos + 2 + width)
        }
    }

    private fun readBigEndian(data: ByteArray, offset: Int, bytes: Int): Long {
        var value = 0L
        for (i in 0 until bytes) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return value
    }
}
