package dev.alastorkaneki.adbovertcp

import android.app.Activity
import android.view.ViewTreeObserver
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.WeakHashMap

/** Applies sticky immersive mode to every activity in the application. */
object ImmersiveMode {
    private val focusListeners =
        WeakHashMap<Activity, ViewTreeObserver.OnWindowFocusChangeListener>()

    fun install(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)

        if (!focusListeners.containsKey(activity)) {
            val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (hasFocus) {
                    activity.window.decorView.post { hide(activity) }
                }
            }
            focusListeners[activity] = listener
            activity.window.decorView.viewTreeObserver
                .addOnWindowFocusChangeListener(listener)
        }

        hide(activity)
    }

    fun hide(activity: Activity) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        }
    }

    fun uninstall(activity: Activity) {
        val listener = focusListeners.remove(activity) ?: return
        val observer = activity.window.decorView.viewTreeObserver
        if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
    }
}
