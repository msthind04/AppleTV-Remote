package dev.atvremote.protocol.hap

import dev.atvremote.protocol.crypto.ChaChaCipher
import dev.atvremote.protocol.crypto.Crypto
import dev.atvremote.protocol.crypto.Srp
import dev.atvremote.protocol.opack.Opack
import java.util.UUID

class HapException(message: String) : Exception(message)

/**
 * Transport-agnostic HAP pair-setup and pair-verify.
 *
 * The Companion protocol and AirPlay run the identical handshake over totally
 * different transports — OPACK frames on one side, HTTP POSTs on the other.
 * These classes deal only in TLV8 maps so both can share the crypto.
 */
object HapErrors {
    fun check(tlv: Map<Int, ByteArray>) {
        val err = tlv[TlvValue.ERROR] ?: return
        val code = err.firstOrNull()?.toInt() ?: 0
        throw HapException(
            when (HapError.from(code)) {
                HapError.AUTHENTICATION -> "Incorrect PIN"
                HapError.BACK_OFF -> "Device is rate-limiting pairing attempts; wait and retry"
                HapError.MAX_TRIES -> "Too many failed attempts; restart the Apple TV"
                HapError.MAX_PEERS -> "Device has reached its pairing limit"
                HapError.UNAVAILABLE -> "Pairing unavailable on this device"
                else -> "Pairing failed: 0x${code.toString(16)}"
            }
        )
    }
}

/** Drives M1..M6 of pair-setup. One instance per pairing attempt. */
class PairSetupSession(private val deviceName: String = "Android Remote") {

    private val signingSeed = Crypto.randomBytes(32)
    private val authPublic = Crypto.ed25519PublicKey(signingSeed)
    val pairingId: ByteArray = UUID.randomUUID().toString().toByteArray(Charsets.UTF_8)

    private val srp = Srp(clientPrivate = signingSeed)
    private lateinit var sessionKey: ByteArray

    /** M1: request a PIN-based pairing. */
    fun startRequest(): Map<Int, ByteArray> = linkedMapOf(
        TlvValue.METHOD to byteArrayOf(0x00),
        TlvValue.SEQ_NO to byteArrayOf(0x01),
    )

    /** M3: prove knowledge of the PIN, given the device's M2. */
    fun proofRequest(m2: Map<Int, ByteArray>, pin: String): Map<Int, ByteArray> {
        HapErrors.check(m2)
        val salt = m2[TlvValue.SALT] ?: throw HapException("missing salt in M2")
        val serverPublic = m2[TlvValue.PUBLIC_KEY] ?: throw HapException("missing key in M2")

        srp.processChallenge(salt, serverPublic, pin)

        return linkedMapOf(
            TlvValue.SEQ_NO to byteArrayOf(0x03),
            TlvValue.PUBLIC_KEY to srp.clientPublicBytes(),
            TlvValue.PROOF to srp.proof,
        )
    }

    /** M5: hand over our long-term key, after checking the device's M4 proof. */
    fun exchangeRequest(m4: Map<Int, ByteArray>): Map<Int, ByteArray> {
        HapErrors.check(m4)
        val serverProof = m4[TlvValue.PROOF] ?: throw HapException("missing device proof")
        if (!srp.verifyServerProof(serverProof)) {
            throw HapException("Device proof mismatch — wrong PIN or a tampered exchange")
        }

        sessionKey = Crypto.hkdf(
            "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", srp.sessionKey
        )
        val controllerX = Crypto.hkdf(
            "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", srp.sessionKey
        )
        val signature = Crypto.ed25519Sign(signingSeed, controllerX + pairingId + authPublic)

        val inner = Tlv8.write(
            linkedMapOf(
                TlvValue.IDENTIFIER to pairingId,
                TlvValue.PUBLIC_KEY to authPublic,
                TlvValue.SIGNATURE to signature,
                TlvValue.NAME to Opack.pack(mapOf("name" to deviceName)),
            )
        )

        return linkedMapOf(
            TlvValue.SEQ_NO to byteArrayOf(0x05),
            TlvValue.ENCRYPTED_DATA to ChaChaCipher.pairing(sessionKey)
                .encrypt(inner, nonce = "PS-Msg05".toByteArray()),
        )
    }

