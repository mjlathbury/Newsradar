package com.newsradar.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material.icons.filled.SentimentNeutral
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.newsradar.app.data.Article
import com.newsradar.app.data.Outlets
import com.newsradar.app.data.Paywall
import com.newsradar.app.data.Rating
import com.newsradar.app.ui.MainViewModel.SummaryState
import com.newsradar.app.prefs.RatingDisplay
import com.newsradar.app.ui.theme.RatingAmber
import com.newsradar.app.ui.theme.RatingGreen
import com.newsradar.app.ui.theme.RatingRed

@Composable
fun ArticleCard(
    article: Article,
    reasons: List<String>,
    showImages: Boolean = true,
    summary: SummaryState? = null,
    onSummary: () -> Unit = {},
    ratingDisplay: RatingDisplay = RatingDisplay.FULL,
    onOpen: (String) -> Unit,
    onRate: (Rating) -> Unit
) {
    val current = Rating.entries.firstOrNull { it.name == article.rating } ?: Rating.NONE

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column {
            if (showImages && !article.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = article.imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                )
            }
            Column(modifier = Modifier.padding(14.dp)) {
                val paywall = Outlets.byId(article.outletId)?.paywall ?: Paywall.NONE
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = article.outletName.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (paywall != Paywall.NONE) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "Subscription may be required",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = if (paywall == Paywall.HARD) "Subscription" else "May be limited",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = article.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (article.summary.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = article.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3
                    )
                }
                if (reasons.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Matched your interests: ${reasons.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ModernPill(
                        text = "Full article",
                        icon = Icons.Filled.OpenInBrowser,
                        filled = true,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpen(article.link) }
                    )
                    ModernPill(
                        text = "Brief Summary",
                        icon = Icons.AutoMirrored.Filled.Article,
                        filled = false,
                        loading = summary?.loading == true,
                        modifier = Modifier.weight(1f),
                        onClick = onSummary
                    )
                }
                Spacer(Modifier.height(12.dp))
                when (ratingDisplay) {
                    RatingDisplay.FULL -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RateButton("Interested", RatingGreen, Icons.Filled.SentimentSatisfied,
                            selected = current == Rating.GREEN, modifier = Modifier.weight(1f)) {
                            onRate(Rating.GREEN)
                        }
                        RateButton("Maybe", RatingAmber, Icons.Filled.SentimentNeutral,
                            selected = current == Rating.AMBER, modifier = Modifier.weight(1f)) {
                            onRate(Rating.AMBER)
                        }
                        RateButton("Not for me", RatingRed, Icons.Filled.SentimentDissatisfied,
                            selected = current == Rating.RED, modifier = Modifier.weight(1f)) {
                            onRate(Rating.RED)
                        }
                    }
                    RatingDisplay.COLOUR -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RatingDot(RatingGreen, current == Rating.GREEN) { onRate(Rating.GREEN) }
                        RatingDot(RatingAmber, current == Rating.AMBER) { onRate(Rating.AMBER) }
                        RatingDot(RatingRed, current == Rating.RED) { onRate(Rating.RED) }
                    }
                    RatingDisplay.NONE -> { /* ratings hidden */ }
                }
            }
        }
    }
}

@Composable
private fun RatingDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(if (selected) 26.dp else 20.dp)
            .clip(CircleShape)
            .background(if (selected) color else color.copy(alpha = 0.35f))
            .clickable { onClick() }
    )
}

@Composable
private fun RateButton(
    label: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = if (selected) color else color.copy(alpha = 0.12f)
    val fg = if (selected) Color.White else color
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = fg)
    }
}

/**
 * Modern pill button used for the card's primary actions (Full article / 60s summary).
 * Filled = primary emphasis; outline = secondary. Shows a spinner while [loading].
 */
@Composable
private fun ModernPill(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    filled: Boolean,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val container = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
    val content = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .clickable(enabled = !loading) { onClick() }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
                color = content
            )
        } else {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (loading) "Loading…" else text,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            fontWeight = FontWeight.SemiBold
        )
    }
}
