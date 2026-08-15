package dev.atvremote.protocol.airplay

import dev.atvremote.protocol.crypto.ChaChaCipher
import java.io.ByteArrayOutputStream

/**
 * HAP transport encryption for AirPlay channels.
 *
 * Unlike the Companion protocol — where one frame is one AEAD unit — HAP
 * encrypts a byte stream in blocks of at most 1024 bytes. Each block is
 * preceded by a little-endian 2-byte length that also serves as the AEAD
 * additional data, and the counter nonce advances per block.
 *
 * Because this sits under a stream protocol, [decrypt] buffers partial blocks
 * and returns only the plaintext that is fully available.
 */
class HapSession {

    private var cipher: ChaChaCipher? = null
    private var pending = ByteArray(0)

    val isEnabled: Boolean get() = cipher != null

    fun enable(outputKey: ByteArray, inputKey: ByteArray) {
        cipher = ChaChaCipher(outputKey, inputKey, nonceLength = 8)
    }

    fun encrypt(data: ByteArray): ByteArray {
        val c = cipher ?: return data
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset < data.size) {
            val size = minOf(FRAME_LENGTH, data.size - offset)
            val block = data.copyOfRange(offset, offset + size)
            val length = byteArrayOf((size and 0xFF).toByte(), ((size shr 8) and 0xFF).toByte())
            out.write(length)
            out.write(c.encrypt(block, aad = length))
            offset += size
        }
        return out.toByteArray()
    }

    fun decrypt(data: ByteArray): ByteArray {
        val c = cipher ?: return data
        pending += data

        val out = ByteArrayOutputStream()
        while (pending.size >= 2) {
            val length = (pending[0].toInt() and 0xFF) or ((pending[1].toInt() and 0xFF) shl 8)
            val blockLength = length + ChaChaCipher.TAG_LENGTH
            if (pending.size < blockLength + 2) break

            val aad = pending.copyOfRange(0, 2)
            val block = pending.copyOfRange(2, 2 + blockLength)
            out.write(c.decrypt(block, aad = aad))
            pending = pending.copyOfRange(2 + blockLength, pending.size)
        }
        return out.toByteArray()
    }

    private companion object {
        /** Mandated by the HAP specification, section 5.2.2. */
        const val FRAME_LENGTH = 1024
    }
}
