package com.music.vivi.ui.screens.settings

import androidx.annotation.StringRes
import com.music.vivi.R

private val betaVersionName = Regex("\\d+\\.\\d+\\.\\d+-beta\\.[1-9]\\d*")

/** About-only label: the installed version identifies beta; build flags identify nightly/debug. */
@StringRes
internal fun aboutBuildChannelLabel(versionName: String, isNightly: Boolean, isDebug: Boolean): Int = when {
    // Legacy nightly builds keep a plain versionName and use an explicit Gradle flag.
    isNightly -> R.string.build_nightly
    betaVersionName.matches(versionName) -> R.string.build_beta
    isDebug -> R.string.build_development
    else -> R.string.build_stable
}
