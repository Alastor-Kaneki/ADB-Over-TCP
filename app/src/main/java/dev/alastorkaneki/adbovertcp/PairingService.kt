package dev.alastorkaneki.adbovertcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pairingJob: Job? = null
    private lateinit var controller: AdbController
    private lateinit var discovery: AdbMdnsDiscovery
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        controller = AdbController(this)
        discovery = AdbMdnsDiscovery(this)
        notifications = getSystemService(NotificationManager::class.java)
        createChannel()
        startForegroundNow("Preparing automatic ADB pairing…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SUBMIT_CODE -> submitCode(
                intent.getStringExtra(EXTRA_PAIRING_CODE).orEmpty()
            )
            ACTION_CANCEL -> stopPairing()
            else -> startPairingDiscovery()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPairingDiscovery() {
        pairingJob?.cancel()
        pairingJob = scope.launch {
            publishResult("Searching for Android's ADB pairing service…")
            updateNotification(
                "Open Wireless debugging and tap “Pair device with pairing code”.",
                codeInput = false,
                ongoing = true
            )

            runCatching { discovery.awaitPairingService() }
                .onSuccess { endpoint ->
                    saveEndpoint(PREF_PAIRING_ENDPOINT, endpoint.address)
                    publishResult("Pairing service found at ${endpoint.address}. Enter the six-digit code from Settings.")
                    updateNotification(
                        "Pairing service found. Enter the six-digit code.",
                        codeInput = true,
                        ongoing = true
                    )
                }
                .onFailure { error ->
                    val message = "Pairing service was not found: ${error.message}. Keep the pairing-code dialog open and retry."
                    publishResult(message)
                    updateNotification(message, codeInput = false, ongoing = false)
                }
        }
    }

    private fun submitCode(rawCode: String) {
        val code = rawCode.filter(Char::isDigit)
        if (code.length != 6) {
            val message = "The pairing code must contain exactly six digits."
            publishResult(message)
            updateNotification(message, codeInput = true, ongoing = true)
            return
        }

        pairingJob?.cancel()
        pairingJob = scope.launch {
            updateNotification("Pairing embedded ADB…", codeInput = false, ongoing = true)

            val pairingAddress = loadEndpoint(PREF_PAIRING_ENDPOINT)
                ?: runCatching { discovery.awaitPairingService(35_000) }
                    .getOrElse { error ->
                        finishWithError("Could not rediscover the pairing service: ${error.message}")
                        return@launch
                    }
                    .address
                    .also { saveEndpoint(PREF_PAIRING_ENDPOINT, it) }

            val pairResult = controller.pair(pairingAddress, code)
            if (!pairResult.ok && !pairResult.output.contains("paired", ignoreCase = true)) {
                finishWithError("ADB pairing failed.\n\n$pairResult")
                return@launch
            }

            updateNotification("Paired. Finding the normal ADB connection service…", false, true)
            val connectionEndpoint = runCatching { discovery.awaitConnectionService() }
                .getOrElse { error ->
                    finishWithError("Paired successfully, but the ADB connection service was not found: ${error.message}")
                    return@launch
                }
            saveEndpoint(PREF_CONNECTION_ENDPOINT, connectionEndpoint.address)

            val startShizuku = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(PREF_START_SHIZUKU, true)
            val result = controller.enableTcpFromEndpoint(
                connectionEndpoint.address,
                startShizuku
            )
            publishResult(result)

            if (controller.isLoopbackListening(1_500)) {
                val message = if (startShizuku) {
                    "TCP 5555 is active through loopback. Shizuku startup was attempted. Wi-Fi may now be disconnected."
                } else {
                    "TCP 5555 is active through loopback. Wi-Fi may now be disconnected."
                }
                updateNotification(message, codeInput = false, ongoing = false)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            } else {
                finishWithError("Pairing completed, but 127.0.0.1:5555 did not become reachable.\n\n$result")
            }
        }
    }

    private fun finishWithError(message: String) {
        publishResult(message)
        updateNotification(message.lineSequence().firstOrNull().orEmpty(), false, false)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun stopPairing() {
        pairingJob?.cancel()
        publishResult("Automatic pairing cancelled.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishResult(message: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(PREF_LAST_RESULT, message)
            .apply()
    }

    private fun saveEndpoint(key: String, address: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(key, address).apply()
    }

    private fun loadEndpoint(key: String): String? =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, null)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "ADB automatic pairing",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Discovers Android Wireless Debugging and accepts the pairing code"
                }
            )
        }
    }

    private fun startForegroundNow(message: String) {
        val notification = buildNotification(message, codeInput = false, ongoing = true)
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

    private fun updateNotification(message: String, codeInput: Boolean, ongoing: Boolean) {
        notifications.notify(
            NOTIFICATION_ID,
            buildNotification(message, codeInput, ongoing)
        )
    }

    private fun buildNotification(
        message: String,
        codeInput: Boolean,
        ongoing: Boolean
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this,
            11,
            Intent(this, PairingService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("ADB Over TCP")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .addAction(R.drawable.ic_launcher, "Cancel", cancel)

        if (codeInput) {
            val submitIntent = PendingIntent.getBroadcast(
                this,
                12,
                Intent(this, PairingCodeReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val remoteInput = RemoteInput.Builder(REMOTE_INPUT_CODE)
                .setLabel("Six-digit pairing code")
                .build()
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_launcher,
                    "Enter pairing code",
                    submitIntent
                ).addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(false)
                    .build()
            )
        }

        return builder.build()
    }

    override fun onDestroy() {
        pairingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.alastorkaneki.adbovertcp.action.START_PAIRING"
        const val ACTION_SUBMIT_CODE = "dev.alastorkaneki.adbovertcp.action.SUBMIT_CODE"
        const val ACTION_CANCEL = "dev.alastorkaneki.adbovertcp.action.CANCEL_PAIRING"
        const val EXTRA_PAIRING_CODE = "pairing_code"
        const val REMOTE_INPUT_CODE = "remote_pairing_code"

        const val PREFS = "adb_tcp"
        const val PREF_LAST_RESULT = "last_pairing_result"
        const val PREF_START_SHIZUKU = "start_shizuku"
        private const val PREF_PAIRING_ENDPOINT = "auto_pairing_endpoint"
        private const val PREF_CONNECTION_ENDPOINT = "auto_connection_endpoint"

        private const val CHANNEL_ID = "adb_pairing"
        private const val NOTIFICATION_ID = 5556

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, PairingService::class.java).setAction(ACTION_START)
            )
        }

        fun submitCode(context: Context, code: String) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, PairingService::class.java).apply {
                    action = ACTION_SUBMIT_CODE
                    putExtra(EXTRA_PAIRING_CODE, code)
                }
            )
        }
    }
}
