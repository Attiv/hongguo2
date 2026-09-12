# Restore native video bounds — implementation plan

**Goal:** Keep the original video behind the status bar and down to the episode selector, without stretching, forcing crop modes, or hiding the selector.

**Architecture:** System-bar visibility must not own video geometry. Preserve the host's decor fitting, layout flags, cutout mode, status-height calculations, video scale, and episode-bar space. Track only module-owned visibility changes and restore them independently. Do not synthetically call the host's configuration/layout-reset methods when toggling a bar.

**Tech stack:** Kotlin, Android WindowInsetsController (API 30+), legacy visibility bits (API 29), libxposed 102, JUnit/Robolectric; physical Android 16 with Hongguo 7.3.7.32.

## Scope and evidence

- User approved restoring native framing, not forced crop/stretch; clarified video bottom is above the episode selector.
- Source baseline is `873c0cb` plus the pre-existing uncommitted raw-video-mode changes, copied without modification to this worktree.
- Original 2026-08-20 plan proposed extra fit modes and episode hiding. Those features are outside this fix; do not implement that plan wholesale.
- Native 7.3.7.32 device hierarchy: decor 1264×2780, content 1264×2724, RoundFrameLayout video `(0,0)-(1264,2528)`, bottom selector parent starts at y=2549. Its 21 px native gap is not an extra blank video band.
- Current module code clears host `LAYOUT_FULLSCREEN` and cutout on restore, reports status height as zero, changes bottom decor-fitting on navigation hiding, and manually reruns host layout logic.
- Historical device logs mention module 1.0.3, but this checkout is 1.0.1. Preserve this checkout's unrelated features; do not claim full 7.3.7.32 compatibility without testing them.

## Task 1 — regression evidence

Files: `app/build.gradle`, `app/src/test/kotlin/xyz/kejiyu/hongguo/hooks/NativeVideoLayoutTest.kt`.

1. Baseline `ANDROID_HOME=$HOME/Library/Android/sdk sh gradlew :app:testDebugUnitTest :app:assembleDebug`; expect build success, currently no tests.
2. Add JUnit 4.13.2, Robolectric 4.16.1 and test-runtime libxposed API; enable Android resources for unit tests.
3. Exercise real `Hooks` entry methods via reflection against Robolectric Activity windows. Assert hide/restore leaves host flags, cutout, colors, layout bits and video/selector layout parameters unchanged; untouched restore is a no-op.
4. Run `:app:testDebugUnitTest --tests '*NativeVideoLayoutTest'`; record failing assertions before fixing.

## Task 2 — visibility-only implementation

Files: `Hooks.kt`, `NativeSystemBars.kt`, regression test above.

1. Add weak per-window ownership for status/navigation visibility and shared transient-bar behavior. On API 30+, use only `hide(type)` / `show(type)`. Do not call setDecorFitsSystemWindows, alter colors/cutout/window flags or rewrite layout flags. Legacy fallback owns only bits it adds.
2. Route applyCleanTop/showStatusBar and applyNavBar/showNavBar to this controller. Do not change series top margins. Restore only previously acquired bar state and only module-owned changes.
3. Remove zero-height overrides and boolean rewriting in host status utilities. Host methods proceed normally; if a hide preference is active, schedule foreground reapplication afterward.
4. Remove forced host configuration/layout-reset calls. Keep genuine lifecycle tracking and let the host perform its own configuration handling.
5. Apply status/navigation toggles immediately in applyToCurrent and master-off restoration. Update misleading black-border setting descriptions.
6. Run regression tests; verify green. Preserve existing scale editor changes, but do not invoke scale APIs as part of this fix.

## Task 3 — build and device validation

Files: `docs/testing/2026-09-12-native-video-bounds.md`; test-only diagnostics under ignored build directories.

1. Run all unit tests, assembleDebug, lintDebug, and git diff --check.
2. On physical 7.3.7.32, pause one frame. Compare native, status hidden/restored, navigation hidden/restored, both hidden/restored; inspect video/selector coordinates and image bounds. Test episode switching and app return if runtime integration is available.
3. If a device install requires replacing a different signing identity or enabling a module, do not uninstall or change LSPosed configuration silently. Report the boundary clearly.
4. Record concrete passed checks and remaining limitations; provide APK path. Keep branch/worktree for review, do not push or overwrite the original dirty file.
