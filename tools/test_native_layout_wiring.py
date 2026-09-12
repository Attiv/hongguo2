"""Guard Xposed wiring which cannot execute in a local Android unit test.

Run: python3 -m unittest discover -s tools -p 'test_*.py' -v
Window behavior itself is covered by NativeVideoLayoutTest and device comparison.
"""
from pathlib import Path
import re
import unittest

SOURCE = (Path(__file__).resolve().parents[1] /
          "app/src/main/kotlin/xyz/kejiyu/hongguo/hooks/Hooks.kt").read_text()


def function_body(name):
    start = SOURCE.index("private fun " + name + "(")
    following = SOURCE.find("\n    private ", start + 1)
    return SOURCE[start:following if following >= 0 else len(SOURCE)]


class NativeLayoutWiringTest(unittest.TestCase):
    def test_status_height_is_not_falsified(self):
        self.assertNotRegex(SOURCE, r"if \(shouldHideStatusBar\(\)\) 0 else chain\.proceed\(\)")

    def test_status_toggle_does_not_reconfigure_the_host_player(self):
        self.assertNotIn("refreshShortVideoWindowLayout", SOURCE)
        self.assertNotIn("refreshOneShortVideoHolder", SOURCE)

    def test_status_restore_does_not_clear_native_edge_to_edge(self):
        body = function_body("showStatusBar")
        for mutation in ("clearFlags", "systemUiVisibility", "layoutInDisplayCutoutMode", "setDecorFitsSystemWindows"):
            self.assertNotIn(mutation, body)

    def test_navigation_hide_does_not_change_bottom_content_fitting(self):
        self.assertNotIn("applyBottomEdgeToEdge", function_body("applyNavBar"))

    def test_normal_bar_toggles_never_set_player_scaling(self):
        for name in ("applyCleanTop", "showStatusBar", "applyNavBar", "showNavBar"):
            self.assertNotRegex(function_body(name), r"setTextureLayout|applyVideoScale|\.layoutParams\s*=")


if __name__ == "__main__":
    unittest.main()
