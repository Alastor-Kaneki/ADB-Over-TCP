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
            val last = run("devices")
            append(log, "DEVICES", last)
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

    fun testPersistentPort(): String {
        val serial = "127.0.0.1:5555"
        val before = run("-s", serial, "shell", "getprop", "persist.adb.tcp.port")
        val attempt = run("-s", serial, "shell", "setprop", "persist.adb.tcp.port", "5555")
        val after = run("-s", serial, "shell", "getprop", "persist.adb.tcp.port")
        val service = run("-s", serial, "shell", "getprop", "service.adb.tcp.port")
        return "BEFORE\n$before\n\nSET ATTEMPT\n$attempt\n\nAFTER\n$after\n\nACTIVE SERVICE PORT\n$service"
    }

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
