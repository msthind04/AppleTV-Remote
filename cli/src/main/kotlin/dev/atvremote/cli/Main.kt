package dev.atvremote.cli

import dev.atvremote.protocol.companion.AppleTvRemote
import dev.atvremote.protocol.companion.Button
import dev.atvremote.protocol.companion.CompanionClient
import dev.atvremote.protocol.hap.Credentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File

private val credentialsDir = File(System.getProperty("user.home"), ".config/appletv-remote")

private fun credentialsFile(id: String) =
    File(credentialsDir, "${id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.credentials")

private fun usage(): Nothing {
    println(
        """
        Apple TV Remote - protocol test harness

          scan                            Discover Apple TVs on the local network
          pair <host> <port>              Pair with a device (PIN is shown on the TV)
          pair-airplay <host>             Pair over AirPlay (port 7000), for now-playing
          nowplaying <host> [seconds]     Stream now-playing updates over MRP
          info <host> <port>              Connect with stored credentials and report status
          apps <host> <port>              List installed apps
          launch <host> <port> <bundle>   Launch an app
          key <host> <port> <button>      Press a button (e.g. home, up, select, play_pause)
          volume <host> <port> [level]    Get volume, or set it to level (0.0-1.0)
          skip <host> <port> <seconds>    Seek relative (negative to rewind)
          text <host> <port> <text...>    Type into the focused text field
          tisession <host> <port>         Report the current text input session

        Buttons: ${Button.entries.joinToString(", ") { it.name.lowercase() }}
        """.trimIndent()
    )
    kotlin.system.exitProcess(1)
}

fun main(args: Array<String>): Unit = runBlocking {
    if (args.isEmpty()) usage()
    val scope = CoroutineScope(SupervisorJob())

    when (args[0]) {
        "scan" -> {
            println("Scanning for Apple TVs...")
            val devices = JmdnsDiscovery.scan()
            if (devices.isEmpty()) {
                println("No devices found. Ensure you are on the same network as the Apple TV.")
            } else {
                devices.forEach { d ->
                    val paired = credentialsFile(d.identifier ?: d.name).exists()
                    println(
                        "  ${d.name}  ${d.address}:${d.port}  ${d.model ?: "?"}" +
                            if (paired) "  [paired]" else ""
                    )
                    println("      id: ${d.identifier}")
                }
            }
        }

        "nowplaying" -> {
            if (args.size < 2) usage()
            val host = args[1]
            val file = credentialsFile("$host-airplay")
            if (!file.exists()) {
                println("No AirPlay credentials - run 'pair-airplay $host' first.")
                kotlin.system.exitProcess(1)
            }
            val seconds = args.getOrNull(2)?.toIntOrNull() ?: 25
            val session = dev.atvremote.protocol.airplay.Ap2Session(
                host, Credentials.parse(file.readText()), scope,
            )
            session.onNowPlaying = { println("  [now playing] ${it.describe()}") }
            session.onDisconnect = { println("  [disconnected] $it") }
            println("Establishing MRP-over-AirPlay tunnel...")
            session.connect()
            println("Tunnel up. Listening for ${seconds}s (play something on the Apple TV)...")
            kotlinx.coroutines.delay(seconds * 1000L)
            session.close()
            println("Done.")
        }

        "pair-airplay" -> {
            if (args.size < 2) usage()
            val host = args[1]
            val connection = dev.atvremote.protocol.airplay.AirPlayConnection(host, 7000)
            connection.connect()
            println("Connected to $host:7000 (AirPlay)")

            val pairing = dev.atvremote.protocol.airplay.AirPlayAuth.startPairing(connection)
            println("PIN should now be displayed on the Apple TV.")
            print("Enter PIN: ")
            System.out.flush()
            val pin = readlnOrNull()?.trim().orEmpty()

            val credentials = pairing.complete(pin)
            credentialsDir.mkdirs()
            val file = credentialsFile("$host-airplay")
            file.writeText(credentials.serialize())
            println("AirPlay paired successfully.")
            println("Credentials saved to ${file.absolutePath}")
            connection.close()
        }

        "pair" -> {
            if (args.size < 3) usage()
            val host = args[1]
            val port = args[2].toInt()
            val client = CompanionClient(host, port, scope)
            client.connect()
            println("Connected to $host:$port")

            val session = client.startPairing()
            println("PIN should now be displayed on the Apple TV.")
            print("Enter PIN: ")
            System.out.flush()
            val pin = readlnOrNull()?.trim().orEmpty()

            val credentials = session.complete(pin)
            credentialsDir.mkdirs()
            val file = credentialsFile("$host-$port")
            file.writeText(credentials.serialize())
            file.setReadable(false, false)
            file.setReadable(true, true)
            println("Paired successfully.")
            println("Credentials saved to ${file.absolutePath}")
            client.close()
        }

        else -> {
            if (args.size < 3) usage()
            val host = args[1]
            val port = args[2].toInt()
            val file = credentialsFile("$host-$port")
            if (!file.exists()) {
                println("No credentials for $host:$port - run 'pair $host $port' first.")
                kotlin.system.exitProcess(1)
            }
            val remote = AppleTvRemote(host, port, Credentials.parse(file.readText()), scope)
            remote.connect()

            when (args[0]) {
                "info" -> {
                    println("Connected and authenticated to $host:$port")
                    // Capabilities arrive asynchronously as an _iMC event.
                    val caps = kotlinx.coroutines.withTimeoutOrNull(4000) {
                        val d = kotlinx.coroutines.CompletableDeferred<Any>()
                        remote.onCapabilities = { d.complete(it) }
                        d.await()
                    }
                    if (caps == null) {
                        println("Capabilities: device sent no _iMC event within 4s")
                    } else {
                        println("Capabilities: $caps")
                    }
                }

                "apps" -> remote.listApps().forEach { println("  ${it.name}  (${it.bundleId})") }

                "launch" -> {
                    if (args.size < 4) usage()
                    remote.launchApp(args[3])
                    println("Launched ${args[3]}")
                }

                "key" -> {
                    if (args.size < 4) usage()
                    val button = Button.entries.firstOrNull { it.name.equals(args[3], true) }
                        ?: run {
                            println("Unknown button '${args[3]}'")
                            kotlin.system.exitProcess(1)
                        }
                    remote.press(button)
                    println("Pressed ${button.name}")
                }

                "text" -> {
                    if (args.size < 4) usage()
                    val result = remote.sendText(args.drop(3).joinToString(" "))
                    if (result == null) {
                        println("No text field is focused on the Apple TV.")
                    } else {
                        println("Field now contains: \"$result\"")
                    }
                }

                "tisession" -> {
                    val session = remote.textInputSession()
                    if (session == null) println("No active text input session.")
                    else println("Session uuid=${session.sessionUuid.size} bytes, " +
                        "text=\"${session.textBeforeCursor}\"")
                }

                "skip" -> {
                    if (args.size < 4) usage()
                    remote.skipBy(args[3].toDouble())
                    println("Skipped ${args[3]}s")
                }

                "volume" -> {
                    if (args.size >= 4) {
                        remote.setVolume(args[3].toDouble())
                        println("Volume set to ${args[3]}")
                    } else {
                        println("Volume: ${remote.getVolume()}")
                    }
                }

                else -> usage()
            }
            remote.close()
        }
    }
    kotlin.system.exitProcess(0)
}
