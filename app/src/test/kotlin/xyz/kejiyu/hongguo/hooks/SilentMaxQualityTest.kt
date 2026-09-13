package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SilentMaxQualityTest {
    private enum class Resolution { HD, FULL_HD }

    // Mirrors the verified host contract: q configures the engine and marks the
    // selection as user-originated; the later bitrate callback reads that flag.
    private class Controller {
        var c = false
        var current = Resolution.HD
        var applications = 0
        var fail = false

        fun q(resolution: Resolution) {
            current = resolution
            applications++
            c = true
            if (fail) error("native selection failed")
        }
    }

    @Before fun useVerifiedProfile() {
        setNames(QualityTargetNames.forVersion(
            TargetNames.CN_PACKAGE, "7.3.7.32", TargetNames.CN_73318,
        ))
    }

    private fun setNames(names: QualityTargetNames.Names) {
        Hooks::class.java.getDeclaredField("gQualityNames").apply { isAccessible = true }
            .set(Hooks, names)
    }

    private fun apply(controller: Any): Boolean = Hooks::class.java.getDeclaredMethod(
        "applyHighestViaController", Any::class.java, Any::class.java,
    ).apply { isAccessible = true }.invoke(Hooks, controller, Resolution.FULL_HD) as Boolean

    @Test fun everyEpisodeStillSelectsHighestWithoutArmingNativeBanner() {
        val controller = Controller()
        repeat(3) {
            controller.current = Resolution.HD
            assertTrue(apply(controller))
            assertEquals(Resolution.FULL_HD, controller.current)
            assertFalse("automatic selection must not be marked as manual", controller.c)
        }
        assertEquals(3, controller.applications)
    }

    @Test fun laterManualSelectionStillArmsNativeBanner() {
        val controller = Controller()
        assertTrue(apply(controller))
        assertFalse(controller.c)
        controller.q(Resolution.HD)
        assertTrue(controller.c)
        assertEquals(Resolution.HD, controller.current)
    }

    @Test fun nativeFailureDoesNotLeaveAnAutomaticSelectionMarkedAsManual() {
        val controller = Controller().apply { fail = true }
        assertFalse(apply(controller))
        assertFalse(controller.c)
    }

    @Test fun unmappedVersionDoesNotGuessTheMeaningOfControllerFields() {
        setNames(QualityTargetNames.forVersion(
            TargetNames.CN_PACKAGE, "7.3.3.18", TargetNames.CN_73318,
        ))
        val controller = Controller()
        assertTrue(apply(controller))
        assertTrue(controller.c)
        assertEquals(Resolution.FULL_HD, controller.current)
    }

    private class ControllerWithoutMarker {
        var current = Resolution.HD
        fun q(resolution: Resolution) { current = resolution }
    }

    @Test fun missingMarkerDoesNotBreakWorkingQualitySelection() {
        val controller = ControllerWithoutMarker()
        assertTrue(apply(controller))
        assertEquals(Resolution.FULL_HD, controller.current)
    }
}
