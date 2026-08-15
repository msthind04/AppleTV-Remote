package dev.atvremote.protocol.companion

import dev.atvremote.protocol.plist.BinaryPlist
import dev.atvremote.protocol.plist.Uid

/**
 * NSKeyedArchiver payloads for the RTI (remote text input) channel.
 *
 * These archives are hand-built rather than produced by a general archiver:
 * the object graph is fixed, and only the session UUID and the text vary. Key
 * order is preserved deliberately — it matches what tvOS itself emits.
 */
object RtiPayloads {

    private const val ARCHIVER = "RTIKeyedArchiver"
    private const val VERSION = 100000

    private fun classEntry(name: String) = linkedMapOf<Any?, Any?>(
        "\$classname" to name,
        "\$classes" to listOf(name, "NSObject"),
    )

    private fun archive(objects: List<Any?>): ByteArray = BinaryPlist.write(
        linkedMapOf<Any?, Any?>(
            "\$version" to VERSION,
            "\$archiver" to ARCHIVER,
            "\$top" to linkedMapOf<Any?, Any?>("textOperations" to Uid(1)),
            "\$objects" to objects,
        )
    )

    /** Replace whatever is in the field with nothing. */
    fun clearText(sessionUuid: ByteArray): ByteArray = archive(
        listOf(
            "\$null",
            linkedMapOf<Any?, Any?>(
                "\$class" to Uid(7),
                "targetSessionUUID" to Uid(5),
                "keyboardOutput" to Uid(2),
                "textToAssert" to Uid(4),
            ),
            linkedMapOf<Any?, Any?>("\$class" to Uid(3)),
            classEntry("TIKeyboardOutput"),
            "",
            linkedMapOf<Any?, Any?>(
                "NS.uuidbytes" to sessionUuid,
                "\$class" to Uid(6),
            ),
            classEntry("NSUUID"),
            classEntry("RTITextOperations"),
        )
    )

    /** Insert [text] at the current cursor position. */
    fun inputText(sessionUuid: ByteArray, text: String): ByteArray = archive(
        listOf(
            "\$null",
            linkedMapOf<Any?, Any?>(
                "keyboardOutput" to Uid(2),
                "\$class" to Uid(7),
                "targetSessionUUID" to Uid(5),
            ),
            linkedMapOf<Any?, Any?>(
                "insertionText" to Uid(3),
                "\$class" to Uid(4),
            ),
            text,
            classEntry("TIKeyboardOutput"),
            linkedMapOf<Any?, Any?>(
                "NS.uuidbytes" to sessionUuid,
                "\$class" to Uid(6),
            ),
            classEntry("NSUUID"),
            classEntry("RTITextOperations"),
        )
    )

    /**
     * Pull the session UUID and any text already in the field out of the
     * `_tiD` archive the device sends when a text field gains focus.
     *
     * Rather than implement a full unarchiver, this follows UID references from
     * `$top` the same way the reference implementation does.
     */
    fun parseSession(archiveBytes: ByteArray): RtiSession? {
        val root = BinaryPlist.read(archiveBytes) as? Map<*, *> ?: return null
        val objects = root["\$objects"] as? List<*> ?: return null
        val top = root["\$top"] as? Map<*, *> ?: return null

        fun resolve(value: Any?): Any? =
            if (value is Uid) objects.getOrNull(value.value.toInt()) else value

        fun follow(start: Any?, vararg path: String): Any? {
            var current = resolve(start)
            for (key in path) {
                val map = current as? Map<*, *> ?: return null
                current = resolve(map[key])
            }
            return current
        }

        val uuid = when (val session = follow(top["sessionUUID"])) {
            is ByteArray -> session
            is Map<*, *> -> resolve(session["NS.uuidbytes"]) as? ByteArray
            else -> null
        } ?: return null

        val existing = follow(top["documentState"], "docSt", "contextBeforeInput") as? String

        return RtiSession(sessionUuid = uuid, textBeforeCursor = existing ?: "")
    }
}

/** An active text-entry session on the device. */
data class RtiSession(val sessionUuid: ByteArray, val textBeforeCursor: String) {
    override fun equals(other: Any?): Boolean =
        other is RtiSession && sessionUuid.contentEquals(other.sessionUuid) &&
            textBeforeCursor == other.textBeforeCursor

    override fun hashCode(): Int = sessionUuid.contentHashCode() * 31 + textBeforeCursor.hashCode()
}
