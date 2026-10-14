package com.music.vivi.ui.screens.settings

import com.music.vivi.R
import org.junit.Assert.assertEquals
import org.junit.Test

class AboutBuildInformationTest {
    @Test fun beta3IsBeta() {
        assertEquals(R.string.build_beta, aboutBuildChannelLabel("6.0.8-beta.3", isNightly = false, isDebug = false))
    }

    @Test fun beta2IsBeta() {
        assertEquals(R.string.build_beta, aboutBuildChannelLabel("6.0.8-beta.2", isNightly = false, isDebug = false))
    }

    @Test fun futureBetaVersionsAreNotHardcoded() {
        assertEquals(R.string.build_beta, aboutBuildChannelLabel("7.1.0-beta.12", isNightly = false, isDebug = false))
    }

    @Test fun releaseWithoutBetaSuffixIsStable() {
        assertEquals(R.string.build_stable, aboutBuildChannelLabel("6.0.8", isNightly = false, isDebug = false))
    }

    @Test fun ordinaryDebugBuildIsDevelopment() {
        assertEquals(R.string.build_development, aboutBuildChannelLabel("6.0.8", isNightly = false, isDebug = true))
    }

    @Test fun nightlyFlagPreservesPlainVersionNameNightlies() {
        assertEquals(R.string.build_nightly, aboutBuildChannelLabel("6.0.8", isNightly = true, isDebug = false))
    }

    @Test fun explicitNightlyFlagTakesPrecedence() {
        assertEquals(R.string.build_nightly, aboutBuildChannelLabel("6.0.8-beta.3", isNightly = true, isDebug = true))
    }

    @Test fun installedBetaNameRemainsBetaInADebugBuild() {
        assertEquals(R.string.build_beta, aboutBuildChannelLabel("6.0.8-beta.3", isNightly = false, isDebug = true))
    }
}
