package dev.atvremote.protocol.airplay

import dev.atvremote.protocol.Log
import dev.atvremote.protocol.hap.Credentials
import dev.atvremote.protocol.mrp.Mrp
import dev.atvremote.protocol.mrp.NowPlaying
import dev.atvremote.protocol.mrp.PlaybackState
import dev.atvremote.protocol.mrp.ProtoMessage
import dev.atvremote.protocol.plist.BinaryPlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

/**
 * AirPlay 2 session carrying MRP, which is how now-playing metadata is exposed
 * from tvOS 15 onwards.
 *
 * Bringing this up is a fixed sequence: verify credentials on the control
 * connection, SETUP an event channel (unused, but required), RECORD, then SETUP
 * the data channel that actually carries MRP. Each channel gets its own
 * encryption keys derived from the same shared secret.
 */
class Ap2Session(
    private val host: String,
    private val credentials: Credentials,
    private val scope: CoroutineScope,
    private val deviceName: String = "Android Remote",
) {
    private var connection: AirPlayConnection? = null
    private var eventChannel: EventChannel? = null
    private var dataChannel: DataStreamChannel? = null
    private var feedbackJob: Job? = null

    private var nowPlaying = NowPlaying()

    /** Per-app state, keyed by bundle identifier. */
    private val clients = LinkedHashMap<String, NowPlaying>()
    private var activeBundleId: String? = null

    var onNowPlaying: ((NowPlaying) -> Unit)? = null
    var onDisconnect: ((Throwable?) -> Unit)? = null

    suspend fun connect() {
        val conn = AirPlayConnection(host, AIRPLAY_PORT)
        conn.connect()
        connection = conn

        Log.d { "ap2: control connected" }
        val verifier = AirPlayAuth.verify(conn, credentials)
        Log.d { "ap2: pair-verify ok" }

        // --- event channel (required even though we ignore its traffic) ---
        val eventResponse = setup(
            conn,
            linkedMapOf<Any?, Any?>(
                "isRemoteControlOnly" to true,
                "osName" to "iPhone OS",
                "sourceVersion" to "550.10",
                "timingProtocol" to "None",
                "model" to "iPhone14,3",
                "deviceID" to DEVICE_ID,
                "osVersion" to "16.7.2",
                "osBuildVersion" to "20H19",
                "macAddress" to DEVICE_ID,
                "sessionUUID" to UUID.randomUUID().toString().uppercase(),
                "name" to deviceName,
            ),
        )
        val eventPort = (eventResponse["eventPort"] as? Long)?.toInt()
            ?: error("device did not return an eventPort")
        Log.d { "ap2: eventPort=$eventPort" }

        // Read/write info are swapped here: this channel's traffic originates
        // at the receiver.
        val (eventOut, eventIn) = verifier.encryptionKeys(
            AirPlayAuth.EVENTS_SALT, AirPlayAuth.EVENTS_READ_INFO, AirPlayAuth.EVENTS_WRITE_INFO
        )
        eventChannel = EventChannel(host, eventPort, eventOut, eventIn, scope).also { it.connect() }

        Log.d { "ap2: event channel connected, sending RECORD" }
        conn.exchange("RECORD")
        Log.d { "ap2: RECORD ok" }

        // --- data channel: this is the one carrying MRP ---
        val seed = Random.nextLong(0, Long.MAX_VALUE)
        val dataResponse = setup(
            conn,
            linkedMapOf<Any?, Any?>(
                "streams" to listOf(
                    linkedMapOf<Any?, Any?>(
                        "controlType" to 2L,
                        "channelID" to UUID.randomUUID().toString().uppercase(),
                        "seed" to seed,
                        "clientUUID" to UUID.randomUUID().toString().uppercase(),
                        "type" to 130L,
                        "wantsDedicatedSocket" to true,
                        "clientTypeUUID" to "1910A70F-DBC0-4242-AF95-115DB30604E1",
                    )
                )
            ),
        )
        Log.d { "ap2: data SETUP ok" }
        val streams = dataResponse["streams"] as? List<*> ?: error("no streams in response")
        val dataPort = ((streams.firstOrNull() as? Map<*, *>)?.get("dataPort") as? Long)?.toInt()
            ?: error("device did not return a dataPort")

        // The seed participates in the salt, binding keys to this stream.
        val (dataOut, dataIn) = verifier.encryptionKeys(
            AirPlayAuth.DATASTREAM_SALT + seed,
            AirPlayAuth.DATASTREAM_OUTPUT_INFO,
            AirPlayAuth.DATASTREAM_INPUT_INFO,
        )
        val channel = DataStreamChannel(host, dataPort, dataOut, dataIn, scope)
        channel.onProtobuf = ::handleProtobuf
        channel.onClosed = { onDisconnect?.invoke(it) }
        channel.connect()
        dataChannel = channel
        Log.d { "ap2: data channel connected on $dataPort" }

        // MRP handshake. DEVICE_INFO must come first or the device stays mute.
        channel.sendProtobuf(Mrp.deviceInfo(String(credentials.clientId), deviceName))
        delay(300)
        channel.sendProtobuf(Mrp.setConnectionState())
        channel.sendProtobuf(Mrp.clientUpdatesConfig())

        Log.d { "ap2: MRP handshake sent" }
        startFeedback(conn)
    }

    private suspend fun setup(
        conn: AirPlayConnection,
        body: Map<Any?, Any?>,
    ): Map<*, *> {
        val response = conn.exchange(
            "SETUP",
            body = BinaryPlist.write(body),
            contentType = AirPlayConnection.BPLIST_CONTENT_TYPE,
        )
        if (!response.isSuccess) error("SETUP failed: ${response.code} ${response.message}")
        return BinaryPlist.read(response.body) as? Map<*, *>
            ?: error("SETUP response was not a plist")
    }

    /** iOS sends feedback every two seconds; without it the session is dropped. */
    private fun startFeedback(conn: AirPlayConnection) {
        feedbackJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2000)
                runCatching { conn.post("/feedback") }
                    .onFailure {
                        Log.d { "feedback failed: $it" }
                        return@launch
                    }
            }
        }
    }

    // ------------------------------------------------------------ messages

    private fun handleProtobuf(message: ProtoMessage) {
        val type = message.varint(Mrp.FIELD_TYPE)?.toInt()
        Log.d {
            val detail = when (type) {
                Mrp.TYPE_SET_STATE -> {
                    val st = message.message(Mrp.EXT_SET_STATE)
                    "setState fields=${st?.fields?.keys?.sorted()} " +
                        "npInfo=${st?.message(Mrp.STATE_NOW_PLAYING_INFO)?.fields?.keys?.sorted()}"
                }
                Mrp.TYPE_UPDATE_CONTENT_ITEM -> {
                    val u = message.message(Mrp.EXT_UPDATE_CONTENT_ITEM)
                    "contentItems=${u?.messages(1)?.map { it.fields.keys.sorted() }}"
                }
                else -> "fields=${message.fields.keys.sorted()}"
            }
            "MRP type=$type $detail"
        }
        when (type) {
            Mrp.TYPE_SET_STATE -> applySetState(message.message(Mrp.EXT_SET_STATE))
            Mrp.TYPE_UPDATE_CONTENT_ITEM ->
                applyContentItems(message.message(Mrp.EXT_UPDATE_CONTENT_ITEM))
            Mrp.TYPE_SET_NOW_PLAYING_CLIENT ->
                applyActiveClient(message.message(Mrp.EXT_SET_NOW_PLAYING_CLIENT))
            else -> Unit
        }
    }

    /**
     * State arrives per player, one message per app, and the last one to speak
     * is not necessarily the one on screen. Each client's state is therefore
     * kept separately and only the active one — designated by
     * SET_NOW_PLAYING_CLIENT — is published.
     */
    private fun applySetState(state: ProtoMessage?) {
        if (state == null) return

        val bundleId = state.message(Mrp.STATE_PLAYER_PATH)
            ?.message(Mrp.PATH_CLIENT)
            ?.string(Mrp.CLIENT_BUNDLE_ID)
            ?: return

        var entry = clients[bundleId] ?: NowPlaying()

        if (state.has(Mrp.STATE_PLAYBACK_STATE)) {
            entry = entry.copy(
                playbackState = PlaybackState.from(state.varint(Mrp.STATE_PLAYBACK_STATE))
            )
        }
        state.string(Mrp.STATE_DISPLAY_NAME)?.let { entry = entry.copy(appName = it) }

        // nowPlayingInfo is rarely populated in practice; real track metadata
        // travels in the playback queue's content items.
        state.message(Mrp.STATE_NOW_PLAYING_INFO)?.let { info ->
            entry = entry.copy(
                title = info.string(Mrp.NP_TITLE) ?: entry.title,
                artist = info.string(Mrp.NP_ARTIST) ?: entry.artist,
                album = info.string(Mrp.NP_ALBUM) ?: entry.album,
                duration = info.double(Mrp.NP_DURATION) ?: entry.duration,
                elapsedTime = info.double(Mrp.NP_ELAPSED) ?: entry.elapsedTime,
            )
        }

        state.message(Mrp.STATE_PLAYBACK_QUEUE)?.let { queue ->
            val items = queue.messages(Mrp.QUEUE_CONTENT_ITEMS)
            val index = (queue.varint(Mrp.QUEUE_LOCATION) ?: 0L).toInt()
            items.getOrNull(index)?.let { entry = applyContentItem(entry, it) }
        }

        clients[bundleId] = entry
        Log.d { "setState bundle=$bundleId active=$activeBundleId -> ${entry.describe()}" }
        if (bundleId == activeBundleId || activeBundleId == null) publish(entry)
    }

    private fun applyContentItem(current: NowPlaying, item: ProtoMessage): NowPlaying {
        var updated = current
        item.bytes(Mrp.CI_ARTWORK_DATA)
            ?.takeIf { it.isNotEmpty() }
            ?.let { updated = updated.copy(artwork = it) }

        val metadata = item.message(Mrp.CI_METADATA) ?: return updated
        return updated.copy(
            title = metadata.string(Mrp.META_TITLE) ?: updated.title,
            artist = metadata.string(Mrp.META_TRACK_ARTIST)
                ?: metadata.string(Mrp.META_SUBTITLE) ?: updated.artist,
            album = metadata.string(Mrp.META_ALBUM) ?: updated.album,
            duration = metadata.double(Mrp.META_DURATION) ?: updated.duration,
        )
    }

    private fun applyActiveClient(message: ProtoMessage?) {
        val client = message?.message(Mrp.SNPC_CLIENT) ?: return
        val bundleId = client.string(Mrp.CLIENT_BUNDLE_ID) ?: return
        Log.d { "active client -> $bundleId (${client.string(Mrp.CLIENT_DISPLAY_NAME)})" }
        activeBundleId = bundleId

        val existing = clients[bundleId] ?: NowPlaying(
            appName = client.string(Mrp.CLIENT_DISPLAY_NAME)
        )
        clients[bundleId] = existing
        publish(existing)
    }

    private fun applyContentItems(update: ProtoMessage?) {
        if (update == null) return
        val bundleId = activeBundleId ?: return
        var entry = clients[bundleId] ?: NowPlaying()
        for (item in update.messages(1)) entry = applyContentItem(entry, item)
        clients[bundleId] = entry
        publish(entry)
    }

    private fun publish(state: NowPlaying) {
        if (state == nowPlaying) return
        nowPlaying = state
        onNowPlaying?.invoke(state)
    }

    fun close() {
        feedbackJob?.cancel()
        dataChannel?.close()
        eventChannel?.close()
        connection?.close()
        connection = null
    }

    private companion object {
        const val AIRPLAY_PORT = 7000
        const val DEVICE_ID = "AA:BB:CC:DD:EE:FF"
    }
}
