@file:Suppress("DEPRECATION")

package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NativeVideoLayoutTest {
    private lateinit var activity: Activity

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = FrameLayout(activity)
        // A host video whose lower edge is immediately above the selector.
        content.addView(View(activity), FrameLayout.LayoutParams(1264, 2528))
        content.addView(View(activity), FrameLayout.LayoutParams(1264, 175).apply { topMargin = 2549 })
        activity.setContentView(content)
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        activity.window.attributes = activity.window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.BLACK
        field("gMasterOn", true)
        field("gStatusOn", true)
        field("gNavBarOff", true)
    }

    private fun field(name: String, value: Any) {
        Hooks::class.java.getDeclaredField(name).apply { isAccessible = true }.set(Hooks, value)
    }

    private fun call(name: String) {
        Hooks::class.java.getDeclaredMethod(name, Activity::class.java).apply { isAccessible = true }
            .invoke(Hooks, activity)
    }

    private fun geometry() = listOf(
        activity.window.attributes.flags,
        activity.window.attributes.layoutInDisplayCutoutMode,
        activity.window.statusBarColor,
        activity.window.navigationBarColor,
        activity.window.decorView.systemUiVisibility and (
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR),
    )

    @Test fun hidingStatusDoesNotChangeHostWindowGeometry() {
        val before = geometry()
        call("applyCleanTop")
        assertEquals("Status foreground must not resize the video viewport", before, geometry())
    }

    @Test fun restoringStatusKeepsDrawingVideoBehindStatusBar() {
        val before = geometry()
        call("applyCleanTop")
        call("showStatusBar")
        assertEquals("Restore must not clear the host's LAYOUT_FULLSCREEN or cutout", before, geometry())
    }

    @Test fun hidingNavigationDoesNotExpandContentOrMoveEpisodeBoundary() {
        val before = geometry()
        call("applyNavBar")
        assertEquals("Do not change decor fitting or bottom layout flags to hide the gesture indicator", before, geometry())
    }

    @Test fun restoringNavigationReturnsOriginalBottomWindowState() {
        val before = geometry()
        call("applyNavBar")
        call("showNavBar")
        assertEquals(before, geometry())
    }

    @Test fun untouchedRestoreDoesNotChangeNativeWindow() {
        val before = geometry()
        call("showStatusBar")
        call("showNavBar")
        assertEquals(before, geometry())
    }

    @Test fun defaultCutoutMustNotExcludeTheTopOfTheWindowWhenStatusIsHidden() {
        activity.window.attributes = activity.window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        call("applyCleanTop")
        assertEquals("DEFAULT stops allowing the status-bar cutout when its foreground is hidden",
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
            activity.window.attributes.layoutInDisplayCutoutMode)
        call("showStatusBar")
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT,
            activity.window.attributes.layoutInDisplayCutoutMode)
    }

    @Test fun togglingSystemBarsDoesNotModifyVideoOrEpisodeLayoutParams() {
        val content = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
        val video = content.getChildAt(0)
        val selector = content.getChildAt(1)
        call("applyCleanTop")
        call("applyNavBar")
        call("showStatusBar")
        call("showNavBar")
        assertEquals(2528, video.layoutParams.height)
        assertEquals(2549, (selector.layoutParams as FrameLayout.LayoutParams).topMargin)
        assertEquals(175, selector.layoutParams.height)
        assertEquals(View.VISIBLE, selector.visibility)
    }
}
