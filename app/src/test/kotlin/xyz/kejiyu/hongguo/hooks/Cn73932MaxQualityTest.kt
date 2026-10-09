package xyz.kejiyu.hongguo.hooks

import e65.w
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class Cn73932MaxQualityTest {
    @Before fun use73932Profile() {
        Hooks::class.java.getDeclaredField("gQualityNames").apply { isAccessible = true }
            .set(Hooks, QualityTargetNames.forVersion(
                TargetNames.CN_PACKAGE, "7.3.9.32", TargetNames.CN_73318,
            ))
    }

    private fun apply(controller: w): Boolean = Hooks::class.java.getDeclaredMethod(
        "applyHighestViaController", Any::class.java, Any::class.java,
    ).apply { isAccessible = true }.invoke(Hooks, controller, w.Resolution.ExtremelyHigh) as Boolean

    @Test fun repeatedEpisodesUseNativeRAndDoNotArmTheBanner() {
        val controller = w()
        repeat(3) {
            controller.h.selected = w.Resolution.High
            assertTrue("7.3.9.32 must invoke r rather than the old q method", apply(controller))
            assertEquals(w.Resolution.ExtremelyHigh, controller.h.selected)
            assertFalse(controller.c)
        }
        assertEquals(3, controller.h.applications)
    }

    @Test fun manualSelectionStillArmsNativeFeedback() {
        val controller = w()
        assertTrue(apply(controller))
        assertFalse(controller.c)
        controller.r(w.Resolution.SuperHigh)
        assertTrue(controller.c)
        assertEquals(w.Resolution.SuperHigh, controller.h.selected)
    }

    @Test fun nativeFailureStillClearsTheAutomaticMarker() {
        val controller = w().apply { fail = true }
        assertFalse(apply(controller))
        assertEquals(1, controller.h.applications)
        assertFalse(controller.c)
    }
}
