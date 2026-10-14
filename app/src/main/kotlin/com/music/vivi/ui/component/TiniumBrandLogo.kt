package com.music.vivi.ui.component

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.music.vivi.R

/** Full-color branding for prominent surfaces; compact icons keep their transparent artwork. */
@Composable
fun TiniumBrandLogo(modifier: Modifier = Modifier, contentDescription: String? = null) {
    Image(
        painter = painterResource(R.drawable.tinium_logo_background),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        colorFilter = null,
    )
}
