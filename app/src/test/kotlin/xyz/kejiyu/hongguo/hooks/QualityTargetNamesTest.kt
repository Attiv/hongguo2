package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QualityTargetNamesTest {
    @Test fun cn73932UsesItsVerifiedNativeResolutionController() {
        val names = QualityTargetNames.forVersion(TargetNames.CN_PACKAGE, "7.3.9.32", TargetNames.CN_73318)
        assertEquals("e65.w", names.resolutionController)
        assertEquals(listOf("f", "i"), names.resolutionModelMethods)
        assertEquals("h", names.resolutionEngineField)
        assertEquals("r", names.resolutionApplyMethod)
        assertEquals("c", names.resolutionUserSelectionField)
    }

    @Test fun normalized73932VersionUsesVerifiedMapping() {
        val names = QualityTargetNames.forVersion(TargetNames.CN_PACKAGE, " 7.3.9.32 (release)", TargetNames.CN_73318)
        assertEquals("e65.w", names.resolutionController)
        assertEquals("r", names.resolutionApplyMethod)
    }

    @Test fun missingVersionCanInferVerified73932Controller() {
        val names = QualityTargetNames.forVersion(
            TargetNames.CN_PACKAGE, null, TargetNames.CN_73318, e65.w::class.java.classLoader,
        )
        assertEquals("e65.w", names.resolutionController)
        assertEquals("r", names.resolutionApplyMethod)
        assertEquals("c", names.resolutionUserSelectionField)
    }

    @Test fun available73932ClassDoesNotOverrideExplicitUnknownOrOverseasVersions() {
        for ((pkg, version, fallback) in listOf(
            Triple(TargetNames.CN_PACKAGE, "7.3.9.33", TargetNames.CN_73318),
            Triple(TargetNames.OVERSEA_PACKAGE, "7.3.9.32", TargetNames.OVERSEA_73132),
        )) {
            val names = QualityTargetNames.forVersion(pkg, version, fallback, e65.w::class.java.classLoader)
            assertEquals(fallback.resolutionController, names.resolutionController)
            assertEquals(fallback.resolutionApplyMethod, names.resolutionApplyMethod)
            assertEquals("", names.resolutionUserSelectionField)
        }
    }

    @Test fun cn73732UsesItsVerifiedNativeResolutionController() {
        val fallback = TargetNames.namesFor(TargetNames.CN_PACKAGE, "7.3.7.32")
        val names = QualityTargetNames.forVersion(TargetNames.CN_PACKAGE, "7.3.7.32", fallback)
        assertEquals("l35.w", names.resolutionController)
        assertEquals(listOf("f", "i"), names.resolutionModelMethods)
        assertEquals("h", names.resolutionEngineField)
        assertEquals("q", names.resolutionApplyMethod)
        assertEquals("c", names.resolutionUserSelectionField)
    }

    @Test fun normalizedVersionUsesVerifiedMapping() {
        val names = QualityTargetNames.forVersion(TargetNames.CN_PACKAGE, " 7.3.7.32 (release)", TargetNames.CN_73318)
        assertEquals("l35.w", names.resolutionController)
        assertEquals("c", names.resolutionUserSelectionField)
    }

    @Test fun olderSupportedVersionsKeepTheirExistingResolutionTargets() {
        for (version in TargetNames.SUPPORTED_CN_VERSIONS) {
            val fallback = TargetNames.namesFor(TargetNames.CN_PACKAGE, version)
            val names = QualityTargetNames.forVersion(TargetNames.CN_PACKAGE, version, fallback)
            assertEquals(fallback.resolutionController, names.resolutionController)
            assertEquals(fallback.resolutionModelMethods, names.resolutionModelMethods)
            assertEquals(fallback.resolutionEngineField, names.resolutionEngineField)
            assertEquals(fallback.resolutionApplyMethod, names.resolutionApplyMethod)
            assertEquals("", names.resolutionUserSelectionField)
        }
    }

    @Test fun unknownOrOverseasVersionsDoNotInheritUnverified73732Mapping() {
        for ((pkg, version, fallback) in listOf(
            Triple(TargetNames.CN_PACKAGE, "7.3.7.33", TargetNames.CN_73318),
            Triple(TargetNames.CN_PACKAGE, null, TargetNames.CN_73318),
            Triple(TargetNames.OVERSEA_PACKAGE, "7.3.7.32", TargetNames.OVERSEA_73132),
        )) {
            val names = QualityTargetNames.forVersion(pkg, version, fallback)
            assertEquals(fallback.resolutionController, names.resolutionController)
            assertEquals(fallback.resolutionApplyMethod, names.resolutionApplyMethod)
            assertEquals("", names.resolutionUserSelectionField)
        }
    }

    @Test fun qualityMappingDoesNotClaimFullHostVersionSupport() {
        assertFalse(TargetNames.isSupported(TargetNames.CN_PACKAGE, "7.3.7.32"))
        assertFalse(TargetNames.isSupported(TargetNames.CN_PACKAGE, "7.3.9.32"))
        assertEquals("nx4.w", TargetNames.CN_73318.resolutionController)
    }
}
