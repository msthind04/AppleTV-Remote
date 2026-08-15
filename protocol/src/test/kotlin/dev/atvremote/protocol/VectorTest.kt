package dev.atvremote.protocol

import dev.atvremote.protocol.crypto.ChaChaCipher
import dev.atvremote.protocol.crypto.Crypto
import dev.atvremote.protocol.crypto.Srp
import dev.atvremote.protocol.hap.Tlv8
import dev.atvremote.protocol.hap.fromHex
import dev.atvremote.protocol.hap.toHex
import dev.atvremote.protocol.opack.Opack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Conformance tests against vectors produced by the reference Python
 * implementation (pyatv), which is known to interoperate with real devices.
 *
 * These are the tests that matter: a self-consistent round-trip proves nothing
 * about whether an Apple TV will accept our bytes.
 */
class VectorTest {

    private val vectors: Map<String, Any?> by lazy {
        val json = javaClass.getResourceAsStream("/vectors.json")!!.readBytes().decodeToString()
        @Suppress("UNCHECKED_CAST")
        MiniJson.parse(json) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun section(name: String) = vectors[name] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun list(name: String) = vectors[name] as List<Map<String, Any?>>

    @Test
    fun `opack encoding matches reference`() {
        val cases = list("opack")
        // The generator emits values in a fixed order; mirror it here.
        val inputs: List<Any?> = listOf(
            null, true, false, 0L, 1L, 39L, 40L, 255L, 256L, 65535L, 65536L, 4294967296L,
            "", "hello", "a".repeat(32), "b".repeat(33), "c".repeat(300),
            ByteArray(0), byteArrayOf(1, 2, 3), ByteArray(33) { 0xFF.toByte() },
            listOf(1L, 2L, 3L), emptyList<Any>(),
            linkedMapOf<Any?, Any?>("_i" to "_hidC", "_t" to 2L,
                "_c" to linkedMapOf<Any?, Any?>("_hBtS" to 1L, "_hidC" to 6L)),
            linkedMapOf<Any?, Any?>(
                "_pd" to byteArrayOf(0x06, 0x01, 0x01, 0x00, 0x01, 0x00), "_pwTy" to 1L),
            listOf("dup", "dup", "dup"),
            linkedMapOf<Any?, Any?>("a" to listOf(1L, linkedMapOf<Any?, Any?>("b" to "c")),
                "d" to null, "e" to true),
            (0L until 20L).toList(),
            (0 until 18).associate { it.toString() to it.toLong() }
                .toList().toMap(LinkedHashMap()),
        )
        assertEquals(cases.size, inputs.size, "vector/input count mismatch")

        inputs.forEachIndexed { i, input ->
            val expected = cases[i]["hex"] as? String ?: return@forEachIndexed
            assertEquals(expected, Opack.pack(input).toHex(), "pack mismatch for ${cases[i]["desc"]}")
        }
    }

    @Test
    fun `opack round-trips reference bytes`() {
        for (case in list("opack")) {
            val hex = case["hex"] as? String ?: continue
            val decoded = Opack.unpack(hex.fromHex())
            val repacked = Opack.pack(decoded).toHex()
            assertEquals(hex, repacked, "round-trip mismatch for ${case["desc"]}")
        }
    }

    @Test
    fun `tlv8 matches reference`() {
        val cases = list("tlv8").associateBy { it["desc"] as String }
        assertEquals(cases["simple"]!!["hex"],
            Tlv8.write(linkedMapOf(0x06 to byteArrayOf(1), 0x00 to byteArrayOf(0))).toHex())
        assertEquals(cases["long260"]!!["hex"],
            Tlv8.write(mapOf(0x03 to ByteArray(260) { 0xAB.toByte() })).toHex())
        assertEquals(cases["empty_val"]!!["hex"],
            Tlv8.write(mapOf(0x06 to ByteArray(0))).toHex())

        // A >255 byte value must survive the split/rejoin.
        val long = ByteArray(260) { 0xAB.toByte() }
        assertTrue(Tlv8.read(Tlv8.write(mapOf(0x03 to long)))[0x03]!!.contentEquals(long))
    }

    @Test
    fun `hkdf matches reference`() {
        for (case in list("hkdf")) {
            val out = Crypto.hkdf(
                case["salt"] as String,
                case["info"] as String,
                (case["secret"] as String).fromHex(),
            )
            assertEquals(case["out"], out.toHex(), "HKDF mismatch for ${case["info"]}")
        }
    }

    @Test
    fun `chacha with named nonce matches reference`() {
        val c = section("chacha_named")
        val cipher = ChaChaCipher.pairing((c["key"] as String).fromHex())
        val ct = cipher.encrypt(
            (c["pt"] as String).fromHex(),
            nonce = "PS-Msg05".toByteArray(),
        )
        assertEquals(c["ct"], ct.toHex())

        val back = ChaChaCipher.pairing((c["key"] as String).fromHex())
            .decrypt(ct, nonce = "PS-Msg05".toByteArray())
        assertEquals(c["pt"], back.toHex())
    }

    @Test
    fun `chacha counter nonce and aad match reference`() {
        val c = section("chacha_counter")
        val cipher = ChaChaCipher(
            (c["key"] as String).fromHex(), (c["key"] as String).fromHex(), nonceLength = 12,
        )
        val aad = (c["aad"] as String).fromHex()
        val pt = (c["pt"] as String).fromHex()
        assertEquals(c["ct0"], cipher.encrypt(pt, aad = aad).toHex())
        assertEquals(c["ct1"], cipher.encrypt(pt, aad = aad).toHex(), "counter must advance")
    }

    @Test
    fun `srp derives same session key and proof as reference`() {
        val v = section("srp")
        val srp = Srp(clientPrivate = (v["a"] as String).fromHex())

        assertEquals(v["A"], srp.clientPublicBytes().toHex(), "client public A mismatch")

        srp.processChallenge(
            salt = (v["salt"] as String).fromHex(),
            serverPublic = (v["B"] as String).fromHex(),
            pin = v["pin"] as String,
        )

        assertEquals(v["K"], srp.sessionKey.toHex(), "session key K mismatch")
        assertEquals(v["M1"], srp.proof.toHex(), "client proof M1 mismatch")
        assertTrue(srp.verifyServerProof((v["M2"] as String).fromHex()), "M2 verification failed")
    }

    @Test
    fun `opack refuses negative integers rather than wrapping`() {
        // -10 would otherwise encode as 246 and skip the wrong way.
        assertFailsWith<IllegalArgumentException> { Opack.pack(-10) }
        // Doubles carry sign correctly and are what the device expects.
        assertEquals(
            Opack.pack(-10.0).toHex(),
            Opack.pack(-10.0).toHex(),
        )
        assertEquals(-10.0, Opack.unpack(Opack.pack(-10.0)))
    }

    @Test
    fun `ed25519 sign verify round trip`() {
        val seed = Crypto.randomBytes(32)
        val pub = Crypto.ed25519PublicKey(seed)
        val msg = "companion".toByteArray()
        assertTrue(Crypto.ed25519Verify(pub, msg, Crypto.ed25519Sign(seed, msg)))
    }

    @Test
    fun `x25519 agreement is symmetric`() {
        val aPriv = Crypto.randomBytes(32)
        val bPriv = Crypto.randomBytes(32)
        val aShared = Crypto.x25519SharedSecret(aPriv, Crypto.x25519PublicKey(bPriv))
        val bShared = Crypto.x25519SharedSecret(bPriv, Crypto.x25519PublicKey(aPriv))
        assertTrue(aShared.contentEquals(bShared))
    }
}
