package com.newsradar.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newsradar.app.R

/**
 * Full-screen startup splash: the NewsRadar logo (N glyph, no wordmark) centred on
 * a theme-matched surface, with two live counters that rise as the scan progresses
 * — outlets scanned and articles found. This replaces the blank spinner so the user
 * can see the app is genuinely doing work (fetching N feeds in parallel) rather than
 * hanging. Shown on cold start (empty DB) and on a manual refresh.
 */
@Composable
fun SplashScreen(
    outletsDone: Int,
    outletsTotal: Int,
    articles: Int
) {
    // Animated progress fraction for the bar (smooth, never jumps backwards).
    val fraction by animateFloatAsState(
        targetValue = if (outletsTotal > 0) (outletsDone.toFloat() / outletsTotal) else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "splashProgress"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo: the N glyph, tinted to the theme primary so it matches the
            // user's chosen colour scheme (TEAL / blue / green / etc.).
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "NewsRadar",
                modifier = Modifier.size(132.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
            )

            Spacer(Modifier.height(40.dp))

            // Outlets counter.
            Text(
                text = "Scanning outlets  $outletsDone / $outletsTotal",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(10.dp))

            // Articles counter — rises as items parse in.
            Text(
                text = "Articles found  $articles",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(28.dp))

            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}
