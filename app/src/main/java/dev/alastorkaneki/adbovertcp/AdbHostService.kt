package dev.alastorkaneki.adbovertcp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Keeps the embedded ADB host server alive in official `adb server nodaemon` mode.
 * All short-lived embedded adb client processes then share the same transport table and keys.
 */
class AdbHostService : Service() {
    private var serverProcess: Process? = null
    private var outputThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground()
        ensureServerProcess()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureServerProcess()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureServerProcess() {
        if (serverProcess?.isAlive == true) return

        val binary = File(applicationInfo.nativeLibraryDir, "libadb.so")
        if (!binary.exists()) {
            stopSelf()
            return
        }

        val adbHome = File(filesDir, "adb-home").apply { mkdirs() }
        serverProcess = runCatching {
            ProcessBuilder(binary.absolutePath, "server", "nodaemon")
                .directory(adbHome)
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = adbHome.absolutePath
                    environment()["TMPDIR"] = cacheDir.absolutePath
                    environment()["ADB_VENDOR_KEYS"] =
                        File(adbHome, ".android/adbkey").absolutePath
                }
                .start()
        }.getOrNull()

        val process = serverProcess ?: return
        outputThread = Thread({
            runCatching {
                val logFile = File(filesDir, "adb-host-server.log")
                process.inputStream.bufferedReader().useLines { lines ->
                    logFile.outputStream().bufferedWriter().use { writer ->
                        lines.forEach { line ->
                            writer.appendLine(line)
                            writer.flush()
                        }
                    }
                }
            }
        }, "adb-host-output").apply {
            isDaemon = true
            start()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Embedded ADB host",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps the app's built-in ADB server and transport alive"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("ADB Over TCP")
            .setContentText("Embedded ADB host server is active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        serverProcess?.destroy()
        runCatching {
            if (serverProcess?.isAlive == true) serverProcess?.destroyForcibly()
        }
        outputThread?.interrupt()
        serverProcess = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START =
            "dev.alastorkaneki.adbovertcp.action.START_ADB_HOST"
        private const val ACTION_STOP =
            "dev.alastorkaneki.adbovertcp.action.STOP_ADB_HOST"
        private const val CHANNEL_ID = "adb_host"
        private const val NOTIFICATION_ID = 5557

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AdbHostService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AdbHostService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
