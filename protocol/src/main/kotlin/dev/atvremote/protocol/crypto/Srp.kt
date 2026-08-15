package dev.atvremote.protocol.crypto

import java.math.BigInteger

/**
 * SRP-6a client for HomeKit pair-setup: 3072-bit group, generator 5, SHA-512.
 *
 * Byte-encoding rules here are subtle and non-obvious, and getting them wrong
 * produces a proof mismatch that only shows up against a real device:
 *
 *  - `k` and `u` hash operands zero-padded to the full 384-byte modulus length.
 *  - The integers in the M1/M2 proofs use *minimal-length* big-endian
 *    encoding instead, so a value with leading zero bytes is shorter than 384.
 *  - The salt is hashed as the raw bytes the device sent, NOT re-encoded
 *    through an integer, so a leading zero byte in the salt is preserved.
 *
 * This mirrors the encoding the reference Python implementation produces, which
 * is what Apple devices actually interoperate with.
 */
class Srp(
    private val username: String = "Pair-Setup",
    clientPrivate: ByteArray? = null,
) {
    private val n = BigInteger(PRIME_3072_HEX, 16)
    private val g = BigInteger.valueOf(5)
    private val padLength = minimalBytes(n).size

    /** k = H(N | PAD(g)) */
    private val k = BigInteger(1, Crypto.sha512(minimalBytes(n), pad(g)))

    private val a: BigInteger = clientPrivate
        ?.let { BigInteger(1, it) }
        ?: BigInteger(1, Crypto.randomBytes(32))

    /** A = g^a mod N */
    val clientPublic: BigInteger = g.modPow(a, n)

    lateinit var sessionKey: ByteArray
        private set

    lateinit var proof: ByteArray
        private set

    private lateinit var expectedServerProof: ByteArray

    /**
     * Consume the device's salt and public key, deriving the session key and
     * our proof M1.
     */
    fun processChallenge(salt: ByteArray, serverPublic: ByteArray, pin: String) {
        val bigB = BigInteger(1, serverPublic)

        require(bigB.mod(n) != BigInteger.ZERO) { "SRP: server public key is zero mod N" }

        // x = H(s | H(I | ":" | P))
        val inner = Crypto.sha512("$username:$pin".toByteArray(Charsets.UTF_8))
        val x = BigInteger(1, Crypto.sha512(salt, inner))

        // u = H(PAD(A) | PAD(B))
        val u = BigInteger(1, Crypto.sha512(pad(clientPublic), pad(bigB)))

        // S = (B - k * g^x) ^ (a + u * x) mod N
        val v = g.modPow(x, n)
        val base = bigB.subtract(k.multiply(v)).mod(n)
        val sharedSecret = base.modPow(a.add(u.multiply(x)), n)

        // K = H(S), with S in minimal-length form.
        sessionKey = Crypto.sha512(minimalBytes(sharedSecret))

        // M1 = H( H(N) XOR H(g) | H(I) | s | A | B | K )
        val hn = BigInteger(1, Crypto.sha512(minimalBytes(n)))
        val hg = BigInteger(1, Crypto.sha512(minimalBytes(g)))
        proof = Crypto.sha512(
            minimalBytes(hn.xor(hg)),
            Crypto.sha512(username.toByteArray(Charsets.UTF_8)),
            salt,
            minimalBytes(clientPublic),
            minimalBytes(bigB),
            sessionKey,
        )

        // M2 = H( A | M1 | K )
        expectedServerProof = Crypto.sha512(minimalBytes(clientPublic), proof, sessionKey)
    }

    /** Verify the device's M2 proof, confirming it knew the PIN. */
    fun verifyServerProof(serverProof: ByteArray): Boolean =
        expectedServerProof.contentEquals(serverProof)

    fun clientPublicBytes(): ByteArray = minimalBytes(clientPublic)

    private fun pad(value: BigInteger): ByteArray {
        val raw = minimalBytes(value)
        if (raw.size >= padLength) return raw
        return ByteArray(padLength - raw.size) + raw
    }

    companion object {
        /**
         * Minimal-length big-endian encoding, matching Python's
         * `unhexlify('%x' % value)`. BigInteger.toByteArray() prepends a sign
         * byte when the high bit is set, which must be stripped.
         */
        fun minimalBytes(value: BigInteger): ByteArray {
            val raw = value.toByteArray()
            return if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        }

        const val PRIME_3072_HEX =
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
            "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
            "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
            "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33" +
            "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
            "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864" +
            "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2" +
            "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"
    }
}
