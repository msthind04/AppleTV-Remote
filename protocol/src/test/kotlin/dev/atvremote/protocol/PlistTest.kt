package dev.atvremote.protocol

import dev.atvremote.protocol.companion.RtiPayloads
import dev.atvremote.protocol.hap.fromHex
import dev.atvremote.protocol.hap.toHex
import dev.atvremote.protocol.plist.BinaryPlist
import dev.atvremote.protocol.plist.Uid
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Binary plist and RTI payload conformance, again against reference bytes —
 * tvOS rejects a malformed archive silently, so equality with a known-good
 * encoder is the only cheap way to be confident.
 */
class PlistTest {

    private val vectors: Map<String, Any?> by lazy {
        val json = javaClass.getResourceAsStream("/plistvectors.json")!!.readBytes().decodeToString()
        @Suppress("UNCHECKED_CAST")
        MiniJson.parse(json) as Map<String, Any?>
    }

    private fun hex(name: String) = vectors[name] as String
    private val uuid: ByteArray by lazy { hex("uuid").fromHex() }

    @Test
    fun `rti clear payload matches reference`() {
        assertEquals(hex("clear"), RtiPayloads.clearText(uuid).toHex())
    }

    @Test
    fun `rti input payload matches reference`() {
        assertEquals(hex("input_hello"), RtiPayloads.inputText(uuid, "hello").toHex())
    }

    @Test
    fun `rti input handles non-ascii text`() {
        // Non-ASCII forces UTF-16BE string encoding rather than the ASCII path.
        assertEquals(hex("input_unicode"), RtiPayloads.inputText(uuid, "café – naïve").toHex())
    }

    @Test
    fun `rti input handles long text spilling the length field`() {
        // >= 15 chars pushes the count out into a follow-on integer.
        assertEquals(hex("input_long"), RtiPayloads.inputText(uuid, "x".repeat(300)).toHex())
    }

    @Test
    fun `rti input handles empty text`() {
        assertEquals(hex("input_empty"), RtiPayloads.inputText(uuid, "").toHex())
    }

    @Test
    fun `generic plist matches reference encoder`() {
        val value = linkedMapOf<Any?, Any?>(
            "a" to 1,
            "b" to listOf(true, false, null),
            "c" to byteArrayOf(1, 2),
            "d" to "x".repeat(20),
            "e" to 3.5,
            "f" to linkedMapOf<Any?, Any?>("g" to 65536),
        )
        assertEquals(hex("generic"), BinaryPlist.write(value).toHex())
    }

    @Test
    fun `reads back what reference encoder produced`() {
        @Suppress("UNCHECKED_CAST")
        val decoded = BinaryPlist.read(hex("generic").fromHex()) as Map<Any?, Any?>
        assertEquals(1L, decoded["a"])
        assertEquals(listOf(true, false, null), decoded["b"])
        assertContentEquals(byteArrayOf(1, 2), decoded["c"] as ByteArray)
        assertEquals("x".repeat(20), decoded["d"])
        assertEquals(3.5, decoded["e"])
        assertEquals(65536L, (decoded["f"] as Map<*, *>)["g"])
    }

    @Test
    fun `uid survives a round trip as a uid`() {
        val decoded = BinaryPlist.read(BinaryPlist.write(listOf(Uid(1), Uid(7), Uid(300))))
        assertEquals(listOf(Uid(1), Uid(7), Uid(300)), decoded)
    }

    @Test
    fun `parses session uuid out of an rti archive`() {
        // The archive we emit is structurally what the device sends back, so it
        // is a fair fixture for the reverse direction.
        val archive = RtiPayloads.inputText(uuid, "hello")
        val objects = (BinaryPlist.read(archive) as Map<*, *>)["\$objects"] as List<*>
        assertTrue(objects.isNotEmpty())

        // A device archive nests sessionUUID under $top; build that shape.
        val deviceLike = BinaryPlist.write(
            linkedMapOf<Any?, Any?>(
                "\$version" to 100000,
                "\$archiver" to "RTIKeyedArchiver",
                "\$top" to linkedMapOf<Any?, Any?>("sessionUUID" to Uid(1)),
                "\$objects" to listOf("\$null", uuid),
            )
        )
        val session = RtiPayloads.parseSession(deviceLike)
        assertContentEquals(uuid, session!!.sessionUuid)
        assertEquals("", session.textBeforeCursor)
    }

    @Test
    fun `parse returns null on a non-rti archive`() {
        assertNull(RtiPayloads.parseSession(BinaryPlist.write(linkedMapOf<Any?, Any?>("x" to 1))))
    }
}
