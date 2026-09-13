# Silent automatic highest quality — implementation plan

**Goal:** Keep automatic highest quality without the host's per-episode success banner; retain manual-selection behavior.

**Architecture:** Fix the source of the notification, not global Toast/TextView rendering. In the verified CN 7.3.7.32 controller, `l35.w.q(Resolution)` configures the engine and sets boolean `c` (`isUserSelect`). The asynchronous `onVideoStreamBitrateChanged` dispatches `o.D(requested, actual)` only when `c` is true, then clears it. `zu4.r2.D` renders “清晰度已切换到” for 3 seconds. Clear this marker only around module-originated native selection; do not change engine configuration, playback state, or global UI callbacks.

**Tech stack:** Kotlin, libxposed 102, JUnit/Robolectric, Gradle; physical CN 7.3.7.32.

## Scope and evidence

- User confirmed that the APP's “已切换到1080p” banner is the unwanted notification and approved keeping automatic quality selection.
- Use the existing clean `fix/restore-native-video` worktree at `8c0c442`, which contains the working 7.3.7.32 quality mapping. Preserve the original checkout's uncommitted changes.
- Verified from the local APK's `classes4.dex`: `l35.w.q`, `l35.w.onVideoStreamBitrateChanged`, `zu4.r2.D`.
- Do not guess obfuscated fields for older/overseas versions. Unmapped versions retain their existing behavior. Manual invocations continue to set the native marker themselves.
- Alternatives rejected: blocking every toast (unrelated UI regression), blocking the whole bitrate callback (drops playback/reporting work), applying highest quality only once globally (fails subsequent episodes).

## Task 1 — failing regression tests

Files: `app/src/test/kotlin/xyz/kejiyu/hongguo/hooks/SilentMaxQualityTest.kt`.

Use Robolectric and reflection to invoke the real `Hooks.applyHighestViaController` with a small controller fixture exposing the verified `q` / `c` contract. Assert that engine selection occurs on every episode, automatic selection clears the marker, a subsequent manual selection sets it again, exceptions clean up the marker, and unmapped/missing fields do not prevent normal selection.

Run: `ANDROID_HOME=$HOME/Library/Android/sdk sh gradlew :app:testDebugUnitTest --tests '*SilentMaxQualityTest'`.
Expect the automatic-marker assertions to fail before the fix.

## Task 2 — minimal fix

Files: `QualityTargetNames.kt`, `Hooks.kt`, `QualityTargetNamesTest.kt`.

1. Add an optional `resolutionUserSelectionField` mapping, default blank; set `c` only for the verified 7.3.7.32 profile (including its existing class-loader fallback).
2. In `applyHighestViaController`, retain the native invocation and clear the mapped marker in `finally` via the existing `setFieldValue` helper. Log a warning and retain native behavior if the field cannot be set.
3. Do not add UI hooks or global timing windows. Both model-load application and settings-triggered application already use this helper.
4. Run targeted tests, all unit tests, the existing wiring checks, assembleDebug, lintDebug, and `git diff --check`.

## Task 3 — device validation

Check APK signing compatibility before an in-place install; never uninstall the host/module to bypass a signature mismatch. With the updated module loaded, verify repeated episode changes reach 1080p without the native banner. Verify manual selection still shows native feedback, and disabling the feature restores host behavior. Record any device coverage limitations explicitly.

## Validation results

- Regression before fix: 5 tests ran, 3 failed on the automatic/manual marker assertions. After fix: all 17 unit tests passed (7 layout, 5 mapping, 5 silent-quality); 5 existing layout wiring checks passed.
- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` completed successfully. Existing compiler/deprecation and lint warnings remain; no new build errors. APK signature verification passed.
- In-place device install succeeded without uninstalling or clearing data. Host was restarted. Device logs confirm `l35.w.q(1080p)` continues to execute and `maxQualitySilentApply=4` accompanies `maxQualityNativeApply=4` across subsequent playback activity. No marker-clear failure was logged.
- Device UI hierarchy capture could not obtain an idle state. The banner's visual disappearance and actual manual-selection feedback were not conclusively observed on-device; manual behavior is covered by regression tests.
- Output: `app/build/outputs/apk/debug/app-debug.apk` in this worktree. Device evidence: ignored `build/silent-quality/device.log`.
- Changes remain in `fix/restore-native-video`; original dirty checkout was not modified.
