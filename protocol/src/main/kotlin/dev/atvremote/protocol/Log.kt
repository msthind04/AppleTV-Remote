package dev.atvremote.protocol

/**
 * Minimal wire logging, enabled with ATV_DEBUG=1.
 *
 * Protocol faults here are usually silent (a frame that fails to decode simply
 * never resolves its waiter), so being able to see raw frames is the difference
 * between a diagnosis and a guess.
 */
object Log {
    @Volatile
    var enabled: Boolean = System.getenv("ATV_DEBUG") == "1"

    fun d(message: () -> String) {
        if (enabled) System.err.println("[atv] ${message()}")
    }

    fun hex(data: ByteArray, limit: Int = 96): String {
        val shown = data.take(limit).joinToString("") { "%02x".format(it) }
        return if (data.size > limit) "$shown... (${data.size} bytes)" else "$shown (${data.size} bytes)"
    }
}
