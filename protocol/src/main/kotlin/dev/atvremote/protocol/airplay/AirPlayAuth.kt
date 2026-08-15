package dev.atvremote.protocol.airplay

import dev.atvremote.protocol.hap.Credentials
import dev.atvremote.protocol.hap.HapException
import dev.atvremote.protocol.hap.PairSetupSession
import dev.atvremote.protocol.hap.PairVerifySession
import dev.atvremote.protocol.hap.Tlv8

/**
 * HAP pairing over the AirPlay control connection.
 *
 * This is a second, independent pairing from the Companion one: different
 * endpoint, different credentials, its own PIN. An Apple TV will show a fresh
 * code for it even if Companion is already paired.
 */
object AirPlayAuth {

    private val HEADERS = mapOf<String, Any>(
        "User-Agent" to "AirPlay/320.20",
        "Connection" to "keep-alive",
        "X-Apple-HKP" to 3,
    )

    private suspend fun postTlv(
        connection: AirPlayConnection,
        path: String,
        tlv: Map<Int, ByteArray>,
    ): Map<Int, ByteArray> {
        val response = connection.post(path, Tlv8.write(tlv), HEADERS)
        if (!response.isSuccess) {
            throw HapException("$path returned ${response.code} ${response.message}")
        }
        return Tlv8.read(response.body)
    }

    /**
     * Begin pair-setup. The Apple TV shows a PIN in response; feed it to
     * [AirPlayPairing.complete].
     */
    suspend fun startPairing(
        connection: AirPlayConnection,
        deviceName: String = "Android Remote",
    ): AirPlayPairing {
        val session = PairSetupSession(deviceName)
        // Explicitly ask the device to display a PIN.
        connection.post("/pair-pin-start", null, HEADERS)
        val m2 = postTlv(connection, "/pair-setup", session.startRequest())
        return AirPlayPairing(connection, session, m2)
    }

    class AirPlayPairing internal constructor(
        private val connection: AirPlayConnection,
        private val session: PairSetupSession,
        private val m2: Map<Int, ByteArray>,
    ) {
        suspend fun complete(pin: String): Credentials {
            val m4 = postTlv(connection, "/pair-setup", session.proofRequest(m2, pin))
            val m6 = postTlv(connection, "/pair-setup", session.exchangeRequest(m4))
            return session.finish(m6)
        }
    }

    /**
     * Verify credentials and switch the connection to encrypted operation.
     * The returned verifier also derives per-channel keys.
     */
    suspend fun verify(
        connection: AirPlayConnection,
        credentials: Credentials,
    ): PairVerifySession {
        val session = PairVerifySession(credentials)
        val m2 = postTlv(connection, "/pair-verify", session.startRequest())
        postTlv(connection, "/pair-verify", session.finishRequest(m2))

        // The control connection itself is encrypted with the "Control-Salt"
        // key pair; the event and data channels derive their own below.
        connection.enableEncryption(
            outputKey = session.encryptionKeys(
                CONTROL_SALT, CONTROL_WRITE_INFO, CONTROL_READ_INFO
            ).first,
            inputKey = session.encryptionKeys(
                CONTROL_SALT, CONTROL_WRITE_INFO, CONTROL_READ_INFO
            ).second,
        )
        return session
    }

    const val CONTROL_SALT = "Control-Salt"
    const val CONTROL_WRITE_INFO = "Control-Write-Encryption-Key"
    const val CONTROL_READ_INFO = "Control-Read-Encryption-Key"

    const val EVENTS_SALT = "Events-Salt"
    const val EVENTS_WRITE_INFO = "Events-Write-Encryption-Key"
    const val EVENTS_READ_INFO = "Events-Read-Encryption-Key"

    const val DATASTREAM_SALT = "DataStream-Salt"
    const val DATASTREAM_OUTPUT_INFO = "DataStream-Output-Encryption-Key"
    const val DATASTREAM_INPUT_INFO = "DataStream-Input-Encryption-Key"
}