    /** M6: unwrap and validate the device's long-term key. */
    fun finish(m6: Map<Int, ByteArray>): Credentials {
        HapErrors.check(m6)
        val encrypted = m6[TlvValue.ENCRYPTED_DATA] ?: throw HapException("missing data in M6")

        val decrypted = runCatching {
            ChaChaCipher.pairing(sessionKey).decrypt(encrypted, nonce = "PS-Msg06".toByteArray())
        }.getOrElse { throw HapException("could not decrypt M6: ${it.message}") }

        val inner = Tlv8.read(decrypted)
        val atvId = inner[TlvValue.IDENTIFIER] ?: throw HapException("missing device id")
        val atvLtpk = inner[TlvValue.PUBLIC_KEY] ?: throw HapException("missing device key")
        val signature = inner[TlvValue.SIGNATURE] ?: throw HapException("missing signature")

        val accessoryX = Crypto.hkdf(
            "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info", srp.sessionKey
        )
        if (!Crypto.ed25519Verify(atvLtpk, accessoryX + atvId + atvLtpk, signature)) {
            throw HapException("device signature invalid")
        }

        return Credentials(ltpk = atvLtpk, ltsk = signingSeed, atvId = atvId, clientId = pairingId)
    }
}

/**
 * Drives pair-verify and derives session keys.
 *
 * The same verifier is reused after the handshake: AirPlay derives several
 * independent key pairs from one shared secret, one per logical channel.
 */
class PairVerifySession(private val credentials: Credentials) {

    private val verifySeed = Crypto.randomBytes(32)
    val publicKey: ByteArray = Crypto.x25519PublicKey(verifySeed)
    private lateinit var shared: ByteArray

    fun startRequest(): Map<Int, ByteArray> = linkedMapOf(
        TlvValue.SEQ_NO to byteArrayOf(0x01),
        TlvValue.PUBLIC_KEY to publicKey,
    )

    /** Validate the device's M2 and produce M3. */
    fun finishRequest(m2: Map<Int, ByteArray>): Map<Int, ByteArray> {
        HapErrors.check(m2)
        val serverPublic = m2[TlvValue.PUBLIC_KEY] ?: throw HapException("missing session key")
        val encrypted = m2[TlvValue.ENCRYPTED_DATA] ?: throw HapException("missing data")

        shared = Crypto.x25519SharedSecret(verifySeed, serverPublic)
        val sessionKey = Crypto.hkdf(
            "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared
        )

        val decrypted = runCatching {
            ChaChaCipher.pairing(sessionKey).decrypt(encrypted, nonce = "PV-Msg02".toByteArray())
        }.getOrElse { throw HapException("credentials rejected by device (decrypt failed)") }

        val tlv = Tlv8.read(decrypted)
        val identifier = tlv[TlvValue.IDENTIFIER] ?: throw HapException("missing identifier")
        val signature = tlv[TlvValue.SIGNATURE] ?: throw HapException("missing signature")

        if (!identifier.contentEquals(credentials.atvId)) {
            throw HapException("device identity mismatch — credentials are for another device")
        }
        if (!Crypto.ed25519Verify(
                credentials.ltpk, serverPublic + identifier + publicKey, signature
            )
        ) {
            throw HapException("device signature invalid during verify")
        }

        val ourSignature = Crypto.ed25519Sign(
            credentials.ltsk, publicKey + credentials.clientId + serverPublic
        )
        val reply = ChaChaCipher.pairing(sessionKey).encrypt(
            Tlv8.write(
                linkedMapOf(
                    TlvValue.IDENTIFIER to credentials.clientId,
                    TlvValue.SIGNATURE to ourSignature,
                )
            ),
            nonce = "PV-Msg03".toByteArray(),
        )

        return linkedMapOf(
            TlvValue.SEQ_NO to byteArrayOf(0x03),
            TlvValue.ENCRYPTED_DATA to reply,
        )
    }

    /** Derive a (output, input) key pair for one logical channel. */
    fun encryptionKeys(
        salt: String,
        outputInfo: String,
        inputInfo: String,
    ): Pair<ByteArray, ByteArray> = Crypto.hkdf(salt, outputInfo, shared) to
        Crypto.hkdf(salt, inputInfo, shared)
}
