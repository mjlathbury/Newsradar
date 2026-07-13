package com.newsradar.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newsradar.app.R

/**
 * Brand mark that recolours to the user's chosen colour scheme. The structural
 * "server-N" + wordmark is shipped as a white silhouette (brand_struct_silhouette)
 * and tinted with the active scheme's primary; the neon radar glow (brand_neon) is
 * overlaid on top, fixed. So when the user picks PURPLE / SUNSET / etc. in Settings,
 * the logo follows automatically.
 */
@Composable
fun BrandLogo(
    modifier: Modifier = Modifier,
    height: Dp = 120.dp
) {
    // Stack the structural (tinted to scheme primary) + neon glow layers so the
    // glow sits on top of the server-N. Both fill the same box.
    Box(
        modifier = modifier.fillMaxWidth().height(height),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.brand_struct_silhouette),
            contentDescription = "NewsRadar",
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().height(height)
        )
        Image(
            painter = painterResource(id = R.drawable.brand_neon),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(height)
        )
    }
}

/**
 * Full-colour brand logo (teal structural + neon glow) for static display where
 * no runtime recolour is needed (e.g. About). Uses the separated layers composited.
 */
@Composable
fun BrandLogoStatic(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 120.dp) {
    Image(
        painter = painterResource(id = R.drawable.brand_struct),
        contentDescription = "NewsRadar",
        contentScale = ContentScale.Fit,
        modifier = modifier.height(height).padding(8.dp)
    )
}
