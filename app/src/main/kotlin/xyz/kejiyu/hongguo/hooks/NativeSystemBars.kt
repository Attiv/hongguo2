@file:Suppress("DEPRECATION", "NewApi")

package xyz.kejiyu.hongguo.hooks

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import java.util.WeakHashMap

/**
 * Owns foreground visibility, plus the cutout allowance needed to keep the same
 * window bounds while the status bar is hidden. The host owns decor fitting,
 * colors, layout flags and player/episode geometry. Main-thread calls only.
 */
internal class NativeSystemBars {
    private data class State(
        val behavior: Int?,
        val addedImmersive: Int,
        var statusVisible: Boolean? = null,
        var navigationVisible: Boolean? = null,
        var addedStatus: Int = 0,
        var addedNavigation: Int = 0,
        var originalCutout: Int? = null,
    )

    // Values deliberately do not reference the Window or its decor.
    private val states = WeakHashMap<Window, State>()

    fun hide(window: Window, statusBar: Boolean): Boolean {
        val decor = window.decorView
        val modern = Build.VERSION.SDK_INT >= 30
        val controller = if (modern) window.insetsController ?: return false else null
        val ui = decor.systemUiVisibility
        val flag = if (statusBar) View.SYSTEM_UI_FLAG_FULLSCREEN else View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        val state = states.getOrPut(window) {
            State(
                behavior = controller?.systemBarsBehavior,
                addedImmersive = if (modern) 0 else View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY and ui.inv(),
            )
        }
        val first = (if (statusBar) state.statusVisible else state.navigationVisible) == null
        if (first) {
            val visible = if (modern) {
                decor.rootWindowInsets?.isVisible(type(statusBar)) ?: legacyVisible(window, statusBar)
            } else legacyVisible(window, statusBar)
            if (statusBar) {
                state.statusVisible = visible
                state.addedStatus = flag and ui.inv()
            } else {
                state.navigationVisible = visible
                state.addedNavigation = flag and ui.inv()
            }
        }
        if (statusBar && window.attributes.layoutInDisplayCutoutMode ==
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT) {
            // DEFAULT allows a cutout behind a *visible* status bar, but can
            // exclude it once that bar is hidden (observed: window y=140 on
            // Android 16). Keep the original pixel bounds, not DEFAULT's enum.
            state.originalCutout = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = cutoutAllowance()
            }
        }
        if (controller != null) {
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(type(statusBar))
        } else {
            decor.systemUiVisibility = ui or flag or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        return first
    }

    fun restore(window: Window, statusBar: Boolean): Boolean {
        val state = states[window] ?: return false
        val visible = (if (statusBar) state.statusVisible else state.navigationVisible) ?: return false
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= 30) {
            // Keep ownership until a controller is available so resume can retry.
            val controller = window.insetsController ?: return false
            if (visible) controller.show(type(statusBar)) else controller.hide(type(statusBar))
        } else {
            val added = if (statusBar) state.addedStatus else state.addedNavigation
            decor.systemUiVisibility = decor.systemUiVisibility and added.inv()
        }
        if (statusBar) {
            state.originalCutout?.let { original ->
                // Do not overwrite a different cutout policy set by the host.
                if (window.attributes.layoutInDisplayCutoutMode == cutoutAllowance()) {
                    window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = original }
                }
            }
            state.originalCutout = null
        }
        if (statusBar) state.statusVisible = null else state.navigationVisible = null
        if (state.statusVisible == null && state.navigationVisible == null) {
            if (Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.let { controller ->
                    if (controller.systemBarsBehavior == WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) {
                        state.behavior?.let { controller.systemBarsBehavior = it }
                    }
                }
            } else {
                decor.systemUiVisibility = decor.systemUiVisibility and state.addedImmersive.inv()
            }
            states.remove(window)
        }
        return true
    }

    private fun type(statusBar: Boolean): Int =
        if (statusBar) WindowInsets.Type.statusBars() else WindowInsets.Type.navigationBars()

    private fun cutoutAllowance(): Int = if (Build.VERSION.SDK_INT >= 30) {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    } else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

    private fun legacyVisible(window: Window, statusBar: Boolean): Boolean {
        val flag = if (statusBar) View.SYSTEM_UI_FLAG_FULLSCREEN else View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        val hiddenByWindow = statusBar &&
            window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0
        return !hiddenByWindow && window.decorView.systemUiVisibility and flag == 0
    }
}
