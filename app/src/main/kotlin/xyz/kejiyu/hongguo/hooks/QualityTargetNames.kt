package xyz.kejiyu.hongguo.hooks

/** Resolution mappings are independent of holder / UI compatibility. */
internal object QualityTargetNames {
    data class Names(
        val resolutionController: String,
        val resolutionModelMethods: List<String>,
        val resolutionEngineField: String,
        val resolutionApplyMethod: String,
        val resolutionUserSelectionField: String = "",
    )

    fun forVersion(pkg: String, versionName: String?, fallback: TargetNames.Names, classLoader: ClassLoader? = null): Names {
        val normalized = versionName?.trim()?.substringBefore(' ')
        val inferred73932 = pkg == TargetNames.CN_PACKAGE && normalized == null && classLoader != null &&
            runCatching { Class.forName("e65.w", false, classLoader) }.isSuccess
        if (pkg == TargetNames.CN_PACKAGE && (normalized == "7.3.9.32" || inferred73932)) {
            // Verified against classes4.dex from CN 7.3.9.32. Model entry points
            // and engine/marker fields are unchanged, but the active controller
            // moved to e65.w and native setResolution is now r(Resolution).
            // r sets c; the bitrate callback reads it for manual-selection UI.
            return Names("e65.w", listOf("f", "i"), "h", "r", "c")
        }
        val inferred73732 = pkg == TargetNames.CN_PACKAGE && normalized == null && classLoader != null &&
            runCatching { Class.forName("l35.w", false, classLoader) }.isSuccess
        if (pkg == TargetNames.CN_PACKAGE && (normalized == "7.3.7.32" || inferred73732)) {
            // Verified against classes4.dex from CN 7.3.7.32:
            // f(VideoModel) configures before play; i(VideoModel, long, String,
            // SaasVideoData) prepares the model; q(Resolution) is the host's
            // native selection path and updates controller and engine together.
            // q also sets c (isUserSelect); onVideoStreamBitrateChanged uses it
            // to dispatch the native selection banner. Automatic calls clear it.
            return Names("l35.w", listOf("f", "i"), "h", "q", "c")
        }
        return Names(
            fallback.resolutionController,
            fallback.resolutionModelMethods,
            fallback.resolutionEngineField,
            fallback.resolutionApplyMethod,
        )
    }
}
