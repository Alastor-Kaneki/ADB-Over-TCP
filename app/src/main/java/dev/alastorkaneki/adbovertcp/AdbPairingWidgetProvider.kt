package dev.alastorkaneki.adbovertcp

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AdbPairingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetPreferences.delete(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE_WIFI) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { AdbController(context).toggleWifi() }
                .getOrElse { it.stackTraceToString() }
            context.getSharedPreferences(PairingService.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_LAST_WIDGET_RESULT, result)
                .apply()

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    result.lineSequence().firstOrNull().orEmpty().ifBlank { "Wi-Fi command finished." },
                    Toast.LENGTH_LONG
                ).show()
                refreshAll(context)
            }
            pending.finish()
        }
    }

    companion object {
        private const val ACTION_TOGGLE_WIFI =
            "dev.alastorkaneki.adbovertcp.action.WIDGET_TOGGLE_WIFI"
        private const val PREF_LAST_WIDGET_RESULT = "last_widget_result"

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val startShizuku = WidgetPreferences.startShizuku(context, appWidgetId)
            val forgetAfter = WidgetPreferences.forgetAfterSetup(context, appWidgetId)
            val views = RemoteViews(context.packageName, R.layout.widget_adb_controls)

            val pairIntent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_WIDGET_PAIR
                putExtra(MainActivity.EXTRA_WIDGET_START_SHIZUKU, startShizuku)
                putExtra(MainActivity.EXTRA_WIDGET_FORGET_AFTER, forgetAfter)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                data = Uri.parse("adbovertcp://widget/$appWidgetId/pair")
            }
            val pairPending = PendingIntent.getActivity(
                context,
                appWidgetId * 10 + 1,
                pairIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val wifiIntent = Intent(context, AdbPairingWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_WIFI
                flags = Intent.FLAG_RECEIVER_FOREGROUND
                data = Uri.parse("adbovertcp://widget/$appWidgetId/wifi")
            }
            val wifiPending = PendingIntent.getBroadcast(
                context,
                appWidgetId * 10 + 2,
                wifiIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val openIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                data = Uri.parse("adbovertcp://widget/$appWidgetId/open")
            }
            val openPending = PendingIntent.getActivity(
                context,
                appWidgetId * 10 + 3,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val summary = buildString {
                append(if (startShizuku) "Start Shizuku" else "ADB only")
                if (forgetAfter) append(" • forget key")
            }
            views.setTextViewText(R.id.widgetSummary, summary)
            views.setOnClickPendingIntent(R.id.widgetPair, pairPending)
            views.setOnClickPendingIntent(R.id.widgetWifi, wifiPending)
            views.setOnClickPendingIntent(R.id.widgetOpen, openPending)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, AdbPairingWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
        }
    }
}
