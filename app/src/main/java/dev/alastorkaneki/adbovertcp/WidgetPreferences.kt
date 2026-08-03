package dev.alastorkaneki.adbovertcp

import android.content.Context

object WidgetPreferences {
    private const val PREFS = "adb_pairing_widgets"

    fun startShizuku(context: Context, widgetId: Int): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("widget_${widgetId}_start_shizuku", true)

    fun forgetAfterSetup(context: Context, widgetId: Int): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("widget_${widgetId}_forget_after", false)

    fun save(
        context: Context,
        widgetId: Int,
        startShizuku: Boolean,
        forgetAfterSetup: Boolean
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("widget_${widgetId}_start_shizuku", startShizuku)
            .putBoolean("widget_${widgetId}_forget_after", forgetAfterSetup)
            .apply()
    }

    fun delete(context: Context, widgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("widget_${widgetId}_start_shizuku")
            .remove("widget_${widgetId}_forget_after")
            .apply()
    }
}
