package dev.alastorkaneki.adbovertcp

import android.content.Context
import kotlinx.coroutines.delay
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class AdbController(private val context: Context) {
    data class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
        override fun toString(): String = "exit=$exitCode\n$output".trim()
    }

    private val adbBinary: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libadb.so")

    private val adbHome: File
        get() = File(context.filesDir, "adb-home").apply { mkdirs() }

    fun isLoopbackListening(timeoutMs: Int = 700): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 5555), timeoutMs)
        }
        true
    }.getOrDefault(false)

    fun run(vararg args: String, timeoutSeconds: Long = 35): Result {
        if (!adbBinary.exists()) {
            return Result(127, "Embedded ADB is missing: ${adbBinary.absolutePath}")
        }
        return runCatching {
            val process = ProcessBuilder(listOf(adbBinary.absolutePath) + args)
                .directory(adbHome)
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = adbHome.absolutePath
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                    environment()["ADB_VENDOR_KEYS"] = File(adbHome, ".android/adbkey").absolutePath
                }
                .start()
            process.outputStream.close()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Result(124, "Command timed out: adb ${args.joinToString(" ")}")
            } else {
                Result(process.exitValue(), process.inputStream.bufferedReader().use { it.readText() }.trim())
            }
        }.getOrElse { Result(126, it.stackTraceToString()) }
    }

    suspend fun pairAndEnable(pairAddress: String, code: String, connectionPort: Int): String {
        val cleanAddress = pairAddress.trim()
        require(cleanAddress.contains(':')) { "Pairing address must be IP:port" }
        require(code.matches(Regex("\\d{6}"))) { "Pairing code must contain six digits" }
        require(connectionPort in 1..65535) { "Connection port is invalid" }

        val host = extractHost(cleanAddress)
        val connectionAddress = formatAddress(host, connectionPort)
        val log = StringBuilder()

        append(log, "PAIR", run("pair", cleanAddress, code))
        if (!log.toString().contains("Successfully paired", ignoreCase = true)) {
            append(log, "DEVICES", run("devices"))
        }

        val connected = run("connect", connectionAddress)
        append(log, "CONNECT", connected)
        if (!connected.ok && !connected.output.contains("connected", ignoreCase = true)) return log.toString()

        append(log, "TCPIP", run("-s", connectionAddress, "tcpip", "5555"))
        delay(2_000)
        append(log, "LOOPBACK", run("connect", "127.0.0.1:5555"))
        append(log, "VERIFY", run("-s", "127.0.0.1:5555", "shell", "id"))
        return log.toString()
    }

    fun reconnect(): String = buildString {
        append("Socket listening: ${isLoopbackListening()}\n")
        append(run("connect", "127.0.0.1:5555"))
        append("\n\n")
        append(run("devices", "-l"))
    }

    fun safeOff(): Result = run("disconnect", "127.0.0.1:5555")

    fun fullOff(): Result = run("-s", "127.0.0.1:5555", "usb")

    fun grantAppPermissions(): String {
        val pkg = context.packageName
        val commands = listOf(
            listOf("pm", "grant", pkg, "android.permission.WRITE_SECURE_SETTINGS"),
            listOf("pm", "grant", pkg, "android.permission.POST_NOTIFICATIONS"),
            listOf("appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow"),
            listOf("appops", "set", pkg, "RUN_IN_BACKGROUND", "allow"),
            listOf("appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow")
        )
        return buildString {
            commands.forEach { command ->
                val result = run("-s", "127.0.0.1:5555", "shell", *command.toTypedArray())
                append("$ ${command.joinToString(" ")}\n$result\n\n")
            }
        }.trim()
    }

    /**
     * Tests only boot-recovery mechanisms that can be verified from the shell identity.
     * A live socket and an authenticated shell are the source of truth; Motorola may expose
     * blank ADB properties even while classic TCP ADB is active.
     */
    fun bootCompatibilityScan(): String {
        val serial = "127.0.0.1:5555"
        val socketListening = isLoopbackListening(1_500)
        val connect = if (socketListening) run("connect", serial) else Result(1, "Port 5555 is closed")
        val identity = if (socketListening) run("-s", serial, "shell", "id") else Result(1, "Unavailable")
        val manufacturer = shellGetProp(serial, "ro.product.manufacturer")
        val fingerprint = shellGetProp(serial, "ro.build.fingerprint")
        val enforcing = run("-s", serial, "shell", "getenforce")
        val usbConfig = shellGetProp(serial, "persist.sys.usb.config")

        val persistBefore = shellGetProp(serial, "persist.adb.tcp.port")
        val persistAttempt = run(
            "-s", serial, "shell", "setprop", "persist.adb.tcp.port", "5555"
        )
        val persistAfter = shellGetProp(serial, "persist.adb.tcp.port")
        val servicePort = shellGetProp(serial, "service.adb.tcp.port")

        val persistWritable = persistAttempt.ok && persistAfter.output.trim() == "5555"
        context.getSharedPreferences("adb_tcp", Context.MODE_PRIVATE).edit()
            .putBoolean("persistent_port_supported", persistWritable)
            .putBoolean("property_status_reliable", servicePort.output.trim() == "5555")
            .apply()

        val initScanCommand = """
            for d in /system/etc/init /system_ext/etc/init /product/etc/init /vendor/etc/init /odm/etc/init; do
              if [ -d "${'$'}d" ]; then
                grep -RniE 'persist\\.adb\\.tcp\\.port|service\\.adb\\.tcp\\.port|adb[^ ]*tcp|tcp[^ ]*adb|start adbd|restart adbd' "${'$'}d" 2>/dev/null
              fi
            done | head -n 160
        """.trimIndent()
        val initMatches = run(
            "-s", serial, "shell", "sh", "-c", initScanCommand,
            timeoutSeconds = 25
        )
        val oemTriggerFound = initMatches.ok && initMatches.output.isNotBlank()
        context.getSharedPreferences("adb_tcp", Context.MODE_PRIVATE).edit()
            .putBoolean("oem_adb_trigger_found", oemTriggerFound)
            .putString("last_boot_compatibility_scan", initMatches.output.take(24_000))
            .apply()

        return buildString {
            appendLine("BOOT COMPATIBILITY RESULT")
            appendLine()
            appendLine("Current-session loopback: ${if (socketListening && identity.ok) "WORKING" else "NOT WORKING"}")
            appendLine("Persistent TCP property: ${if (persistWritable) "WRITABLE" else "BLOCKED"}")
            appendLine("Property-based status: ${if (servicePort.output.trim() == "5555") "AVAILABLE" else "UNRELIABLE / BLANK"}")
            appendLine("OEM init trigger candidates: ${if (oemTriggerFound) "FOUND—REVIEW BELOW" else "NONE FOUND IN READABLE FILES"}")
            appendLine()
            appendLine("CONCLUSION")
            if (persistWritable) {
                appendLine("This device may preserve TCP ADB across reboot. Reboot testing is still required.")
            } else if (oemTriggerFound) {
                appendLine("The standard persistent-property route is blocked. An OEM-specific init trigger may still be testable.")
            } else {
                appendLine("Cold-boot restart is not currently available without root, USB ADB, or temporary Wi-Fi pairing. Same-boot mobile-data operation remains supported.")
            }
            appendLine()
            appendLine("DEVICE")
            appendLine("Manufacturer: ${manufacturer.output.ifBlank { "unknown" }}")
            appendLine("Build: ${fingerprint.output.ifBlank { "unknown" }}")
            appendLine("SELinux: ${enforcing.output.ifBlank { "unknown" }}")
            appendLine("USB config: ${usbConfig.output.ifBlank { "blank" }}")
            appendLine()
            appendLine("SOCKET / AUTH")
            appendLine("Socket probe: $socketListening")
            appendLine("Connect:\n$connect")
            appendLine("Identity:\n$identity")
            appendLine()
            appendLine("PERSISTENT PROPERTY")
            appendLine("Before:\n$persistBefore")
            appendLine("Set attempt:\n$persistAttempt")
            appendLine("After:\n$persistAfter")
            appendLine("Active service property:\n$servicePort")
            appendLine()
            appendLine("READABLE INIT MATCHES")
            appendLine(if (initMatches.output.isBlank()) "No matching readable init entries." else initMatches.output)
        }.trim()
    }

    private fun shellGetProp(serial: String, name: String): Result =
        run("-s", serial, "shell", "getprop", name)

    private fun append(log: StringBuilder, title: String, result: Result) {
        log.append("[$title]\n$result\n\n")
    }

    private fun extractHost(address: String): String {
        if (address.startsWith("[")) return address.substringAfter('[').substringBefore(']')
        return address.substringBeforeLast(':')
    }

    private fun formatAddress(host: String, port: Int): String =
        if (host.contains(':')) "[$host]:$port" else "$host:$port"
}
