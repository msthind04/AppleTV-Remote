package dev.atvremote.protocol.hap

/**
 * Long-term pairing credentials produced by pair-setup and consumed by every
 * subsequent pair-verify.
 *
 * These are the entire contents of a pairing: losing them means re-pairing with
 * a new PIN, and leaking them grants full control of the device. Serialized as
 * a colon-separated hex string, matching the format used by pyatv so
 * credentials can be moved between the two.
 */
data class Credentials(
    /** Device's Ed25519 long-term public key. */
    val ltpk: ByteArray,
    /** Our Ed25519 long-term secret key (seed). */
    val ltsk: ByteArray,
    /** Device's pairing identifier. */
    val atvId: ByteArray,
    /** Our pairing identifier (a UUID string, as bytes). */
    val clientId: ByteArray,
) {
    fun serialize(): String = listOf(ltpk, ltsk, atvId, clientId)
        .joinToString(":") { it.toHex() }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Credentials) return false
        return ltpk.contentEquals(other.ltpk) && ltsk.contentEquals(other.ltsk) &&
            atvId.contentEquals(other.atvId) && clientId.contentEquals(other.clientId)
    }

    override fun hashCode(): Int = ltpk.contentHashCode() * 31 + clientId.contentHashCode()

    override fun toString(): String = "Credentials(atvId=${String(atvId)}, clientId=${String(clientId)})"

    companion object {
        fun parse(text: String): Credentials {
            val parts = text.trim().split(":")
            require(parts.size == 4) { "Expected 4 colon-separated fields, got ${parts.size}" }
            return Credentials(
                ltpk = parts[0].fromHex(),
                ltsk = parts[1].fromHex(),
                atvId = parts[2].fromHex(),
                clientId = parts[3].fromHex(),
            )
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.fromHex(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
